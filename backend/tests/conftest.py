"""Never run visual retention against the personal archive during unit tests."""
import pytest

import visual_api
from visual_memory import VisualMemory


@pytest.fixture(autouse=True)
def isolated_visual_archive(monkeypatch, tmp_path):
    monkeypatch.setattr(visual_api, "memory", VisualMemory(tmp_path / "visual"))
