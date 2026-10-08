"""Real Gemini memory smoke test using fake data in a disposable SQLite database.

Run from backend/: uv run python ../scripts/smoke_memory.py [--live-setup]
Never prints credentials, tokens or real stored conversations.
"""

import asyncio
import json
import os
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
import app  # Loads .env without printing it.
import memory_api
from fastapi.testclient import TestClient
from memory import Memory
from memory_summary import DEFAULT_SUMMARY_MODEL


async def live_setup(token: dict):
    from websockets.asyncio.client import connect

    endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
    async with connect(endpoint, additional_headers={"Authorization": f"Token {token['token']}"}, open_timeout=20) as ws:
        await ws.send(json.dumps({"setup": {"model": f"models/{token['model']}", "sessionResumption": {}}}))
        message = json.loads(await asyncio.wait_for(ws.recv(), timeout=20))
        if "setupComplete" not in message:
            raise RuntimeError("Live setup was not accepted")
    print("PASS: constrained Live setupComplete with memory and all four tools; token redacted.")


def main():
    if not os.getenv("GEMINI_API_KEY") or not os.getenv("APP_SHARED_SECRET"):
        raise SystemExit("SKIPPED: configure GEMINI_API_KEY and APP_SHARED_SECRET; values never printed.")
    original = memory_api.memory
    try:
        with TemporaryDirectory(prefix="glasses-memory-smoke-") as directory:
            memory_api.memory = Memory(Path(directory) / "memory.db")
            with TestClient(app.app, headers={"X-App-Secret": os.environ["APP_SHARED_SECRET"]}) as client:
                response = client.post("/transcripts", json={"session_id": "fake-smoke", "turns": [
                    {"seq": 0, "speaker": "user", "text": "My dog is named Maple. I live in Toronto. I prefer short spoken answers.", "timestamp": "2026-10-07T12:00:00Z"},
                    {"seq": 1, "speaker": "model", "text": "Understood.", "timestamp": "2026-10-07T12:00:01Z"},
                ]})
                if response.status_code != 200:
                    raise RuntimeError("Transcript upload failed")
                response = client.post("/sessions/fake-smoke/end")
                if response.status_code != 200:
                    raise RuntimeError(f"Summary HTTP {response.status_code}")
                result = response.json()
                if not result.get("facts"):
                    raise RuntimeError("No facts extracted")
                print(f"PASS: Gemini summary; model={os.getenv('GEMINI_SUMMARY_MODEL') or DEFAULT_SUMMARY_MODEL}")
                print(json.dumps({"fake_summary": result["summary"], "fake_facts": result["facts"]}, indent=2))
                recalled = client.post("/tools/recall", json={"query": "Maple"}).json()
                if not recalled.get("matches"):
                    raise RuntimeError("Recall found no fake fact")
                print("PASS: stored facts and keyword recall.")
                if "--live-setup" in sys.argv:
                    response = client.post("/token", json={})
                    if response.status_code != 200:
                        raise RuntimeError(f"Token HTTP {response.status_code}")
                    asyncio.run(live_setup(response.json()))
    except Exception as exc:
        # Exception bodies from SDK/network libraries may contain credentials or payloads.
        raise SystemExit(f"FAILED: memory smoke ({type(exc).__name__}); no upstream error body printed.") from None
    finally:
        memory_api.memory = original


if __name__ == "__main__":
    main()
