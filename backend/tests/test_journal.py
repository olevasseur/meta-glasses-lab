import asyncio
import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, date, datetime
from unittest.mock import Mock

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

import app
import journal
import memory_api
from journal import Entry, Journal, JournalDateError, Todo, parse_day
from journal_data import record_event
from journal_docs import replacement, resolve_document, utf16, write_document
from memory import Memory
from notes import Notes


def entry(text="I enjoyed a harbour walk with Alex.", todos=None):
    return Entry(diary=text, highlights=["Walked by the harbour"], people_mentioned=["Alex"],
                 todos=todos or [Todo(text="Call Alex", due_date="2026-10-08")])


@pytest.fixture
def service(tmp_path, monkeypatch):
    monkeypatch.setenv("JOURNAL_TIMEZONE", "America/Toronto")
    monkeypatch.setenv("JOURNAL_TIME", "21:30")
    monkeypatch.delenv("JOURNAL_DOC_ID", raising=False)
    service = Journal(Memory(tmp_path / "memory.db"), Notes())
    monkeypatch.setattr(service, "now", lambda: datetime(2026, 10, 8, 8, tzinfo=service.timezone))
    monkeypatch.setattr(journal, "generate_entry", Mock(return_value=entry()))
    monkeypatch.setattr(journal, "resolve_document", Mock(return_value="journal-id"))
    monkeypatch.setattr(journal, "write_document", Mock())
    return service


def ingest(service, timestamp="2026-10-07T16:00:00Z", text="I walked with Alex.", session="s", seq=0):
    service.memory.ingest(session, [{"seq": seq, "speaker": "user", "text": text, "timestamp": timestamp}])


@pytest.mark.parametrize("value,expected", [
    (None, "2026-10-08"), ("yesterday", "2026-10-07"), ("last Tuesday", "2026-10-06"),
    ("last Thursday", "2026-10-01"), ("2026-09-30", "2026-09-30"),
])
def test_relative_dates(value, expected):
    assert parse_day(value, date(2026, 10, 8)).isoformat() == expected


@pytest.mark.parametrize("value", ["tomorrow", "2026-02-30", "2026-10-09", "20261007", "2026-10-07T00:00:00"])
def test_invalid_dates(value):
    with pytest.raises(JournalDateError):
        parse_day(value, date(2026, 10, 8))


def test_collection_local_boundaries_cross_midnight_sessions_events_and_forget(service):
    for seq, (stamp, text) in enumerate([
        ("2026-10-07T03:59:59Z", "previous day"), ("2026-10-07T04:00:00Z", "first today"),
        ("2026-10-08T03:59:59Z", "last today"), ("2026-10-08T04:00:00Z", "next day"),
    ]):
        ingest(service, stamp, text, seq=seq)
    ingest(service, "2026-10-07T15:00:00-04:00", "Maple", session="forgotten")
    with service.memory.db() as db:
        db.execute("INSERT INTO summaries VALUES ('s','Contains other days','2026-10-08T04:00:00Z')")
        db.execute("INSERT INTO forgotten VALUES ('pet','Maple','Maple')")
        record_event(db, "note_appended", {"text": "Buy milk", "doc_title": "Inbox"}, "2026-10-07T12:00:00Z")
        record_event(db, "fact_learned", {"key": "hobby", "text": "I like walks"}, "2026-10-07T12:00:00Z")
        record_event(db, "job_finished", {"text": "Build succeeded"}, "2026-10-07T12:00:00Z")
        record_event(db, "place_seen", {"text": "Harbour"}, "2026-10-07T12:00:00Z")
    data = service.collect(date(2026, 10, 7))
    assert [t["text"] for t in data["transcripts"]] == ["first today", "last today"]
    assert data["transcripts"][0]["timestamp"] == "2026-10-07T00:00:00-04:00"
    assert data["summaries"] == []
    assert data["notes_appended"][0]["text"] == "Buy milk"
    assert data["facts_learned"][0]["key"] == "hobby"
    assert {e["kind"] for e in data["events"]} == {"job_finished", "place_seen"}
    assert "Maple" not in json.dumps(data)


def test_fact_history_preserves_corrections_and_same_day_summary(service):
    ingest(service)
    service.memory.remember("Toronto", "city")
    service.memory.remember("Montreal", "city")
    with service.memory.db() as db:
        db.execute("UPDATE journal_events SET occurred_at='2026-10-07T12:00:00Z'")
        db.execute("UPDATE facts SET updated_at='2026-10-07T12:00:00Z'")
        db.execute("INSERT INTO summaries VALUES ('s','Walked with Alex','2026-10-07T16:10:00Z')")
    data = service.collect(date(2026, 10, 7))
    assert [f["text"] for f in data["facts_learned"]] == ["Toronto", "Montreal"]
    assert data["summaries"][0]["text"] == "Walked with Alex"


def test_day_bounds_follow_dst(service):
    for day, hours in [(date(2026, 3, 8), 23), (date(2026, 11, 1), 25)]:
        start, end = map(datetime.fromisoformat, service.bounds(day))
        assert (end - start).total_seconds() == hours * 3600


def test_input_is_bounded_and_reports_omissions(service):
    for seq in range(15):
        ingest(service, text="x" * 12000, seq=seq)
    data = service.collect(date(2026, 10, 7))
    assert data["truncated"] and len(data["transcripts"]) < 15
    assert len(json.dumps(data)) < 121000


def test_write_replace_read_recall_and_prior_todos(service):
    ingest(service)
    first = service.write_journal("yesterday")
    assert first["doc_status"] == "synced"
    journal.generate_entry.return_value = entry("I met Alex again.")
    second = service.write_journal("2026-10-07")
    assert second["entry"]["diary"] == "I met Alex again."
    assert journal.generate_entry.call_count == 2
    assert {c.args[1:3] for c in journal.write_document.call_args_list} == {("journal-id", "2026-10-07")}
    assert service.memory.recall("Alex")["matches"][0]["kind"] == "journal"
    with service.memory.db() as db:
        assert db.execute("SELECT count(*) FROM journal_entries").fetchone()[0] == 1
    assert service.collect(date(2026, 10, 8))["previous_todos"] == first["entry"]["todos"]
    assert service.read_journal("last Tuesday")["status"] == "not_found"


def test_uncertain_docs_write_retains_draft_and_retry_reuses_day(service):
    ingest(service)
    journal.write_document.side_effect = RuntimeError("lost response")
    with pytest.raises(RuntimeError):
        service.write_journal("yesterday")
    assert service.read_journal("yesterday")["doc_status"] == "pending"
    journal.write_document.side_effect = None
    # Scheduler retries a saved draft without another model request.
    service.write_journal("yesterday", refresh=False)
    assert journal.generate_entry.call_count == 1
    assert service.read_journal("yesterday")["doc_status"] == "synced"


def test_failed_generation_does_not_replace_existing_entry(service):
    ingest(service)
    before = service.write_journal("yesterday")
    journal.generate_entry.side_effect = RuntimeError("upstream")
    with pytest.raises(RuntimeError):
        service.write_journal("yesterday")
    assert service.read_journal("yesterday") == before


def test_forget_hides_journal_read_recall_and_todos(service):
    ingest(service)
    service.write_journal("yesterday")
    service.memory.remember("Alex", "friend")
    token = service.memory.forget("Alex")["confirmation_token"]
    service.memory.forget("Alex", token)
    assert service.read_journal("yesterday")["status"] == "not_found"
    assert service.memory.recall("Alex")["matches"] == []
    assert service.morning_brief()["open_todos"] == []
    assert service.collect(date(2026, 10, 8))["previous_todos"] == []
    with pytest.raises(RuntimeError, match="forgotten"):
        service.write_journal("yesterday", refresh=False)


def test_morning_brief_generates_local_yesterday_and_reports_due(service):
    ingest(service)
    journal.generate_entry.return_value = entry(todos=[
        Todo(text="Overdue", due_date="2026-10-06"), Todo(text="Today", due_date="2026-10-08"),
        Todo(text="Later", due_date="2026-10-09"), Todo(text="Someday"),
    ])
    brief = service.morning_brief()
    assert [t["text"] for t in brief["due"]] == ["Overdue", "Today"]
    assert len(brief["open_todos"]) == 4
    assert service.read_journal("yesterday")["doc_status"] == "pending"
    journal.write_document.assert_not_called()
    # A newer snapshot contains an explicit completion, so don't resurrect yesterday's task.
    journal.generate_entry.return_value = Entry(diary="Done.", highlights=[], people_mentioned=[], todos=[])
    service.write_journal("today")
    assert service.morning_brief()["open_todos"] == []


def test_empty_day_skips_gemini(service):
    assert service.morning_brief()["open_todos"] == []
    journal.generate_entry.assert_not_called()


def test_morning_refreshes_evening_snapshot_before_scheduler_runs(service, monkeypatch):
    ingest(service)
    monkeypatch.setattr(service, "now", lambda: datetime(2026, 10, 7, 21, 30, tzinfo=service.timezone))
    service.write_journal("today")
    monkeypatch.setattr(service, "now", lambda: datetime(2026, 10, 8, 8, tzinfo=service.timezone))
    journal.generate_entry.return_value = Entry(diary="I finished the task late last night.", highlights=[], people_mentioned=[], todos=[])
    assert service.morning_brief()["open_todos"] == []
    assert service.read_journal("yesterday")["doc_status"] == "pending"
    assert journal.generate_entry.call_count == 2


@pytest.mark.parametrize("name,value", [("JOURNAL_TIMEZONE", "invalid/zone"), ("JOURNAL_TIME", "25:00"), ("JOURNAL_TIME", "9:30")])
def test_bad_configuration_fails_with_sanitizable_error(service, monkeypatch, name, value):
    monkeypatch.setenv(name, value)
    with pytest.raises(ValueError):
        Journal(service.memory, service.notes)


def test_scheduler_finalizes_yesterday_and_runs_once_after_local_time(service, monkeypatch):
    ingest(service)
    service.scheduled_tick()  # morning catch-up
    service.scheduled_tick()
    assert journal.write_document.call_count == 1
    monkeypatch.setattr(service, "now", lambda: datetime(2026, 10, 8, 21, 29, tzinfo=service.timezone))
    service.write_journal("today")  # manual early entry doesn't suppress the scheduled refresh
    service.scheduled_tick()
    assert journal.write_document.call_count == 2
    monkeypatch.setattr(service, "now", lambda: datetime(2026, 10, 8, 21, 30, tzinfo=service.timezone))
    service.scheduled_tick()
    service.scheduled_tick()
    assert journal.write_document.call_count == 3
    monkeypatch.setattr(service, "now", lambda: datetime(2026, 10, 9, 8, tzinfo=service.timezone))
    service.scheduled_tick()  # finalize late evening once after midnight
    service.scheduled_tick()
    assert journal.write_document.call_count == 4


def test_concurrent_scheduler_calls_share_memory_lock(service):
    ingest(service)
    with ThreadPoolExecutor(max_workers=2) as executor:
        list(executor.map(lambda _: service.scheduled_tick(), range(2)))
    assert journal.write_document.call_count == 1


def document(body="\n", split=None):
    chunks = [body] if split is None else [body[:split], body[split:]]
    elements, index = [], 1
    for chunk in chunks:
        elements.append({"startIndex": index, "endIndex": index + utf16(chunk), "textRun": {"content": chunk}})
        index += utf16(chunk)
    return {"revisionId": "revision-1", "body": {"content": [
        {"sectionBreak": {}, "endIndex": 1},
        {"startIndex": 1, "endIndex": index, "paragraph": {"elements": elements}},
    ]}}


def test_docs_marker_replacement_utf16_split_runs_and_adjacent_days():
    first = replacement(document(), "2026-10-07", "I saw a fox 🦊.")
    block = first["requests"][0]["insertText"]["text"]
    prefix, suffix = "Earlier 😀\n", "Another day's entry\n\n"
    body = prefix + block + suffix
    replaced = replacement(document(body, split=len(prefix) + 15), "2026-10-07", "New diary ✨")
    delete = replaced["requests"][0]["deleteContentRange"]["range"]
    assert delete == {"startIndex": 1 + utf16(prefix), "endIndex": 1 + utf16(prefix + block)}
    assert replaced["writeControl"] == {"requiredRevisionId": "revision-1"}
    assert replaced["requests"][1]["insertText"]["location"]["index"] == 1 + utf16(prefix)
    assert replaced["requests"][-1]["updateParagraphStyle"]["paragraphStyle"] == {"namedStyleType": "HEADING_1"}
    encoded = body.encode("utf-16-le")
    updated = (encoded[:2 * (delete["startIndex"] - 1)] +
               replaced["requests"][1]["insertText"]["text"].encode("utf-16-le") +
               encoded[2 * (delete["endIndex"] - 1):]).decode("utf-16-le")
    assert updated.startswith(prefix) and updated.endswith(suffix)
    assert updated.count("[glasses-journal:2026-10-07:begin]") == 1
    assert "I saw a fox" not in updated


@pytest.mark.parametrize("body", [
    "[glasses-journal:2026-10-07:begin]\n\n",
    "[glasses-journal:2026-10-07:end]\n[glasses-journal:2026-10-07:begin]\n\n",
    "[glasses-journal:2026-10-07:begin]\n[glasses-journal:2026-10-07:end]\n",  # no final separator
    "[glasses-journal:2026-10-07:begin]\n[glasses-journal:2026-10-08:begin]\n[glasses-journal:2026-10-07:end]\n\n",
    "2026-10-07\nAn entry with both markers removed\n\n",
])
def test_damaged_markers_fail_closed(body):
    with pytest.raises(RuntimeError):
        replacement(document(body), "2026-10-07", "New")


def test_docs_unsupported_content_and_generated_markers_fail_closed():
    doc = document()
    doc["body"]["content"].append({"table": {}})
    with pytest.raises(RuntimeError):
        replacement(doc, "2026-10-07", "New")
    for text in ("[glasses-journal:evil]", "bad\x00text"):
        with pytest.raises(ValueError):
            replacement(document(), "2026-10-07", text)


def test_docs_append_preserves_existing_last_paragraph_and_requires_revision():
    request = replacement(document("My introduction\n"), "2026-10-07", "Diary")
    insert = request["requests"][0]["insertText"]
    assert insert["text"].startswith("\n[glasses-journal:")
    assert request["requests"][1]["updateParagraphStyle"]["range"]["startIndex"] == insert["location"]["index"] + 1
    doc = document()
    doc.pop("revisionId")
    with pytest.raises(RuntimeError, match="revision"):
        replacement(doc, "2026-10-07", "Diary")


def test_google_resolution_and_revision_conflict_no_blind_retry(monkeypatch):
    notes = Notes()
    notes._connect = Mock()
    notes._list_documents = Mock(return_value=[{"id": "a", "title": "Glasses Journal"}])
    notes.docs = Mock()
    notes.docs.documents().get().execute.return_value = document()
    assert resolve_document(notes, None) == "a"
    assert resolve_document(notes, "explicit") == "explicit"
    notes._list_documents.return_value *= 2
    with pytest.raises(RuntimeError):
        resolve_document(notes, None)
    notes._list_documents.return_value = []
    notes.docs.documents().create().execute.return_value = {"documentId": "new"}
    assert resolve_document(notes, None) == "new"
    notes.docs.documents().batchUpdate().execute.side_effect = RuntimeError("revision changed")
    notes.docs.reset_mock()
    with pytest.raises(RuntimeError):
        write_document(notes, "a", "2026-10-07", "Diary")
    assert notes.docs.documents().batchUpdate.call_args.kwargs["body"]["writeControl"]["requiredRevisionId"] == "revision-1"
    notes.docs.documents().batchUpdate().execute.assert_called_once()


def test_real_sdk_journal_serialization(monkeypatch):
    monkeypatch.setenv("GEMINI_API_KEY", "fake")
    monkeypatch.setenv("GEMINI_JOURNAL_MODEL", "gemini-3.5-flash-lite")
    requests = []

    def send(_client, request, **kwargs):
        requests.append(request)
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": entry().model_dump_json()}]}}]}, request=request)

    monkeypatch.setattr(httpx.Client, "send", send)
    assert journal.generate_entry({"date": "2026-10-07", "transcripts": []}).todos[0].due_date == date(2026, 10, 8)
    body = json.loads(requests[0].content)
    assert "gemini-3.5-flash-lite:generateContent" in str(requests[0].url)
    assert "todos" in body["generationConfig"]["responseJsonSchema"]["properties"]
    assert "untrusted" in body["systemInstruction"]["parts"][0]["text"]
    with pytest.raises(ValidationError):
        Todo(text="Task", due_date="not a date")


def test_routes_auth_validation_dispatch_sanitization_and_note_capture(service, monkeypatch):
    monkeypatch.setenv("APP_SHARED_SECRET", "test")
    monkeypatch.setattr(memory_api, "memory", service.memory)
    monkeypatch.setattr(app, "journal_service", lambda: service)
    monkeypatch.setattr(app.notes, "_connect", Mock())
    monkeypatch.setattr(app.notes, "_resolve", Mock(return_value={
        "status": "matched", "document": {"id": "inbox", "title": "Inbox"},
    }))
    monkeypatch.setattr(app.notes, "docs", Mock())
    with TestClient(app.app, headers={"X-App-Secret": "test"}) as client:
        for name in ("write_journal", "read_journal", "morning_brief"):
            assert client.post(f"/tools/{name}", json={}, headers={"X-App-Secret": "bad"}).status_code == 401
        assert client.post("/tools/read_journal", json={}).status_code == 422
        assert client.post("/tools/write_journal", json={"date": "tomorrow"}).status_code == 422
        assert client.post("/tools/morning_brief", json={"date": "today"}).status_code == 422
        ingest(service)
        assert client.post("/tools/write_journal", json={"date": "yesterday"}).json()["doc_status"] == "synced"
        assert client.post("/tools/read_journal", json={"date": "yesterday"}).json()["status"] == "ok"
        assert client.post("/tools/morning_brief", json={}).status_code == 200
        assert client.post("/tools/append_to_doc", json={"text": "Buy milk"}).status_code == 200
        with service.memory.db() as db:
            row = db.execute("SELECT payload FROM journal_events WHERE kind='note_appended'").fetchone()
            assert json.loads(row[0]) == {"text": "Buy milk", "doc_title": "Inbox"}
            assert db.execute("SELECT count(*) FROM journal_events WHERE kind='note_appended'").fetchone()[0] == 1
            assert db.execute("SELECT text FROM note_history").fetchone()[0] == "Buy milk"
        monkeypatch.setattr(service, "record_note", Mock(side_effect=sqlite3.OperationalError("private")))
        response = client.post("/tools/append_to_doc", json={"text": "Already saved"})
        assert response.json() == {"status": "ok", "doc_title": "Inbox", "journal_tracking": "failed"}
        journal.generate_entry.side_effect = RuntimeError("secret private content")
        response = client.post("/tools/write_journal", json={"date": "yesterday"})
        assert response.status_code == 502 and "secret private" not in response.text


def test_lifespan_stops_scheduler_cleanly(monkeypatch):
    monkeypatch.setenv("JOURNAL_ENABLED", "true")
    monkeypatch.setattr(app, "journal_service", Mock())
    calls = []

    async def loop(factory, stop):
        calls.append("start")
        await stop.wait()
        calls.append("stop")

    monkeypatch.setattr(app, "schedule_loop", loop)
    from unittest.mock import AsyncMock
    jobs = Mock(start=AsyncMock(), close=AsyncMock())
    monkeypatch.setattr(app, "Jobs", Mock(return_value=jobs))

    async def run():
        async with app.lifespan(app.app):
            await asyncio.sleep(0)
        assert calls == ["start", "stop"]
        jobs.start.assert_awaited_once()
        jobs.close.assert_awaited_once()

    asyncio.run(run())


def test_invalid_journal_startup_closes_job_runner(monkeypatch):
    from unittest.mock import AsyncMock

    monkeypatch.setenv("JOURNAL_ENABLED", "true")
    monkeypatch.setattr(app, "journal_service", Mock(side_effect=ValueError("Invalid schedule")))
    jobs = Mock(start=AsyncMock(), close=AsyncMock())
    monkeypatch.setattr(app, "Jobs", Mock(return_value=jobs))

    async def run():
        with pytest.raises(ValueError, match="Invalid schedule"):
            async with app.lifespan(app.app):
                pytest.fail("Startup should reject invalid schedule")
        jobs.start.assert_awaited_once()
        jobs.close.assert_awaited_once()

    asyncio.run(run())


def test_note_tracking_releases_transport_before_waiting_for_journal_memory(service, monkeypatch):
    from threading import Event

    tracking = Event()
    monkeypatch.setattr(memory_api, "memory", service.memory)
    monkeypatch.setattr(app, "journal_service", lambda: service)
    notes = service.notes
    notes._connect = Mock()
    notes._resolve = Mock(return_value={"status": "matched", "document": {"id": "inbox", "title": "Inbox"}})
    notes.docs = Mock()

    def record(document, text):
        tracking.set()
        return app.record_note(document, text)

    notes.on_append = record
    with ThreadPoolExecutor() as pool:
        # Journal holds memory while acquiring the same Google transport.
        with service.memory.lock:
            append = pool.submit(notes.append, "Inbox", "A concurrent note")
            assert tracking.wait(2)
            acquired = notes.lock.acquire(timeout=2)
            if acquired:
                notes.lock.release()
            assert acquired, "Note history deadlocks journal publishing"
        assert append.result(timeout=2) == {"status": "ok", "doc_title": "Inbox"}
    with service.memory.db() as db:
        assert db.execute("SELECT text FROM note_history").fetchone()[0] == "A concurrent note"
        rows = db.execute("SELECT payload FROM journal_events WHERE kind='note_appended'").fetchall()
        assert [json.loads(row[0]) for row in rows] == [{"text": "A concurrent note", "doc_title": "Inbox"}]


def test_scheduler_failure_backs_off_and_recovers_without_sensitive_logs(monkeypatch, caplog):
    timeouts = []

    async def run():
        stop = asyncio.Event()
        calls = []

        def tick():
            calls.append(1)
            if len(calls) == 1:
                raise RuntimeError("private upstream content")
            stop.set()

        async def wait(awaitable, timeout):
            awaitable.close()
            timeouts.append(timeout)
            raise TimeoutError

        monkeypatch.setattr(journal.asyncio, "wait_for", wait)
        await journal.schedule_loop(lambda: Mock(scheduled_tick=tick), stop)
        assert len(calls) == 2

    asyncio.run(run())
    assert timeouts == [30, 300, 30]
    assert "RuntimeError" in caplog.text and "private upstream content" not in caplog.text


def test_meeting_participants_keep_provenance_in_journal(service):
    from meetings import Meetings
    Meetings(service.memory, service.notes).start('group', 'Team review')
    ingest(service, text='Maya: I live in Paris.', session='group')
    ingest(service, text='I live in Toronto.', session='personal')
    with service.memory.db() as db:
        db.execute("INSERT INTO summaries VALUES ('group','Maya lives in Paris','2026-10-07T16:10:00Z')")
    data = service.collect(date(2026, 10, 7))
    assert data['summaries'][0]['source_kind'] == 'meeting'
    group, personal = data['transcripts']
    assert group['session_id'] == 'group' and group['source_kind'] == 'meeting'
    assert personal['session_id'] == 'personal' and 'source_kind' not in personal
