"""Real /token + Gemini manual-turn smoke check. Never logs credentials or payloads.

Run from backend/: uv run python ../scripts/smoke_over.py --wav /path/to/question.wav
"""

import asyncio
import argparse
import base64
import json
import os
import sys
from pathlib import Path
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
import app  # Loads root .env without replacing explicit environment variables.
from fastapi.testclient import TestClient
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

ENDPOINT = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
)


async def check(result: dict, pcm: bytes) -> None:
    async with connect(ENDPOINT, additional_headers={"Authorization": f"Token {result['token']}"}, open_timeout=20) as socket:
        await socket.send(json.dumps({"setup": {"model": f"models/{result['model'].removeprefix('models/')}", "sessionResumption": {}}}))
        async with asyncio.timeout(20):
            while "setupComplete" not in json.loads(await socket.recv()):
                pass
        print("PASS: constrained WebSocket setupComplete; client-owned sessionResumption accepted.")
        await socket.send(json.dumps({"realtimeInput": {"activityStart": {}}}))
        for offset in range(0, len(pcm), 640):
            await socket.send(json.dumps({"realtimeInput": {"audio": {
                "mimeType": "audio/pcm;rate=16000", "data": base64.b64encode(pcm[offset:offset + 640]).decode(),
            }}}))
            await asyncio.sleep(0.02)
        # Manual activity must remain open even after a pause longer than Auto's default.
        deadline = asyncio.get_running_loop().time() + 2.5
        while (remaining := deadline - asyncio.get_running_loop().time()) > 0:
            try:
                message = json.loads(await asyncio.wait_for(socket.recv(), remaining))
            except TimeoutError:
                break
            content = message.get("serverContent", {})
            if content.get("modelTurn") or content.get("turnComplete"):
                raise RuntimeError("Model responded before activityEnd")
        print("PASS: no model response during a 2.5-second pause before activityEnd.")
        await socket.send(json.dumps({"realtimeInput": {"activityEnd": {}}}))
        audio_bytes = 0
        output = []
        async with asyncio.timeout(40):
            while True:
                message = json.loads(await socket.recv())
                if "error" in message:
                    raise RuntimeError("Gemini returned an error")
                content = message.get("serverContent", {})
                output.append(content.get("outputTranscription", {}).get("text", ""))
                for part in content.get("modelTurn", {}).get("parts", []):
                    blob = part.get("inlineData", {})
                    if blob.get("mimeType", "").startswith("audio/pcm"):
                        audio_bytes += len(base64.b64decode(blob["data"]))
                if content.get("turnComplete"):
                    break
        if audio_bytes == 0:
            raise RuntimeError("No audio response received")
        text = "".join(output).lower()
        if not ("four" in text or "4" in text) or "over" in text:
            raise RuntimeError("Response did not answer the request cleanly")
        print(f"PASS: activityStart → PCM audio → activityEnd → {audio_bytes} PCM bytes + turnComplete; answer verified, sign-off ignored.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wav", type=Path, required=True, help="Mono PCM16 16 kHz WAV saying 'What is two plus two? Over.'")
    args = parser.parse_args()
    with wave.open(str(args.wav), "rb") as audio:
        if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 16000):
            raise SystemExit("WAV must be mono PCM16 at 16000 Hz.")
        pcm = audio.readframes(audio.getnframes())
    if not os.getenv("GEMINI_API_KEY") or not os.getenv("APP_SHARED_SECRET"):
        raise SystemExit("SKIPPED: GEMINI_API_KEY and APP_SHARED_SECRET are required.")
    with TestClient(app.app) as client:
        response = client.post("/token", json={"mode": "over"}, headers={"X-App-Secret": os.environ["APP_SHARED_SECRET"]})
    if response.status_code != 200:
        raise SystemExit(f"FAILED: POST /token mode=over returned HTTP {response.status_code}; credentials withheld.")
    print("PASS: real POST /token mode=over HTTP 200; token withheld.")
    try:
        asyncio.run(check(response.json(), pcm))
    except ConnectionClosed as exc:
        print(f"WebSocket close code: {exc.rcvd.code if exc.rcvd else 'no close frame'}")
        # Print only a fixed diagnostic category, never the server reason or exception.
        reason = exc.rcvd.reason.lower() if exc.rcvd else ""
        for word in ("audio", "activity", "internal", "unsupported", "invalid"):
            if word in reason:
                print(f"Close reason category: {word}")
        raise SystemExit("FAILED: WebSocket closed before the check completed; credentials withheld.") from None
    except Exception as exc:
        # Exceptions may contain request headers; do not print exception text or tracebacks.
        raise SystemExit(f"FAILED: WebSocket check ({type(exc).__name__}); credentials and payloads withheld.") from None


if __name__ == "__main__":
    main()
