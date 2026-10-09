"""Offline check of the repeated-turn Live probe; no tokens or network."""
import asyncio
import json
from contextlib import asynccontextmanager
from types import SimpleNamespace

from voice_eval import over_latency
from voice_eval.client import media


def test_probe_uses_balanced_manual_turns_and_stops_audio_at_end(monkeypatch):
    async def check():
        events = asyncio.Queue()
        events.put_nowait(json.dumps({'setupComplete': {}}))
        sent = []
        talking = False

        class Socket:
            async def send(self, message):
                nonlocal talking
                value = json.loads(message)
                sent.append(value)
                realtime = value.get('realtimeInput', {})
                if 'activityStart' in realtime:
                    assert not talking
                    talking = True
                if 'audio' in realtime:
                    assert talking
                if 'activityEnd' in realtime:
                    assert talking
                    talking = False
                    events.put_nowait(json.dumps({'serverContent': {
                        'inputTranscription': {'text': 'synthetic'},
                        'modelTurn': {'parts': [{'inlineData': {'mimeType': 'audio/pcm;rate=24000', 'data': 'AQI='}}]},
                        'turnComplete': True,
                    }}))

            async def recv(self):
                return await events.get()

        @asynccontextmanager
        async def connection(*args, **kwargs):
            yield Socket()

        async def stream(ws, pcm):
            await ws.send(json.dumps(media('audio', pcm)))
            return 0

        original_sleep = asyncio.sleep
        async def sleep(_):
            await original_sleep(0)

        monkeypatch.setattr(over_latency, 'metered_connection', connection)
        monkeypatch.setattr(over_latency, 'stream_pcm', stream)
        monkeypatch.setattr(over_latency.asyncio, 'sleep', sleep)
        monkeypatch.setenv('APP_SHARED_SECRET', 'fixture')
        http = SimpleNamespace(post=lambda *a, **kw: SimpleNamespace(
            status_code=200, json=lambda: {'model': 'fixture', 'token': 'fixture'}))
        row = {'condition': 'fixture'}
        await over_latency.session(http, b'\x01\x02', row)
        assert len(row['turns']) == 3
        assert all(t['audio_bytes'] == 2 and t['end_to_audio_ms'] >= 0 for t in row['turns'])
        assert sum('activityStart' in e.get('realtimeInput', {}) for e in sent) == 3
        assert sum('activityEnd' in e.get('realtimeInput', {}) for e in sent) == 3
        assert not talking

    asyncio.run(check())
