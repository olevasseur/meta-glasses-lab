import pytest

import gemini
from translation import translator_instruction


@pytest.mark.parametrize("mode", ["auto", "ptt", "over", "meeting"])
def test_assistant_keeps_english_preference_in_locked_instruction(mode):
    config = gemini.token_config(gemini.DEFAULT_MODEL, mode)
    live = config.live_connect_constraints.config
    assert "Reply in English by default throughout the conversation, including tool confirmations" in live.system_instruction
    assert "explicitly requests that language for the whole conversation" in live.system_instruction
    assert "Noisy or ambiguous audio" in live.system_instruction
    # SDK accepts this generic field, but native-audio Live does not support it.
    assert live.speech_config.language_code is None
    assert "language_code" not in live.speech_config.model_dump(exclude_none=True)
    assert config.lock_additional_fields == []
    assert live.session_resumption is None


@pytest.mark.parametrize("mode", ["auto", "ptt", "over"])
def test_translator_does_not_inherit_assistant_language_preference(mode):
    live = gemini.token_config(gemini.DEFAULT_MODEL, mode, persona="translator", language="es").live_connect_constraints.config
    assert live.system_instruction == translator_instruction("es")
    assert "Reply in English by default" not in live.system_instruction
    assert live.speech_config.language_code is None
