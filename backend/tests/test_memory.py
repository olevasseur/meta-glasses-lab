import json
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock

import httpx
import pytest
from fastapi.testclient import TestClient
from google.genai.errors import APIError

import app
import gemini
import memory
import memory_api
import memory_summary
from memory import Memory, MemoryConflict
from memory_summary import Fact, Summary


@pytest.fixture
def store(tmp_path):
    return Memory(tmp_path / "memory.db")


@pytest.fixture
def client(store, monkeypatch):
    monkeypatch.setenv("APP_SHARED_SECRET", "test-secret")
    monkeypatch.setattr(memory_api, "memory", store)
    with TestClient(app.app, headers={"X-App-Secret": "test-secret"}) as client:
        yield client


def turn(seq=0, text="I live in Toronto.", speaker="user"):
    return {"seq": seq, "speaker": speaker, "text": text, "timestamp": "2026-10-07T12:00:00Z"}


def result(text="The user lives in Toronto.", key="user.home_city"):
    return Summary(summary="Discussed the user's home city.", facts=[Fact(key=key, text=text)])


@pytest.mark.parametrize("path", ["/transcripts", "/sessions/test/end", "/tools/remember", "/tools/recall", "/tools/forget"])
def test_memory_routes_require_secret(client, path):
    assert client.post(path, json={}, headers={"X-App-Secret": "wrong"}).status_code == 401


@pytest.mark.parametrize("change", [
    {"speaker": "system"}, {"seq": -1}, {"seq": 1.5}, {"text": ""},
    {"text": "x" * 12001}, {"timestamp": "2026-10-07T12:00:00"},
])
def test_transcript_validation(client, change):
    assert client.post("/transcripts", json={"session_id": "test", "turns": [{**turn(), **change}]}).status_code == 422


def test_invalid_batch_and_tool_arguments(client):
    for turns in ([], [turn()] * 51):
        assert client.post("/transcripts", json={"session_id": "test", "turns": turns}).status_code == 422
    assert client.post("/transcripts", json={"session_id": "../x", "turns": [turn()]}).status_code == 422
    for tool, args in (("remember", {"fact": " "}), ("recall", {"query": " "}),
                       ("forget", {"query": "Toronto", "confirmed": True})):
        assert client.post(f"/tools/{tool}", json=args).status_code == 422


def test_upload_dedup_order_conflicts_and_atomicity(store):
    assert store.ingest("s", [turn(1, "Toronto."), turn(0, "I live in ")])["stored"] == 2
    assert store.ingest("s", [turn(0, "I live in ")])["stored"] == 0
    with pytest.raises(MemoryConflict):
        store.ingest("s", [turn(2, "new"), turn(0, "changed")])
    with store.db() as db:
        assert db.execute("SELECT count(*) FROM turns").fetchone()[0] == 2
    assert "I live in Toronto." in store.recall("Toronto")["matches"][0]["text"]
    assert store.path.stat().st_mode & 0o777 == 0o600
    assert Memory(store.path).recall("Tornto")["matches"]


def test_end_is_idempotent_and_concurrent_calls_do_not_repeat_summary(store, monkeypatch):
    store.ingest("s", [turn()])
    summarize = Mock(return_value=result())
    monkeypatch.setattr(memory, "summarize", summarize)
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(store.end, ["s", "s"]))
    assert all(r["status"] == "ok" for r in responses)
    summarize.assert_called_once()
    assert store.ingest("s", [turn()])["stored"] == 0
    with pytest.raises(MemoryConflict):
        store.ingest("s", [turn(1)])
    with store.db() as db:
        assert db.execute("SELECT count(*) FROM facts").fetchone()[0] == 1
    assert store.end("empty")["facts"] == []
    assert store.end("empty")["already_ended"]


def test_failed_summary_retains_transcripts_and_allows_retry(client, store, monkeypatch):
    client.post("/transcripts", json={"session_id": "s", "turns": [turn()]})
    summarize = Mock(side_effect=APIError(429, {"error": {"message": "private-key"}}))
    monkeypatch.setattr(memory, "summarize", summarize)
    response = client.post("/sessions/s/end")
    assert response.status_code == 502
    assert "private-key" not in response.text
    with store.db() as db:
        assert db.execute("SELECT ended_at FROM sessions").fetchone()[0] is None
        assert db.execute("SELECT count(*) FROM turns").fetchone()[0] == 1
    summarize.side_effect = None
    summarize.return_value = result()
    assert client.post("/sessions/s/end").status_code == 200
    assert client.post("/transcripts", json={"session_id": "s", "turns": [turn(1)]}).status_code == 409


def test_fact_dedupe_and_correction(store, monkeypatch):
    first = store.remember("The user lives in Toronto.", "user.home_city")
    same = store.remember("THE USER LIVES IN TORONTO!")
    assert first["fact"]["id"] == same["fact"]["id"]
    store.ingest("s", [turn(text="I moved to Montreal.")])
    monkeypatch.setattr(memory, "summarize", Mock(return_value=result("The user lives in Montreal.")))
    store.end("s")
    with store.db() as db:
        facts = store._facts(db)
    assert len(facts) == 1
    assert facts[0]["text"] == "The user lives in Montreal."
    assert "Toronto" not in store.context("base")


def test_correction_merges_duplicate_value_without_leaving_stale_fact(store):
    store.remember("Toronto", "user.home_city")
    store.remember("Montreal", "duplicate")
    store.remember("Montreal", "user.home_city")
    with store.db() as db:
        assert [f["text"] for f in store._facts(db)] == ["Montreal"]


def test_recall_searches_all_sources_and_typos(store, monkeypatch):
    store.remember("The user's dog is named Maple.", "user.pet.name")
    store.ingest("s", [turn(text="We walked near the harbour."), turn(1, "Sounds peaceful.", "model")])
    monkeypatch.setattr(memory, "summarize", Mock(return_value=Summary(summary="Discussed a harbour walk.", facts=[])))
    store.end("s")
    assert store.recall("Mapel")["matches"][0]["kind"] == "fact"
    assert {m["kind"] for m in store.recall("harbour")["matches"]} == {"summary", "transcript"}
    assert store.recall("zxqvzz")["matches"] == []


def test_forget_requires_bound_unexpired_confirmation_and_blocks_reextraction(client, store, monkeypatch):
    store.remember("The user's dog is named Maple.", "user.pet.name")
    preview = client.post("/tools/forget", json={"query": "Maple"}).json()
    assert preview["status"] == "needs_confirmation"
    assert store.recall("Maple")["matches"]
    token = preview["confirmation_token"]
    assert client.post("/tools/forget", json={"query": "dog", "confirmation_token": token}).status_code == 409
    confirmed = client.post("/tools/forget", json={"query": "Maple", "confirmation_token": token})
    assert confirmed.json() == {"status": "ok", "deleted": 1}
    assert not store.recall("Maple")["matches"]
    assert "Maple" not in store.context("base")
    store.ingest("s", [turn(text="My dog is named Maple.")])
    monkeypatch.setattr(memory, "summarize", Mock(return_value=Summary(
        summary="The user has a dog named Maple.", facts=[Fact(key="user.pet.name", text="The user's dog is Maple.")],
    )))
    assert store.end("s")["facts"] == []
    assert not store.recall("Maple")["matches"]
    assert "Maple" not in store.context("base")
    store.remember("The user's dog is named Maple.", "user.pet.name")
    assert store.recall("Maple")["matches"]


def test_changed_and_expired_confirmation_never_deletes(store, monkeypatch):
    store.remember("Toronto", "city")
    preview = store.forget("Toronto")
    store.remember("Montreal", "city")
    with pytest.raises(MemoryConflict):
        store.forget("Toronto", preview["confirmation_token"])
    preview = store.forget("Montreal")
    monkeypatch.setattr(memory, "time", lambda: 10**12)
    with pytest.raises(MemoryConflict):
        store.forget("Montreal", preview["confirmation_token"])
    assert store.recall("Montreal")["matches"]


def test_context_budget_and_latest_three_summaries(store):
    for i in range(40):
        store.remember(f"Fact {i}: " + "猫" * 150, f"topic {i}")
    with store.db() as db:
        for i in range(5):
            db.execute("INSERT INTO sessions VALUES (?,?,?)", (str(i), str(i), str(i)))
            db.execute("INSERT INTO summaries VALUES (?,?,?)", (str(i), f"Session marker {i}", str(i)))
    context = store.context(gemini.PROMPT_PATH.read_text())
    assert len(context.encode()) <= 8000
    assert "submit_reading" in context
    assert "start_project" in context
    assert "Pending yes/no questions" in context
    assert "at most twice" in context
    assert "What you know about the user" in context
    assert "Session marker 4" in context
    assert "Session marker 0" not in context and "Session marker 1" not in context


@pytest.mark.parametrize("mode", ["auto", "ptt", "over", "meeting"])
def test_token_locks_memory_and_registry_but_not_resumption(client, store, monkeypatch, mode):
    store.remember("The user likes kayaking.")
    monkeypatch.setenv("GEMINI_API_KEY", "fake-key")
    factory = Mock(return_value={"token": "redacted", "model": gemini.DEFAULT_MODEL})
    monkeypatch.setattr(gemini, "create_token", factory)
    assert client.post("/token", json={"mode": mode, "silence_ms": 3250}).status_code == 200
    assert factory.call_args.args == ("fake-key", gemini.DEFAULT_MODEL, mode)
    assert factory.call_args.kwargs["silence_ms"] == 3250
    config = gemini.token_config(gemini.DEFAULT_MODEL, mode, **factory.call_args.kwargs)
    live = config.live_connect_constraints.config
    assert "kayaking" in live.system_instruction
    detection = live.realtime_input_config.automatic_activity_detection
    assert detection.disabled is (mode in {"ptt", "over"})
    if mode in {"auto", "meeting"}:
        assert detection.silence_duration_ms == 3250
    assert {t.name for t in live.tools[0].function_declarations} == {
        "append_to_doc", "remember", "recall", "forget", "await_gesture_confirmation", "look", "set_watch_mode",
        "codex_task", "deep_research", "job_status", "cancel_job",
        "start_translation", "stop_translation", "start_meeting", "stop_meeting",
        "read_text", "submit_reading", "reading_control",
        "write_journal", "read_journal", "morning_brief",
        "start_project", "next_step", "previous_step", "repeat_step", "current_step", "how_much", "scale_project",
        "check_progress", "set_timer", "list_timers", "cancel_timer", "set_nod_navigation",
        "start_workout", "stop_workout", "workout_status", "workout_history",
    }
    assert config.lock_additional_fields == [] and live.session_resumption is None


def test_real_sdk_summary_serialization_and_validation(monkeypatch):
    requests = []
    monkeypatch.setenv("GEMINI_API_KEY", "fake-key")
    monkeypatch.delenv("GEMINI_SUMMARY_MODEL", raising=False)
    def send(_client, request, **kwargs):
        requests.append(request)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": result().model_dump_json()}]}}]}, request=request)
    monkeypatch.setattr(httpx.Client, "send", send)
    assert memory_summary.summarize([turn()], [], []).facts[0].key == "user.home_city"
    request, = requests
    assert "gemini-3.5-flash-lite:generateContent" in str(request.url)
    body = json.loads(request.content)
    assert body["generationConfig"]["responseMimeType"] == "application/json"
    assert "facts" in body["generationConfig"]["responseJsonSchema"]["properties"]
    assert "untrusted" in body["systemInstruction"]["parts"][0]["text"]


def test_storage_failure_is_sanitized(client, store, monkeypatch):
    import sqlite3
    monkeypatch.setattr(store, "recall", Mock(side_effect=sqlite3.OperationalError("private path")))
    response = client.post("/tools/recall", json={"query": "Toronto"})
    assert response.status_code == 503
    assert "private path" not in response.text


def test_empty_or_malformed_summary_does_not_persist(store, monkeypatch):
    from pydantic import ValidationError
    store.ingest("s", [turn()])
    def invalid(*args):
        return Summary.model_validate_json('{"summary":"valid", "facts":[{"key":"", "text":"bad"}]}')
    monkeypatch.setattr(memory, "summarize", invalid)
    with pytest.raises(ValidationError):
        store.end("s")
    with store.db() as db:
        assert db.execute("SELECT count(*) FROM facts").fetchone()[0] == 0
        assert db.execute("SELECT ended_at FROM sessions").fetchone()[0] is None
