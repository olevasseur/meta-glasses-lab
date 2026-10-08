"""Serial, resumable synthetic meeting evaluation; no Live or Docs writes.

uv run --project backend python scripts/eval_meeting_chunked.py --live
Audio/state remain in /tmp by default. Reports contain synthetic text only.
"""
import argparse
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import wave
from contextlib import contextmanager
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend'))
from dotenv import load_dotenv
load_dotenv(ROOT / '.env')
from google.genai import models
from gemini_resilience import GeminiUnavailable
from memory import Memory
from meetings import Meetings
import meeting_chunks
from usage import meter


def words(text):
    return re.findall(r"\w+(?:['’]\w+)?", text.lower())


def word_errors(expected, actual):
    from collections import Counter
    from rapidfuzz.distance import Levenshtein
    a, b = words(expected), words(actual)
    counts = Counter(op.tag for op in Levenshtein.editops(a, b))
    return {'reference_words': len(a), 'recognized_words': len(b),
            'substitutions': counts['replace'], 'deletions': counts['delete'], 'insertions': counts['insert'],
            'wer': sum(counts.values()) / max(1, len(a))}



def make_audio(folder):
    manifest = folder / 'fixture.json'
    if manifest.exists(): return json.loads(manifest.read_text())
    voices = {'Maya': 'Samantha', 'Alex': 'Daniel', 'Priya': 'Karen', 'Jordan': 'Moira'}
    source = (ROOT / 'scripts/fixtures/meeting-20min.txt').read_text()
    passages = re.findall(r'^\[\d+:\d+\] (\w+)(?: \([^)]*\))?: (.+)$', source, re.M)
    pcm = bytearray(); script = []
    for i, (speaker, text) in enumerate(passages):
        path = folder / f'voice-{i}.wav'
        if not path.exists():
            subprocess.run(['say', '-v', voices[speaker], '-r', '140', '-o', str(path),
                            '--file-format=WAVE', '--data-format=LEI16@16000', text], check=True)
        with wave.open(str(path)) as w:
            assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 16000)
            pcm.extend(w.readframes(w.getnframes()))
        pcm.extend(b'\0' * (5 * 32000)); script.append(text)
    if len(pcm) < 1200 * 32000: pcm.extend(b'\0' * (1200 * 32000 - len(pcm)))
    (folder / 'meeting.pcm').write_bytes(pcm)
    result = {'voices': voices, 'seconds': len(pcm)/32000, 'reference': '\n'.join(script)}
    manifest.write_text(json.dumps(result, indent=2))
    return result


def chunks(folder):
    # Same 60 s target / 62 s cap / 2 s overlap as the phone, hard 60 s cuts here
    # intentionally exercise speech crossing chunk boundaries.
    with (folder / 'meeting.pcm').open('rb') as f:
        index = 0; start = 0
        while True:
            f.seek(start * 32); pcm = f.read(60 * 32000)
            if not pcm or (index > 0 and len(pcm) <= 64000): break
            buf = io.BytesIO()
            with wave.open(buf, 'wb') as w:
                w.setparams((1, 2, 16000, 0, 'NONE', 'not compressed')); w.writeframes(pcm)
            yield index, start, buf.getvalue()
            index += 1; start += 58000


@contextmanager
def budget(folder):
    path = folder / 'calls.json'
    counts = json.loads(path.read_text()) if path.exists() else {'attempts': 0, 'audio_attempts': 0, 'failures': 0}
    original = models.Models.generate_content
    last = 0
    def counted(self, *args, **kwargs):
        nonlocal last
        audio = isinstance(kwargs.get('contents'), list)
        if counts['audio_attempts'] >= 150 or counts['attempts'] >= 160:
            raise RuntimeError('Explicit evaluation budget exhausted')
        # Approximate reservation, actual provider tokens are recorded by shared helper.
        meter.reserve_eval({'text_calls': 1, 'total_tokens': 2500 if audio else 9000}, model=kwargs['model'])
        time.sleep(max(0, last + 8 - time.monotonic()))
        counts['attempts'] += 1; counts['audio_attempts'] += int(audio)
        path.write_text(json.dumps(counts))
        try:
            response = original(self, *args, **kwargs)
            (folder / f"response-{counts['attempts']}.json").write_text(json.dumps({
                'text': response.text, 'finish': [str(c.finish_reason) for c in response.candidates or []]}))
            return response
        except Exception as exc:
            counts['failures'] += 1
            counts['last_error'] = {'type': type(exc).__name__, 'code': getattr(exc, 'code', None)}
            path.write_text(json.dumps(counts))
            if getattr(exc, 'code', None) in (429, 503): time.sleep(30)
            raise
        finally: last = time.monotonic()
    with patch.object(models.Models, 'generate_content', counted): yield counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--live', action='store_true')
    parser.add_argument('--state', type=Path, default=Path('/tmp/meeting2-eval'))
    args = parser.parse_args(); args.state.mkdir(parents=True, exist_ok=True)
    fixture = make_audio(args.state)
    service = Meetings(Memory(args.state / 'meeting.sqlite3'), None)
    service.start('synthetic', 'Harbour pilot synthetic meeting', chunked=True)
    n = 0; total_bytes = 0
    for index, start, data in chunks(args.state):
        service.chunks.upload('synthetic', index, start, data); n += 1; total_bytes += len(data)
    service.chunks.seal('synthetic', n)
    report = {'audio_seconds': fixture['seconds'], 'chunks': n, 'wav_bytes': total_bytes, 'live_sessions': 0,
              'models': json.loads((args.state/'models.json').read_text()) if (args.state/'models.json').exists() else [],
              'latencies': [], 'status': 'fixture ready'}
    previous_report = json.loads((args.state / 'report.json').read_text()) if (args.state / 'report.json').exists() else {}
    report['latencies'] = previous_report.get('latencies', [])
    if args.live:
        started = time.monotonic()
        try:
            with budget(args.state) as calls:
                failures = 0
                while True:
                    with service.memory.db() as db:
                        pending = db.execute('SELECT count(*) FROM meeting_chunks WHERE audio IS NOT NULL').fetchone()[0]
                    if not pending: break
                    before = time.monotonic()
                    try:
                        result = service.chunks.process('synthetic')
                    except GeminiUnavailable:
                        failures += 1
                        if failures >= 3: raise
                        print('Provider unavailable; backing off 60 seconds', flush=True)
                        time.sleep(60)
                        continue
                    if 'transcribed_index' not in result:
                        print('Waiting for durable cooldown', flush=True)
                        time.sleep(60)
                        continue
                    failures = 0
                    report['latencies'].append(time.monotonic()-before)
                    print('transcribed', result['transcribed_index'], flush=True)
                if not pending:
                    for _ in range(100):
                        result = service.step('synthetic', allow_export=False)
                        if result.get('minutes'):
                            report['minutes'] = result['minutes']; report['status'] = 'minutes ready; no Docs export'; break
                report['calls'] = calls
        except Exception as exc:
            report['status'] = 'deferred'; report['error'] = {'type': type(exc).__name__, 'code': getattr(exc, 'code', None), 'local_validation': str(exc) if type(exc) is ValueError else None}
        report['wall_seconds'] = previous_report.get('wall_seconds', 0) + time.monotonic() - started
        report['calls'] = json.loads((args.state / 'calls.json').read_text())
    with service.memory.db() as db:
        rows = db.execute('SELECT segments FROM meeting_chunks WHERE segments IS NOT NULL ORDER BY idx').fetchall()
        texts = [s['text'] for row in rows for s in json.loads(row[0])]
    report['transcribed_chunks'] = len(rows)
    report['lexical'] = word_errors(fixture['reference'], ' '.join(texts))
    report['transcript'] = texts
    boundaries = [' '.join(s['text'] for s in json.loads(row[0])) for row in rows]
    report['repeated_boundary_words'] = sum(next((n for n in range(min(len(words(a)), len(words(b)), 40), 2, -1) if words(a)[-n:] == words(b)[:n]), 0) for a,b in zip(boundaries, boundaries[1:]))
    with meter.database() as db:
        report['usage'] = [dict(r) for r in db.execute('SELECT model,kind,sum(calls) calls,sum(failed) failed,sum(input_tokens) input_tokens,sum(output_tokens) output_tokens,sum(total_tokens) total_tokens FROM usage GROUP BY model,kind')]
    (args.state / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps({k:v for k,v in report.items() if k not in ('transcript','minutes','models')}), flush=True)


if __name__ == '__main__': main()
