"""Real constrained Live confirmation UX against synthetic local memory only.

No app lifespan, jobs, scheduled journal, Google writes, or personal memory reads.
Run from backend: uv run python ../scripts/smoke_device_approvals.py
"""
import asyncio
import json
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
import app
import approvals
import memory_api
from fastapi.testclient import TestClient
from memory import Memory
from websockets.asyncio.client import connect

ENDPOINT = 'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained'


class SmokeFailure(Exception):
    pass


def turn(text):
    return {'clientContent': {'turns': [{'role': 'user', 'parts': [{'text': text}]}], 'turnComplete': True}}


async def check(client, mode, method):
    memory_api.memory.remember('The synthetic marker is blue.', 'synthetic marker')
    response = client.post('/token', json={'mode': mode})
    if response.status_code != 200:
        raise SmokeFailure('Token request failed')
    token = response.json()
    async with connect(ENDPOINT, additional_headers={'Authorization': 'Token ' + token['token']}, max_size=4_000_000) as ws:
        await ws.send(json.dumps({'setup': {'model': 'models/' + token['model'], 'sessionResumption': {}}}))
        while 'setupComplete' not in json.loads(await ws.recv()):
            pass
        print(f'PASS {mode}: /token HTTP 200; constrained setupComplete; credentials hidden', flush=True)
        await ws.send(json.dumps(turn('Please forget the saved fact about the synthetic marker. Request the deletion now.')))
        pending = None
        spoken = ''
        while True:
            event = json.loads(await ws.recv())
            for call in event.get('toolCall', {}).get('functionCalls', []):
                if call['name'] not in {'recall', 'forget'}:
                    raise SmokeFailure('Unexpected tool; no action executed')
                result = client.post('/tools/' + call['name'], json=call.get('args', {}))
                if result.status_code != 200:
                    raise SmokeFailure('Synthetic tool failed')
                body = result.json()
                if body.get('approval_id'):
                    if pending is not None:
                        raise SmokeFailure('Model repeated approval request')
                    pending = body
                    spoken = ''
                await ws.send(json.dumps({'toolResponse': {'functionResponses': [
                    {'id': call['id'], 'name': call['name'], 'response': body}]} }))
            content = event.get('serverContent', {})
            spoken += content.get('outputTranscription', {}).get('text', '')
            if pending and spoken.strip() and content.get('turnComplete'):
                break
        if not any(word in spoken.lower() for word in ('nod', 'capture', 'button', 'tap')):
            raise SmokeFailure('Hardware confirmation was not explained')
        print(f'PASS {mode}: Gemini requested human hardware/screen confirmation', flush=True)
        approval_id = pending['approval_id']
        if method:
            result = client.post(f'/approvals/{approval_id}/device', json={'method': method})
            expected = '[approval granted]'
            assert result.json().get('deleted') == 1
        else:
            result = client.post(f'/approvals/{approval_id}/deny')
            expected = '[approval denied]'
            assert memory_api.memory.recall('synthetic marker')['matches']
        assert result.status_code == 200
        await ws.send(json.dumps(turn(expected + ' Already handled by the app. Do not repeat any tool. Result data: ' + json.dumps(result.json()))))
        while True:
            event = json.loads(await ws.recv())
            if event.get('toolCall'):
                raise SmokeFailure('Model repeated a tool after the device result')
            if event.get('serverContent', {}).get('turnComplete'):
                break
        print(f'PASS {mode}: device {method or "denial"} result acknowledged without tool replay', flush=True)


async def main():
    if not os.getenv('GEMINI_API_KEY') or not os.getenv('APP_SHARED_SECRET'):
        raise SmokeFailure('Missing credentials')
    original = memory_api.memory
    with tempfile.TemporaryDirectory(prefix='device-approval-smoke-') as directory:
        memory_api.memory = Memory(Path(directory) / 'memory.db')
        # No context manager: deliberately do not start the personal app lifespan.
        client = TestClient(app.app, base_url='http://localhost', headers={'X-App-Secret': os.environ['APP_SHARED_SECRET']})
        try:
            for mode, method in [('auto', 'nod'), ('ptt', None), ('over', 'button')]:
                await asyncio.wait_for(check(client, mode, method), timeout=75)
        finally:
            client.close()
            approvals.pending.clear()
            approvals.completed.clear()
            memory_api.memory = original


if __name__ == '__main__':
    try:
        asyncio.run(main())
    except Exception as exc:
        print(f'FAILED: {str(exc) if isinstance(exc, SmokeFailure) else type(exc).__name__}; private diagnostics suppressed', flush=True)
        sys.exit(1)
