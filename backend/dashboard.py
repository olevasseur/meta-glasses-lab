"""Local single-user dashboard. Call install() after defining the API routes."""

import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
from collections import deque
from datetime import UTC, datetime
from pathlib import Path
from tempfile import NamedTemporaryFile
from threading import RLock
from time import time
from urllib.parse import parse_qs, quote

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from fastapi.templating import Jinja2Templates

import memory_api
from memory import normalized
from notes import BACKEND_DIR

router = APIRouter(prefix="/dashboard")
templates = Jinja2Templates(directory=BACKEND_DIR / "templates")
SETTINGS_PATH = BACKEND_DIR / "data" / "dashboard_settings.json"
REPOS_PATH = BACKEND_DIR / "codex_repos.json"
COOKIE = "glasses_dashboard"
SESSION_SECONDS = 8 * 60 * 60
# ponytail: process-local sessions/errors suit the existing one-worker backend.
sessions: dict[str, dict] = {}
recent_errors: deque = deque(maxlen=50)
session_lock = RLock()
settings_lock = RLock()
PAGES: dict[str, dict] = {}


def register_page(slug: str, title: str, template: str, loader):
    """Register trusted server code at startup; loader(request) returns template data."""
    if not re.fullmatch(r"[a-z][a-z0-9-]*", slug) or slug in PAGES:
        raise ValueError("Page slug must be unique and URL-safe")
    PAGES[slug] = {"title": title, "template": template, "loader": loader}


def now():
    return datetime.now(UTC).isoformat()


def record_error(operation: str, status: int):
    # Only our fixed operation/route names, never exception text, bodies or URLs.
    recent_errors.append({"timestamp": now(), "operation": operation, "status": status})


def is_dashboard(request):
    return request.url.path == "/dashboard" or request.url.path.startswith("/dashboard/")


def redirect(path):
    return RedirectResponse(path, status_code=303)


def render(request, template, title, status_code=200, **data):
    return templates.TemplateResponse(request=request, name=f"dashboard/{template}",
        context={"title": title, "pages": PAGES, "csrf": request.state.dashboard_session["csrf"],
                 "logged_in": request.state.dashboard_session["authenticated"], **data},
        status_code=status_code)


def new_session(secret, authenticated=False):
    with session_lock:
        current = time()
        for key in list(sessions):
            if sessions[key]["expires"] <= current:
                del sessions[key]
        while len(sessions) >= 256:
            victim = next((key for key, value in sessions.items() if not value["authenticated"]), next(iter(sessions)))
            del sessions[victim]
        sid = secrets.token_urlsafe(32)
        sessions[sid] = {"csrf": secrets.token_urlsafe(32), "authenticated": authenticated,
                         "secret_hash": hashlib.sha256(secret.encode()).digest(),
                         "expires": current + (SESSION_SECONDS if authenticated else 600)}
        return sid, sessions[sid]


def set_cookie(response, request, sid, authenticated):
    response.set_cookie(COOKIE, sid, httponly=True, samesite="strict", path="/dashboard",
                        secure=request.url.scheme == "https",
                        max_age=SESSION_SECONDS if authenticated else 600)


async def protect(request, call_next):
    secret = os.getenv("APP_SHARED_SECRET", "")
    if not secret:
        return HTMLResponse("APP_SHARED_SECRET is not configured", status_code=503)
    sid = request.cookies.get(COOKIE, "")
    with session_lock:
        session = sessions.get(sid)
        if session and (session["expires"] <= time() or
                        session["secret_hash"] != hashlib.sha256(secret.encode()).digest()):
            sessions.pop(sid, None)
            session = None
    login = request.url.path == "/dashboard/login"
    if not session or (not session["authenticated"] and not login):
        if login and request.method == "GET":
            sid, session = new_session(secret)
        else:
            return redirect("/dashboard/login")
    request.state.dashboard_session = session
    request.state.dashboard_sid = sid
    if request.method not in {"GET", "HEAD", "OPTIONS"}:
        # SameSite is defense in depth; every POST, including login, needs a token.
        origin = request.headers.get("origin")
        fetch_site = request.headers.get("sec-fetch-site")
        # Privacy contexts send Origin "null"; then require Fetch Metadata to prove same-origin.
        if origin == "null":
            origin = None if fetch_site == "same-origin" else origin
        if (origin and origin != str(request.base_url).rstrip("/")) or fetch_site in {"cross-site", "same-site"}:
            return HTMLResponse("Cross-site request rejected", status_code=403)
        if request.headers.get("content-type", "").split(";")[0] != "application/x-www-form-urlencoded":
            return HTMLResponse("Expected a form", status_code=415)
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 64_000:
                return HTMLResponse("Form too large", status_code=413)
        try:
            fields = parse_qs(body.decode("utf-8"), keep_blank_values=True, max_num_fields=20)
            if any(len(values) != 1 for values in fields.values()):
                raise ValueError("Repeated field")
            form = {key: values[0] for key, values in fields.items()}
        except (ValueError, UnicodeError):
            return HTMLResponse("Invalid form", status_code=400)
        if not hmac.compare_digest(form.get("csrf", "").encode(), session["csrf"].encode()):
            return HTMLResponse("Invalid CSRF token; reload the page", status_code=403)
        request.state.form = form
    response = await call_next(request)
    if login and request.method == "GET":
        set_cookie(response, request, sid, session["authenticated"])
    return response


def install(app, notes_service, token_test):
    app.state.dashboard_notes = notes_service
    app.state.dashboard_token_test = token_test
    app.include_router(router)

    @app.middleware("http")
    async def dashboard_boundary(request: Request, call_next):
        dashboard_request = is_dashboard(request)
        try:
            response = await protect(request, call_next) if dashboard_request else await call_next(request)
        except Exception:
            route = request.scope.get("route")
            if not dashboard_request:
                record_error(getattr(route, "path", "request"), 500)
                response = JSONResponse(status_code=500, content={"detail": "Backend operation failed"})
                response.headers["Cache-Control"] = "no-store"
                return response
            response = HTMLResponse("Dashboard unavailable. Check storage and configuration, then reload.", status_code=503)
        if response.status_code >= 500:
            route = request.scope.get("route")
            record_error(getattr(route, "path", "request"), response.status_code)
        if dashboard_request:
            response.headers.update({
                "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
                "Referrer-Policy": "same-origin", "X-Frame-Options": "DENY",
                "Content-Security-Policy": "default-src 'self'; script-src https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
            })
        return response


@router.get("/login")
def login_page(request: Request):
    return render(request, "login.html", "Sign in")


@router.post("/login")
def login(request: Request):
    supplied = request.state.form.get("secret", "")
    secret = os.environ["APP_SHARED_SECRET"]
    if not hmac.compare_digest(supplied.encode(), secret.encode()):
        return render(request, "login.html", "Sign in", status_code=401, error="Incorrect secret.")
    with session_lock:
        sessions.pop(request.state.dashboard_sid, None)
        sid, _ = new_session(secret, authenticated=True)
    response = redirect("/dashboard")
    set_cookie(response, request, sid, True)
    return response


@router.post("/logout")
def logout(request: Request):
    with session_lock:
        sessions.pop(request.state.dashboard_sid, None)
    response = redirect("/dashboard/login")
    response.delete_cookie(COOKIE, path="/dashboard")
    return response


@router.get("")
@router.get("/")
def home():
    return redirect("/dashboard/conversations")


def conversations(request):
    query = request.query_params.get("q", "").strip()[:500]
    page = max(0, min(int(request.query_params.get("page", "0")), 100_000))
    memory = memory_api.memory
    with memory.lock, memory.db() as db:
        # Search complete ordered transcripts, including words split across Live chunks.
        rows = db.execute("""SELECT s.*, COALESCE((SELECT group_concat(text, '') FROM
            (SELECT text FROM turns WHERE session_id=s.id ORDER BY seq)), '') AS transcript
            FROM sessions s ORDER BY started_at DESC,id DESC""").fetchall()
    # ponytail: linear search follows memory.recall; use SQLite FTS for a large archive.
    matches = [dict(row) for row in rows if not query or query.casefold() in row["transcript"].casefold()
               or query.casefold() in row["id"].casefold()]
    return {"sessions_list": matches[page * 50:(page + 1) * 50], "q": query,
            "page": page, "has_next": len(matches) > (page + 1) * 50}


@router.get("/conversations/{session_id}")
def transcript(request: Request, session_id: str):
    memory = memory_api.memory
    with memory.lock, memory.db() as db:
        session = db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        if not session:
            raise HTTPException(404, "Conversation not found")
        turns = db.execute("SELECT * FROM turns WHERE session_id=? ORDER BY seq", (session_id,)).fetchall()
    return render(request, "transcript.html", "Conversation", session=session, turns=turns)


def meetings_page(request):
    meeting_id = request.query_params.get("id")
    page = max(0, min(int(request.query_params.get("page", "0")), 100_000))
    memory = memory_api.memory
    with memory.lock, memory.db() as db:
        exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='meetings'").fetchone()
        if meeting_id:
            row = db.execute("SELECT data FROM meetings WHERE id=?", (meeting_id,)).fetchone() if exists else None
            if not row:
                raise HTTPException(404, "Meeting not found")
            meeting = json.loads(row["data"])
            turns = db.execute("SELECT * FROM turns WHERE session_id=? ORDER BY seq", (meeting_id,)).fetchall()
            from meeting_summary import render_minutes
            from urllib.parse import quote
            return {"meeting": meeting, "turns": turns,
                    "minutes": render_minutes(meeting["result"]) if meeting.get("result") else None,
                    "doc_url": "https://docs.google.com/document/d/" + quote(meeting["document"]["id"], safe="") + "/edit"
                    if meeting.get("document") else None}
        rows = db.execute("""SELECT id, json_extract(data, '$.title') AS title,
            json_extract(data, '$.started_at') AS started_at, json_extract(data, '$.phase') AS phase
            FROM meetings ORDER BY started_at DESC, id DESC LIMIT 51 OFFSET ?""", (page * 50,)).fetchall() if exists else []
    return {"meetings_list": rows[:50], "page": page, "has_next": len(rows) > 50}


def memory_page(request):
    memory = memory_api.memory
    with memory.lock, memory.db() as db:
        return {"facts": memory._facts(db), "summaries": db.execute(
            "SELECT * FROM summaries ORDER BY created_at DESC LIMIT 100").fetchall()}


@router.post("/memory/save")
def save_fact(request: Request):
    form = request.state.form
    fact, key = form.get("text", "").strip(), form.get("key", "").strip()
    if not (0 < len(fact) <= 500 and 0 < len(key) <= 120 and normalized(fact) and normalized(key)):
        raise HTTPException(422, "Fact and key must contain words (maximum 500 / 120 characters)")
    memory = memory_api.memory
    with memory.lock, memory.db() as db:
        if form.get("id"):
            original = db.execute("SELECT * FROM facts WHERE id=?", (form["id"],)).fetchone()
            if not original:
                raise HTTPException(404, "Fact not found")
            if original["updated_at"] != form.get("updated_at"):
                raise HTTPException(409, "Fact changed; reload before editing")
            if original["key"] != normalized(key):
                raise HTTPException(422, "The fact key cannot change during an edit")
        elif db.execute("SELECT 1 FROM facts WHERE key=?", (normalized(key),)).fetchone():
            raise HTTPException(409, "Key already exists; edit that fact instead")
        duplicate = db.execute("SELECT key FROM facts WHERE normalized=?", (normalized(fact),)).fetchone()
        if duplicate and duplicate[0] != normalized(key):
            raise HTTPException(409, "That fact already exists under another key")
        memory._restore_forgotten(db, normalized(key), fact)
        memory._upsert(db, normalized(key), fact)
    return redirect("/dashboard/memory")


@router.post("/memory/delete")
def delete_fact(request: Request):
    memory = memory_api.memory
    with memory.lock, memory.db() as db:
        fact = db.execute("SELECT * FROM facts WHERE id=?", (request.state.form.get("id", ""),)).fetchone()
        if not fact:
            raise HTTPException(404, "Fact not found")
        if fact["updated_at"] != request.state.form.get("updated_at"):
            raise HTTPException(409, "Fact changed; reload before deleting")
        memory._delete_fact(db, fact, fact["text"])
    return redirect("/dashboard/memory")


def history_table(db):
    db.execute("""CREATE TABLE IF NOT EXISTS note_history (
        id INTEGER PRIMARY KEY, doc_id TEXT NOT NULL, title TEXT NOT NULL,
        text TEXT NOT NULL, created_at TEXT NOT NULL)""")


def record_note(document, text):
    # A local audit failure must never report a successful remote append as failed.
    try:
        memory = memory_api.memory
        with memory.lock, memory.db() as db:
            history_table(db)
            db.execute("INSERT INTO note_history(doc_id,title,text,created_at) VALUES (?,?,?,?)",
                       (document["id"], document["title"], text, now()))
    except (OSError, sqlite3.Error):
        record_error("note history (Google append succeeded)", 503)


def notes_page(request):
    memory = memory_api.memory
    with memory.lock, memory.db() as db:
        history_table(db)
        history = [dict(row) for row in db.execute("SELECT * FROM note_history ORDER BY id DESC LIMIT 100")]
    for note in history:
        note["url"] = "https://docs.google.com/document/d/" + quote(note["doc_id"], safe="") + "/edit"
    return {"history": history}


def read_json(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default


def preferences():
    values = read_json(SETTINGS_PATH, {})
    if not isinstance(values, dict):
        raise ValueError("Invalid dashboard settings")
    return {"journal_time": os.getenv("JOURNAL_TIME", "21:30"), "default_mode": "auto", **values}


def journal_time():
    # Saved dashboard settings take precedence; reread them on each scheduler tick.
    try:
        value = preferences()["journal_time"]
        if isinstance(value, str) and re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", value):
            return value
    except (OSError, ValueError, TypeError):
        pass
    return os.getenv("JOURNAL_TIME", "21:30")


def default_mode():
    # An unreadable optional preference must not break the Android token API.
    try:
        mode = preferences()["default_mode"]
        return mode if mode in {"auto", "ptt", "over"} else "auto"
    except (OSError, ValueError, TypeError):
        return "auto"


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(value, stream, indent=2, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def settings_page(request):
    notes = request.app.state.dashboard_notes()
    # Show malformed files verbatim so the editor can repair them.
    def contents(path, default):
        return path.read_text(encoding="utf-8") if path.exists() else default
    return {"aliases": contents(notes.aliases_path, '{"inbox": null}'),
            "repos": contents(REPOS_PATH, '{}'), "preferences": preferences(),
            "job_agents": request.app.state.jobs.agent_settings()}


@router.post("/settings/{section}")
def save_settings(request: Request, section: str):
    form = request.state.form
    try:
        with settings_lock:
            if section == "preferences":
                mode, journal_time = form.get("default_mode"), form.get("journal_time", "")
                if mode not in {"auto", "ptt", "over"} or not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", journal_time):
                    raise ValueError("Choose auto/PTT/Over and a valid 24-hour journal time")
                atomic_json(SETTINGS_PATH, {**preferences(), "default_mode": mode, "journal_time": journal_time})
            elif section == "job-agents":
                value = {}
                for agent in ("codex", "claude"):
                    model = form.get(agent + "_model", "").strip()
                    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,119}", model):
                        raise ValueError("Invalid model name")
                    value[agent] = {"enabled": form.get(agent + "_enabled") == "on", "model": model}
                atomic_json(request.app.state.jobs.data_dir / "job-settings.json", value)
            elif section in {"aliases", "repos"}:
                value = json.loads(form.get("json", ""))
                if not isinstance(value, dict) or any(not key.strip() for key in value):
                    raise ValueError("Use a JSON object with nonempty names")
                if section == "aliases":
                    if len({key.strip().casefold() for key in value}) != len(value):
                        raise ValueError("Alias names must be unique ignoring case/whitespace")
                    if any(not (isinstance(v, str) and re.fullmatch(r"[A-Za-z0-9_-]+", v))
                           and not (k.strip().casefold() == "inbox" and v is None) for k, v in value.items()):
                        raise ValueError("Aliases map names to document IDs; only inbox may be null")
                    atomic_json(request.app.state.dashboard_notes().aliases_path, value)
                else:
                    if any(not isinstance(v, str) or not Path(v).is_absolute() or "\x00" in v for v in value.values()):
                        raise ValueError("Repository names must map to absolute directory paths")
                    atomic_json(REPOS_PATH, value)
            else:
                raise HTTPException(404, "Unknown settings section")
    except (ValueError, TypeError):
        return render(request, "settings.html", "Settings", status_code=422,
                      error="Invalid settings. Use the format shown below; nothing was saved.",
                      **{**settings_page(request), **({section: form.get("json", "")} if section in {"aliases", "repos"} else {})})
    return redirect("/dashboard/settings")


def status_page(request):
    memory = memory_api.memory
    try:
        with memory.lock, memory.db() as db:
            db.execute("SELECT 1").fetchone()
        storage = "Ready"
    except (OSError, sqlite3.Error):
        storage = "Unavailable"
    notes = request.app.state.dashboard_notes()
    # Inspect locally only; credentials, refresh tokens and errors never enter HTML.
    from google.oauth2.credentials import Credentials
    from notes import SCOPES
    try:
        credentials = Credentials.from_authorized_user_file(str(notes.token_path), SCOPES)
        google = "Credentials valid locally (not checked with Google)" if credentials.valid else (
            "Refresh required; refresh token present (not checked with Google)" if credentials.refresh_token else "Reauthorize Google")
    except FileNotFoundError:
        google = "Not configured; run scripts/google_auth.py"
    except (ValueError, OSError):
        google = "Credentials unreadable; run scripts/google_auth.py"
    return {"storage": storage, "google": google, "errors": list(recent_errors.copy())[::-1]}


@router.post("/status/gemini")
def test_gemini(request: Request):
    from gemini_health import health
    result = health()
    details = "; ".join(f"{item['model']}: {item['status']}" + (f" ({item['code']})" if item.get("code") else "") for item in result["models"])
    message = f"Live setup: {result['status']} (checked {result['checked_at']}; cached={result['cached']}). {details}"
    return render(request, "status.html", "Status", token_result=message, **status_page(request))


@router.post("/status/token")
def test_token(request: Request):
    try:
        request.app.state.dashboard_token_test()
        message = "Gemini token created successfully. Token discarded; Live connection not tested."
    except HTTPException as exc:
        record_error("Gemini token test", exc.status_code)
        message = "Gemini token test failed. Check backend key, model access and quota."
    return render(request, "status.html", "Status", token_result=message, **status_page(request))


# Register built-ins; extension pages get the same authentication/CSRF boundary.
register_page("conversations", "Conversations", "conversations.html", conversations)
register_page("meetings", "Meetings", "meetings.html", meetings_page)
register_page("memory", "Memory", "memory.html", memory_page)
register_page("notes", "Notes", "notes.html", notes_page)
register_page("settings", "Settings", "settings.html", settings_page)
register_page("status", "Status", "status.html", status_page)


register_page("jobs", "Jobs", "jobs.html", lambda request: {})


@router.get("/jobs")
async def jobs_page(request: Request):
    return render(request, "jobs.html", "Jobs", jobs=request.app.state.jobs.status()["jobs"])


@router.get("/{slug}")
def page(request: Request, slug: str):
    definition = PAGES.get(slug)
    if not definition:
        raise HTTPException(404, "Page not found")
    try:
        data = definition["loader"](request)
    except ValueError:
        raise HTTPException(422, "Invalid page parameters or configuration") from None
    return render(request, definition["template"], definition["title"], **data)
