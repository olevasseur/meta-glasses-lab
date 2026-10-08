"""Run from backend/: uv run uvicorn app:app --port 8787."""

import hmac
import os
import sqlite3
from typing import Literal

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from google.auth.exceptions import GoogleAuthError
from google.genai.errors import APIError
from googleapiclient.errors import HttpError
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

import gemini
import memory_api
from memory import MemoryConflict
from notes import BACKEND_DIR, Notes
from tools import REGISTRY

load_dotenv(BACKEND_DIR.parent / ".env")  # Explicit environment values take precedence.
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
notes = Notes()
app.include_router(memory_api.router)


@app.exception_handler(sqlite3.Error)
async def memory_storage_error(request: Request, exc: sqlite3.Error):
    return JSONResponse(status_code=503, content={"detail": "Memory storage unavailable"})


@app.middleware("http")
async def authenticate(request: Request, call_next):
    secret = os.getenv("APP_SHARED_SECRET", "")
    supplied = request.headers.get("X-App-Secret", "")
    if not secret:
        return JSONResponse(status_code=503, content={"detail": "APP_SHARED_SECRET is not configured"})
    if not hmac.compare_digest(supplied.encode("utf-8"), secret.encode("utf-8")):
        return JSONResponse(status_code=401, content={"detail": "Invalid app secret"})
    response = await call_next(request)
    response.headers["Cache-Control"] = "no-store"
    return response


class TokenRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["auto", "ptt", "over"] = "auto"
    silence_ms: int = Field(default=2000, strict=True)

    @field_validator("silence_ms")
    @classmethod
    def clamp_silence(cls, value: int) -> int:
        return max(500, min(5000, value))


@app.post("/token")
def token(request: TokenRequest):
    api_key = os.getenv("GEMINI_API_KEY", "")
    if not api_key:
        raise HTTPException(503, "GEMINI_API_KEY is not configured")
    model = os.getenv("GEMINI_LIVE_MODEL") or gemini.DEFAULT_MODEL
    try:
        return gemini.create_token(
            api_key, model, request.mode,
            system_instruction=memory_api.memory.context(gemini.PROMPT_PATH.read_text(encoding="utf-8")),
            silence_ms=request.silence_ms,
        )
    except (APIError, httpx.HTTPError, RuntimeError) as exc:
        # Upstream exception text can contain credentials or request contents.
        raise HTTPException(502, "Gemini token creation failed") from exc


@app.post("/tools/{name}")
def dispatch_tool(name: str, args: dict | None = None):
    if name not in REGISTRY:
        raise HTTPException(404, "Unknown tool")
    request_type, owner, method, _ = REGISTRY[name]
    if owner == "client":
        raise HTTPException(400, "This tool must be executed on the glasses app")
    try:
        request = request_type.model_validate(args)
    except ValidationError as exc:
        raise RequestValidationError(exc.errors()) from exc
    try:
        service = notes if owner == "notes" else memory_api.memory
        return getattr(service, method)(**request.model_dump())
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except (FileNotFoundError, ValueError, GoogleAuthError) as exc:
        if owner == "memory":
            raise HTTPException(503, "Memory storage unavailable") from exc
        raise HTTPException(503, "Check Google credentials and doc_aliases.json; run scripts/google_auth.py if needed") from exc
    except (HttpError, OSError, RuntimeError) as exc:
        if owner == "memory":
            raise HTTPException(503, "Memory storage unavailable") from exc
        raise HTTPException(502, "Google Docs operation failed; check the document before retrying") from exc
