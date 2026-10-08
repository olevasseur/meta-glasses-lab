"""User approvals live in the browser boundary, never in model tool arguments."""

import hashlib
import hmac
import json
import os
import secrets
import time

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse

import dashboard

SENSITIVE = {"codex_task", "deep_research", "append_to_doc", "forget", "write_journal", "remember", "cancel_job"}
router = APIRouter(prefix="/dashboard/approvals")
pending = {}
MAX_PENDING = 32
TTL = 300


def secret_hash():
    return hashlib.sha256(os.getenv("APP_SHARED_SECRET", "").encode()).digest()


def prune():
    for key, item in list(pending.items()):
        if item["expires"] <= time.monotonic() or item["secret"] != secret_hash():
            del pending[key]


def propose(name, args, *, deduplicate=False):
    prune()
    if deduplicate:
        for key, item in pending.items():
            if item["name"] == name and item["args"] == args:
                return response(key)
    if len(pending) >= MAX_PENDING:
        raise HTTPException(429, "Too many pending approvals; review or wait five minutes")
    approval_id = secrets.token_hex(16)
    pending[approval_id] = {"name": name, "args": dict(args), "expires": time.monotonic() + TTL,
                            "secret": secret_hash(), "prepared": False, "nonce": None, "sid": None}
    return response(approval_id)


def response(approval_id):
    return {"status": "needs_confirmation", "approval_id": approval_id,
            "approval_url": "/dashboard/approvals/" + approval_id,
            "message": "Open Dashboard → Approvals, review the exact request and press Approve. Voice, gestures and tool confirmation fields cannot approve it."}


def get(approval_id):
    prune()
    if approval_id not in pending:
        raise HTTPException(409, "Approval expired or already used; request a new preview")
    return pending[approval_id]


def listing(request):
    prune()
    return {"approvals": [{"id": key, "name": item["name"]} for key, item in pending.items()]}


def install(app, prepare, execute):
    app.state.prepare_approval = prepare
    app.state.execute_approval = execute
    app.include_router(router)


@router.get("/{approval_id}")
async def review(request: Request, approval_id: str):
    item = get(approval_id)
    if not item["prepared"]:
        # Prepare a snapshot only when the human opens it, not on a model's request.
        args, details = await request.app.state.prepare_approval(item["name"], dict(item["args"]))
        item["args"], item["details"], item["prepared"] = args, details, True
    # Recheck after any slow preview; never revive an expired request.
    get(approval_id)
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
    # Consume before awaiting the side effect: concurrent/retried submits cannot repeat it.
    del pending[approval_id]
    if form["decision"] == "reject":
        result = {"status": "rejected"}
    else:
        result = await request.app.state.execute_approval(item["name"], item["args"])
    if request.headers.get("accept") == "application/json":
        return JSONResponse(result)
    return dashboard.render(request, "approval-result.html", "Action result",
                            result=json.dumps(result, ensure_ascii=True, indent=2))


dashboard.register_page("approvals", "Approvals", "approvals.html", listing)
