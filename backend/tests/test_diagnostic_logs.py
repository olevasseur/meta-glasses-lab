import json
import stat
from concurrent.futures import ThreadPoolExecutor

import pytest

import diagnostic_logs as logs
from test_dashboard import SECRET, client, login


def entry(number=1, **kwargs):
    return logs.Entry(id=f"event-{number}", timestamp="2026-10-08T12:00:00Z", feature="audio", event="route_ready", **kwargs)


def test_rotation_restart_permissions_and_dedup(tmp_path):
    store = logs.LogStore(tmp_path / "logs", max_bytes=600, backups=2)
    for i in range(12):
        store.append([entry(i)])
    assert len(list(store.directory.iterdir())) == 3
    assert stat.S_IMODE(store.directory.stat().st_mode) == 0o700
    assert all(stat.S_IMODE(p.stat().st_mode) == 0o600 for p in store.directory.iterdir())
    restarted = logs.LogStore(store.directory, max_bytes=600, backups=2)
    result = restarted.read()
    assert result["entries"][-1]["id"] == "event-11"
    restarted.append([entry(11)])
    assert len(restarted.read()["entries"]) == len(result["entries"])
    restarted.append([entry(12)])
    assert restarted.read(result["cursor"])["entries"][0]["id"] == "event-12"


def test_concurrent_bounded_order_and_filters(tmp_path):
    store = logs.LogStore(tmp_path / "logs", max_bytes=6000, backups=2)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda i: store.append([entry(i, level="WARN" if i % 2 else "INFO")]), range(2100)))
    assert len(store.rows) == 2000
    rows = store.read(level="WARN")["entries"]
    assert len(rows) == 200 and all(r["level"] == "WARN" for r in rows)
    assert len({r["cursor"] for r in store.rows}) == 2000
    assert store.read(after=1)["gap"]
    assert store.read(feature="vision")["entries"] == []


def test_auth_upload_redaction_validation_and_limits(client, monkeypatch):
    payload = {"entries": [entry().model_dump()]}
    assert client.post("/logs", json=payload).status_code == 401
    assert client.get("/logs").status_code == 401
    headers = {"X-App-Secret": SECRET}
    assert client.get("/diagnostics/health", headers=headers).json()["status"] == "ok"
    payload["entries"][0]["event"] = f"{SECRET} auth_tokens/private\n<script>alert(1)</script>"
    result = client.post("/logs", json=payload, headers=headers)
    assert result.status_code == 200 and result.headers["cache-control"] == "no-store"
    row = client.get("/logs", headers=headers).json()["entries"][0]
    assert SECRET not in json.dumps(row) and "auth_tokens/private" not in row["event"]
    assert "\n" not in row["event"]
    payload["entries"][0]["extra"] = SECRET
    response = client.post("/logs", json=payload, headers=headers)
    assert response.status_code == 422 and SECRET not in response.text
    assert client.post("/logs", content=b"x" * 131_073, headers=headers).status_code == 413
    assert client.post("/logs", json={"entries": [entry().model_dump()] * 101}, headers=headers).status_code == 422


def test_dashboard_cookie_escaping_and_poll_filters(client):
    logs.store.append([logs.Entry(id="x", timestamp="now", feature="audio", event="<script>oops</script>", level="WARN"), entry()])
    assert client.get("/dashboard/live-logs", headers={"X-App-Secret": SECRET}).status_code == 303
    login(client)
    html = client.get("/dashboard/live-logs?feature=audio&level=WARN").text
    assert "&lt;script&gt;oops&lt;/script&gt;" in html and "<script>oops" not in html
    assert 'hx-trigger="every 2s"' in html and 'hx-include="#log-filters"' in html
    assert 'href="/dashboard/live-logs"' in html and "route_ready" not in html
    assert client.get("/logs").status_code == 401  # Cookie cannot bypass app auth.


def test_store_failure_returns_retryable_without_echo(client, monkeypatch):
    def fail(*args):
        raise OSError("private path and secret")
    monkeypatch.setattr(logs.store, "append", fail)
    result = client.post("/logs", json={"entries": [entry().model_dump()]}, headers={"X-App-Secret": SECRET})
    assert result.status_code == 503 and "private" not in result.text
