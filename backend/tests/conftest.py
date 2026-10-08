import pytest


@pytest.fixture(autouse=True)
def disable_journal_schedule(monkeypatch):
    # TestClient must never schedule real Gemini/Google writes, regardless of wall clock.
    monkeypatch.setenv("JOURNAL_ENABLED", "false")
