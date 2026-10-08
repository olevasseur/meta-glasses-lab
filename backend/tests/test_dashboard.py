import json
import re
import sqlite3
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

import app
import dashboard
import memory_api
from memory import Memory
from notes import Notes

SECRET = "dashboard-test-secret"


def csrf(response):
    return re.search(r'name="csrf" value="([^"]+)"', response.text)[1]


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("APP_SHARED_SECRET", SECRET)
    monkeypatch.setenv("GEMINI_API_KEY", "fake")
    monkeypatch.setattr(memory_api, "memory", Memory(tmp_path / "memory.db"))
    monkeypatch.setattr(dashboard, "SETTINGS_PATH", tmp_path / "settings.json")
    monkeypatch.setattr(dashboard, "REPOS_PATH", tmp_path / "repos.json")
    aliases = tmp_path / "aliases.json"
    aliases.write_text('{"inbox": null}')
    monkeypatch.setattr(app, "notes", Notes(token_path=tmp_path / "token.json", aliases_path=aliases,
                                         on_append=dashboard.record_note))
    dashboard.sessions.clear()
    dashboard.recent_errors.clear()
    with TestClient(app.app, follow_redirects=False) as browser:
        yield browser


def login(client):
    token = csrf(client.get("/dashboard/login"))
    response = client.post("/dashboard/login", data={"csrf": token, "secret": SECRET})
    assert response.status_code == 303
    return csrf(client.get("/dashboard/memory"))


def post(client, path, token, **data):
    return client.post("/dashboard" + path, data={"csrf": token, **data})


@pytest.mark.parametrize("path", ["", "/", "/conversations", "/conversations/private", "/memory", "/notes", "/settings", "/status", "/unknown"])
def test_all_pages_require_cookie(client, path):
    response = client.get("/dashboard" + path, headers={"X-App-Secret": SECRET})
    assert response.status_code == 303
    assert response.headers["location"] == "/dashboard/login"
    assert response.headers["cache-control"] == "no-store"


def test_login_constant_time_cookie_rotation_logout_and_api_isolation(client, monkeypatch):
    page = client.get("/dashboard/login")
    prelogin = client.cookies.get(dashboard.COOKIE)
    token = csrf(page)
    compare = Mock(wraps=dashboard.hmac.compare_digest)
    monkeypatch.setattr(dashboard.hmac, "compare_digest", compare)
    assert post(client, "/login", token, secret="wrong").status_code == 401
    assert post(client, "/login", token, secret="non-ascii-☃").status_code == 401
    response = post(client, "/login", token, secret=SECRET)
    compare.assert_any_call(SECRET.encode(), SECRET.encode())
    assert response.status_code == 303
    assert client.cookies.get(dashboard.COOKIE) != prelogin
    cookie = response.headers["set-cookie"]
    assert "HttpOnly" in cookie and "SameSite=strict" in cookie and "Path=/dashboard" in cookie
    assert SECRET not in response.text + cookie
    assert client.post("/token", json={}).status_code == 401
    assert client.post("/dashboardevil", json={}).status_code == 401
    token = csrf(client.get("/dashboard/memory"))
    saved_cookie = client.cookies.get(dashboard.COOKIE)
    assert post(client, "/logout", token).status_code == 303
    client.cookies.set(dashboard.COOKIE, saved_cookie)
    assert client.get("/dashboard/memory").status_code == 303


def test_expiry_secret_rotation_missing_secret_and_https(client, monkeypatch):
    login(client)
    dashboard.sessions[client.cookies.get(dashboard.COOKIE)]["expires"] = 0
    assert client.get("/dashboard/status").status_code == 303
    login(client)
    monkeypatch.setenv("APP_SHARED_SECRET", "rotated")
    assert client.get("/dashboard/status").status_code == 303
    monkeypatch.delenv("APP_SHARED_SECRET")
    assert client.get("/dashboard/login").status_code == 503
    monkeypatch.setenv("APP_SHARED_SECRET", SECRET)
    with TestClient(app.app, base_url="https://testserver") as https:
        assert "Secure" in https.get("/dashboard/login").headers["set-cookie"]


@pytest.mark.parametrize("path", ["/login", "/logout", "/memory/save", "/memory/delete", "/settings/aliases", "/settings/repos", "/settings/preferences", "/status/token"])
def test_csrf_all_mutations(client, path):
    token = login(client)
    assert client.post("/dashboard" + path, data={"secret": SECRET}).status_code == 403
    assert post(client, path, "wrong").status_code == 403
    assert client.post("/dashboard" + path, data={"csrf": token}, headers={"Origin": "https://evil.test"}).status_code == 403
    assert client.post("/dashboard" + path, data={"csrf": token}, headers={"Sec-Fetch-Site": "cross-site"}).status_code == 403


def test_login_csrf_session_binding_and_request_bounds(client):
    token = csrf(client.get("/dashboard/login"))
    assert client.post("/dashboard/login", data={"secret": SECRET}).status_code == 403
    with TestClient(app.app) as other:
        other.get("/dashboard/login")
        assert post(other, "/login", token, secret=SECRET).status_code == 403
    assert client.post("/dashboard/login", json={"csrf": token}).status_code == 415
    assert post(client, "/login", token, secret="a" * 64_000).status_code == 413
    assert client.post("/dashboard/login", content=f"csrf={token}&csrf={token}", headers={"Content-Type": "application/x-www-form-urlencoded"}).status_code == 400


def test_fact_crud_conflicts_suppression_and_escape(client):
    token = login(client)
    assert post(client, "/memory/save", token, key="user.city", text="Lives in Toronto <script>alert(1)</script>").status_code == 303
    response = client.get("/dashboard/memory")
    assert "&lt;script&gt;" in response.text and "<script>alert(1)</script>" not in response.text
    with memory_api.memory.db() as db:
        fact = dict(db.execute("SELECT * FROM facts").fetchone())
    assert post(client, "/memory/save", token, key="user.city", text="duplicate key").status_code == 409
    assert post(client, "/memory/save", token, key="different", text=fact["text"]).status_code == 409
    assert post(client, "/memory/save", token, id=fact["id"], key=fact["key"], text="Lives in Ottawa", updated_at=fact["updated_at"]).status_code == 303
    assert post(client, "/memory/delete", token, id=fact["id"], updated_at=fact["updated_at"]).status_code == 409
    with memory_api.memory.db() as db:
        edited = dict(db.execute("SELECT * FROM facts").fetchone())
    assert edited["id"] == fact["id"] and edited["text"] == "Lives in Ottawa"
    assert post(client, "/memory/save", token, id=edited["id"], key="another", text="Changed", updated_at=edited["updated_at"]).status_code == 422
    assert post(client, "/memory/delete", token, id=edited["id"], updated_at=edited["updated_at"]).status_code == 303
    with memory_api.memory.db() as db:
        assert db.execute("SELECT COUNT(*) FROM facts").fetchone()[0] == 0
        assert db.execute("SELECT key FROM forgotten").fetchone()[0] == edited["key"]
    assert post(client, "/memory/delete", token, id=edited["id"]).status_code == 404
    assert post(client, "/memory/save", token, key="user.city", text="Lives in Ottawa").status_code == 303
    with memory_api.memory.db() as db:
        assert db.execute("SELECT COUNT(*) FROM forgotten").fetchone()[0] == 0


@pytest.mark.parametrize("key,text", [("", "fact"), ("key", " "), ("key", "x" * 501), ("x" * 121, "fact"), ("!!!", "!!!")])
def test_fact_validation(client, key, text):
    assert post(client, "/memory/save", login(client), key=key, text=text).status_code == 422


def test_conversation_search_chunks_timestamps_pagination_and_summaries(client):
    login(client)
    memory_api.memory.ingest("session-toronto", [
        {"seq": 1, "speaker": "user", "text": "onto harbour", "timestamp": "2026-10-07T12:00:01Z"},
        {"seq": 0, "speaker": "user", "text": "Tor", "timestamp": "2026-10-07T12:00:00Z"},
        {"seq": 2, "speaker": "model", "text": "<img src=x onerror=alert(1)>", "timestamp": "2026-10-07T12:00:02Z"},
    ])
    with memory_api.memory.db() as db:
        db.execute("INSERT INTO summaries VALUES (?,?,?)", ("session-toronto", "A harbour conversation", "2026-10-07T13:00:00Z"))
        db.executemany("INSERT INTO sessions VALUES (?,?,?)", [(f"empty-{i}", "2026-10-06", None) for i in range(51)])
    response = client.get("/dashboard/conversations", params={"q": "TORONTO HARBOUR"}, headers={"HX-Request": "true"})
    assert "session-toronto" in response.text and "empty-" not in response.text
    assert "session-toronto" not in client.get("/dashboard/conversations", params={"q": "%' OR 1=1 --"}).text
    assert "Next page" in client.get("/dashboard/conversations").text
    assert "Previous page" in client.get("/dashboard/conversations?page=1").text
    assert client.get("/dashboard/conversations?page=invalid").status_code == 422
    transcript = client.get("/dashboard/conversations/session-toronto")
    assert transcript.text.index("12:00:00Z") < transcript.text.index("12:00:01Z")
    assert "&lt;img" in transcript.text
    assert "A harbour conversation" in client.get("/dashboard/memory").text
    assert client.get("/dashboard/conversations/absent").status_code == 404


def test_settings_validation_absence_atomic_save_and_mode(client, monkeypatch):
    token = login(client)
    assert client.get("/dashboard/settings").status_code == 200
    assert not dashboard.REPOS_PATH.exists()
    assert post(client, "/settings/repos", token, json='{"glasses":"/tmp/repo"}').status_code == 303
    assert json.loads(dashboard.REPOS_PATH.read_text()) == {"glasses": "/tmp/repo"}
    assert post(client, "/settings/repos", token, json='{"glasses":"relative"}').status_code == 422
    assert json.loads(dashboard.REPOS_PATH.read_text()) == {"glasses": "/tmp/repo"}
    for invalid in ['[]', '{broken', '{"work":null}', '{"x":"bad/id"}', '{"x":"id","X":"id2"}']:
        assert post(client, "/settings/aliases", token, json=invalid).status_code == 422
        assert json.loads(app.notes.aliases_path.read_text()) == {"inbox": None}
    assert post(client, "/settings/aliases", token, json='{"inbox":null,"work":"work-id"}').status_code == 303
    assert post(client, "/settings/preferences", token, journal_time="25:30", default_mode="auto").status_code == 422
    assert post(client, "/settings/preferences", token, journal_time="21:30", default_mode="ptt").status_code == 303
    assert dashboard.preferences() == {"journal_time": "21:30", "default_mode": "ptt"}
    create = Mock(return_value={"token": "never-show-this-token"})
    monkeypatch.setattr(app.gemini, "create_token", create)
    assert client.post("/token", json={}, headers={"X-App-Secret": SECRET}).status_code == 200
    assert create.call_args.args[2] == "ptt"
    client.post("/token", json={"mode": "auto"}, headers={"X-App-Secret": SECRET})
    assert create.call_args.args[2] == "auto"
    assert dashboard.SETTINGS_PATH.stat().st_mode & 0o777 == 0o600
    dashboard.SETTINGS_PATH.write_text('{broken')
    assert client.post("/token", json={}, headers={"X-App-Secret": SECRET}).status_code == 200
    assert create.call_args.args[2] == "auto"


def test_status_token_redaction_and_sanitized_errors(client, monkeypatch):
    token = login(client)
    assert "Not configured" in client.get("/dashboard/status").text
    create = Mock(return_value={"token": "never-show-this-token"})
    monkeypatch.setattr(app.gemini, "create_token", create)
    result = post(client, "/status/token", token)
    assert "successfully" in result.text and "never-show-this-token" not in result.text
    create.side_effect = RuntimeError("private credential never-show-this-token")
    result = post(client, "/status/token", token)
    assert "test failed" in result.text and "never-show-this-token" not in result.text
    assert client.post("/token", json={}, headers={"X-App-Secret": SECRET}).status_code == 502
    assert "/token" in client.get("/dashboard/status").text
    assert "never-show-this-token" not in str(dashboard.recent_errors)
    app.notes.token_path.write_text('{bad')
    assert "Credentials unreadable" in client.get("/dashboard/status").text


def test_notes_success_only_history_and_local_failure(client, monkeypatch):
    login(client)
    monkeypatch.setattr(app.notes, "_connect", Mock())
    resolve = Mock(return_value={"status": "matched", "document": {"id": "doc-123", "title": "Work <log>"}})
    monkeypatch.setattr(app.notes, "_resolve", resolve)
    app.notes.docs = Mock()
    response = client.post("/tools/append_to_doc", json={"text": "My note"}, headers={"X-App-Secret": SECRET})
    assert response.json() == {"status": "ok", "doc_title": "Work <log>"}
    page = client.get("/dashboard/notes")
    assert "https://docs.google.com/document/d/doc-123/edit" in page.text and "My note" in page.text
    resolve.return_value = {"status": "needs_confirmation", "candidates": []}
    client.post("/tools/append_to_doc", json={"text": "Unconfirmed"}, headers={"X-App-Secret": SECRET})
    assert "Unconfirmed" not in client.get("/dashboard/notes").text
    resolve.return_value = {"status": "matched", "document": {"id": "doc-123", "title": "Work"}}
    app.notes.docs.documents().batchUpdate().execute.side_effect = RuntimeError("private upstream error")
    response = client.post("/tools/append_to_doc", json={"text": "Failed write"}, headers={"X-App-Secret": SECRET})
    assert response.status_code == 502
    assert "Failed write" not in client.get("/dashboard/notes").text
    app.notes.docs.documents().batchUpdate().execute.side_effect = None
    monkeypatch.setattr(dashboard, "history_table", Mock(side_effect=sqlite3.OperationalError("private data")))
    response = client.post("/tools/append_to_doc", json={"text": "Saved remotely"}, headers={"X-App-Secret": SECRET})
    assert response.json()["status"] == "ok"
    assert "Google append succeeded" in str(dashboard.recent_errors)
    assert "private data" not in str(dashboard.recent_errors)


def test_page_registry_inherits_authentication(client):
    dashboard.register_page("jobs-test", "Jobs test", "notes.html", lambda request: {"history": []})
    try:
        assert client.get("/dashboard/jobs-test").status_code == 303
        login(client)
        assert "Jobs test" in client.get("/dashboard/jobs-test").text
        assert "/dashboard/jobs-test" in client.get("/dashboard/memory").text
    finally:
        del dashboard.PAGES["jobs-test"]
