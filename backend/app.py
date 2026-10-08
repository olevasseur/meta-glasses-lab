"""Run from backend/: uv run uvicorn app:app --port 8787."""

import asyncio
import hmac
import http.client
import os
import sqlite3
import time
from contextlib import asynccontextmanager, suppress
from typing import Literal

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from google.auth.exceptions import GoogleAuthError
from google.genai.errors import APIError
from googleapiclient.errors import HttpError
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

import gemini
import gemini_health
import resilience_work
from gemini_resilience import live_model, transient
import dashboard
import diagnostic_logs
import approvals
import memory_api
import translation
import workouts
from journal import Journal, JournalDateError, schedule_loop
import meeting_api
from meetings import Meetings
import visual_api
import visual_dashboard  # Registers the extension before dashboard.install includes its router.
from memory import MemoryConflict
from jobs import Jobs
from coach import Projects
from notes import BACKEND_DIR, Notes
from tools import REGISTRY

load_dotenv(BACKEND_DIR.parent / ".env")  # Explicit environment values take precedence.


def journal_service():
    return Journal(memory_api.memory, notes, schedule=dashboard.journal_time())


def record_note(document, text):
    dashboard.record_note(document, text)
    try:
        journal_service().record_note(text, document["title"])
    except (sqlite3.Error, OSError, ValueError):
        # Google already saved the note; don't invite a duplicate append.
        dashboard.record_error("journal tracking (Google append succeeded)", 503)
        return {"journal_tracking": "failed"}


notes = Notes(on_append=record_note)
projects = Projects()


@asynccontextmanager
async def lifespan(app):
    approvals.pending.clear()
    approvals.completed.clear()
    stop = asyncio.Event()
    task = None
    app.state.jobs = Jobs(notes)
    await app.state.jobs.start()
    retention = asyncio.create_task(visual_api.retention_loop())
    recovery = asyncio.create_task(resilience_work.retry_loop())
    try:
        if os.getenv("JOURNAL_ENABLED", "true").lower() in {"true", "1", "yes"}:
            journal_service()  # Validate schedule/timezone at startup.
            task = asyncio.create_task(schedule_loop(journal_service, stop))
        yield
    finally:
        stop.set()
        recovery.cancel()
        with suppress(asyncio.CancelledError):
            await recovery
        retention.cancel()
        with suppress(asyncio.CancelledError):
            await retention
        try:
            if task:
                await task
        finally:
            await app.state.jobs.close()


app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
app.include_router(diagnostic_logs.router)
app.include_router(gemini_health.router)
app.include_router(memory_api.router)
app.include_router(visual_api.router)
app.include_router(workouts.router)
meeting_api.service = Meetings(memory_api.memory, notes)
app.include_router(meeting_api.router)


@app.exception_handler(sqlite3.Error)
async def memory_storage_error(request: Request, exc: sqlite3.Error):
    return JSONResponse(status_code=503, content={"detail": "Memory storage unavailable"})


@app.middleware("http")
async def authenticate(request: Request, call_next):
    if dashboard.is_dashboard(request):
        return await call_next(request)  # Dashboard middleware enforces cookie + CSRF.
    secret = os.getenv("APP_SHARED_SECRET", "")
    supplied = request.headers.get("X-App-Secret", "")
    if not secret:
        return JSONResponse(status_code=503, content={"detail": "APP_SHARED_SECRET is not configured"})
    if not hmac.compare_digest(supplied.encode("utf-8"), secret.encode("utf-8")):
        return JSONResponse(status_code=401, content={"detail": "Invalid app secret"})
    started = time.monotonic()
    response = await call_next(request)
    path = request.url.path
    feature = None
    if path == "/token":
        feature = "gemini"
    elif path.startswith("/tools/") and path.rsplit("/", 1)[-1] in REGISTRY:
        owner = REGISTRY[path.rsplit("/", 1)[-1]][1]
        feature = {"visual": "visual-memory", "workouts": "workout", "translation": "translator",
                   "meeting_control": "meeting", "client": "tools"}.get(owner, owner)
    elif path.startswith(("/transcripts", "/sessions/", "/meetings/", "/visual/", "/workouts")):
        feature = "visual-memory" if path.startswith("/visual/") else "meeting" if path.startswith("/meetings/") else "workout" if path.startswith("/workouts") else "memory"
    if feature:
        await asyncio.to_thread(diagnostic_logs.record_request, feature, response.status_code, round((time.monotonic() - started) * 1000))
    response.headers["Cache-Control"] = "no-store"
    return response


class TokenRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["auto", "ptt", "over", "meeting"] = Field(default_factory=dashboard.default_mode)
    silence_ms: int = Field(default=2000, strict=True)
    persona: Literal["assistant", "translator"] = "assistant"
    language: str | None = Field(default=None, max_length=40)
    avoid_models: list[str] = Field(default_factory=list, max_length=16)

    @model_validator(mode="after")
    def validate_persona(self):
        if (self.persona == "translator") != (self.language is not None):
            raise ValueError("Translator requires language; assistant must omit language")
        if self.mode == "meeting" and self.persona != "assistant":
            raise ValueError("Meeting capture requires the assistant persona")
        if self.language is not None:
            self.language = translation.normalize_language(self.language)
        return self

    @field_validator("silence_ms")
    @classmethod
    def clamp_silence(cls, value: int) -> int:
        return max(500, min(5000, value))


@app.post("/token")
def token(request: TokenRequest):
    api_key = os.getenv("GEMINI_API_KEY", "")
    if not api_key:
        raise HTTPException(503, "GEMINI_API_KEY is not configured")
    model = live_model(request.avoid_models)
    try:
        return gemini.create_token(
            api_key, model, request.mode,
            system_instruction=(memory_api.memory.context(gemini.assistant_instruction(request.mode))
                                if request.persona == "assistant" else None),
            silence_ms=request.silence_ms,
            persona=request.persona, language=request.language,
        )
    except (APIError, httpx.HTTPError, RuntimeError) as exc:
        # Upstream exception text can contain credentials or request contents.
        status = 503 if transient(exc) else 422 if getattr(exc, "code", None) in (400, 401, 403, 404) else 502
        raise HTTPException(status, "Gemini token configuration rejected" if status == 422 else "Gemini temporarily unavailable") from exc


@app.post("/tools/{name}")
async def dispatch_tool(name: str, http_request: Request, args: dict | None = None):
    if name not in REGISTRY:
        raise HTTPException(404, "Unknown tool")
    request_type, owner, method, _ = REGISTRY[name]
    if owner == "client":
        raise HTTPException(400, "This tool must be executed on the glasses app")
    try:
        request = request_type.model_validate(args if args is not None else {})
    except ValidationError as exc:
        raise RequestValidationError(exc.errors()) from exc
    if http_request.headers.get("X-Offline-Id"):
        from offline_capture import execute
        return await execute(http_request.headers["X-Offline-Id"], name, request.model_dump())
    if name == "append_to_doc":
        return await approval_actions.append_note(request.model_dump())
    if name in approvals.SENSITIVE:
        return await approvals.propose(name, request.model_dump())
    return await execute_tool(name, request)


async def execute_tool(name, request):
    _, owner, method, _ = REGISTRY[name]
    if owner == "meeting_control":
        return {"status": "ok", "client_action": name, **request.model_dump()}
    if owner == "translation":
        # Validated commands only: persona state belongs to each phone session.
        return getattr(translation, method)(**request.model_dump())
    if owner == "coach":
        try:
            return await asyncio.to_thread(projects.run, name, request.model_dump())
        except ValidationError as exc:
            raise HTTPException(422, "Invalid coach arguments or extracted project") from exc
        except ValueError as exc:
            raise HTTPException(422, "Invalid project source or URL; use a public HTTPS text page or dictate instructions") from exc
        except (APIError, httpx.HTTPError, RuntimeError, OSError, sqlite3.Error, http.client.HTTPException) as exc:
            raise HTTPException(502, "Coach operation failed; check current_step before retrying") from exc
    if owner == "jobs":
        # Jobs owns an event-loop-bound SQLite connection and schedules async work.
        try:
            handler = getattr(app.state.jobs, method)
            if name == "cancel_job":
                return await handler(**request.model_dump())
            return handler(**request.model_dump())
        except (OSError, ValueError, sqlite3.Error):
            raise HTTPException(503, "Check the job configuration and repository allowlist") from None
    try:
        service = (journal_service() if owner == "journal" else notes if owner == "notes"
                   else workouts if owner == "workouts" else visual_api.memory if owner == "visual" else memory_api.memory)
        return await asyncio.to_thread(getattr(service, method), **request.model_dump())
    except JournalDateError as exc:
        raise HTTPException(422, str(exc)) from exc
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except (FileNotFoundError, ValueError, GoogleAuthError) as exc:
        if owner == "journal":
            raise HTTPException(503, "Journal unavailable; check schedule, Gemini and Google credentials") from exc
        if owner in {"memory", "workouts", "visual"}:
            raise HTTPException(503, "Memory storage unavailable") from exc
        raise HTTPException(503, "Check Google credentials and doc_aliases.json; run scripts/google_auth.py if needed") from exc
    except (APIError, httpx.HTTPError, HttpError, OSError, RuntimeError) as exc:
        if owner == "journal":
            raise HTTPException(502, "Journal operation failed; a local draft may be saved. Retrying replaces the same day's entry.") from exc
        if owner in {"memory", "workouts", "visual"}:
            raise HTTPException(503, "Memory storage unavailable") from exc
        raise HTTPException(502, "Google Docs operation failed; check the document before retrying") from exc


@app.get("/events")
async def events(after: int = Query(0, ge=0), wait: float = Query(20, ge=0, le=20)):
    return await app.state.jobs.events(after, wait)


import approval_actions
approvals.install(app, approval_actions.prepare, approval_actions.execute)
dashboard.install(app, notes_service=lambda: notes, token_test=lambda: token(TokenRequest()))

# Outermost: limits also cover login and malformed/unvalidated API requests.
from security_boundary import SecurityBoundary
app.add_middleware(SecurityBoundary)


@app.exception_handler(RequestValidationError)
async def invalid_request(request, exc):
    # Pydantic's default response echoes input (including credentials and huge values).
    return JSONResponse(status_code=422, content={"detail": "Invalid request arguments"})
