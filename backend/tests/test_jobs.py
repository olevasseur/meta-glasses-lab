import asyncio
import json
import signal
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from fastapi.testclient import TestClient

import app
import gemini
from jobs import Jobs
from notes import Notes


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.fixture
def service(tmp_path):
    aliases = tmp_path / "repos.json"
    aliases.write_text(json.dumps({"valiant": str(tmp_path / "repo"), "other": str(tmp_path / "other")}))
    notes = Mock(token_path=tmp_path / "absent-token.json")
    notes.append_research.return_value = {"status": "ok", "doc_title": "Glasses Research"}
    return Jobs(notes, tmp_path / "data", aliases, concurrency=1)


@pytest.fixture
def processes(monkeypatch):
    calls, signals, procs = [], [], []
    gate = asyncio.Event()
    gate.set()

    async def spawn(*command, **kwargs):
        calls.append((command, kwargs))
        if command[0] == "git":
            if "rev-parse" in command:
                kwargs["stdout"].write((str(kwargs["cwd"]) + "\n").encode())
            elif "worktree" in command:
                Path(command[-2]).mkdir(parents=True)
        else:
            Path(command[command.index("-o") + 1]).write_text(json.dumps({
                "summary": "Ottawa is Canada's capital. The Government of Canada confirms this.",
                "report": "# Canada\nOttawa is the capital.\nSource: https://www.canada.ca/",
            }))
        proc = SimpleNamespace(pid=10000 + len(calls), returncode=None)

        async def wait():
            if command[0] == "codex":
                await gate.wait()
            proc.returncode = 0
            return 0
        proc.wait = AsyncMock(side_effect=wait)
        procs.append(proc)
        return proc

    def kill(pid, sig):
        signals.append((pid, sig))
        gate.set()

    monkeypatch.setattr(asyncio, "create_subprocess_exec", AsyncMock(side_effect=spawn))
    monkeypatch.setattr("jobs.os.killpg", kill)
    return SimpleNamespace(calls=calls, signals=signals, procs=procs, gate=gate)


async def settle(service):
    await asyncio.gather(*list(service.tasks.values()))


@pytest.mark.anyio
async def test_allowlist_preview_confirmation_and_replay(service, processes):
    await service.start()
    try:
        for alias in ("/tmp/valiant", "../../valiant"):
            assert service.codex_task(alias, "edit")["status"] == "not_allowed"
        assert service.codex_task("unlisted", "edit")["status"] == "choose_repo"
        assert service.codex_task("valiant", "edit", True)["status"] == "confirmation_required"
        preview = service.codex_task("VALIANT", "edit")
        job_id = preview["job_id"]
        assert preview["status"] == "needs_confirmation" and not processes.calls
        for repo, instruction in (("valiant", "different"), ("other", "edit")):
            assert service.codex_task(repo, instruction, True, job_id)["status"] == "confirmation_required"
        for _ in range(2):
            assert service.codex_task("valiant", "edit", True, job_id)["status"] == "queued"
        await settle(service)
        result = service.status(job_id)
        assert result["status"] == "completed"
        assert result["branch"].startswith("glasses/valiant-")
        assert Path(result["worktree"]).parent.name == job_id
        assert len(processes.calls) == 3
        command, kwargs = processes.calls[-1]
        assert command[command.index("-m") + 1] == "gpt-6-astra"
        assert "model_reasoning_effort=high" in command
        assert command[command.index("-s") + 1] == "workspace-write"
        assert command[command.index("-C") + 1] == result["worktree"]
        assert "Never push, merge" in command[-1]
        assert kwargs["stdin"] == asyncio.subprocess.DEVNULL and kwargs["start_new_session"]
        assert not {"GEMINI_API_KEY", "APP_SHARED_SECRET"} & kwargs["env"].keys()
        assert service.codex_task("valiant", "edit", True, job_id)["status"] == "completed"
        assert len(processes.calls) == 3
    finally:
        await service.close()


@pytest.mark.anyio
async def test_fuzzy_alias_ambiguity_expiry_and_allowlist_revocation(service, processes):
    await service.start()
    try:
        assert service.codex_task("valian", "edit")["status"] == "needs_confirmation"
        service.repos_path.write_text(json.dumps({"work one": "/tmp/a", "work two": "/tmp/b"}))
        assert service.codex_task("work", "edit")["status"] == "choose_repo"
        job_id = service.codex_task("work one", "edit")["job_id"]
        service.db.execute("UPDATE jobs SET created=0 WHERE id=?", (job_id,))
        service.db.commit()
        assert service.codex_task("work one", "edit", True, job_id)["status"] == "confirmation_expired"
        job_id = service.codex_task("work one", "edit")["job_id"]
        service.codex_task("work one", "edit", True, job_id)
        service.repos_path.write_text("{}")
        await settle(service)
        assert service.status(job_id)["status"] == "failed" and not processes.calls
    finally:
        await service.close()


@pytest.mark.anyio
async def test_research_result_docs_and_events(service, processes):
    await service.start()
    try:
        waiter = asyncio.create_task(service.events(0, 1))
        result = service.deep_research("capital of Canada")
        await settle(service)
        done = service.status(result["job_id"])
        assert done["status"] == "completed" and done["document"]["status"] == "skipped"
        assert "Ottawa" in done["summary"]
        service.notes.append_research.assert_not_called()
        batch = await waiter
        assert batch["events"][0]["job_id"] == result["job_id"]
        assert (await service.events(batch["cursor"], 0))["events"] == []
        command, _ = processes.calls[0]
        assert command[command.index("-s") + 1] == "read-only"
        assert "tools.web_search=true" in command and 'web_search="live"' in command
        service.notes.token_path.touch()
        result = service.deep_research("capital", "my notes")
        await settle(service)
        assert service.status(result["job_id"])["document"]["status"] == "ok"
        assert service.notes.append_research.call_args.args[0] == "my notes"
    finally:
        await service.close()


@pytest.mark.anyio
@pytest.mark.parametrize("doc_result", [{"status": "needs_confirmation", "candidates": []}, RuntimeError("private credential")])
async def test_docs_failure_retains_report_without_false_saved_claim(service, processes, doc_result):
    service.notes.token_path.touch()
    if isinstance(doc_result, Exception):
        service.notes.append_research.side_effect = doc_result
    else:
        service.notes.append_research.return_value = doc_result
    await service.start()
    try:
        result = service.deep_research("capital")
        await settle(service)
        done = service.status(result["job_id"])
        assert done["status"] == "completed" and "needs attention" in done["summary"]
        assert "private credential" not in json.dumps(done)
        assert (service.data_dir / "jobs" / result["job_id"] / "report.md").exists()
        assert service.notes.append_research.call_count == 1
    finally:
        await service.close()


@pytest.mark.anyio
async def test_concurrency_queued_and_running_cancellation(service, processes):
    processes.gate.clear()
    await service.start()
    try:
        first = service.deep_research("one")["job_id"]
        second = service.deep_research("two")["job_id"]
        for _ in range(20):
            await asyncio.sleep(0)
            if processes.procs:
                break
        assert service.status(first)["status"] == "running"
        assert service.status(second)["status"] == "queued" and len(processes.calls) == 1
        assert (await service.cancel(second))["status"] == "cancelled"
        assert (await service.cancel(first))["status"] == "cancelled"
        assert (processes.procs[0].pid, signal.SIGTERM) in processes.signals
        assert (processes.procs[0].pid, signal.SIGKILL) in processes.signals
        assert (await service.cancel(first))["status"] == "cancelled"
        assert len((await service.events(0, 0))["events"]) == 2
        assert (await service.cancel("missing"))["status"] == "not_found"
    finally:
        await service.close()


@pytest.mark.anyio
async def test_timeout_and_subprocess_failure(service, processes):
    service.timeouts["deep_research"] = 0.02
    processes.gate.clear()
    await service.start()
    try:
        result = service.deep_research("one")
        await settle(service)
        done = service.status(result["job_id"])
        assert done["status"] == "failed" and "timed out" in done["summary"]
        asyncio.create_subprocess_exec.side_effect = OSError("secret detail")
        result = service.deep_research("two")
        await settle(service)
        done = service.status(result["job_id"])
        assert done["status"] == "failed" and "secret detail" not in done["summary"]
    finally:
        await service.close()


@pytest.mark.anyio
async def test_persistence_recovery_and_single_worker_lock(service, processes):
    await service.start()
    preview = service.codex_task("valiant", "edit")["job_id"]
    orphan = service._insert("deep_research", "running", {"topic": "orphan", "doc_name": "Glasses Research"}, "running")
    queued = service._insert("deep_research", "queued", {"topic": "queued", "doc_name": "Glasses Research"}, "queued")
    second = Jobs(service.notes, service.data_dir, service.repos_path)
    with pytest.raises(RuntimeError, match="one backend worker"):
        await second.start()
    await service.close()
    await second.start()
    try:
        assert second.status(preview)["status"] == "needs_confirmation"
        assert second.status(orphan)["status"] == "failed"
        await settle(second)
        assert second.status(queued)["status"] == "completed"
        assert len((await second.events(0, 0))["events"]) == 2
    finally:
        await second.close()


def test_job_endpoints_validation_auth_and_declarations(monkeypatch, service):
    monkeypatch.setenv("APP_SHARED_SECRET", "test")
    monkeypatch.setattr(app, "Jobs", lambda _: service)
    with TestClient(app.app) as client:
        assert client.get("/events").status_code == 401
        assert client.post("/tools/codex_task", json={}).status_code == 401
        client.headers["X-App-Secret"] = "test"
        assert client.get("/events?wait=0").json() == {"events": [], "cursor": 0}
        assert client.get("/events?after=-1").status_code == 422
        assert client.get("/events?wait=100").status_code == 422
        assert client.post("/tools/deep_research", json={"topic": "  "}).status_code == 422
        assert client.post("/tools/codex_task", json={"repo": "valiant", "instruction": "edit", "confirm": "true"}).status_code == 422
        preview = client.post("/tools/codex_task", json={"repo": "valiant", "instruction": "edit"}).json()
        assert preview["status"] == "needs_confirmation"
        assert client.post("/tools/job_status", json={}).json()["jobs"][0]["job_id"] == preview["job_id"]
        assert client.post("/tools/cancel_job", json={"job_id": preview["job_id"]}).json()["status"] == "cancelled"
    config = gemini.token_config(gemini.DEFAULT_MODEL, "auto")
    assert config.lock_additional_fields == [] and config.live_connect_constraints.config.session_resumption is None
    names = {t.name for t in config.live_connect_constraints.config.tools[0].function_declarations}
    assert {"codex_task", "deep_research", "job_status", "cancel_job"} <= names


def test_default_research_doc_creation_reuse_and_ambiguity(tmp_path):
    service = Notes(aliases_path=tmp_path / "aliases.json")
    service._connect = Mock()
    service._list_documents = Mock(return_value=[])
    service.docs = Mock()
    service.docs.documents().create().execute.return_value = {"documentId": "new"}
    service.docs.reset_mock()
    service.append = Mock(return_value={"status": "ok", "doc_title": "Glasses Research"})
    assert service.append_research("Glasses Research", "report")["status"] == "ok"
    service.docs.documents().create.assert_called_with(body={"title": "Glasses Research"})
    service.append.assert_called_with("Glasses Research", "report", confirmed_doc_id="new")
    service._list_documents.return_value = [{"id": "one", "title": "Glasses Research"}]
    service.append_research("Glasses Research", "report")
    assert service.docs.documents().create.call_count == 1
    service.append.assert_called_with("Glasses Research", "report", confirmed_doc_id="one")
    service._list_documents.return_value *= 2
    assert service.append_research("Glasses Research", "report")["status"] == "needs_confirmation"
    assert service.append.call_count == 2


@pytest.mark.anyio
async def test_shutdown_preserves_queued_work_and_fails_interrupted_work(service, processes):
    processes.gate.clear()
    await service.start()
    first = service.deep_research("one")["job_id"]
    second = service.deep_research("two")["job_id"]
    for _ in range(20):
        await asyncio.sleep(0)
        if processes.procs:
            break
    await service.close()
    await service.start()
    try:
        assert service.status(first)["status"] == "failed"
        await settle(service)
        assert service.status(second)["status"] == "completed"
    finally:
        await service.close()


@pytest.mark.anyio
async def test_confirmation_bound_to_original_path_and_root(service, processes):
    await service.start()
    try:
        preview = service.codex_task("valiant", "edit")
        service.repos_path.write_text(json.dumps({"valiant": "/tmp/replacement"}))
        assert service.codex_task("valiant", "edit", True, preview["job_id"])["status"] == "confirmation_required"
        preview = service.codex_task("valiant", "edit")
        # A path inside a repository must not accidentally resolve to its ancestor.
        original = asyncio.create_subprocess_exec.side_effect
        async def wrong_root(*command, **kwargs):
            proc = await original(*command, **kwargs)
            if "rev-parse" in command:
                kwargs["stdout"].write(b"/different-root\n")
            return proc
        asyncio.create_subprocess_exec.side_effect = wrong_root
        service.codex_task("valiant", "edit", True, preview["job_id"])
        await settle(service)
        assert service.status(preview["job_id"])["status"] == "failed"
        assert len(processes.calls) == 1
    finally:
        await service.close()


@pytest.mark.parametrize("name,args,owner,method", [
    ("append_to_doc", {"text": "test note"}, "notes", "append"),
    ("recall", {"query": "test memory"}, "memory", "recall"),
])
def test_blocking_tools_do_not_block_job_events(monkeypatch, service, name, args, owner, method):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event

    entered, release, finished = Event(), Event(), Event()

    def blocking_tool(**kwargs):
        entered.set()
        try:
            assert release.wait(5), "Tool dispatch blocked the job event loop"
            return {"status": "ok"}
        finally:
            finished.set()

    monkeypatch.setenv("APP_SHARED_SECRET", "test")
    monkeypatch.setattr(app, "Jobs", lambda _: service)
    target = app.notes if owner == "notes" else app.memory_api.memory
    monkeypatch.setattr(target, method, blocking_tool)
    with TestClient(app.app, headers={"X-App-Secret": "test"}) as client, ThreadPoolExecutor() as pool:
        tool = pool.submit(client.post, f"/tools/{name}", json=args)
        try:
            assert entered.wait(2)
            response = client.get("/events?wait=0")
            assert response.status_code == 200
            assert response.json() == {"events": [], "cursor": 0}
            assert response.headers["Cache-Control"] == "no-store"
            assert not finished.is_set()
            assert client.post("/tools/job_status", json={}).json() == {"status": "ok", "jobs": []}
        finally:
            release.set()
        assert tool.result().json() == {"status": "ok"}
