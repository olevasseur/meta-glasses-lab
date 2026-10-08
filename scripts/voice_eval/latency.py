"""Sequential latency probe; real Live, disposable memory and simulated Docs writes.

PYTHONPATH=scripts uv run --project backend python -m voice_eval.latency --label before
One label opens exactly samples * 3 sessions (default six); stops on any error.
"""
import argparse
import asyncio
import hashlib
import json
import os
import time
from pathlib import Path
from unittest.mock import patch

from .audio import cached_path, read
from .client import evaluate
from .runner import ROOT
from .simulator import backend


def waterfall(result):
    end_wait = (result['activity_end_s'] - result['speech_end_s']
                if result['activity_end_s'] is not None else 2.0)
    tool_seconds = [c['response_sent_s'] - c['time_s'] for c in result['calls']
                    if 'response_sent_s' in c]
    first = result['first_audio_latency_s']
    return {
        'mode': result['mode'],
        'token_s': result['token_s'],
        'websocket_s': result['websocket_s'],
        'setup_s': result['setup_s'],
        'end_wait_s': end_wait,
        'end_wait_estimated': result['mode'] == 'auto',
        # Auto has no server VAD timestamp: residual includes transport and tools.
        'after_end_to_audio_s': first - end_wait if first is not None else None,
        'speech_end_to_audio_s': first,
        'tool_round_trips_s': tool_seconds,
        'tool_names': [c['name'] for c in result['calls']],
        'audio_bytes': result['audio_bytes'],
        'max_send_lag_s': result['max_send_lag_s'],
        'complete': result['complete'],
        'error': result['error'],
    }


def main():
    from dotenv import load_dotenv
    from google.genai.tokens import Tokens
    load_dotenv(ROOT / '.env')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True)
    parser.add_argument('--cold-transport', action='store_true', help='Reproduce the original per-request Google client')
    parser.add_argument('--samples', type=int, default=2, choices=range(1, 5))
    parser.add_argument('--output', default=str(ROOT / 'docs/evals/latency-2026-10-08.json'))
    args = parser.parse_args()
    path = Path(args.output)
    report = json.loads(path.read_text()) if path.exists() else {'runs': []}
    if any(r['label'] == args.label for r in report['runs']):
        parser.error('Label already exists')
    cases = json.loads(Path(__file__).with_name('cases.json').read_text())
    case = next(c for c in cases if c['id'] == 'note-inbox')
    pcm = bytes(9600) + read(cached_path(case['segments'][0], case['voice'], 'gemini-3.8-flash-tts'))
    run = {'label': args.label, 'pcm_sha256': hashlib.sha256(pcm).hexdigest(),
           'speech_seconds': len(pcm)/32000, 'cold_transport': args.cold_transport, 'results': []}
    report['runs'].append(run)
    original = Tokens.create
    auth_times = []

    def timed_create(self, *a, **kw):
        start = time.monotonic()
        try:
            return original(self, *a, **kw)
        finally:
            auth_times.append(time.monotonic() - start)

    def save():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, indent=2) + '\n')

    with backend() as http, patch.object(Tokens, 'create', timed_create):
        for _ in range(args.samples):
            for mode in ('auto', 'ptt', 'over'):
                auth_times.clear()
                if args.cold_transport:
                    from token_transport import token_transport
                    token_transport.close()
                try:
                    result = asyncio.run(evaluate({**case, 'mode': mode}, pcm, http,
                                                  os.environ['APP_SHARED_SECRET'], 30, settle_seconds=5))
                    row = waterfall(result)
                except Exception as exc:
                    # Never persist upstream exception messages or credentials.
                    row = {'mode': mode, 'error': type(exc).__name__}
                row['auth_tokens_create_s'] = sum(auth_times)
                run['results'].append(row)
                save()
                print(json.dumps(row), flush=True)
                if row.get('error') or not row.get('complete') or not row.get('audio_bytes'):
                    # No retry storm on shared quota; operator can resume with a new label.
                    raise SystemExit('Stopped after provider/transport failure; no automatic retry.')
                time.sleep(2)


if __name__ == '__main__':
    main()
