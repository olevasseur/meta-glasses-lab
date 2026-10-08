"""Real Live workout tools/cues; synthetic progress, disposable memory, no hardware.

Run from backend: uv run python ../scripts/smoke_workout.py
"""

import asyncio
from contextlib import closing
import json
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from uuid import uuid4
from datetime import UTC, datetime

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
import app
import memory_api
from memory import Memory
from fastapi.testclient import TestClient
from websockets.asyncio.client import connect
from smoke_gestures import ENDPOINT, text_turn


async def check(client, mode):
    headers = {"X-App-Secret": os.environ["APP_SHARED_SECRET"]}
    response = client.post("/token", json={"mode": mode}, headers=headers)
    assert response.status_code == 200, "Token failed"
    credentials = response.json()
    async with connect(ENDPOINT, additional_headers={"Authorization": "Token " + credentials["token"]}, max_size=4_000_000) as ws:
        await ws.send(json.dumps({"setup": {"model": "models/" + credentials["model"], "sessionResumption": {}}}))
        while "setupComplete" not in json.loads(await ws.recv()):
            pass
        print(f"PASS {mode}: constrained setup with workout tools", flush=True)

        async def exchange(text, tool=None):
            await ws.send(json.dumps(text_turn(text)))
            seen = False
            spoken = ""
            while True:
                event = json.loads(await ws.recv())
                for call in event.get("toolCall", {}).get("functionCalls", []):
                    assert call["name"] == tool, "Unexpected tool; no side effect executed"
                    seen = True
                    if tool == "start_workout":
                        assert call["args"]["exercise"] == "squats"
                        assert call["args"]["target_reps"] == 12
                        result = {"status": "work", "exercise": "squats", "sets": 1, "target_reps": 12, "motion": "IMU live"}
                    elif tool == "workout_status":
                        result = {"status": "work", "exercise": "squats", "reps_per_set": [5], "completed_sets": 0, "sets": 1}
                    elif tool == "stop_workout":
                        result = {"status": "stopped", "exercise": "squats", "reps_per_set": [5]}
                    else:
                        reply = client.post("/tools/workout_history", json=call["args"], headers=headers)
                        assert reply.status_code == 200
                        result = reply.json()
                        assert result["totals"]["total_reps"] == 5
                    await ws.send(json.dumps({"toolResponse": {"functionResponses": [{
                        "id": call["id"], "name": call["name"], "response": result,
                    }]}}))
                content = event.get("serverContent", {})
                spoken += content.get("outputTranscription", {}).get("text", "")
                if content.get("turnComplete") and (tool is None or seen) and spoken.strip():
                    break
            assert spoken.strip(), "No spoken transcript"
            print(f"PASS {mode}: {tool or 'milestone encouragement'}", flush=True)

        await exchange("Start one set of 12 squats. Call start_workout now.", "start_workout")
        await exchange("[rep 5/12 squats] Give very brief encouragement, no tool calls.")
        await exchange("How is my active workout going? Call workout_status.", "workout_status")
        await exchange("Stop this workout now. Call stop_workout.", "stop_workout")
        now = datetime.now(UTC).isoformat()
        log = {"workout_id": str(uuid4()), "exercise": "squats", "started_at": now,
               "ended_at": now, "reps_per_set": [5], "completed_sets": 0,
               "planned_sets": 1, "target_reps": 12, "work_seconds": None,
               "rest_seconds": 30, "active_seconds": 10, "status": "stopped"}
        assert client.post("/workouts", json=log, headers=headers).status_code == 200
        replay = client.post("/workouts", json=log, headers=headers)
        assert replay.status_code == 200 and replay.json()["stored"] is False
        await exchange("How many squats did I do this week? Call workout_history for this_week and squats.", "workout_history")


async def main():
    with TemporaryDirectory(prefix="workout-smoke-") as directory:
        for mode in ("auto", "ptt"):
            memory_api.memory = Memory(Path(directory) / f"{mode}.db")
            # Exercise workouts without starting/resuming personal background jobs.
            with closing(TestClient(app.app)) as client:
                await asyncio.wait_for(check(client, mode), timeout=100)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as exc:
        if isinstance(exc, AssertionError):
            import traceback
            print("Failed smoke assertion at line", traceback.extract_tb(exc.__traceback__)[-1].lineno, flush=True)
        print(f"FAIL: workout smoke ({type(exc).__name__}); provider details withheld.", flush=True)
        sys.exit(1)
