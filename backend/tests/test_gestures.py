import pytest

from gemini import DEFAULT_MODEL, token_config


@pytest.mark.parametrize("mode", ["auto", "ptt", "over"])
def test_gesture_confirmation_is_advertised_without_changing_resumption(mode):
    config = token_config(DEFAULT_MODEL, mode)
    live = config.live_connect_constraints.config
    tool = next(t for t in live.tools[0].function_declarations if t.name == "await_gesture_confirmation")
    assert tool.parameters.required == ["question"]
    assert set(tool.parameters.properties) == {"question"}
    assert "[gesture: nod = yes]" in live.system_instruction
    assert "[gesture: shake = no]" in live.system_instruction
    assert config.lock_additional_fields == []
    assert live.session_resumption is None
