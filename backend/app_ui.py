"""Small authenticated, read-only snapshots for the phone's review screen."""

import asyncio
import json
import sqlite3

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

import doc_nicknames
import memory_api
from coach import Projects

router = APIRouter()


def saved_snapshot():
    memory = memory_api.memory
    with memory.lock, memory.db() as db:
        facts = [dict(row) for row in db.execute(
            "SELECT text,updated_at FROM facts ORDER BY updated_at DESC,id DESC LIMIT 5")]
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        notes = [dict(row) for row in db.execute(
            "SELECT title,substr(text,1,500) AS text,created_at FROM note_history ORDER BY id DESC LIMIT 5"
        )] if "note_history" in tables else []
        meetings = [dict(row) for row in db.execute("""SELECT
            json_extract(data,'$.title') AS title, json_extract(data,'$.phase') AS phase,
            json_extract(data,'$.started_at') AS started_at FROM meetings
            ORDER BY started_at DESC,id DESC LIMIT 5""")] if "meetings" in tables else []
    # Do not call current_step: its tool implementation performs an UPDATE.
    project = None
    path = Projects().path
    if path.exists():
        with sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True) as db:
            exists = db.execute("SELECT 1 FROM sqlite_master WHERE name='project'").fetchone()
            row = db.execute("SELECT body,position FROM project WHERE id=1").fetchone() if exists else None
        if row:
            body, position = json.loads(row[0]), row[1]
            if position < len(body["steps"]):
                project = {"title": body["title"], "step_number": position + 1,
                           "step_count": len(body["steps"]), "instruction": body["steps"][position]["instruction"]}
    return {"facts": facts, "notes": notes, "meetings": meetings, "project": project}


@router.get("/ui/overview")
async def overview(request: Request):
    snapshot = await asyncio.to_thread(saved_snapshot)
    # Jobs owns an event-loop-thread SQLite connection; never read it in a worker.
    jobs = [{key: row.get(key) for key in ("job_id", "kind", "status", "summary")}
            for row in request.app.state.jobs.status()["jobs"][:5]]
    return JSONResponse(dict(snapshot, jobs=jobs), headers={"Cache-Control": "no-store"})


@router.get("/ui/nicknames")
async def nicknames():
    """Existing doc nicknames for the phone's pickers; read-only."""
    import app
    names = await asyncio.to_thread(doc_nicknames.existing_nicknames, app.notes)
    return JSONResponse({"nicknames": names}, headers={"Cache-Control": "no-store"})
