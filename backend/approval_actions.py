"""Prepare immutable human-readable snapshots and execute approved server actions."""

import asyncio
import json
from datetime import UTC, datetime

from fastapi import HTTPException
from memory import MemoryConflict

import approvals
import doc_nicknames
import app
from notes import CREATE_INBOX
import meeting_api
import visual_api
from tools import REGISTRY


async def prepare(name, args):
    request_type = REGISTRY[name][0] if name in REGISTRY else None
    if name == "export_meeting":
        def snapshot():
            service = meeting_api.service
            with service.memory.lock, service.memory.db() as db:
                return service._load(db, args["meeting_id"])
        meeting = await asyncio.to_thread(snapshot)
        if not meeting or meeting["phase"] not in {"document", "write"}:
            raise HTTPException(409, "Meeting is not ready for export")
        args["snapshot"] = {key: meeting[key] for key in ("id", "title", "doc_name", "started_at", "result")}
        details = {"title": meeting["title"], "destination": meeting["document"] or meeting["doc_name"] or "New document in Glasses Meetings",
                   "minutes": meeting["result"]}
    elif name == "write_journal":
        from journal import parse_day, JournalDateError
        try:
            args["date"] = parse_day(args.get("date"), app.journal_service().now().date()).isoformat()
        except JournalDateError as exc:
            raise HTTPException(422, str(exc)) from exc
        details = dict(args)
    elif name == "visual_delete":
        details = {"from_utc": datetime.fromtimestamp(args["since"], UTC).isoformat(),
                   "through_utc": datetime.fromtimestamp(args["through"], UTC).isoformat(),
                   "delete": "Captured records and thumbnail files, including pins. Older queued captures are discarded."}
    elif name == "codex_task":
        args.update(confirm=False, job_id=None)
        preview = await app.execute_tool(name, request_type.model_validate(args))
        if preview.get("status") != "needs_confirmation":
            raise HTTPException(409, "Repository unavailable; choose an allowlisted alias")
        args.update(confirm=True, job_id=preview["job_id"])
        # Full canonical repo + instruction, never the truncated spoken summary.
        details = json.loads(app.app.state.jobs._row(preview["job_id"])["request"])
    elif name == "forget":
        args["confirmation_token"] = None
        preview = await app.execute_tool(name, request_type.model_validate(args))
        if preview.get("status") != "needs_confirmation":
            raise HTTPException(409, "No matching saved facts")
        args["confirmation_token"] = preview["confirmation_token"]
        details = {"query": args["query"], "facts_to_delete": preview["candidates"]}
    elif name == "append_to_doc":
        try:
            preview = await asyncio.to_thread(app.notes.preview, **args)
        except Exception:
            raise HTTPException(503, "Cannot preview destination; check Google credentials and aliases") from None
        if preview["status"] != "matched":
            raise HTTPException(409, "Ambiguous or missing destination; request an exact document ID")
        args["confirmed_doc_id"] = preview["document"]["id"]
        details = {**args, "destination": preview["document"]}
    else:
        details = dict(args)
    return args, details


async def execute(name, args):
    if name in doc_nicknames.TOOLS:
        return await asyncio.to_thread(doc_nicknames.execute, app.notes, name, args)
    if name == "visual_delete":
        return await asyncio.to_thread(visual_api.memory.delete_window, **args)
    if name == "export_meeting":
        try:
            for _ in range(2):
                result = await asyncio.to_thread(meeting_api.service.step, args["meeting_id"], approved_snapshot=args["snapshot"])
                if result["status"] == "ok":
                    return result
            return result
        except MemoryConflict as exc:
            raise HTTPException(409, str(exc)) from exc
    return await app.execute_tool(name, REGISTRY[name][0].model_validate(args))


async def append_note(args):
    try:
        preview = await asyncio.to_thread(app.notes.preview, **args)
    except Exception:
        raise HTTPException(503, "Cannot resolve destination; check Google credentials and aliases") from None
    if preview["status"] != "matched":
        return preview
    args["confirmed_doc_id"] = preview["document"]["id"]
    if args["confirmed_doc_id"] == CREATE_INBOX:
        return await approvals.propose("append_to_doc", args,
                                       prepared=(args, {**args, "destination": preview["document"]}))
    # A fixed existing ID cannot fall back to creating a document if an alias changes.
    return await execute("append_to_doc", args)


async def doc_action(name, args):
    try:
        preview = await asyncio.to_thread(doc_nicknames.prepare, app.notes, name, args)
    except ValueError as exc:
        raise HTTPException(422, str(exc)) from None
    except Exception:
        raise HTTPException(503, "Cannot preview document action; check Google credentials and aliases") from None
    if preview["status"] != "prepared":
        return preview
    return await approvals.propose(name, args, prepared=(preview["args"], preview["details"]))
