"""Real captions and search over synthetic samples, using disposable local storage.

cd backend && uv run python ../scripts/smoke_visual_memory.py [--live-setup]
"""
import asyncio
import base64
import io
import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
import app
import memory_api
import visual_api
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw, ImageFont
from memory import Memory
from visual_memory import VisualMemory


def samples():
    yield "mug", "mug", (Path(__file__).resolve().parents[1] / "android/app/src/main/res/raw/vision_sample.jpg").read_bytes()
    image = Image.new("RGB", (800, 600), "#b98c63")
    draw = ImageDraw.Draw(image)
    draw.ellipse((80, 170, 280, 370), outline="#dedede", width=14)
    # Two conventional metal keys on a wooden table, without text giving away the object.
    for x, y in [(230, 280), (330, 240)]:
        draw.ellipse((x, y, x + 90, y + 90), fill="#c4c5c6", outline="#666666", width=3)
        draw.ellipse((x + 28, y + 20, x + 62, y + 54), fill="#b98c63")
        draw.rectangle((x + 36, y + 70, x + 60, y + 220), fill="#c4c5c6")
        draw.rectangle((x + 50, y + 180, x + 82, y + 200), fill="#c4c5c6")
        draw.rectangle((x + 50, y + 211, x + 72, y + 230), fill="#c4c5c6")
    output = io.BytesIO(); image.save(output, format="JPEG")
    yield "keys", "keys", output.getvalue()
    image = Image.new("RGB", (800, 600), "#888888")
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 64)
    draw.rectangle((50, 70, 750, 390), fill="white")
    draw.multiline_text((95, 95), "PARKING\nLEVEL B2\nSPOT 42", font=font, fill="black", spacing=18)
    output = io.BytesIO(); image.save(output, format="JPEG")
    yield "parking", "B2", output.getvalue()


async def setup(token):
    from websockets.asyncio.client import connect
    endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
    async with connect(endpoint, additional_headers={"Authorization": f"Token {token['token']}"}, open_timeout=20) as ws:
        await ws.send(json.dumps({"setup": {"model": "models/" + token["model"], "sessionResumption": {}}}))
        result = json.loads(await asyncio.wait_for(ws.recv(), 20))
        assert "setupComplete" in result
    print("PASS: real constrained Live setup with visual tools; token redacted")


def main():
    if not os.getenv("GEMINI_API_KEY") or not os.getenv("APP_SHARED_SECRET"):
        raise SystemExit("Configure backend credentials first; no values printed")
    old_visual, old_memory = visual_api.memory, memory_api.memory
    try:
        with TemporaryDirectory(prefix="visual-smoke-") as temp:
            visual_api.memory = VisualMemory(Path(temp) / "visual")
            memory_api.memory = Memory(Path(temp) / "memory.db")
            with TestClient(app.app, headers={"X-App-Secret": os.environ["APP_SHARED_SECRET"]}) as client:
                for name, query, jpeg in samples():
                    response = client.post("/visual/frames", json={"jpeg": base64.b64encode(jpeg).decode(),
                        "captured_at": datetime.now(UTC).isoformat(), "pinned": name == "parking"})
                    assert response.status_code == 200, f"caption HTTP {response.status_code}"
                    result = response.json()
                    assert result["status"] == "ok"
                    print(f"PASS: {name} caption: {result['caption']}")
                    matches = client.post("/tools/find_object", json={"query": query}).json()["matches"]
                    assert any(match["id"] == result["id"] for match in matches)
                    print(f"PASS: find_object({query}) found that sample")
                assert client.post("/visual/delete", json={}).json()["deleted"] == 3
                assert not list((Path(temp) / "visual").glob("*.jpg"))
                print("PASS: delete last hour removed all three records/thumbnails including pin")
                if "--live-setup" in sys.argv:
                    for mode in ("auto", "ptt", "over"):
                        response = client.post("/token", json={"mode": mode})
                        assert response.status_code == 200
                        asyncio.run(setup(response.json()))
    except Exception as exc:
        raise SystemExit(f"FAILED: visual smoke ({type(exc).__name__}); upstream body redacted") from None
    finally:
        visual_api.memory, memory_api.memory = old_visual, old_memory


if __name__ == "__main__":
    main()
