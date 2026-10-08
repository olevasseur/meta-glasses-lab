import json

import httpx
import pytest
from fastapi.testclient import TestClient

import app
import gemini
from vision import CLIENT_TOOL_NAMES
from coach import COACH_CLIENT_TOOL_NAMES, PROJECT_TOOL_NAMES


@pytest.mark.parametrize("mode", ["auto", "ptt"])
def test_vision_declarations_are_locked_client_tools(monkeypatch, mode):
    captured = []

    def send(_client, request, **kwargs):
        captured.append(json.loads(request.content))
        return httpx.Response(200, json={"name": "auth_tokens/vision-test"}, request=request)

    monkeypatch.setattr(httpx.Client, "send", send)
    result = gemini.create_token("fake-key", gemini.DEFAULT_MODEL, mode)
    assert result["client_tools"] == ["look", "set_watch_mode", *COACH_CLIENT_TOOL_NAMES]
    body, = captured
    assert "tools" in body["fieldMask"]
    assert "sessionResumption" not in body["fieldMask"]
    setup = body["bidiGenerateContentSetup"]
    assert "contextWindowCompression" in setup
    declarations = {d["name"]: d for d in setup["tools"][0]["functionDeclarations"]}
    assert set(declarations) == {"append_to_doc", *CLIENT_TOOL_NAMES, *COACH_CLIENT_TOOL_NAMES, *PROJECT_TOOL_NAMES}
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
