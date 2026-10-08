"""Isolate personal jobs, scheduled journal writes and visual retention in tests."""

import pytest

import app
import visual_api
from jobs import Jobs
from visual_memory import VisualMemory


@pytest.fixture(autouse=True)
def isolated_job_runner(monkeypatch, tmp_path):
    repos = tmp_path / "codex_repos.json"
    repos.write_text("{}")
    monkeypatch.setattr(app, "Jobs", lambda notes: Jobs(notes, tmp_path / "jobs-data", repos))


@pytest.fixture(autouse=True)
def disable_journal_schedule(monkeypatch):
    # TestClient must never schedule real Gemini/Google writes, regardless of wall clock.
    monkeypatch.setenv("JOURNAL_ENABLED", "false")

@pytest.fixture(autouse=True)
def isolated_visual_archive(monkeypatch, tmp_path):
    monkeypatch.setattr(visual_api, "memory", VisualMemory(tmp_path / "visual"))
