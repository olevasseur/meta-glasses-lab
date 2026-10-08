"""Real /token -> Live -> look -> JPEG -> frame_sent smoke test. Never logs credentials.

Run from backend/: uv run python ../scripts/smoke_vision.py [--mode auto|ptt]
Uses only the bundled synthetic image; no glasses or Google Docs writes.
"""

import argparse
import base64
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import app  # Loads .env without printing its contents.
from fastapi.testclient import TestClient
from websockets.sync.client import connect

ENDPOINT = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
)
SAMPLE = ROOT / "android/app/src/main/res/raw/vision_sample.jpg"


def main(mode: str) -> None:
    if not os.getenv("GEMINI_API_KEY") or not os.getenv("APP_SHARED_SECRET"):
        raise RuntimeError("Missing configured credentials")
    with TestClient(app.app) as client:
        response = client.post(
            "/token", json={"mode": mode}, headers={"X-App-Secret": os.environ["APP_SHARED_SECRET"]}
        )
    if response.status_code != 200:
        raise RuntimeError("Token creation failed")
    token = response.json()
    assert {"look", "set_watch_mode"} <= set(token["client_tools"])
    jpeg = base64.b64encode(SAMPLE.read_bytes()).decode("ascii")
    with connect(
        ENDPOINT,
        additional_headers={"Authorization": f"Token {token['token']}"},
        open_timeout=20,
        max_size=4 * 1024 * 1024,
    ) as ws:
        ws.send(json.dumps({"setup": {"model": f"models/{token['model']}", "sessionResumption": {}}}))
        deadline = time.monotonic() + 90
        asked = False
        frames = 0
        last_frame = 0.0
        transcript = []
        while time.monotonic() < deadline:
            event = json.loads(ws.recv(timeout=max(0.1, deadline - time.monotonic())))
            if "error" in event:
                raise RuntimeError("Live API reported an error")
            if "setupComplete" in event and not asked:
                asked = True
                print(f"PASS: /token and Live setup accepted ({mode}); credentials redacted.")
                ws.send(json.dumps({"clientContent": {
                    "turns": [{"role": "user", "parts": [{"text":
                        "What am I looking at? Use look to get a fresh image, then name the objects and their colors."
                    }]}], "turnComplete": True,
                }}))
            for call in event.get("toolCall", {}).get("functionCalls", []):
                if call["name"] != "look":
                    result = {"status": "error", "message": "Smoke test only supports look"}
                else:
                    if frames >= 3:
                        raise RuntimeError("Model repeatedly requested a frame without answering")
                    time.sleep(max(0, 1 - (time.monotonic() - last_frame)))
                    ws.send(json.dumps({"realtimeInput": {"video": {"mimeType": "image/jpeg", "data": jpeg}}}))
                    last_frame = time.monotonic()
                    frames += 1
                    result = {"status": "frame_sent"}
                    print("PASS: look called; realtimeInput.video JPEG sent before frame_sent response.")
                function_response = {
                    "id": call["id"], "name": call["name"], "response": result,
                }
                if call["name"] == "look":
                    function_response["parts"] = [{"inlineData": {"mimeType": "image/jpeg", "data": jpeg}}]
                ws.send(json.dumps({"toolResponse": {"functionResponses": [function_response]}}))
            content = event.get("serverContent", {})
            text = content.get("outputTranscription", {}).get("text")
            if text:
                transcript.append(text)
            # The tool-request turn can complete before the subsequent spoken answer.
            if content.get("turnComplete") and frames and transcript:
                answer = "".join(transcript).strip()
                if not ("blue" in answer.lower() and ("mug" in answer.lower() or "cup" in answer.lower()) and "orange" in answer.lower()):
                    print(f"Pending sample answer: {answer}", flush=True)
                    transcript.clear()
                    continue
                print(f"PASS: sample answer: {answer}")
                return
        raise TimeoutError("No completed visual answer")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["auto", "ptt"], default="auto")
    try:
        main(parser.parse_args().mode)
    except Exception as exc:
        # Upstream exception strings may contain authentication/request details.
        raise SystemExit(f"FAILED: vision smoke ({type(exc).__name__}); credential-bearing details suppressed.") from None
