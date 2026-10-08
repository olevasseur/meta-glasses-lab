from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient
from google.genai import types

import app
import gemini


@pytest.mark.parametrize("silence,expected", [(-10, 500), (500, 500), (2300, 2300), (5000, 5000), (9000, 5000)])
def test_auto_patience_is_clamped_at_request_and_sdk_boundaries(monkeypatch, silence, expected):
    monkeypatch.setenv("APP_SHARED_SECRET", "test-secret")
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    create = Mock(return_value={"token": "auth_tokens/test", "model": "test", "expires_at": "test"})
    monkeypatch.setattr(gemini, "create_token", create)
    with TestClient(app.app) as client:
        result = client.post("/token", json={"mode": "auto", "silence_ms": silence}, headers={"X-App-Secret": "test-secret"})
    assert result.status_code == 200
    assert create.call_args.args[2:] == ("auto", expected)
    config = gemini.token_config(gemini.DEFAULT_MODEL, "auto", silence)
    assert config.live_connect_constraints.config.realtime_input_config.automatic_activity_detection.silence_duration_ms == expected


@pytest.mark.parametrize("mode", ["over", "ptt"])
def test_patience_never_enables_vad_for_manual_modes(mode):
    config = gemini.token_config(gemini.DEFAULT_MODEL, mode, 5000)
    live = config.live_connect_constraints.config
    assert live.realtime_input_config.automatic_activity_detection == types.AutomaticActivityDetection(disabled=True)
    assert live.session_resumption is None
    assert config.lock_additional_fields == []


@pytest.mark.parametrize("value", [None, True, "2000", 1.5, [], {}])
def test_patience_rejects_non_integer_input(monkeypatch, value):
    monkeypatch.setenv("APP_SHARED_SECRET", "test-secret")
    create = Mock()
    monkeypatch.setattr(gemini, "create_token", create)
    with TestClient(app.app) as client:
        result = client.post("/token", json={"mode": "auto", "silence_ms": value}, headers={"X-App-Secret": "test-secret"})
    assert result.status_code == 422
    create.assert_not_called()


def test_sign_off_instruction_is_in_the_locked_prompt():
    prompt = gemini.token_config(gemini.DEFAULT_MODEL, "over").live_connect_constraints.config.system_instruction
    assert '"over" or "over and out"' in prompt
    assert "Never mention, repeat, acknowledge" in prompt
