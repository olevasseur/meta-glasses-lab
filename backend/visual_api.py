"""Visual routes share the app's authentication and no-store middleware."""

import asyncio
import logging

import httpx
from fastapi import APIRouter, HTTPException
from google.genai.errors import APIError
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from visual_memory import Frame, VisualMemory

memory = VisualMemory()
router = APIRouter()


class DeleteRecent(BaseModel):
    model_config = ConfigDict(extra="forbid")
    minutes: int = Field(default=60, ge=1, le=10080, strict=True)


class CaptureStatus(BaseModel):
    model_config = ConfigDict(extra="forbid")
    on: bool = Field(strict=True)


@router.post("/visual/status")
def report_status(request: CaptureStatus):
    memory.report_capture(request.on)
    return {"status": "ok"}


@router.post("/visual/frames")
def capture(frame: Frame):
    try:
        return memory.capture(frame)
    except ValidationError as exc:
        raise HTTPException(502, "Gemini returned an invalid caption; nothing saved") from exc
    except ValueError as exc:
        raise HTTPException(422, "Invalid JPEG or capture timestamp") from exc
    except (APIError, httpx.HTTPError, RuntimeError) as exc:
        raise HTTPException(502, "Visual caption failed; nothing saved") from exc
    except OSError as exc:
        raise HTTPException(503, "Visual storage unavailable") from exc


@router.post("/visual/delete")
def delete(request: DeleteRecent):
    try:
        return memory.delete_recent(request.minutes)
    except OSError as exc:
        raise HTTPException(503, "Visual deletion failed; retry") from exc


async def retention_loop():
    while True:
        try:
            await asyncio.to_thread(memory.prune)
        except Exception:
            logging.getLogger(__name__).warning("Visual retention cleanup failed; retrying in one minute")
        await asyncio.sleep(60)
