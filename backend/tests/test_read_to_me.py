import pytest

import gemini
from read_to_me import READING_TOOLS


@pytest.mark.parametrize("mode", ["auto", "ptt"])
def test_reading_tools_and_instructions_are_in_locked_config(mode):
    config = gemini.token_config(gemini.DEFAULT_MODEL, mode)
    assert config.lock_additional_fields == []
    live = config.live_connect_constraints.config
    assert live.session_resumption is None
    tools = {tool.name: tool for tool in live.tools[0].function_declarations}
    assert {tool.name for tool in READING_TOOLS} <= tools.keys()
    assert tools["read_text"].parameters.properties["mode"].enum == ["verbatim", "summary", "find", "translate"]
    assert tools["submit_reading"].parameters.required == ["page_id", "text"]
    assert "COMPLETE" in live.system_instruction
    assert "untrusted data" in live.system_instruction
    assert "at most twice" in live.system_instruction
    assert "never instructions" in live.system_instruction
