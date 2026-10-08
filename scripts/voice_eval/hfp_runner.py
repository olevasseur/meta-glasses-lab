"""Paired HFP eval: one Live connection at a time, <=50 attempts, no automatic retries."""
import argparse
import asyncio
import hashlib
import json
import os
import sys
from importlib.metadata import version
import time
from dataclasses import asdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend'))
from dotenv import load_dotenv
from .audio import assemble, cached_path, insert_pause, read
from .hfp import CONDITIONS, simulate
from .hfp_keywords import replay, transcribe
from .metrics import aggregate, score

SELECTED = ('note-inbox', 'memory-save', 'small-joke', 'small-thanks', 'over-pause-1', 'auto-pause-1')


def cases_and_audio():
    all_cases = json.loads((Path(__file__).with_name('cases.json')).read_text())
    by_id = {c['id']: c for c in all_cases}
    rows = []
    for id in SELECTED:
        case = by_id[id]
        pcm = assemble([read(cached_path(s, case['voice'])) for s in case['segments']], case['pauses'])
        if case.get('insert_pause'):
            pcm = insert_pause(pcm, case['insert_pause'])
        rows.append((case, bytes(9600) + pcm))
    chatter = by_id['timer-set']
    bystander = read(cached_path(chatter['segments'][0], chatter['voice']))
    return rows, bystander


def save(path, report):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(report, indent=2) + '\n')
    temp.replace(path)


def evaluate_one(case, pcm):
    from .client import evaluate
    from .simulator import backend
    try:
        with backend() as http:
            return asyncio.run(evaluate(case, pcm, http, os.environ['APP_SHARED_SECRET'], timeout=30))
    except Exception as exc:
        return {'id': case['id'], 'mode': case['mode'], 'feature': case['feature'],
                'calls': [], 'complete': False, 'error': type(exc).__name__,
                'close_code': getattr(getattr(exc, 'rcvd', None), 'code', None)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vosk-model', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--live', action='store_true', help='Spend at most 42 sequential Live sessions')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output exists: refusing accidental repeat/budget reset')
    from vosk import Model, SetLogLevel
    SetLogLevel(-1)
    # This wideband model needs explicit internal upsampling to accept actual 8 kHz PCM.
    if '--allow-upsample=true' not in (args.vosk_model / 'conf/mfcc.conf').read_text():
        parser.error('Use a disposable model copy with --allow-upsample=true in conf/mfcc.conf')
    model = Model(str(args.vosk_model))
    rows, chatter = cases_and_audio()  # Fail missing fixtures before spending any quota.
    load_dotenv(ROOT / '.env')
    report = {'date': '2026-10-08', 'conditions': [asdict(c) for c in CONDITIONS],
              'cases': [c for c, _ in rows], 'live_attempts': 0, 'max_attempts': 42,
              'vosk_model': args.vosk_model.name, 'vosk_input_rate': 8000,
              'vosk_internal_rate': 16000, 'results': [],
              'versions': {name: version(name) for name in ('numpy', 'scipy', 'vosk')}}
    report['prompt_sha256'] = hashlib.sha256((ROOT/'backend/prompts/system.md').read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    # Local work first: API failure never prevents the full acoustic/keyword comparison.
    prepared = []
    for condition in CONDITIONS:
        for case, source in rows:
            pcm, pcm8, channel = simulate(source, condition, seed=42, bystander=chatter)
            local = transcribe(model, pcm8)
            row = {'id': case['id'], 'condition': condition.name, 'channel': channel,
                   'audio_sha256': hashlib.sha256(pcm).hexdigest(), 'speech_end_s': len(pcm)/32000,
                   'keyword_expected': case['id'] in ('over-pause-1', 'auto-pause-1'),
                   'vosk': local}
            report['results'].append(row)
            prepared.append((row, case, pcm))
            save(args.output, report)
            print('local', condition.name, case['id'], local['exact'], flush=True)
    if not args.live:
        return
    consecutive_errors = 0
    for row, case, pcm in prepared:
        if report['live_attempts'] >= min(50, report['max_attempts']):
            break
        report['live_attempts'] += 1
        row['live_started'] = True
        save(args.output, report)  # Count attempts BEFORE token/socket, including failed attempts.
        result = evaluate_one(case, pcm)
        result.update(score(case, result))
        events = result.get('input_transcription_events', [])
        row['gemini_keyword'] = replay(events, max([e['time_s'] for e in events] + [0]) + 1.3)
        row['gemini_keyword_before_end'] = replay(
            [e for e in events if e['time_s'] < (result.get('activity_end_s') or result.get('speech_end_s', 0))],
            result.get('activity_end_s') or result.get('speech_end_s', 0))
        row['gemini_aliases'] = replay(events, max([e['time_s'] for e in events] + [0]) + 1.3, ('ova', 'older'))
        row['live'] = result
        save(args.output, report)
        print('live', report['live_attempts'], row['condition'], case['id'],
              'tool_ok=', result['arguments_ok'], 'error=', result.get('error'), flush=True)
        if result.get('error'):
            consecutive_errors += 1
            # Conservatively back off ANY transport/provider error, including 429/1011/503.
            delay = min(60, 15 * 2 ** (consecutive_errors - 1))
            print('backoff', delay, 'seconds; no retry of this case', flush=True)
            time.sleep(delay)
            if consecutive_errors >= 3:
                report['stopped'] = 'Three consecutive provider/transport errors; preserve shared quota'
                save(args.output, report)
                break
        else:
            consecutive_errors = 0
            time.sleep(2)
    report['metrics'] = {c.name: aggregate([r['live'] for r in report['results']
                         if r['condition'] == c.name and 'live' in r]) for c in CONDITIONS}
    save(args.output, report)


if __name__ == '__main__':
    main()
