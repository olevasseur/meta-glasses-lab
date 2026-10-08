"""Real Gemini gesture/tool smoke; synthetic conversation, no Docs writes or token output.

Run: cd backend && uv run python ../scripts/smoke_gestures.py
"""

import asyncio
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
import app  # Loads the ignored root .env.
from fastapi.testclient import TestClient
from websockets.asyncio.client import connect

ENDPOINT = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
)


def text_turn(text):
    return {"clientContent": {"turns": [{"role": "user", "parts": [{"text": text}]}], "turnComplete": True}}


async def check(mode, gesture, expected):
    with TestClient(app.app) as client:
        response = client.post("/token", json={"mode": mode}, headers={"X-App-Secret": os.environ["APP_SHARED_SECRET"]})
    if response.status_code != 200:
        raise RuntimeError("Token request failed")
    credentials = response.json()
    async with connect(ENDPOINT, additional_headers={"Authorization": "Token " + credentials["token"]}, max_size=4_000_000) as ws:
        await ws.send(json.dumps({"setup": {"model": "models/" + credentials["model"], "sessionResumption": {}}}))
        while "setupComplete" not in json.loads(await ws.recv()):
            pass
        print(f"PASS {mode}: /token and constrained WebSocket setup", flush=True)
        await ws.send(json.dumps(text_turn(
            "We are testing head gestures with no external actions. Call await_gesture_confirmation "
            "with the question 'Would you like the color blue?' and ask it aloud. "
            "After my gesture, say exactly 'You answered yes' or 'You answered no'. Do not call any other tool."
        )))
        armed = False
        while True:
            event = json.loads(await ws.recv())
            calls = event.get("toolCall", {}).get("functionCalls", [])
            for call in calls:
                if call["name"] != "await_gesture_confirmation":
                    raise RuntimeError("Unexpected tool; no side effect executed")
                if not call.get("args", {}).get("question"):
                    raise RuntimeError("Missing confirmation question")
                await ws.send(json.dumps({"toolResponse": {"functionResponses": [{
                    "id": call["id"], "name": call["name"],
                    "response": {"status": "ready", "expires_in_seconds": 30,
                                 "message": "Ask the question now, then wait for a nod or shake."},
                }]}}))
                armed = True
            if armed and event.get("serverContent", {}).get("turnComplete"):
                break
        print(f"PASS {mode}: confirmation tool call and response", flush=True)
        await ws.send(json.dumps(text_turn(gesture)))
        transcript = ""
        while True:
            event = json.loads(await ws.recv())
            if event.get("toolCall"):
                raise RuntimeError("Unexpected tool after gesture; no side effect executed")
            content = event.get("serverContent", {})
            transcript += content.get("outputTranscription", {}).get("text", "")
            if content.get("turnComplete"):
                break
        if expected not in transcript.lower():
            raise RuntimeError("Gesture answer not acknowledged")
        print(f"PASS {mode}: {gesture} acknowledged as {expected}", flush=True)


async def main():
    if not os.getenv("GEMINI_API_KEY") or not os.getenv("APP_SHARED_SECRET"):
        raise RuntimeError("Missing credentials")
    for mode, gesture, expected in [
        ("auto", "[gesture: nod = yes]", "yes"),
        ("ptt", "[gesture: shake = no]", "no"),
    ]:
        await asyncio.wait_for(check(mode, gesture, expected), timeout=60)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as exc:
        # Provider exceptions can include credentials; never print their content.
        print(f"FAIL: gesture smoke ({type(exc).__name__}); provider details withheld.", flush=True)
        sys.exit(1)
