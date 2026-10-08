"""Real CLI + authenticated ASGI smoke: cd backend && uv run python ../scripts/smoke_jobs.py.

Uses the Mac's Codex login. Appends to Glasses Research only if Google is configured.
"""
import os
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
from fastapi.testclient import TestClient
import app


def main():
    secret = os.getenv("APP_SHARED_SECRET")
    if not secret:
        raise SystemExit("Configure APP_SHARED_SECRET in .env first")
    with TestClient(app.app, headers={"X-App-Secret": secret}) as client:
        response = client.post("/tools/deep_research", json={"topic": "What is the capital of Canada? One official source, brief report."})
        response.raise_for_status()
        job_id = response.json()["job_id"]
        print("Started research job", job_id, flush=True)
        deadline = time.monotonic() + 180
        cursor = 0
        observed = False
        while time.monotonic() < deadline:
            batch = client.get("/events", params={"after": cursor, "wait": 2}).json()
            cursor = batch["cursor"]
            observed |= any(e["job_id"] == job_id for e in batch["events"])
            result = client.post("/tools/job_status", json={"job_id": job_id}).json()
            if result["status"] in {"completed", "failed", "cancelled"}:
                if not observed:
                    continue  # Completion may race the previous poll; fetch its event next.

                print(result["status"] + ": " + result["summary"], flush=True)
                print("Document:", result.get("document", {}).get("status", "none"), flush=True)
                return 0 if result["status"] == "completed" else 1
            time.sleep(1)
        client.post("/tools/cancel_job", json={"job_id": job_id}).raise_for_status()
        print("Smoke timed out; cancelled job", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
