"""Credential-safe diagnostics + optional real Gemini setup, with disposable storage."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import re
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
import app
import diagnostic_logs
import memory_api
from fastapi.testclient import TestClient
from memory import Memory


async def setup(token):
    from websockets.asyncio.client import connect
    endpoint = 'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained'
    async with connect(endpoint, additional_headers={'Authorization': 'Token ' + token['token']}, open_timeout=20) as socket:
        await socket.send(json.dumps({'setup': {'model': 'models/' + token['model'], 'sessionResumption': {}}}))
        async with asyncio.timeout(30):
            while True:
                message = json.loads(await socket.recv())
                if 'setupComplete' in message or 'setup_complete' in message:
                    print('PASS: real constrained Gemini setupComplete; token and handle not printed.')
                    return
                if 'error' in message:
                    raise RuntimeError('Gemini setup failed')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--live-setup', action='store_true')
    args = parser.parse_args()
    secret = os.getenv('APP_SHARED_SECRET')
    if not secret:
        raise SystemExit('APP_SHARED_SECRET required in root .env')
    with tempfile.TemporaryDirectory() as directory:
        diagnostic_logs.store = diagnostic_logs.LogStore(Path(directory) / 'logs')
        memory_api.memory = Memory(Path(directory) / 'memory.db')
        # No lifespan: never start personal queued jobs, retention or journal scheduling.
        client = TestClient(app.app, follow_redirects=False)
        headers = {'X-App-Secret': secret}
        assert client.get('/logs').status_code == 401
        assert client.get('/diagnostics/health', headers=headers).status_code == 200
        row = {'id': 'diagnostic-smoke', 'timestamp': '2026-10-08T12:00:00Z',
               'feature': 'diagnostics', 'level': 'INFO', 'event': 'smoke_passed'}
        assert client.post('/logs', json={'entries': [row]}, headers=headers).status_code == 200
        assert client.get('/logs?feature=diagnostics', headers=headers).json()['entries'][0]['event'] == 'smoke_passed'
        login = client.get('/dashboard/login')
        csrf = re.search(r'name="csrf" value="([^"]+)"', login.text)[1]
        assert client.post('/dashboard/login', data={'csrf': csrf, 'secret': secret}).status_code == 303
        page = client.get('/dashboard/live-logs?feature=diagnostics')
        assert page.status_code == 200 and 'smoke_passed' in page.text and 'every 2s' in page.text
        assert secret not in page.text
        print('PASS: authenticated health, upload/read, dashboard login/filter/poll HTML; disposable logs.')
        if args.live_setup:
            if not os.getenv('GEMINI_API_KEY'):
                raise SystemExit('GEMINI_API_KEY required for --live-setup')
            token = client.post('/token', json={'mode': 'auto'}, headers=headers)
            if token.status_code != 200:
                raise SystemExit(f'FAILED: token HTTP {token.status_code}; response redacted.')
            print('PASS: real POST /token HTTP 200.')
            try:
                asyncio.run(setup(token.json()))
            except Exception:
                raise SystemExit('FAILED: real Gemini setup; details withheld to protect credentials.') from None
        client.close()


if __name__ == '__main__':
    main()
