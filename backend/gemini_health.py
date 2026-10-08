"""Credential-safe cached token + constrained Live setup probe (no audio or tools)."""
import json
import os
import threading
import time
import uuid
from datetime import UTC, datetime

from fastapi import APIRouter
from websockets.sync.client import connect

import gemini
from usage import meter
from gemini_resilience import models

router = APIRouter()
_lock = threading.Lock()
_cached = None
_checked = 0.0
ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"


def probe(model):
    token = gemini.create_token(os.environ["GEMINI_API_KEY"], model, "auto", timeout_ms=5_000)
    with connect(ENDPOINT, additional_headers={"Authorization": "Token " + token["token"]},
                 open_timeout=3, close_timeout=1) as socket:
        connected_at = time.monotonic()
        socket.send(json.dumps({"setup": {"model": "models/" + model, "sessionResumption": {}}}))
        result = json.loads(socket.recv(timeout=3))
        if "setupComplete" not in result:
            raise RuntimeError("Live setup did not complete")
        meter.live(uuid.uuid4().hex, model, time.monotonic() - connected_at)


@router.get("/health/gemini")
def health():
    global _cached, _checked
    with _lock:
        if _cached is not None and time.monotonic() - _checked < 60:
            return {**_cached, "cached": True}
        results = []
        for model in models("LIVE", os.getenv("GEMINI_LIVE_MODEL")):
            try:
                probe(model)
                results.append({"model": model, "status": "available"})
                break
            except Exception as exc:
                # Do not expose upstream messages, URLs or tokens in diagnostics.
                close = getattr(exc, "rcvd", None)
                code = getattr(close, "code", None) or getattr(exc, "code", None)
                results.append({"model": model, "status": "unavailable", "code": code if isinstance(code, int) else None})
        _cached = {"status": "available" if any(r["status"] == "available" for r in results) else "unavailable",
                   "checked_at": datetime.now(UTC).isoformat(), "models": results, "cached": False}
        _checked = time.monotonic()
        return _cached
