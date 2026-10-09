"""Human approvals use browser/device routes, never model tool arguments."""

import hashlib
import hmac
import json
import os
import secrets
import time
from typing import Literal

from pydantic import BaseModel, ConfigDict

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse

import dashboard

SENSITIVE = {"codex_task", "deep_research", "forget", "write_journal", "cancel_job"}
router = APIRouter(prefix="/dashboard/approvals")
device_router = APIRouter(prefix="/approvals")
pending = {}
completed = {}
MAX_PENDING = 32
TTL = 300


def secret_hash():
    return hashlib.sha256(os.getenv("APP_SHARED_SECRET", "").encode()).digest()


def prune():
    for records in (pending, completed):
        for key, item in list(records.items()):
            if item["expires"] <= time.monotonic() or item["secret"] != secret_hash():
                del records[key]


def payload_hash(name, args, details):
    return hashlib.sha256(json.dumps([name, args, details], sort_keys=True,
                                    separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()


async def propose(name, args, *, deduplicate=False, prepared=None):
    prune()
    source = json.loads(json.dumps(args))
    if deduplicate:
        for key, item in pending.items():
            if item["name"] == name and item["source"] == source:
                if not item["prepared"]:
                    raise HTTPException(409, "Approval preview is being prepared")
                return response(key)
    if len(pending) >= MAX_PENDING:
        raise HTTPException(429, "Too many pending approvals; review or wait five minutes")
    approval_id = secrets.token_hex(16)
    item = {"name": name, "source": source, "expires": time.monotonic() + TTL,
            "secret": secret_hash(), "prepared": False, "nonce": None, "sid": None}
    pending[approval_id] = item
    try:
        # Resolve before returning the ID so every human sees the same immutable action.
        args, details = prepared if prepared is not None else await prepare_action(name, dict(source))
        get(approval_id)
        item.update(args=json.loads(json.dumps(args)), details=json.loads(json.dumps(details)), prepared=True)
        item["hash"] = payload_hash(name, item["args"], item["details"])
        return response(approval_id)
    except BaseException:
        pending.pop(approval_id, None)
        raise


def response(approval_id):
    item = get(approval_id)
    if not item["prepared"]:
        raise HTTPException(409, "Approval preview is being prepared")
    tier = 2 if item["name"] == "codex_task" else 1
    details = item["details"]
    summary = {
        "codex_task": "Run " + str(details.get("agent", "codex")).title() + " in " + str(details.get("repo", "the displayed repository")),
        "deep_research": str(details.get("agent", "codex")).title() + " research and publish: " + str(details.get("topic", ""))[:120],
        "append_to_doc": "Create Glasses Inbox and append this note",
        **{name: details.get("summary", "") for name in ("create_doc", "set_doc_alias", "remove_doc_alias")},
        "forget": "Delete saved facts matching: " + str(details.get("query", ""))[:120],
        "write_journal": "Generate and replace journal: " + str(details.get("date", "today")),
        "export_meeting": "Export meeting minutes to a new document",
        "visual_delete": "Delete captured visual memories and thumbnails, including pins",
        "cancel_job": "Cancel the selected background job",
    }[item["name"]]
    return {"status": "needs_confirmation", "approval_id": approval_id,
            "approval_url": "/dashboard/approvals/" + approval_id,
            "tier": tier, "allowed_methods": ["button", "screen"] if tier == 2 else ["nod", "button", "screen"],
            "summary": summary, "details": details, "payload_hash": item["hash"],
            "message": "Ask the user to press the capture button or tap Approve." if tier == 2 else
                       "Ask the user to nod or press the capture button, or tap Approve. Shake or timeout denies. Model confirmation fields cannot approve."}


def get(approval_id):
    prune()
    if approval_id not in pending:
        raise HTTPException(409, "Approval expired or already used; request a new preview")
    return pending[approval_id]


def listing(request):
    prune()
    return {"approvals": [{"id": key, "name": item["name"], "agent": item.get("details", {}).get("agent"), "repo": item.get("details", {}).get("repo")} for key, item in pending.items()]}


def install(app, prepare, execute):
    global prepare_action
    prepare_action = prepare
    app.state.prepare_approval = prepare
    app.state.execute_approval = execute
    app.include_router(router)
    app.include_router(device_router)


@router.get("/{approval_id}")
async def review(request: Request, approval_id: str):
    item = get(approval_id)
    if not item["prepared"]:
        raise HTTPException(409, "Approval preview is being prepared")
    item["nonce"] = secrets.token_urlsafe(32)
    item["sid"] = request.state.dashboard_sid
    return dashboard.render(request, "approval.html", "Review action", approval_id=approval_id,
                            name=item["name"], details=json.dumps(item["details"], ensure_ascii=True, indent=2),
                            nonce=item["nonce"])


@router.post("/{approval_id}")
async def decide(request: Request, approval_id: str):
    item = get(approval_id)
    form = request.state.form
    if (not item["prepared"] or item["sid"] != request.state.dashboard_sid or
            not item["nonce"] or not hmac.compare_digest(form.get("nonce", "").encode(), item["nonce"].encode())):
        raise HTTPException(403, "Review this request in this browser before approving")
    if set(form) != {"csrf", "nonce", "decision"} or form["decision"] not in {"approve", "reject"}:
        raise HTTPException(422, "Invalid approval form")
    result = await consume(request, approval_id, form["decision"] == "approve")
    if request.headers.get("accept") == "application/json":
        return JSONResponse(result)
    return dashboard.render(request, "approval-result.html", "Action result",
                            result=json.dumps(result, ensure_ascii=True, indent=2))


dashboard.register_page("approvals", "Approvals", "approvals.html", listing)


async def consume(request, approval_id, approved):
    item = get(approval_id)
    if not item["prepared"] or not hmac.compare_digest(
            item["hash"], payload_hash(item["name"], item["args"], item["details"])):
        raise HTTPException(409, "Approval payload changed; request a new preview")
    # No await between lookup and consume. Browser/device races execute at most once.
    del pending[approval_id]
    item["result"] = {"status": "executing" if approved else "rejected"}
    completed[approval_id] = item
    while len(completed) > MAX_PENDING:
        del completed[next(iter(completed))]
    if not approved and item["name"] == "export_meeting":
        import asyncio
        import meeting_api
        await asyncio.to_thread(meeting_api.service.reject_export, item["args"]["meeting_id"])
    if approved:
        try:
            item["result"] = await request.app.state.execute_approval(item["name"], item["args"])
        except BaseException:
            item["result"] = {"status": "error", "message": "Outcome may be unknown; inspect destination before retrying"}
            raise
    return item["result"]


class DeviceDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")
    method: Literal["nod", "button", "screen"]


@device_router.post("/{approval_id}/device")
async def device_decide(request: Request, approval_id: str, decision: DeviceDecision):
    if decision.method not in response(approval_id)["allowed_methods"]:
        raise HTTPException(403, "This action requires the capture button or on-screen approval")
    return await consume(request, approval_id, True)


@device_router.post("/{approval_id}/deny")
async def device_deny(request: Request, approval_id: str):
    return await consume(request, approval_id, False)


@device_router.get("/{approval_id}")
async def device_status(approval_id: str):
    prune()
    if approval_id in completed:
        return completed[approval_id]["result"]
    return response(approval_id)
