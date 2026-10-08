"""Journal tables live in the existing private memory database."""

import json
from datetime import UTC, datetime

SCHEMA = """
    CREATE TABLE IF NOT EXISTS journal_events (
        id INTEGER PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL,
        occurred_at TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS journal_events_time ON journal_events(occurred_at);
    CREATE TABLE IF NOT EXISTS journal_entries (
        day TEXT PRIMARY KEY, content TEXT NOT NULL, text TEXT NOT NULL,
        generated_at TEXT NOT NULL, doc_id TEXT, synced_at TEXT);
"""


def record_event(db, kind: str, payload: dict, occurred_at: str | None = None):
    """Extension point: future producers can record job_finished / place_seen events."""
    db.execute("INSERT INTO journal_events(kind,payload,occurred_at) VALUES (?,?,?)",
               (kind, json.dumps(payload, ensure_ascii=False), occurred_at or datetime.now(UTC).isoformat()))
