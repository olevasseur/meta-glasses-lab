"""Keep the app lifespan's job runner away from personal queued work in tests."""

import pytest

import app
from jobs import Jobs


@pytest.fixture(autouse=True)
def isolated_job_runner(monkeypatch, tmp_path):
    repos = tmp_path / "codex_repos.json"
    repos.write_text("{}")
    monkeypatch.setattr(app, "Jobs", lambda notes: Jobs(notes, tmp_path / "jobs-data", repos))


@pytest.fixture(autouse=True)
def disable_journal_schedule(monkeypatch):
    # TestClient must never schedule real Gemini/Google writes, regardless of wall clock.
    monkeypatch.setenv("JOURNAL_ENABLED", "false")
