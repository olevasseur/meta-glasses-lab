"""Authenticated by app middleware; transcript request limits and memory routes."""

from typing import Annotated, Literal

import httpx
from fastapi import APIRouter, HTTPException, Path
from google.genai.errors import APIError
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field

from memory import Memory, MemoryConflict
from gemini_resilience import GeminiUnavailable
from resilience_work import defer

memory = Memory()
router = APIRouter()
SessionId = Annotated[str, Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")]


class Turn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    seq: int = Field(ge=0, strict=True)
    speaker: Literal["user", "model"]
    text: str = Field(min_length=1, max_length=12_000)
    timestamp: AwareDatetime


class Transcripts(BaseModel):
    model_config = ConfigDict(extra="forbid")
    session_id: SessionId
    turns: list[Turn] = Field(min_length=1, max_length=50)


@router.post("/transcripts")
def transcripts(request: Transcripts):
    try:
        return memory.ingest(request.session_id, [t.model_dump(mode="json") for t in request.turns])
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except OSError as exc:
        raise HTTPException(503, "Memory storage unavailable") from exc


@router.post("/sessions/{session_id}/end")
def end_session(session_id: Annotated[str, Path(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")]):
    try:
        return memory.end(session_id)
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except OSError as exc:
        raise HTTPException(503, "Memory storage unavailable") from exc
    except GeminiUnavailable:
        defer(memory, "summary", session_id)
        return {"status": "processing", "message": "Transcript saved; summary queued"}
    except (APIError, httpx.HTTPError, RuntimeError, ValueError) as exc:
        raise HTTPException(502, "Memory summary failed; transcripts are saved. Retry ending this session.") from exc
