"""Retry saved AI work, never automatically replay uncertain external writes."""
import asyncio
import logging

from gemini_resilience import GeminiUnavailable


def defer(memory, kind, key):
    with memory.db() as db:
        db.execute("CREATE TABLE IF NOT EXISTS ai_pending(kind TEXT, key TEXT, PRIMARY KEY(kind,key))")
        db.execute("INSERT OR IGNORE INTO ai_pending VALUES (?,?)", (kind, key))


def retry_saved(memory, journal):
    with memory.db() as db:
        db.execute("CREATE TABLE IF NOT EXISTS ai_pending(kind TEXT, key TEXT, PRIMARY KEY(kind,key))")
        pending = db.execute("SELECT kind,key FROM ai_pending LIMIT 10").fetchall()
    for kind, key in pending:
        if kind == "summary":
            memory.end(key)
        elif kind == "journal":
            from datetime import date
            with memory.lock:
                journal._entry(date.fromisoformat(key), refresh=True)
            # Only build the draft. Docs export still follows normal approval/scheduler flow.
        with memory.db() as db:
            db.execute("DELETE FROM ai_pending WHERE kind=? AND key=?", (kind, key))


async def retry_loop():
    import app
    import memory_api
    import visual_api
    import meeting_api
    while True:
        await asyncio.sleep(60)
        actions = [lambda: retry_saved(memory_api.memory, app.journal_service()),
                   visual_api.memory.retry_captions, app.projects.retry_pending, meeting_api.service.chunks.process]
        for action in actions:
            try:
                await asyncio.to_thread(action)
            except GeminiUnavailable:
                pass
            except Exception:
                logging.getLogger(__name__).warning("Saved AI work remains pending")
