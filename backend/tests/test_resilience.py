import asyncio
import base64
import io
from datetime import UTC, datetime
from types import SimpleNamespace
from unittest.mock import Mock

import httpx
import pytest
from fastapi.testclient import TestClient
from google.genai.errors import APIError
from PIL import Image

import app
import gemini_health
import memory_api
from gemini_resilience import GeminiUnavailable, generate_content, live_model, models, transient
from memory import Memory
from resilience_work import defer, retry_saved
from visual_memory import Caption, Frame, VisualMemory


def api_error(code):
    return APIError(code, {"error": {"message": "sensitive upstream value"}})


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("APP_SHARED_SECRET", "test-resilience")
    monkeypatch.setenv("GEMINI_API_KEY", "fixture")
    monkeypatch.setattr(memory_api, "memory", Memory(tmp_path / "memory.db"))
    monkeypatch.setattr(gemini_health, "_cached", None)
    with TestClient(app.app, headers={"X-App-Secret": "test-resilience"}) as client:
        yield client


def test_live_fallback_order_and_exhaustion(monkeypatch):
    monkeypatch.setenv("GEMINI_LIVE_MODELS", "first, second,first,third")
    assert models("LIVE") == ["first", "second", "third"]
    assert live_model() == "first"
    assert live_model(["first"]) == "second"
    assert live_model(["first", "second", "third"]) == "first"


def test_token_fallback_retains_locking_and_transient_status(client, monkeypatch):
    monkeypatch.setenv("GEMINI_LIVE_MODELS", "first,second")
    create = Mock(return_value={"model": "second", "token": "not-for-logs"})
    monkeypatch.setattr(app.gemini, "create_token", create)
    response = client.post("/token", json={"avoid_models": ["first"]})
    assert response.json()["model"] == "second"
    assert create.call_args.args[1] == "second"
    create.side_effect = api_error(503)
    response = client.post("/token", json={})
    assert response.status_code == 503
    assert "sensitive" not in response.text
    create.side_effect = api_error(403)
    assert client.post("/token", json={}).status_code == 422
    config = app.gemini.token_config("first", "auto")
    assert config.lock_additional_fields == []
    assert config.live_connect_constraints.config.session_resumption is None


def test_retry_rounds_and_permanent_errors(monkeypatch):
    monkeypatch.setenv("GEMINI_TEXT_MODELS", "first,second")
    generate = Mock(side_effect=[api_error(503), "ok"])
    client = SimpleNamespace(models=SimpleNamespace(generate_content=generate))
    sleep = Mock()
    assert generate_content(client, model="ignored", contents="data", sleep=sleep) == "ok"
    assert [c.kwargs["model"] for c in generate.call_args_list] == ["first", "second"]
    generate.reset_mock(side_effect=True)
    generate.side_effect = api_error(429)
    with pytest.raises(GeminiUnavailable):
        generate_content(client, model="first", contents="data", sleep=sleep, jitter=lambda: .5)
    assert generate.call_count == 4
    sleep.assert_called_once_with(1.5)
    generate.reset_mock(side_effect=True)
    generate.side_effect = api_error(400)
    with pytest.raises(APIError):
        generate_content(client, model="first", contents="data", sleep=sleep)
    assert generate.call_count == 1
    assert transient(httpx.ConnectError("offline"))
    assert not transient(api_error(403))


def test_health_is_authenticated_cached_and_never_exposes_secrets(client, monkeypatch):
    monkeypatch.setenv("GEMINI_LIVE_MODELS", "first,second")
    probe = Mock(side_effect=[RuntimeError("sensitive token"), None])
    monkeypatch.setattr(gemini_health, "probe", probe)
    assert client.get("/health/gemini", headers={"X-App-Secret": "wrong"}).status_code == 401
    response = client.get("/health/gemini")
    assert response.json()["status"] == "available"
    assert "sensitive" not in response.text
    assert probe.call_count == 2
    assert client.get("/health/gemini").json()["cached"] is True
    assert probe.call_count == 2


def test_offline_save_and_health_coexist_with_main_ui_overview(client, monkeypatch):
    probe = Mock()
    monkeypatch.setattr(gemini_health, "probe", probe)
    response = client.post("/tools/remember", json={"fact": "Keys are in the kitchen"},
                           headers={"X-Offline-Id": "44444444-4444-4444-4444-444444444444"})
    assert response.status_code == 200
    assert response.json()["status"] == "ok"
    for path in ("/ui/overview", "/health/gemini"):
        assert client.get(path, headers={"X-App-Secret": "wrong"}).status_code == 401
        response = client.get(path)
        assert response.status_code == 200
        assert response.headers["cache-control"] == "no-store"
    assert [fact["text"] for fact in client.get("/ui/overview").json()["facts"]] == ["Keys are in the kitchen"]
    assert client.get("/health/gemini").json()["cached"] is True
    probe.assert_called_once()


def test_summary_queue_survives_unavailability_and_replays_once(client, monkeypatch):
    memory = memory_api.memory
    end = Mock(side_effect=GeminiUnavailable())
    monkeypatch.setattr(memory, "end", end)
    assert client.post("/sessions/saved/end").json()["status"] == "processing"
    defer(memory, "summary", "saved")
    with pytest.raises(GeminiUnavailable):
        retry_saved(memory, None)
    end.side_effect = None
    end.return_value = {"status": "ok"}
    retry_saved(memory, None)
    retry_saved(memory, None)
    assert end.call_count == 3
    with memory.db() as db:
        assert db.execute("SELECT COUNT(*) FROM ai_pending").fetchone()[0] == 0


def test_offline_receipt_replay_and_tier_zero_boundary(client, monkeypatch):
    receipt = "11111111-1111-1111-1111-111111111111"
    headers = {"X-Offline-Id": receipt}
    request = {"fact": "Keys are in the kitchen"}
    first = client.post("/tools/remember", json=request, headers=headers)
    assert first.status_code == 200
    assert first.json()["status"] == "ok"
    assert client.post("/tools/remember", json=request, headers=headers).json() == first.json()
    assert client.post("/tools/remember", json={"fact": "different"}, headers=headers).status_code == 409
    assert client.post("/tools/forget", json={"query": "keys"}, headers=headers).status_code == 422
    preview = Mock(return_value={"status": "matched", "document": {"id": app.approval_actions.CREATE_INBOX}})
    monkeypatch.setattr(app.notes, "preview", preview)
    response = client.post("/tools/append_to_doc", json={"text": "Buy milk", "doc_name": "Inbox"},
                           headers={"X-Offline-Id": "22222222-2222-2222-2222-222222222222"})
    assert response.json()["status"] == "needs_destination"
    assert not app.approvals.pending


def test_offline_uncertain_write_is_not_repeated(client, monkeypatch):
    receipt = "33333333-3333-3333-3333-333333333333"
    monkeypatch.setattr(app.notes, "preview", Mock(return_value={"status": "matched", "document": {"id": "existing"}}))
    writes = 0
    async def fail(*args):
        nonlocal writes
        writes += 1
        raise RuntimeError("Response lost after save")
    monkeypatch.setattr(app, "execute_tool", fail)
    for _ in range(2):
        response = client.post("/tools/append_to_doc", json={"text": "Buy milk", "doc_name": "Inbox"}, headers={"X-Offline-Id": receipt})
        assert response.json()["status"] == "unknown"
    assert writes == 1


def test_caption_source_survives_outage_replays_and_deletion_clears_queue(tmp_path):
    buffer = io.BytesIO()
    Image.new("RGB", (40, 40), "blue").save(buffer, format="JPEG")
    now = datetime.now(UTC)
    frame = Frame(jpeg=base64.b64encode(buffer.getvalue()).decode(), captured_at=now)
    captioner = Mock(side_effect=GeminiUnavailable())
    memory = VisualMemory(tmp_path, captioner=captioner, clock=lambda: now.timestamp())
    result = memory.capture(frame)
    assert result["status"] == "ok"
    assert result["caption_status"] == "pending"
    assert memory.thumbnail(result["id"])
    captioner.side_effect = None
    captioner.return_value = Caption(caption="A blue wall", objects=["wall"], place_type="room", readable_text=[])
    memory.retry_captions()
    assert memory.browse()["captures"][0]["caption"] == "A blue wall"
    memory.retry_captions()
    assert captioner.call_count == 2
    captioner.side_effect = GeminiUnavailable()
    second = memory.capture(frame)
    memory.delete_one(second["id"])
    memory.retry_captions()  # Deleted source must never be re-created.


def test_recipe_source_is_durable_and_background_does_not_replace_active_project(tmp_path, monkeypatch):
    import coach
    projects = coach.Projects(tmp_path / "coach.db")
    extract = Mock(side_effect=GeminiUnavailable())
    monkeypatch.setattr(coach, "extract_project", extract)
    assert projects.start("cooking", "Boil water")['status'] == 'queued'
    assert projects.start("cooking", "Make tea")['status'] == 'queued'
    with projects.connect() as db:
        assert {r[0] for r in db.execute("SELECT source FROM pending_project")} == {"Boil water", "Make tea"}
        assert db.execute("SELECT COUNT(*) FROM project").fetchone()[0] == 0
    project = SimpleNamespace(model_dump_json=lambda: '{"ready": true}')
    extract.side_effect = None
    extract.return_value = project
    projects.retry_pending()
    with projects.connect() as db:
        assert db.execute("SELECT result FROM pending_project").fetchone()[0] == '{"ready": true}'
        assert db.execute("SELECT COUNT(*) FROM project").fetchone()[0] == 0


def test_text_deadline_and_missing_model_configuration(monkeypatch):
    monkeypatch.setenv("GEMINI_TEXT_MODELS", "first,second")
    generate = Mock(side_effect=api_error(503))
    client = SimpleNamespace(models=SimpleNamespace(generate_content=generate))
    ticks = iter([0, 0, 16])
    with pytest.raises(GeminiUnavailable):
        generate_content(client, model="first", clock=lambda: next(ticks))
    assert generate.call_count == 1
    assert generate.call_args.kwargs["config"].http_options.timeout == 20_000
    assert generate.call_args.kwargs["config"].http_options.retry_options.attempts == 1
    generate.reset_mock(side_effect=True)
    generate.side_effect = api_error(404)
    with pytest.raises(APIError):
        generate_content(client, model="first")
    assert generate.call_count == 2


def test_journal_queue_builds_draft_without_docs_write(tmp_path):
    memory = Memory(tmp_path / "memory.db")
    journal = SimpleNamespace(_entry=Mock(), write_journal=Mock())
    defer(memory, "journal", "2026-10-08")
    retry_saved(memory, journal)
    journal._entry.assert_called_once()
    journal.write_journal.assert_not_called()
    retry_saved(memory, journal)
    assert journal._entry.call_count == 1
