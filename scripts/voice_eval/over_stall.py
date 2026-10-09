"""Two bounded Live sessions: silence-only activity, then same-socket speech recovery.

PYTHONPATH=scripts uv run --project backend python -m voice_eval.over_stall \
  --env /path/to/.env --output /tmp/over-stall.json
Uses cached synthetic speech, production constrained /token, no tool execution.
"""
import argparse
import asyncio
import base64
import json
import os
import time
from pathlib import Path

from dotenv import load_dotenv
from websockets.asyncio.client import connect
from .audio import cached_path, read
from .client import ENDPOINT, activity, setup, stream_pcm
from .quota import metered_connection, reserve_live
from .simulator import backend


async def turn(ws, pcm, wait_seconds, row, usage):
    row.update(input_bytes=len(pcm), events=[], audio_bytes=0, complete=False)
    started = time.monotonic()
    async def receive():
        while True:
            event = json.loads(await ws.recv())
            content = event.get('serverContent', {})
            if 'error' in event or 'goAway' in event:
                raise RuntimeError('Provider error')
            if 'usageMetadata' in event:
                usage.append(event['usageMetadata'])
            kinds = [name for name in ('inputTranscription', 'modelTurn', 'outputTranscription', 'turnComplete')
                     if content.get(name)]
            if event.get('toolCall'):
                kinds.append('toolCall')
                raise RuntimeError('Unexpected tool; not executed')
            if kinds:
                row['events'].append({'seconds': round(time.monotonic()-started, 3), 'kinds': kinds})
            for part in content.get('modelTurn', {}).get('parts', []):
                blob = part.get('inlineData', {})
                if blob.get('mimeType', '').startswith('audio/pcm'):
                    row['audio_bytes'] += len(base64.b64decode(blob['data']))
            if content.get('turnComplete'):
                row['complete'] = True
                return
    receiver = asyncio.create_task(receive())
    try:
        await ws.send(json.dumps(activity(True)))
        await stream_pcm(ws, pcm)
        row['activity_end_s'] = round(time.monotonic()-started, 3)
        await ws.send(json.dumps(activity(False)))
        try:
            await asyncio.wait_for(receiver, wait_seconds)
        except TimeoutError:
            row['observation_timeout_s'] = wait_seconds
    finally:
        receiver.cancel()
        await asyncio.gather(receiver, return_exceptions=True)
    print(json.dumps({k: v for k, v in row.items() if k != 'events'}), flush=True)


async def session(http, row, speech):
    response = await asyncio.to_thread(http.post, '/token', json={'mode': 'over'},
                                      headers={'X-App-Secret': os.environ['APP_SHARED_SECRET']})
    if response.status_code != 200:
        raise RuntimeError('Token rejected')
    token = response.json()
    row.update(model=token['model'], usage=[], turns=[])
    async with metered_connection(connect, token, row, ENDPOINT,
            additional_headers={'Authorization': 'Token ' + token['token']},
            open_timeout=20, max_size=8*1024*1024) as ws:
        await ws.send(json.dumps(setup(token['model'])))
        async with asyncio.timeout(20):
            while 'setupComplete' not in json.loads(await ws.recv()):
                pass
        row['_usage_started'] = time.monotonic()
        if row['warm']:
            row['turns'].append({'scenario': 'healthy_control'})
            await turn(ws, speech, 30, row['turns'][-1], row['usage'])
        row['turns'].append({'scenario': 'ten_seconds_digital_silence'})
        await turn(ws, bytes(320_000), row['wait_seconds'], row['turns'][-1], row['usage'])
        row['turns'].append({'scenario': 'reopened_speech_same_socket'})
        await turn(ws, speech, 30, row['turns'][-1], row['usage'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output exists; refusing repeat attempts')
    load_dotenv(args.env)
    cases = json.loads(Path(__file__).with_name('cases.json').read_text())
    case = next(c for c in cases if c['id'] == 'small-joke')
    speech = read(cached_path(case['segments'][0], case['voice'])) + bytes(38400)
    reserve_live([{}, {}], 120)
    report = {'date': '2026-10-09', 'live_sessions': 0, 'max_sessions': 2, 'results': []}
    def save():
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    with backend() as http:
        for warm, wait_seconds in ((True, 20), (False, 8)):
            row = {'warm': warm, 'wait_seconds': wait_seconds}
            report['results'].append(row)
            report['live_sessions'] += 1
            save()
            try:
                asyncio.run(session(http, row, speech))
            except Exception as exc:
                row['error'] = type(exc).__name__
                save()
                raise SystemExit('Stopped on provider/transport failure; details withheld.') from None
            save()


if __name__ == '__main__':
    main()
