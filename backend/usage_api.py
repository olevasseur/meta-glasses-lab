"""Authenticated usage API and existing cookie-protected dashboard extension."""
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field
import dashboard
from usage import meter

router = APIRouter()


class LiveReport(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[a-zA-Z0-9-]{1,64}$")
    model: str = Field(pattern=r"^[a-zA-Z0-9.-]{1,100}$")
    seconds: float = Field(ge=0, le=86400, allow_inf_nan=False)
    input_tokens: int | None = Field(None, ge=0, le=2_000_000_000, strict=True)
    output_tokens: int | None = Field(None, ge=0, le=2_000_000_000, strict=True)
    total_tokens: int | None = Field(None, ge=0, le=2_000_000_000, strict=True)


@router.get("/usage")
def usage():
    return meter.snapshot()


@router.post("/usage/live")
def live(report: LiveReport):
    try:
        meter.live(**report.model_dump())
    except ValueError as exc:
        raise HTTPException(409, "Connection model changed") from exc
    return {"status": "ok"}


dashboard.register_page("usage", "Usage", "usage.html", lambda request: {"usage": meter.snapshot()})
