"""Real /token + constrained Live translation check; prints only fixed test transcripts.

Run from backend/: uv run python ../scripts/smoke_translation.py
Uses the gitignored root .env; never logs keys or ephemeral credentials.
"""

import asyncio
import base64
import json
import os
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
import app
from fastapi.testclient import TestClient
from websockets.asyncio.client import connect

ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"


def get_token(persona="translator"):
    payload = {"mode": "auto", "persona": persona}
    if persona == "translator":
        payload["language"] = "es"
    with TestClient(app.app) as client:
        response = client.post("/token", json=payload, headers={"X-App-Secret": os.environ["APP_SHARED_SECRET"]})
    if response.status_code != 200:
        raise RuntimeError(f"/token HTTP {response.status_code}")
    return response.json()


async def setup(ws, token):
    await ws.send(json.dumps({"setup": {"model": f"models/{token['model']}", "sessionResumption": {}}}))
    while True:
        event = json.loads(await ws.recv())
        if "error" in event:
            raise RuntimeError("Live setup rejected")
        if "setupComplete" in event:
            return


async def turn(ws, text, expected_tool=None):
    await ws.send(json.dumps({"clientContent": {"turns": [{"role": "user", "parts": [{"text": text}]}], "turnComplete": True}}))
    transcript, audio_bytes = "", 0
    while True:
        event = json.loads(await ws.recv())
        if "error" in event:
            raise RuntimeError("Live turn failed")
        if "toolCall" in event:
            calls = event["toolCall"]["functionCalls"]
            assert expected_tool and len(calls) == 1 and calls[0]["name"] == expected_tool
            call = calls[0]
            with TestClient(app.app) as client:
                response = client.post(f"/tools/{expected_tool}", json=call.get("args", {}),
                                       headers={"X-App-Secret": os.environ["APP_SHARED_SECRET"]})
            assert response.status_code == 200
            result = response.json()
            if expected_tool == "start_translation":
                assert result["language"] == "es"
            await ws.send(json.dumps({"toolResponse": {"functionResponses": [{"id": call["id"], "name": expected_tool, "response": result}]}}))
            print(f"PASS: {expected_tool} tool + backend command")
            return transcript, audio_bytes
        content = event.get("serverContent", {})
        transcript += content.get("outputTranscription", {}).get("text", "")
        for part in content.get("modelTurn", {}).get("parts", []):
            blob = part.get("inlineData", {})
            if blob.get("mimeType", "").startswith("audio/pcm"):
                audio_bytes += len(base64.b64decode(blob["data"]))
        if content.get("turnComplete"):
            assert expected_tool is None, "Expected mode-switch tool was not called"
            return transcript, audio_bytes


async def main():
    assert os.getenv("GEMINI_API_KEY") and os.getenv("APP_SHARED_SECRET"), "Configure root .env"
    token = get_token("assistant")
    async with asyncio.timeout(60), connect(ENDPOINT, additional_headers={"Authorization": f"Token {token['token']}"}) as ws:
        await setup(ws, token)
        await turn(ws, "Translate Spanish", "start_translation")
    # Deliberately fresh token and empty resumption object, just like the Android switch.
    token = get_token()
    async with asyncio.timeout(90), connect(ENDPOINT, additional_headers={"Authorization": f"Token {token['token']}"}) as ws:
        await setup(ws, token)
        for text, label, expected in [
            ("¿Dónde está la estación de tren?", "English", "station"),
            ("I would like a glass of water, please.", "Spanish", "agua"),
        ]:
            transcript, size = await turn(ws, text)
            assert re.match(rf"\s*{label}\s*[:.,!—-]", transcript, re.I), f"Missing routing label: {transcript}"
            assert expected in transcript.lower() and size > 0, "Wrong translation or missing audio"
            print(f"PASS: {text} → {transcript.strip()} ({size} PCM bytes)")
        await turn(ws, "Stop translation", "stop_translation")
    print("PASS: fresh constrained sessions; both translation directions and mode tools; no credentials printed")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as exc:
        # Do not print upstream exceptions: WebSocket diagnostics may include headers.
        print(f"FAILED: translation smoke check ({type(exc).__name__}); credentials withheld")
        raise SystemExit(1)
