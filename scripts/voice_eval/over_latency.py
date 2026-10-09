"""Repeated manual Over turns using real constrained tokens and WAV input.

PYTHONPATH=scripts uv run --project backend --with numpy --with scipy python -m \
voice_eval.over_latency --wav /tmp/over.wav --env /path/to/.env --output /tmp/over.json
At most four Live sessions; oracle sign-off timing, NOT Android ASR or hardware.
"""
import argparse
import asyncio
import hashlib
import json
import os
import time
from pathlib import Path

from dotenv import load_dotenv
from websockets.asyncio.client import connect
from .audio import read
from .client import ENDPOINT, activity, setup, stream_pcm
from .quota import metered_connection, reserve_live
from .simulator import backend


async def session(http, pcm, row):
    response = await asyncio.to_thread(http.post, '/token', json={'mode': 'over'},
                                      headers={'X-App-Secret': os.environ['APP_SHARED_SECRET']})
    if response.status_code != 200:
        raise RuntimeError('Token rejected')
    token = response.json()
    row['model'] = token['model']
    row['usage'] = []
    row['turns'] = []
    async with metered_connection(connect, token, row, ENDPOINT,
            additional_headers={'Authorization': 'Token ' + token['token']},
            open_timeout=20, max_size=8*1024*1024) as ws:
        await ws.send(json.dumps(setup(token['model'])))
        async with asyncio.timeout(20):
            while 'setupComplete' not in json.loads(await ws.recv()):
                pass
        row['_usage_started'] = time.monotonic()
        for scenario in ('single', 'immediate_next_turn', 'missed_first_over'):
            turn = {'scenario': scenario, 'audio_bytes': 0, 'tool_calls': 0,
                    'first_input_s': None, 'first_model_s': None, 'first_audio_s': None,
                    'activity_end_s': None, 'turn_complete_s': None}
            row['turns'].append(turn)
            started = time.monotonic()

            async def receive():
                while True:
                    event = json.loads(await ws.recv())
                    now = time.monotonic() - started
                    if 'error' in event or 'goAway' in event:
                        raise RuntimeError('Live error or goAway')
                    if 'usageMetadata' in event:
                        row['usage'].append(event['usageMetadata'])
                    content = event.get('serverContent', {})
                    if content.get('inputTranscription') and turn['first_input_s'] is None:
                        turn['first_input_s'] = now
                    if content.get('modelTurn') and turn['first_model_s'] is None:
                        turn['first_model_s'] = now
                    for part in content.get('modelTurn', {}).get('parts', []):
                        blob = part.get('inlineData', {})
                        if blob.get('mimeType', '').startswith('audio/pcm'):
                            import base64
                            turn['audio_bytes'] += len(base64.b64decode(blob['data']))
                            if turn['first_audio_s'] is None:
                                turn['first_audio_s'] = now
                    calls = event.get('toolCall', {}).get('functionCalls', [])
                    turn['tool_calls'] += len(calls)
                    if calls:
                        raise RuntimeError('Unexpected tool call; not executed')
                    if content.get('turnComplete'):
                        turn['turn_complete_s'] = now
                        return

            receiver = asyncio.create_task(receive())
            try:
                await ws.send(json.dumps(activity(True)))
                turn['activity_start_s'] = time.monotonic() - started
                if scenario == 'missed_first_over':
                    await stream_pcm(ws, pcm)
                    await stream_pcm(ws, bytes(8 * 32000))
                turn['max_send_lag_s'] = await stream_pcm(ws, pcm)
                turn['wav_end_s'] = time.monotonic() - started
                await stream_pcm(ws, bytes(38400)) # Same 1.2 s grace as OverTurnDetector.
                turn['activity_end_s'] = time.monotonic() - started
                await ws.send(json.dumps(activity(False)))
                # No more audio, matching GeminiSession's locked manual send gate.
                await asyncio.wait_for(receiver, 35)
                if turn['first_audio_s'] is None:
                    raise RuntimeError('No audio')
                turn['end_to_audio_ms'] = round(1000 * (turn['first_audio_s'] - turn['activity_end_s']))
                turn['wav_end_to_audio_ms'] = round(1000 * (turn['first_audio_s'] - turn['wav_end_s']))
                assert turn['first_audio_s'] >= turn['activity_end_s']
                print(json.dumps({'condition': row['condition'], **turn}), flush=True)
                # The provider's turnComplete already accounts for expected playback.
                await asyncio.sleep(.25)
            finally:
                receiver.cancel()
                await asyncio.gather(receiver, return_exceptions=True)


def main():
    from .hfp import Condition, simulate
    from .hfp_ablation import fir_upsample
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wav', type=Path, required=True)
    parser.add_argument('--env', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output exists; refusing to repeat attempts')
    load_dotenv(args.env)
    source = read(args.wav)
    _, pcm8, _ = simulate(source, Condition('hfp-quiet'))
    conditions = [('clean16k', source), ('hfp8k-fir16k', fir_upsample(pcm8))]
    try:
        reserve_live([{}] * 4, 120)
    except ValueError as exc:
        parser.error(str(exc))
    report = {'date': '2026-10-09', 'grace_ms': 1200, 'live_sessions': 0, 'results': []}
    def save():
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    with backend() as http:
        for condition, pcm in conditions:
            for _ in range(2):
                row = {'condition': condition, 'pcm_sha256': hashlib.sha256(pcm).hexdigest()}
                report['results'].append(row)
                report['live_sessions'] += 1
                save()
                try:
                    asyncio.run(session(http, pcm, row))
                except Exception as exc:
                    row['error'] = type(exc).__name__ # No credentials, transcripts or upstream messages.
                    save()
                    raise SystemExit('Stopped on provider/transport failure; details withheld.') from None
                save()


if __name__ == '__main__':
    main()
