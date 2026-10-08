"""At most eight sequential paired FIR Live checks after a completed HFP run."""
import argparse
import hashlib
import json
import os
import time
from pathlib import Path

from dotenv import load_dotenv
from .hfp import CONDITIONS, simulate
from .hfp_ablation import fir_upsample
from .hfp_runner import ROOT, cases_and_audio, evaluate_one, save
from .metrics import score

PAIRS = (
    ('hfp-quiet', 'note-inbox'), ('hfp-quiet', 'memory-save'),
    ('hfp-quiet', 'over-pause-1'), ('street-20dB-1pct', 'over-pause-1'),
    ('cafe-10dB-3pct', 'memory-save'), ('wind-0dB-5pct', 'memory-save'),
    ('kitchen-5dB-1pct', 'memory-save'), ('kitchen-5dB-1pct', 'over-pause-1'),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    baseline = json.loads(args.baseline.read_text())
    if args.output.exists():
        parser.error('Output exists; refusing to repeat attempts')
    if 'metrics' not in baseline or baseline.get('stopped') or baseline['live_attempts'] + len(PAIRS) > 50:
        parser.error('Baseline must be finished normally and leave eight attempts within 50 total')
    rows, chatter = cases_and_audio()
    cases = {case['id']: (case, pcm) for case, pcm in rows}
    conditions = {c.name: c for c in CONDITIONS}
    load_dotenv(ROOT / '.env')
    report = {'baseline': args.baseline.name, 'baseline_attempts': baseline['live_attempts'],
              'live_attempts': 0, 'results': []}
    errors = 0
    for name, id in PAIRS:
        case, source = cases[id]
        _, pcm8, meta = simulate(source, conditions[name], bystander=chatter)
        pcm = fir_upsample(pcm8)
        row = {'condition': name, 'id': id, 'channel': meta,
               'audio_sha256': hashlib.sha256(pcm).hexdigest()}
        report['results'].append(row)
        report['live_attempts'] += 1
        save(args.output, report)
        result = evaluate_one(case, pcm)
        result.update(score(case, result))
        row['live'] = result
        save(args.output, report)
        print('candidate', report['live_attempts'], name, id, result['arguments_ok'], result.get('error'), flush=True)
        if result.get('error'):
            errors += 1
            time.sleep(min(60, 15 * 2 ** (errors - 1)))
            if errors >= 3:
                report['stopped'] = 'Three consecutive errors'
                save(args.output, report)
                break
        else:
            errors = 0
            time.sleep(2)


if __name__ == '__main__':
    main()
