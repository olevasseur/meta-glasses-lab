"""Tier-0 offline tool boundary and durable receipt for safe phone outbox replay."""
import asyncio
import hashlib
import json
import re

from fastapi import HTTPException

from notes import CREATE_INBOX

_lock = asyncio.Lock()


async def execute(receipt_id, name, args):
    import app
    import memory_api
    if not re.fullmatch(r"[a-f0-9-]{36}", receipt_id) or name not in {"remember", "append_to_doc"}:
        raise HTTPException(422, "Invalid offline capture")
    if name == "append_to_doc" and (args.get("doc_name") not in {None, "Inbox"} or args.get("confirmed_doc_id")):
        raise HTTPException(422, "Offline notes can only target Inbox")
    payload = hashlib.sha256(json.dumps([name, args], sort_keys=True).encode()).hexdigest()
    memory = memory_api.memory
    async with _lock:
        with memory.db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS offline_receipts(id TEXT PRIMARY KEY, hash TEXT, result TEXT)")
            row = db.execute("SELECT hash,result FROM offline_receipts WHERE id=?", (receipt_id,)).fetchone()
        if row:
            if row[0] != payload:
                raise HTTPException(409, "Offline receipt cannot be reused for different content")
            return json.loads(row[1])
        if name == "append_to_doc":
            try:
                preview = await asyncio.to_thread(app.notes.preview, **args)
            except Exception:
                raise HTTPException(503, "Inbox unavailable; offline note retained") from None
            if preview["status"] != "matched" or preview["document"]["id"] == CREATE_INBOX:
                return {"status": "needs_destination", "message": "Set up an existing Inbox before saving offline notes"}
            args = {**args, "confirmed_doc_id": preview["document"]["id"]}
        unknown = {"status": "unknown", "message": "Save outcome unknown; retained text needs review before repeating"}
        # Commit before the external side effect. A crash cannot cause a duplicate append.
        with memory.db() as db:
            db.execute("INSERT INTO offline_receipts VALUES (?,?,?)", (receipt_id, payload, json.dumps(unknown)))
        try:
            result = await app.execute_tool(name, app.REGISTRY[name][0].model_validate(args))
        except Exception:
            result = unknown
        with memory.db() as db:
            db.execute("UPDATE offline_receipts SET result=? WHERE id=?", (json.dumps(result), receipt_id))
        return result
