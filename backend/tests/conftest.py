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


@pytest.fixture(autouse=True)
def isolated_diagnostic_logs(monkeypatch, tmp_path):
    import diagnostic_logs
    monkeypatch.setattr(diagnostic_logs, "store", diagnostic_logs.LogStore(tmp_path / "logs"))


@pytest.fixture(autouse=True)
def isolated_security_state(monkeypatch):
    import approvals
    from security_boundary import SecurityBoundary
    monkeypatch.setenv("BACKEND_ALLOWED_HOSTS", "localhost,127.0.0.1,::1,testserver")
    approvals.pending.clear()
    approvals.completed.clear()
    middleware = app.app.middleware_stack
    while middleware is not None:
        if isinstance(middleware, SecurityBoundary):
            middleware.windows.clear()
        middleware = getattr(middleware, "app", None)


@pytest.fixture(autouse=True)
def disable_ai_background_replay(monkeypatch):
    # Tests exercise queues explicitly; never touch personal queued data on slow test runs.
    import asyncio
    import resilience_work
    async def idle():
        await asyncio.Event().wait()
    monkeypatch.setattr(resilience_work, "retry_loop", idle)


@pytest.fixture(autouse=True)
def isolated_token_transport():
    from token_transport import token_transport
    token_transport.close()
    yield
    token_transport.close()
