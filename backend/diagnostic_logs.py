"""Bounded operational logs; never log request bodies, URLs, or exception text."""
import json
import os
import re
import time
from collections import deque
from datetime import UTC, datetime
from pathlib import Path
from threading import RLock
from typing import Literal

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError

import dashboard
from notes import BACKEND_DIR


class Entry(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[a-zA-Z0-9-]{1,80}$")
    timestamp: str = Field(max_length=40)
    feature: str = Field(pattern=r"^[a-z][a-z0-9_-]{0,39}$")
    level: Literal["DEBUG", "INFO", "WARN", "ERROR"] = "INFO"
    event: str = Field(max_length=512)


class Batch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    entries: list[Entry] = Field(min_length=1, max_length=100)


def sanitize(value):
    for name in ("APP_SHARED_SECRET", "GEMINI_API_KEY"):
        secret = os.getenv(name, "")
        if secret:
            value = value.replace(secret, "[redacted]")
    value = re.sub(r"(?i)(auth_tokens/\S+|AIza[\w-]+|(?:bearer|token)\s+\S+)", "[redacted]", value)
    return "".join(c if c.isprintable() else " " for c in value)[:512]


class LogStore:
    # Single worker, matching the rest of the backend. Five 1 MiB files + current.
    def __init__(self, directory: Path, max_bytes=1_048_576, backups=5):
        self.directory, self.max_bytes, self.backups = directory, max_bytes, backups
        self.lock = RLock()
        self.rows = deque(maxlen=2000)
        self.loaded = False
        self.cursor = 0

    def _load(self):
        if self.loaded:
            return
        for index in range(self.backups, -1, -1):
            path = self.directory / (f"events.jsonl.{index}" if index else "events.jsonl")
            if path.exists():
                with path.open() as source:
                    for line in source:
                        try:
                            row = json.loads(line)
                            self.cursor = max(self.cursor, row["cursor"])
                            self.rows.append(row)
                        except (ValueError, KeyError, TypeError):
                            continue  # An interrupted final write must not break the viewer.
        self.loaded = True

    def append(self, entries, source="android"):
        with self.lock:
            self._load()
            self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            self.directory.chmod(0o700)
            path = self.directory / "events.jsonl"
            known = {r["id"] for r in self.rows}
            for entry in entries:
                if entry.id in known:
                    continue
                self.cursor = max(self.cursor + 1, time.time_ns() // 1_000_000)
                row = {**entry.model_dump(), "event": sanitize(entry.event),
                       "timestamp": sanitize(entry.timestamp), "source": source,
                       "cursor": self.cursor, "received_at": datetime.now(UTC).isoformat()}
                encoded = json.dumps(row, ensure_ascii=True) + "\n"
                if path.exists() and path.stat().st_size + len(encoded) > self.max_bytes:
                    for index in range(self.backups, 0, -1):
                        previous = path if index == 1 else self.directory / f"events.jsonl.{index - 1}"
                        if previous.exists():
                            previous.replace(self.directory / f"events.jsonl.{index}")
                fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
                with os.fdopen(fd, "a") as out:
                    os.fchmod(out.fileno(), 0o600)
                    out.write(encoded)
                self.rows.append(row)
                known.add(entry.id)

    def read(self, after=0, feature="", level="", limit=200):
        with self.lock:
            self._load()
            rows = [r for r in self.rows if r["cursor"] > after and
                    (not feature or r["feature"] == feature) and (not level or r["level"] == level)]
            selected = rows[:limit] if after else rows[-limit:]
            return {"entries": selected, "cursor": selected[-1]["cursor"] if selected else self.cursor,
                    "gap": bool(after and self.rows and after < self.rows[0]["cursor"] - 1)}


store = LogStore(BACKEND_DIR / "data" / "logs")
router = APIRouter()


@router.post("/logs")
async def upload(request: Request):
    body = bytearray()
    async for chunk in request.stream():
        if len(body) + len(chunk) > 131_072:
            raise HTTPException(413, "Log batch too large")
        body.extend(chunk)
    try:
        batch = Batch.model_validate_json(body)
    except ValidationError:
        raise HTTPException(422, "Invalid log batch") from None
    try:
        # The file lock and disk IO must not block Gemini token/event requests.
        from starlette.concurrency import run_in_threadpool
        await run_in_threadpool(store.append, batch.entries)
    except OSError:
        raise HTTPException(503, "Log storage unavailable") from None
    return {"accepted": len(batch.entries)}


@router.get("/logs")
def read(after: int = Query(0, ge=0), feature: str = Query("", max_length=40),
         level: str = Query("", max_length=10)):
    return store.read(after, feature, level)


@router.get("/diagnostics/health")
def health():
    return {"status": "ok", "time": datetime.now(UTC).isoformat()}


def page(request):
    feature = request.query_params.get("feature", "")[:40]
    level = request.query_params.get("level", "")[:10]
    return {**store.read(feature=feature, level=level), "feature": feature, "level": level}


dashboard.register_page("live-logs", "Live logs", "live-logs.html", page)


def record_request(feature, status, elapsed_ms):
    try:
        stamp = str(time.time_ns())
        store.append([Entry(id="backend-" + stamp, timestamp=datetime.now(UTC).isoformat(),
                           feature=feature, level="ERROR" if status >= 500 else "WARN" if status >= 400 else "INFO",
                           event=f"HTTP {status} duration_ms={elapsed_ms}")], source="backend")
    except OSError:
        pass  # Diagnostics must not turn a successful side effect into a retry.
