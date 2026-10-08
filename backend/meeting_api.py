"""Meeting routes use the app's existing authentication and no-store middleware."""
import asyncio
import approvals
import httpx
from fastapi import APIRouter, HTTPException
from google.genai.errors import APIError
from google.auth.exceptions import GoogleAuthError
from googleapiclient.errors import HttpError
from pydantic import BaseModel, ConfigDict, Field

from memory import MemoryConflict
from memory_api import SessionId

router = APIRouter()
service = None  # Wired to the same Memory/Notes instances by app.py.


class StartMeeting(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)
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
async def end(meeting_id: SessionId):
    try:
        result = await asyncio.to_thread(service.step, meeting_id, allow_export=False)
        if result.get("phase") in {"document", "write"}:
            return approvals.propose("export_meeting", {"meeting_id": meeting_id}, deduplicate=True)
        return result
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except (APIError, httpx.HTTPError, ValueError, RuntimeError, OSError, GoogleAuthError, HttpError) as exc:
        raise HTTPException(502, 'Meeting saved; retry finalization. Check Gemini quota, Google consent (including drive.file), and exact destination title/alias.') from exc
