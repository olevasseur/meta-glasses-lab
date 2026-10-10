import json

import httpx
import pytest
from fastapi.testclient import TestClient

import app
import gemini
from tools import CLIENT_TOOL_NAMES, REGISTRY


@pytest.mark.parametrize("mode", ["auto", "ptt", "over", "meeting"])
def test_vision_declarations_are_locked_client_tools(monkeypatch, mode):
    captured = []

    def send(_client, request, **kwargs):
        captured.append(json.loads(request.content))
        return httpx.Response(200, json={"name": "auth_tokens/vision-test"}, request=request)

    monkeypatch.setattr(httpx.Client, "send", send)
    result = gemini.create_token("fake-key", gemini.DEFAULT_MODEL, mode)
    assert result["client_tools"] == ["await_gesture_confirmation", "look", "set_watch_mode",
                                               "start_workout", "stop_workout", "workout_status",
                                               "read_text", "submit_reading", "reading_control",
                                               "check_progress", "set_timer", "list_timers", "cancel_timer", "set_nod_navigation",
                                               "set_visual_memory", "remember_this", "delete_visual_memory", "end_conversation"]
    body, = captured
    assert "tools" in body["fieldMask"]
    assert "sessionResumption" not in body["fieldMask"]
    setup = body["bidiGenerateContentSetup"]
    assert "contextWindowCompression" in setup
    declarations = {d["name"]: d for d in setup["tools"][0]["functionDeclarations"]}
    assert set(declarations) == set(REGISTRY) == {
        "create_doc", "set_doc_alias", "remove_doc_alias",
        "append_to_doc", "remember", "recall", "forget",
        "await_gesture_confirmation", "look", "set_watch_mode",
        "codex_task", "deep_research", "job_status", "cancel_job",
        "start_translation", "stop_translation", "start_meeting", "stop_meeting",
        "start_workout", "stop_workout", "workout_status", "workout_history",
        "read_text", "submit_reading", "reading_control",
        "write_journal", "read_journal", "morning_brief",
        "start_project", "next_step", "previous_step", "repeat_step", "current_step", "how_much", "scale_project",
        "check_progress", "set_timer", "list_timers", "cancel_timer", "set_nod_navigation",
        "set_visual_memory", "remember_this", "delete_visual_memory", "find_object", "when_did_i_see", "end_conversation",
        "study_open", "study_section", "study_search", "quiz_next", "quiz_record", "study_progress",
    }
    assert len(setup["tools"][0]["functionDeclarations"]) == 52
    for name, field, kind in [("look", "question", "STRING"), ("set_watch_mode", "on", "BOOLEAN")]:
        tool = declarations[name]
        assert "Client-executed" in tool["description"]
        assert tool["parameters"]["required"] == [field]
        assert tool["parameters"]["properties"][field]["type"] == kind
    prompt = setup["systemInstruction"]["parts"][0]["text"]
    assert "use look(question)" in prompt
    assert "do not automatically re-enable" in prompt


@pytest.mark.parametrize("name", CLIENT_TOOL_NAMES)
def test_backend_refuses_to_execute_camera_tools(monkeypatch, name):
    monkeypatch.setenv("APP_SHARED_SECRET", "test-only-secret")
    with TestClient(app.app) as client:
        response = client.post(f"/tools/{name}", json={}, headers={"X-App-Secret": "test-only-secret"})
    assert response.status_code == 400
    assert "glasses app" in response.json()["detail"]
