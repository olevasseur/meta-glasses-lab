"""A/B: full backend token config vs plain config, manual activity, same audio."""
import asyncio, base64, json, os, sys, time, wave
import httpx
from websockets.asyncio.client import connect
CONSTRAINED = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
PLAIN = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
mode, wav, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
pcm = wave.open(wav, "rb").readframes(10**9)
async def once():
    r = httpx.post("http://127.0.0.1:8787/token", json={"mode": "over"}, headers={"X-App-Secret": os.environ["APP_SHARED_SECRET"]}).json()
    model = f"models/{r['model'].removeprefix('models/')}"
    if mode == "full":
        ws_ctx = connect(CONSTRAINED, additional_headers={"Authorization": f"Token {r['token']}"})
        setup = {"model": model, "sessionResumption": {}}
    else:
        ws_ctx = connect(PLAIN, additional_headers={"x-goog-api-key": os.environ["GEMINI_API_KEY"]})
        setup = {"model": model, "generationConfig": {"responseModalities": ["AUDIO"]},
                 "realtimeInputConfig": {"automaticActivityDetection": {"disabled": True}},
                 "inputAudioTranscription": {}, "outputAudioTranscription": {}}
    async with ws_ctx as ws:
        await ws.send(json.dumps({"setup": setup}))
        while "setupComplete" not in json.loads(await ws.recv()): pass
        await ws.send(json.dumps({"realtimeInput": {"activityStart": {}}}))
        for o in range(0, len(pcm), 640):
            await ws.send(json.dumps({"realtimeInput": {"audio": {"mimeType": "audio/pcm;rate=16000", "data": base64.b64encode(pcm[o:o+640]).decode()}}}))
            await asyncio.sleep(0.02)
        await ws.send(json.dumps({"realtimeInput": {"activityEnd": {}}}))
        t = time.time(); first = {}
        try:
            async with asyncio.timeout(20):
                while True:
                    m = json.loads(await ws.recv())
                    c = m.get("serverContent", {})
                    for k, present in (("transcript", "inputTranscription" in c), ("tool", "toolCall" in m), ("reply", bool(c.get("modelTurn")))):
                        if present and k not in first: first[k] = round(time.time() - t, 1)
                    if "toolCall" in m or c.get("turnComplete"): return first
        except TimeoutError:
            return {**first, "SILENT": True}
silent = 0
for i in range(n):
    try: res = asyncio.run(once())
    except Exception as e: res = {"ERROR": type(e).__name__}
    silent += bool(res.get("SILENT"))
    print(f"{mode} {i+1}: {res}", flush=True)
print(f"{mode} SUMMARY silent={silent}/{n}")
