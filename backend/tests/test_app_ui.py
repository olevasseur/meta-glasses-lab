import json

import pytest
from fastapi.testclient import TestClient

import app
import dashboard
import memory_api
from coach import Projects
from memory import Memory


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("APP_SHARED_SECRET", "ui-test-secret")
    monkeypatch.setenv("COACH_DB_PATH", str(tmp_path / "coach.db"))
    monkeypatch.setattr(memory_api, "memory", Memory(tmp_path / "memory.db"))
    with TestClient(app.app, base_url="http://localhost") as client:
        yield client


def test_overview_auth_and_empty_snapshot(client):
    assert client.get("/ui/overview").status_code == 401
    response = client.get("/ui/overview", headers={"X-App-Secret": "ui-test-secret"})
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert response.json() == {"facts": [], "notes": [], "meetings": [], "jobs": [], "project": None}
    assert not Projects().path.exists()  # A review must not create a project database.


def test_overview_is_bounded_recent_and_does_not_advance_or_mutate(client):
    memory = memory_api.memory
    for i in range(7):
        memory.remember(f"Fact {i}")
        dashboard.record_note({"id": f"doc-{i}", "title": f"Note {i}"}, "x" * 600)
    with memory.lock, memory.db() as db:
        db.execute("CREATE TABLE meetings (id TEXT PRIMARY KEY, data TEXT)")
        for i in range(7):
            db.execute("INSERT INTO meetings VALUES (?, ?)", (str(i), json.dumps(
                {"title": f"Meeting {i}", "phase": "complete", "started_at": f"2026-10-0{i+1}"})))
    project = {"title": "Soup", "steps": [{"instruction": "Heat water"}, {"instruction": "Add vegetables"}]}
    with Projects().connect() as db:
        db.execute("INSERT INTO project VALUES (1, ?, 1, '2', 'cooking')", (json.dumps(project),))
    # Seed a completed job through the event loop, where Jobs' SQLite connection lives.
    async def seed_jobs():
        for i in range(7):
            app.app.state.jobs._insert("research", "completed", {"private": "not-for-overview"}, f"Result {i}")
    client.portal.call(seed_jobs)
    response = client.get("/ui/overview", headers={"X-App-Secret": "ui-test-secret"})
    assert response.status_code == 200
    data = response.json()
    for key in ("facts", "notes", "meetings", "jobs"):
        assert len(data[key]) == 5
    assert data["facts"][0]["text"] == "Fact 6"
    assert data["notes"][0]["title"] == "Note 6"
    assert len(data["notes"][0]["text"]) == 500
    assert data["meetings"][0]["title"] == "Meeting 6"
    assert "not-for-overview" not in response.text
    assert data["project"] == {"title": "Soup", "step_number": 2, "step_count": 2, "instruction": "Add vegetables"}
    with Projects().connect() as db:
        assert db.execute("SELECT position,scale FROM project").fetchone() == (1, "2")
    with memory.lock, memory.db() as db:
        assert db.execute("SELECT count(*) FROM facts").fetchone()[0] == 7
        assert db.execute("SELECT count(*) FROM note_history").fetchone()[0] == 7


def test_completed_project_has_no_active_banner(client):
    with Projects().connect() as db:
        db.execute("INSERT INTO project VALUES (1, ?, 1, '1', 'diy')", (json.dumps(
            {"title": "Shelf", "steps": [{"instruction": "Finish"}]}),))
    response = client.get("/ui/overview", headers={"X-App-Secret": "ui-test-secret"})
    assert response.json()["project"] is None


def test_nicknames_auth_and_sorted_list(client, monkeypatch, tmp_path):
    aliases = tmp_path / "doc_aliases.json"
    aliases.write_text(json.dumps({"inbox": None, "zeta notes": "doc-z", "reading notes": "doc-r"}))
    monkeypatch.setattr(app.notes, "aliases_path", aliases)
    assert client.get("/ui/nicknames").status_code == 401
    response = client.get("/ui/nicknames", headers={"X-App-Secret": "ui-test-secret"})
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert response.json() == {"nicknames": ["inbox", "reading notes", "zeta notes"]}
