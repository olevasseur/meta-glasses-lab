"""Meeting routes use the app's existing authentication and no-store middleware."""
import asyncio
import approvals
import httpx
import wave
from fastapi import APIRouter, HTTPException, Request, Query
from google.genai.errors import APIError
from google.auth.exceptions import GoogleAuthError
from googleapiclient.errors import HttpError
from pydantic import BaseModel, ConfigDict, Field

from memory import MemoryConflict
from gemini_resilience import GeminiUnavailable
from memory_api import SessionId

router = APIRouter()
service = None  # Wired to the same Memory/Notes instances by app.py.


class StartMeeting(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)
    chunked: bool = False
    meeting_id: SessionId
    title: str = Field(default='Untitled', min_length=1, max_length=200)
    doc_name: str | None = Field(default=None, min_length=1, max_length=1000)


@router.post('/meetings/start')
def start(request: StartMeeting):
    try:
        return service.start(**request.model_dump())
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc


@router.post('/meetings/{meeting_id}/end')
async def end(meeting_id: SessionId, chunk_count: int | None = Query(default=None, ge=0)):
    try:
        await asyncio.to_thread(service.chunks.seal, meeting_id, chunk_count)
        progress = await asyncio.to_thread(service.chunks.process, meeting_id)
        if 'transcribed_index' in progress:
            return dict(progress, phase='transcribing', meeting_id=meeting_id)
        result = await asyncio.to_thread(service.step, meeting_id, allow_export=False)
        if result.get("phase") in {"document", "write"}:
            def needs_approval():
                with service.memory.lock, service.memory.db() as db:
                    meeting = service._load(db, meeting_id)
                    return not meeting["doc_name"] and not meeting["document"]
            if await asyncio.to_thread(needs_approval):
                return await approvals.propose("export_meeting", {"meeting_id": meeting_id}, deduplicate=True)
        return result
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except GeminiUnavailable:
        return {"status": "processing", "meeting_id": meeting_id, "message": "Transcript saved; Gemini retry pending"}
    except (APIError, httpx.HTTPError, ValueError, RuntimeError, OSError, GoogleAuthError, HttpError) as exc:
        raise HTTPException(502, 'Meeting saved; retry finalization. Check Gemini quota, Google consent (including drive.file), and exact destination title/alias.') from exc


@router.post('/meetings/{meeting_id}/chunks/{index}')
async def upload_chunk(meeting_id: SessionId, index: int, request: Request, start_ms: int = Query(ge=0)):
    if index < 0 or index > 1_000_000 or start_ms > 10**12:
        raise HTTPException(422, 'Invalid chunk position')
    if request.headers.get('content-type') != 'audio/wav':
        raise HTTPException(415, 'Expected audio/wav')
    try:
        data = await request.body()
        return await asyncio.to_thread(service.chunks.upload, meeting_id, index, start_ms, data)
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except (ValueError, EOFError, wave.Error) as exc:
        raise HTTPException(422, 'Invalid PCM16 16 kHz mono WAV') from exc


@router.post('/meetings/{meeting_id}/process')
async def process_chunks(meeting_id: SessionId):
    try:
        return await asyncio.to_thread(service.chunks.process, meeting_id)
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except (GeminiUnavailable, APIError, httpx.HTTPError, ValueError, RuntimeError):
        return {'status': 'processing', 'message': 'Audio saved; transcription retry pending'}
