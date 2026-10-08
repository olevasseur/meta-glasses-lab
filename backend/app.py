"""Run from backend/: uv run uvicorn app:app --port 8787."""

import hmac
import http.client
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
from coach import PROJECT_TOOL_NAMES, Projects
from notes import BACKEND_DIR, Notes

load_dotenv(BACKEND_DIR.parent / ".env")  # Explicit environment values take precedence.
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
notes = Notes()
projects = Projects()


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
    mode: Literal["auto", "ptt"] = "auto"


class AppendRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    doc_name: str = Field(default="Inbox", min_length=1, max_length=1000)
    text: str = Field(min_length=1, max_length=100_000)
    confirmed_doc_id: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]+$")

    @field_validator("doc_name", "text")
    @classmethod
    def require_nonblank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Must not be blank")
        return value


@app.post("/token")
def token(request: TokenRequest):
    api_key = os.getenv("GEMINI_API_KEY", "")
    if not api_key:
        raise HTTPException(503, "GEMINI_API_KEY is not configured")
    model = os.getenv("GEMINI_LIVE_MODEL") or gemini.DEFAULT_MODEL
    try:
        return gemini.create_token(api_key, model, request.mode)
    except (APIError, httpx.HTTPError, RuntimeError) as exc:
        # Upstream exception text can contain credentials or request contents.
        raise HTTPException(502, "Gemini token creation failed") from exc


@app.post("/tools/{name}")
def dispatch_tool(name: str, args: dict | None = None):
    if name in gemini.CLIENT_TOOL_NAMES:
        raise HTTPException(400, "This tool must be executed on the glasses app")
    if name in PROJECT_TOOL_NAMES:
        try:
            return projects.run(name, args or {})
        except ValidationError as exc:
            raise HTTPException(422, "Invalid coach arguments or extracted project") from exc
        except ValueError as exc:
            raise HTTPException(422, "Invalid project source or URL; use a public HTTPS text page or dictate instructions") from exc
        except (APIError, httpx.HTTPError, RuntimeError, OSError, sqlite3.Error, http.client.HTTPException) as exc:
            raise HTTPException(502, "Coach operation failed; check current_step before retrying") from exc
    if name != "append_to_doc":
        raise HTTPException(404, "Unknown tool")
    try:
        request = AppendRequest.model_validate(args)
    except ValidationError as exc:
        raise RequestValidationError(exc.errors()) from exc
    try:
        return notes.append(**request.model_dump())
    except (FileNotFoundError, ValueError, GoogleAuthError) as exc:
        raise HTTPException(503, "Check Google credentials and doc_aliases.json; run scripts/google_auth.py if needed") from exc
    except (HttpError, OSError, RuntimeError) as exc:
        raise HTTPException(502, "Google Docs operation failed; check the document before retrying") from exc
