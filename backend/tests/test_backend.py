from approval_helpers import approved_post
import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import Mock

import httpx
import pytest
from fastapi.testclient import TestClient
from google.genai import types
from google.genai.errors import APIError
from googleapiclient.errors import HttpError

import app
import gemini
import notes
import memory_api
from memory import Memory


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setattr(memory_api, "memory", Memory(tmp_path / "memory.db"))
    monkeypatch.setenv("APP_SHARED_SECRET", "test-app-secret")
    monkeypatch.setenv("GEMINI_API_KEY", "test-gemini-key")
    monkeypatch.delenv("GEMINI_LIVE_MODEL", raising=False)
    with TestClient(app.app, headers={"X-App-Secret": "test-app-secret"}) as client:
        yield client


@pytest.fixture
def gemini_client(monkeypatch):
    client = Mock()
    client.auth_tokens.create.return_value = types.AuthToken(name="auth_tokens/test")
    context = Mock()
    context.__enter__ = Mock(return_value=client)
    context.__exit__ = Mock(return_value=False)
    factory = Mock(return_value=context)
    monkeypatch.setattr(gemini.genai, "Client", factory)
    return client, factory


@pytest.fixture
def google_clients(monkeypatch, tmp_path):
    aliases_path = tmp_path / "aliases.json"
    aliases_path.write_text('{"inbox": null, "work": "work-id"}')
    service = notes.Notes(token_path=tmp_path / "secrets" / "token.json", aliases_path=aliases_path)
    docs, drive = Mock(), Mock()
    credentials = Mock(valid=True)
    credentials.refresh.side_effect = lambda request: setattr(credentials, "valid", True)
    credentials.to_json.return_value = '{"refresh_token":"fake-test-token"}'
    loader = Mock(return_value=credentials)
    monkeypatch.setattr(notes.Credentials, "from_authorized_user_file", loader)
    monkeypatch.setattr(notes, "build", Mock(side_effect=lambda api, *a, **kw: {"docs": docs, "drive": drive}[api]))
    drive.files().list().execute.return_value = {"files": []}
    docs.documents().get().execute.return_value = {"documentId": "work-id", "title": "Work Log"}
    docs.documents().create().execute.return_value = {"documentId": "inbox-id", "title": notes.INBOX_TITLE}
    docs.documents().batchUpdate().execute.return_value = {}
    # Drop mock configuration calls before asserting real application calls.
    docs.reset_mock()
    drive.reset_mock()
    monkeypatch.setattr(app, "notes", service)
    return SimpleNamespace(service=service, docs=docs, drive=drive, credentials=credentials, loader=loader)


@pytest.mark.parametrize("path", ["/token", "/tools/append_to_doc", "/tools/unknown", "/docs", "/openapi.json"])
@pytest.mark.parametrize("secret", [None, "wrong-secret"])
def test_auth_rejects_every_path(client, gemini_client, google_clients, path, secret):
    client.headers.pop("X-App-Secret")
    headers = {} if secret is None else {"X-App-Secret": secret}
    response = client.post(path, headers=headers, json={})
    assert response.status_code == 401
    gemini_client[1].assert_not_called()
    google_clients.loader.assert_not_called()


def test_auth_unconfigured_fails_closed(client, monkeypatch):
    monkeypatch.delenv("APP_SHARED_SECRET")
    assert client.post("/token", json={}).status_code == 503


def test_gesture_tool_is_client_owned(client, monkeypatch):
    notes_mock, memory_mock = Mock(), Mock()
    monkeypatch.setattr(app, "notes", notes_mock)
    monkeypatch.setattr(memory_api, "memory", memory_mock)
    response = client.post("/tools/await_gesture_confirmation", json={"question": "Forget this fact?"})
    assert response.status_code == 400
    assert response.json() == {"detail": "This tool must be executed on the glasses app"}
    assert notes_mock.mock_calls == memory_mock.mock_calls == []


@pytest.mark.parametrize("mode", ["auto", "ptt", "over"])
def test_token_config_and_response(client, gemini_client, mode):
    before = datetime.now(UTC)
    response = client.post("/token", json={} if mode == "auto" else {"mode": mode})
    after = datetime.now(UTC)
    assert response.status_code == 200
    assert response.headers["Cache-Control"] == "no-store"
    assert set(response.json()) == {"token", "model", "expires_at", "client_tools"}
    assert response.json()["client_tools"] == ["await_gesture_confirmation", "look", "set_watch_mode",
                                               "start_workout", "stop_workout", "workout_status",
                                               "read_text", "submit_reading", "reading_control",
                                               "check_progress", "set_timer", "list_timers", "cancel_timer", "set_nod_navigation",
                                               "set_visual_memory", "remember_this", "delete_visual_memory"]
    assert response.json()["token"] == "auth_tokens/test"
    assert response.json()["model"] == "gemini-3.8-live"
    client_mock, factory = gemini_client
    assert factory.call_args.kwargs["vertexai"] is False
    assert factory.call_args.kwargs["http_options"].api_version == "v1beta"
    config = client_mock.auth_tokens.create.call_args.kwargs["config"]
    assert isinstance(config, types.CreateAuthTokenConfig)
    assert config.uses == 1
    assert config.lock_additional_fields == []
    assert before + timedelta(minutes=30) <= config.expire_time <= after + timedelta(minutes=30)
    assert before + timedelta(seconds=60) <= config.new_session_expire_time <= after + timedelta(seconds=60)
    assert datetime.fromisoformat(response.json()["expires_at"]) == config.expire_time
    constraints = config.live_connect_constraints
    assert constraints.model == "gemini-3.8-live"
    live = constraints.config
    assert live.system_instruction == gemini.PROMPT_PATH.read_text()
    assert live.response_modalities == ["AUDIO"]
    assert live.speech_config.voice_config.prebuilt_voice_config.voice_name == "Kore"
    assert live.input_audio_transcription is not None
    assert live.output_audio_transcription is not None
    assert live.context_window_compression.sliding_window is not None
    assert live.session_resumption is None  # Client-owned so resumption handles work.
    declaration = live.tools[0].function_declarations[0]
    assert declaration.name == "append_to_doc"
    assert declaration.parameters.required == ["text"]
    assert set(declaration.parameters.properties) == {"text", "doc_name", "confirmed_doc_id"}
    detection = live.realtime_input_config.automatic_activity_detection.model_dump(exclude_none=True)
    if mode in ("ptt", "over"):
        assert detection == {"disabled": True}
    else:
        assert detection == {
            "disabled": False,
            "start_of_speech_sensitivity": "START_SENSITIVITY_LOW",
            "end_of_speech_sensitivity": "END_SENSITIVITY_LOW",
            "prefix_padding_ms": 300,
            "silence_duration_ms": 2000,
        }


def test_token_model_override_and_server_expiry(client, gemini_client, monkeypatch):
    monkeypatch.setenv("GEMINI_LIVE_MODEL", "custom-live-model")
    gemini_client[0].auth_tokens.create.return_value.expire_time = "2026-10-06T13:00:00Z"
    response = client.post("/token", json={})
    assert response.json()["model"] == "custom-live-model"
    assert response.json()["expires_at"] == "2026-10-06T13:00:00Z"
    assert gemini_client[0].auth_tokens.create.call_args.kwargs["config"].live_connect_constraints.model == "custom-live-model"


@pytest.mark.parametrize("payload", [{"mode": "invalid"}, {"model": "override"}])
def test_token_rejects_invalid_client_config(client, gemini_client, payload):
    assert client.post("/token", json=payload).status_code == 422
    gemini_client[1].assert_not_called()


def test_token_missing_key_and_sanitized_upstream_error(client, gemini_client, monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY")
    assert client.post("/token", json={}).status_code == 503
    gemini_client[1].assert_not_called()
    monkeypatch.setenv("GEMINI_API_KEY", "private-key")
    gemini_client[0].auth_tokens.create.side_effect = APIError(403, {"error": {"message": "private-key"}})
    response = client.post("/token", json={})
    assert response.status_code == 502
    assert "private-key" not in response.text


@pytest.mark.parametrize("mode", ["auto", "ptt", "over"])
def test_real_sdk_http_serialization(client, monkeypatch, mode):
    """Exercise the actual SDK up to its HTTP transport, without network access."""
    requests = []

    def send(_client, request, **kwargs):
        requests.append(request)
        return httpx.Response(200, json={"name": "auth_tokens/wire-test"}, request=request)

    monkeypatch.setattr(httpx.Client, "send", send)
    # TestClient also uses httpx.Client; use the original ASGI client transport directly.
    result = gemini.create_token("fake-key", gemini.DEFAULT_MODEL, mode)
    assert result["token"] == "auth_tokens/wire-test"
    request, = requests
    assert str(request.url) == "https://generativelanguage.googleapis.com/v1beta/auth_tokens"
    body = json.loads(request.content)
    assert body["uses"] == 1
    # Populated fields are locked; sessionResumption stays client-settable.
    assert "sessionResumption" not in body["fieldMask"]
    assert "realtimeInputConfig" in body["fieldMask"]
    assert "systemInstruction" in body["fieldMask"]
    assert "tools" in body["fieldMask"]
    setup = body["bidiGenerateContentSetup"]
    assert setup["model"] == "models/gemini-3.8-live"
    assert setup["generationConfig"]["responseModalities"] == ["AUDIO"]
    speech = types.SpeechConfig.model_validate(setup["generationConfig"]["speechConfig"])
    assert speech.voice_config.prebuilt_voice_config.voice_name == "Kore"
    detection = types.RealtimeInputConfig.model_validate(setup["realtimeInputConfig"]).automatic_activity_detection
    assert detection.disabled is (mode != "auto")
    if mode == "auto":
        assert detection.silence_duration_ms == 2000
        assert detection.prefix_padding_ms == 300
    assert setup["inputAudioTranscription"] == setup["outputAudioTranscription"] == {}
    assert "sessionResumption" not in setup
    assert types.ContextWindowCompressionConfig.model_validate(setup["contextWindowCompression"]).sliding_window is not None
    assert "Never interrupt" in setup["systemInstruction"]["parts"][0]["text"]
    assert setup["tools"][0]["functionDeclarations"][0]["name"] == "append_to_doc"


@pytest.mark.parametrize("scores,status", [
    ([59.99, 59], "not_found"),
    ([60, 50], "needs_confirmation"),
    ([84.99, 60], "needs_confirmation"),
    ([85, 75], "matched"),
    ([85, 75.01], "needs_confirmation"),
    ([95, 85], "matched"),
    ([100, 100], "needs_confirmation"),
    ([85], "matched"),
    ([], "not_found"),
])
def test_matching_boundaries(monkeypatch, scores, status):
    documents = [{"id": str(i), "title": f"Doc {i}"} for i in range(len(scores))]
    extract = Mock(return_value=[(doc["title"], score, i) for i, (doc, score) in enumerate(zip(documents, scores))])
    monkeypatch.setattr(notes.process, "extract", extract)
    result = notes.match_document("requested", documents)
    assert result["status"] == status
    assert extract.call_args.kwargs["scorer"] is notes.fuzz.token_set_ratio
    assert extract.call_args.kwargs["limit"] == 3


def test_real_fuzzy_matching_normalization_ties_and_candidate_limit():
    assert notes.match_document("WORK, log!", [{"id": "1", "title": "Work Log"}])["status"] == "matched"
    assert notes.match_document("zzzz", [{"id": "1", "title": "Work Log"}])["status"] == "not_found"
    documents = [{"id": str(i), "title": f"Work Log {i}"} for i in range(5)]
    result = notes.match_document("work log", documents)
    assert result["status"] == "needs_confirmation"
    assert len(result["candidates"]) == 3


def test_alias_append_request_body(client, google_clients):
    response = approved_post(client, "/tools/append_to_doc", json={"doc_name": " WORK ", "text": "First line\nSecond line"})
    assert response.json() == {"status": "ok", "doc_title": "Work Log"}
    google_clients.drive.files.assert_not_called()
    assert google_clients.docs.documents().get.call_count == 2
    google_clients.docs.documents().get.assert_called_with(documentId="work-id", fields="documentId,title")
    call = google_clients.docs.documents().batchUpdate.call_args.kwargs
    assert call["documentId"] == "work-id"
    assert len(call["body"]["requests"]) == 1
    insert = call["body"]["requests"][0]["insertText"]
    assert insert["endOfSegmentLocation"] == {"segmentId": ""}
    assert insert["text"].startswith("\n")
    assert insert["text"].endswith("\nFirst line\nSecond line\n")
    datetime.strptime(insert["text"].splitlines()[1], "%Y-%m-%d %H:%M:%S UTC")
    google_clients.docs.documents().batchUpdate().execute.assert_called_once_with()
    google_clients.loader.assert_called_once_with(str(google_clients.service.token_path), notes.SCOPES)


def test_confirmed_id_bypasses_aliases_and_matching(client, google_clients):
    google_clients.service.aliases_path.unlink()
    google_clients.docs.documents().get().execute.return_value = {"documentId": "chosen-id", "title": "Chosen"}
    google_clients.docs.reset_mock()
    response = approved_post(client, "/tools/append_to_doc", json={"doc_name": "nonsense", "text": "Note", "confirmed_doc_id": "chosen-id"})
    assert response.status_code == 200
    assert google_clients.docs.documents().get.call_count == 2
    google_clients.docs.documents().get.assert_called_with(documentId="chosen-id", fields="documentId,title")
    assert google_clients.docs.documents().batchUpdate.call_args.kwargs["documentId"] == "chosen-id"
    google_clients.drive.files.assert_not_called()


@pytest.mark.parametrize("name,status", [("work log", "needs_confirmation"), ("zzzz", "not_found")])
def test_ambiguous_or_missing_docs_never_write(client, google_clients, name, status):
    google_clients.drive.files().list().execute.return_value = {"files": [
        {"id": "1", "name": "Work Log"}, {"id": "2", "name": "Work Log 2026"},
    ]}
    response = approved_post(client, "/tools/append_to_doc", json={"doc_name": name, "text": "Note"})
    assert response.status_code == 200
    assert response.json()["status"] == status
    google_clients.docs.documents().batchUpdate.assert_not_called()
    google_clients.docs.documents().create.assert_not_called()


def test_unambiguous_fuzzy_match_appends(client, google_clients):
    google_clients.docs.documents().get().execute.return_value = {"documentId": "1", "title": "Work Log"}
    google_clients.drive.files().list().execute.return_value = {"files": [{"id": "1", "name": "Work Log"}]}
    response = approved_post(client, "/tools/append_to_doc", json={"doc_name": "WORK LOG", "text": "Note"})
    assert response.json() == {"status": "ok", "doc_title": "Work Log"}
    assert google_clients.docs.documents().batchUpdate.call_args.kwargs["documentId"] == "1"


def test_inbox_creation_is_reused(client, google_clients):
    google_clients.docs.documents().get().execute.return_value = {"documentId": "inbox-id", "title": notes.INBOX_TITLE}
    for _ in range(2):
        assert approved_post(client, "/tools/append_to_doc", json={"text": "Note"}).json() == {"status": "ok", "doc_title": "Glasses Inbox"}
    google_clients.docs.documents().create.assert_called_once_with(body={"title": "Glasses Inbox"})
    assert google_clients.docs.documents().batchUpdate.call_count == 2
    assert google_clients.drive.files().list.call_count == 2  # Preview, then fresh recheck before creation.


def test_inbox_rechecks_cached_miss_before_creating(client, google_clients, monkeypatch):
    google_clients.docs.documents().get().execute.return_value = {"documentId": "new-inbox", "title": notes.INBOX_TITLE}
    monkeypatch.setattr(notes, "monotonic", lambda: 100)
    google_clients.service.cache_expires_at = 200
    google_clients.drive.files().list().execute.return_value = {"files": [{"id": "new-inbox", "name": "Glasses Inbox"}]}
    response = approved_post(client, "/tools/append_to_doc", json={"text": "Note"})
    assert response.json() == {"status": "ok", "doc_title": "Glasses Inbox"}
    google_clients.docs.documents().create.assert_not_called()
    assert google_clients.docs.documents().batchUpdate.call_args.kwargs["documentId"] == "new-inbox"


@pytest.mark.parametrize("count,status", [(1, "ok"), (2, "needs_confirmation")])
def test_find_existing_inbox_and_duplicate_titles(client, google_clients, count, status):
    google_clients.docs.documents().get().execute.return_value = {"documentId": "0", "title": notes.INBOX_TITLE}
    google_clients.drive.files().list().execute.return_value = {"files": [
        {"id": str(i), "name": "Glasses Inbox"} for i in range(count)
    ]}
    response = approved_post(client, "/tools/append_to_doc", json={"text": "Note"})
    assert response.status_code == 200
    assert response.json()["status"] == status
    if count == 1:
        assert response.json()["status"] == status
    google_clients.docs.documents().create.assert_not_called()
    assert google_clients.docs.documents().batchUpdate.call_count == (1 if count == 1 else 0)


def test_drive_pagination_and_five_minute_cache(client, google_clients, monkeypatch):
    clock = Mock(return_value=100)
    monkeypatch.setattr(notes, "monotonic", clock)
    google_clients.drive.files().list().execute.side_effect = [
        {"files": [], "nextPageToken": "page-2"},
        {"files": [{"id": "1", "name": "Work Log"}]},
        {"files": [{"id": "2", "name": "Work Log"}]},
    ]
    google_clients.drive.reset_mock()
    for time in (100, 399, 400):
        clock.return_value = time
        google_clients.docs.documents().get().execute.return_value = {"documentId": "2" if time == 400 else "1", "title": "Work Log"}
        assert approved_post(client, "/tools/append_to_doc", json={"doc_name": "Work Log", "text": "Note"}).json()["status"] == "ok"
    calls = google_clients.drive.files().list.call_args_list
    assert len(calls) == 3
    assert [call.kwargs["pageToken"] for call in calls] == [None, "page-2", None]
    assert "nextPageToken" in calls[0].kwargs["fields"]
    assert "trashed=false" in calls[0].kwargs["q"]
    assert google_clients.docs.documents().batchUpdate.call_args.kwargs["documentId"] == "2"


def test_incomplete_drive_search_never_creates_or_appends(client, google_clients):
    google_clients.drive.files().list().execute.return_value = {"files": [], "incompleteSearch": True}
    assert approved_post(client, "/tools/append_to_doc", json={"text": "Note"}).status_code == 503
    google_clients.docs.documents().create.assert_not_called()
    google_clients.docs.documents().batchUpdate.assert_not_called()


def test_refresh_token_persisted_with_owner_permissions(client, google_clients):
    google_clients.credentials.valid = False
    assert approved_post(client, "/tools/append_to_doc", json={"doc_name": "work", "text": "Note"}).status_code == 200
    google_clients.credentials.refresh.assert_called_once()
    path = google_clients.service.token_path
    assert json.loads(path.read_text()) == {"refresh_token": "fake-test-token"}
    assert path.stat().st_mode & 0o777 == 0o600
    assert path.parent.stat().st_mode & 0o777 == 0o700


@pytest.mark.parametrize("args", [{}, {"text": "   "}, {"text": "Note", "doc_name": ""}, {"text": "Note", "confirmed_doc_id": "bad/id"}])
def test_invalid_note_arguments(client, google_clients, args):
    assert approved_post(client, "/tools/append_to_doc", json=args).status_code == 422
    google_clients.loader.assert_not_called()


def test_google_failure_is_sanitized_and_not_retried(client, google_clients):
    google_clients.docs.documents().batchUpdate().execute.side_effect = HttpError(
        SimpleNamespace(status=500, reason="Server error"), b'{"error":{"message":"private note"}}'
    )
    google_clients.docs.reset_mock()
    response = approved_post(client, "/tools/append_to_doc", json={"doc_name": "work", "text": "Note"})
    assert response.status_code == 502
    assert "private note" not in response.text
    google_clients.docs.documents().batchUpdate().execute.assert_called_once_with()


def test_unknown_tool_404_without_google_access(client, google_clients):
    assert client.post("/tools/unknown", json={}).status_code == 404
    assert client.post("/tools/unknown").status_code == 404
    google_clients.loader.assert_not_called()
