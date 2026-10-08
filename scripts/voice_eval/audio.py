"""Gemini TTS fixtures; deterministic PCM conversion, pauses and noise."""
import audioop
import hashlib
import io
import json
import random
import struct
import wave
from pathlib import Path

MODEL = "gemini-3.8-flash-tts"
STYLE = "Natural conversational speech, as if talking to a glasses assistant."
RATE = 16000
CACHE = Path(__file__).parent / "audio"


def pcm16(data: bytes, mime: str) -> bytes:
    if data.startswith(b"RIFF"):
        with wave.open(io.BytesIO(data)) as wav:
            if wav.getsampwidth() != 2 or wav.getnchannels() != 1:
                raise ValueError("Expected mono PCM16 TTS")
            rate = wav.getframerate()
            data = wav.readframes(wav.getnframes())
    else:
        if not mime.startswith("audio/pcm"):
            raise ValueError("Unexpected TTS audio format")
        rate = int(mime.split("rate=")[1].split(";")[0])
    if rate < RATE or len(data) % 2:
        raise ValueError("Invalid TTS PCM")
    data = audioop.ratecv(data, 2, 1, rate, RATE, None)[0]
    # Remove provider padding; retain 100 ms around speech. Pauses are added explicitly.
    frames = [data[i:i + 320] for i in range(0, len(data), 320)]
    active = [i for i, frame in enumerate(frames) if audioop.rms(frame, 2) > 100]
    if not active:
        raise ValueError("TTS returned silence")
    return b"".join(frames[max(0, active[0] - 10):active[-1] + 11])


def specification(text, voice, model):
    return {"model": model, "voice": voice, "text": text, "style": STYLE, "format": 1}


def cached_path(text, voice, model=MODEL, cache=CACHE):
    digest = hashlib.sha256(json.dumps(specification(text, voice, model), sort_keys=True).encode()).hexdigest()
    return cache / f"{digest}.wav"


def synthesize(client, text: str, voice: str, model=MODEL, cache=CACHE) -> Path:
    spec = specification(text, voice, model)
    path = cached_path(text, voice, model, cache)
    if path.exists():
        read(path)
        return path
    response = client.models.generate_content(
        model=model, contents=[{"role": "user", "parts": [{"text": text,
            "speech_metadata": {"style": STYLE}}]}],
        config={"automatic_function_calling": {"disable": True}, "response_modalities": ["AUDIO"], "speech_config": {
            "voice_config": {"prebuilt_voice_config": {"voice_name": voice}}}},
    )
    blobs = [p.inline_data for p in response.candidates[0].content.parts if p.inline_data]
    if not blobs:
        raise ValueError("TTS returned no audio")
    data = b"".join(pcm16(blob.data, blob.mime_type) for blob in blobs)
    cache.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    with wave.open(str(temp), "wb") as wav:
        wav.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        wav.writeframes(data)
    temp.replace(path)
    spec['pcm_sha256']=hashlib.sha256(data).hexdigest()
    path.with_suffix(".json").write_text(json.dumps(spec, indent=2) + "\n")
    return path


def read(path):
    with wave.open(str(path)) as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, RATE):
            raise ValueError("Expected 16 kHz mono PCM16 fixture")
        return wav.readframes(wav.getnframes())


def mix_noise(data, snr_db, seed=42):
    rng = random.Random(seed)
    sigma = audioop.rms(data, 2) / (10 ** (snr_db / 20))
    samples = (max(-32768, min(32767, round(x[0] + rng.gauss(0, sigma))))
               for x in struct.iter_unpack("<h", data))
    return b"".join(struct.pack("<h", x) for x in samples)


def assemble(parts, pauses, snr_db=None):
    if len(pauses) != len(parts) - 1 or any(not 0 <= p <= 30 for p in pauses):
        raise ValueError("One bounded pause is required between segments")
    data = b"".join(part + (bytes(round(pauses[i] * RATE) * 2) if i < len(pauses) else b"")
                    for i, part in enumerate(parts))
    return mix_noise(data, snr_db) if snr_db is not None else data


def insert_pause(pcm, seconds):
    """Add a precise pause at a quiet mid-turn word boundary, never cut speech."""
    candidates=[]
    start=None
    for i in range(len(pcm)//4//640*640, len(pcm)*3//4//640*640,640):
        if audioop.rms(pcm[i:i+640],2)<100:
            if start is None: start=i
        elif start is not None:
            candidates.append((i-start,start))
            start=None
    if not candidates or max(candidates)[0]<3200:
        raise ValueError("No safe mid-turn pause boundary")
    length,offset=max(candidates)
    # Replace existing quiet span to make the final gap exactly the requested duration.
    return pcm[:offset]+bytes(round(seconds*RATE)*2)+pcm[offset+length:]
