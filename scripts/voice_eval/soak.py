"""Opt-in wall-clock Live/backend soak. Run from backend with PYTHONPATH=../scripts:.

Artifacts contain fictional transcripts, never credentials or resumption handles.
No production lifespan, personal storage, Google Docs, or model-selected writes.
"""
import argparse
import asyncio
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import random
import re
from collections import Counter
import resource
import sys
import math
import subprocess
import time
from datetime import UTC, datetime
from unittest.mock import patch

from websockets.asyncio.client import connect

from .audio import read
from .client import ENDPOINT, media, setup, tool_response
from .quota import metered_connection, reserve_live


def connection_limit(seconds):
    # Reserve normal ten-minute rollovers plus five failed/short connections.
    return math.ceil(seconds / 600) + 5 if seconds else 0

MEETING = [
    'Maya here. The harbour pilot will launch on Friday, provided the accessibility review passes. Alex will send the revised budget tomorrow. The spending cap is two hundred dollars.',
    'Alex speaking. I can finish the invitation draft on Wednesday. We should invite twenty people first, then wait for their feedback before opening the second group.',
    'Sam here. The mobile dashboard needs larger buttons. I will check contrast and keyboard navigation on Thursday. We should keep the old dashboard available during the pilot.',
    'Maya again. We have agreed to keep the pilot local. No customer data goes into the demo. The remaining question is who will cover support over the weekend.',
    'Alex speaking. The deployment checklist needs a rollback step and a contact person. I will update the checklist before lunch tomorrow, and Sam will review it.',
    'Sam here. Friday is still conditional on the review. If the review fails, we will postpone the launch. We have not agreed a replacement date. Let us take a short pause.',
]
AUTO = {
    'opening': 'Hello. We are testing a long conversation. Please keep every answer to one short sentence.',
    'context': 'For this conversation only, the secret picnic phrase is copper otter, and the picnic is at Cedar Pier. Do not save this to memory. Just acknowledge it.',
    'remember': 'Remember that my fictional test bicycle is named Silver Comet.',
    'recall': 'What is the name of my test bicycle? Please check your memory.',
    'question': 'What is one practical thing to pack for a picnic if it might rain?',
    'retention': 'What picnic phrase and location did I tell you near the beginning of this conversation? Answer from this conversation, without searching memory.',
}


def digest(rows):
    h = hashlib.sha256()
    for row in rows:
        h.update(json.dumps([row['seq'], row['speaker'], row['text']], ensure_ascii=False).encode())
        h.update(b'\n')
    return h.hexdigest()


def transcription_metrics(expected, actual):
    """Lexical error rate, not semantic accuracy; numeral/spelling variants count."""
    from rapidfuzz.distance import Levenshtein
    source, target = [re.findall(r"\w+", value.casefold()) for value in (expected, actual)]
    counts = Counter(op.tag for op in Levenshtein.editops(source, target))
    errors = sum(counts.values())
    return {'expected_words': len(source), 'transcribed_words': len(target),
            'substitutions': counts['replace'], 'deletions': counts['delete'],
            'insertions': counts['insert'], 'word_error_rate': errors / len(source) if source else None}


def fresh_token(token, now=None):
    if not token:
        return True
    expiry = datetime.fromisoformat(token['expires_at'].replace('Z', '+00:00'))
    return expiry.timestamp() - (time.time() if now is None else now) < 60


class BackendError(RuntimeError):
    def __init__(self, status):
        self.status = status
        super().__init__(f'BackendHTTP{status}')


def retry_delay(attempt):
    ceiling = min(60, 2 ** min(attempt, 6))
    return random.uniform(ceiling / 2, ceiling)


def fixtures(directory):
    """Offline macOS TTS: no shared Gemini TTS quota used."""
    directory.mkdir(parents=True, exist_ok=True)
    texts = {f'meeting-{i}': text for i, text in enumerate(MEETING)} | AUTO
    clips = {}
    for name, text in texts.items():
        wav = directory / f'{name}.wav'
        if not wav.exists():
            aiff = wav.with_suffix('.aiff')
            subprocess.run(['say', '-v', 'Samantha', '-r', '165', '-o', str(aiff), text], check=True, capture_output=True)
            subprocess.run(['afconvert', '-f', 'WAVE', '-d', 'LEI16@16000', '-c', '1', str(aiff), str(wav)], check=True, capture_output=True)
            aiff.unlink()
        clips[name] = read(wav)
    (directory / 'manifest.json').write_text(json.dumps({name: {
        'text': text, 'pcm_sha256': hashlib.sha256(clips[name]).hexdigest(),
        'seconds': len(clips[name]) / 32000} for name, text in texts.items()}, indent=2) + '\n')
    return clips, texts


def schedule(mode, seconds, clips):
    if mode == 'meeting':
        result, at, i = [], 1.0, 0
        while at < seconds - 15:
            name = f'meeting-{i % len(MEETING)}'
            if at + len(clips[name]) / 32000 > seconds - 5:
                break
            result.append((at, name))
            at = round(at + math.ceil(len(clips[name]) / 3200) / 10 + (8 if i % 6 != 5 else 25), 1)
            i += 1
        return result
    return [(at, name) for at, name in [(1, 'opening'), (120, 'context'), (240, 'remember'),
        (420, 'question'), (600, 'recall'), (840, 'question'), (1080, 'recall'),
        (1320, 'question'), (1500, 'retention'), (1680, 'recall')] if at < seconds - 15]


@contextlib.contextmanager
def isolated_backend(root):
    import app
    import approvals
    import diagnostic_logs
    import meeting_api
    import memory_api
    import visual_api
    from fastapi.testclient import TestClient
    from memory import Memory
    from meetings import Meetings
    from visual_memory import VisualMemory
    memory = Memory(root / 'memory.db')
    visual = VisualMemory(root / 'visual')
    with contextlib.ExitStack() as stack:
        stack.enter_context(patch.object(memory_api, 'memory', memory))
        stack.enter_context(patch.object(meeting_api, 'service', Meetings(memory, None)))
        stack.enter_context(patch.object(visual_api, 'memory', visual))
        stack.enter_context(patch.object(diagnostic_logs, 'store', diagnostic_logs.LogStore(root / 'logs')))
        stack.enter_context(patch.object(approvals, 'pending', {}))
        stack.enter_context(patch.object(approvals, 'completed', {}))
        # No app lifespan: jobs/journal/AI replay must never run during this test.
        client = TestClient(app.app, base_url='http://localhost')
        try:
            yield client, memory, visual
        finally:
            client.close()


class Recorder:
    def __init__(self, path):
        self.file = path.open('a')
        self.started = time.monotonic()

    def __call__(self, event, **fields):
        self.file.write(json.dumps({'t': round(time.monotonic() - self.started, 3),
                                    'event': event, **fields}) + '\n')
        self.file.flush()


class LiveSoak:
    def __init__(self, http, memory, root, mode, seconds, clips, texts):
        self.http, self.memory, self.mode, self.seconds = http, memory, mode, seconds
        self.clips, self.texts = clips, texts
        self.session_id = 'soak-' + mode
        self.record = Recorder(root / f'{mode}.jsonl')
        self.token = None
        self.handle = None
        self.seq = 0
        self.received = []
        self.outbox = []
        self.tool_cache = {}
        self.timeline = schedule(mode, seconds, clips)
        self.cursor = 0  # PCM frame count across reconnects; never replay acknowledged sends.
        self.connections = 0
        self.setup_count = 0
        self.failure_count = 0
        self.goaways = 0
        self.audio_bytes = 0
        self.usage = []
        self.connection_usage = {'usage': []}
        self.gaps = []
        self.pending_gap = None
        self.sent_clips = set()
        self.completed_clips = set()

    async def post(self, path, body):
        response = await asyncio.to_thread(self.http.post, path, json=body,
                                          headers={'X-App-Secret': os.environ['APP_SHARED_SECRET']})
        if response.status_code != 200:
            raise BackendError(response.status_code)
        return response.json()

    def persist(self, speaker, text):
        row = {'seq': self.seq, 'speaker': speaker, 'text': text,
               'timestamp': datetime.now(UTC).isoformat()}
        self.received.append(row)
        self.outbox.append(row)
        self.seq += 1
        self.record('transcript', **row)

    async def flush(self):
        while self.outbox:
            batch = self.outbox[:50]
            # Never dequeue until acknowledged; retries keep the original IDs.
            await self.post('/transcripts', {'session_id': self.session_id, 'turns': batch})
            del self.outbox[:len(batch)]

    async def upload(self):
        while True:
            await asyncio.sleep(2)
            try:
                await self.flush()
            except RuntimeError:
                self.record('upload_retry', pending=len(self.outbox))
                await asyncio.sleep(30)

    async def receive(self, ws):
        while True:
            event = json.loads(await ws.recv())
            update = event.get('sessionResumptionUpdate', {})
            if update.get('resumable') and update.get('newHandle'):
                self.handle = update['newHandle']
                self.record('handle_update')
            if 'usageMetadata' in event:
                self.usage.append(event['usageMetadata'])
                self.connection_usage['usage'].append(event['usageMetadata'])
                self.record('usage', usage=event['usageMetadata'])
            content = event.get('serverContent', {})
            for key, speaker in [('inputTranscription', 'user'), ('outputTranscription', 'model')]:
                text = content.get(key, {}).get('text', '')
                if text:
                    self.persist(speaker, text)
            self.audio_bytes += sum(len(base64.b64decode(p['inlineData']['data']))
                for p in content.get('modelTurn', {}).get('parts', [])
                if p.get('inlineData', {}).get('mimeType', '').startswith('audio/pcm'))
            for call in event.get('toolCall', {}).get('functionCalls', []):
                if call['id'] not in self.tool_cache:
                    if self.mode == 'auto' and call['name'] in ('remember', 'recall'):
                        reply = await self.post('/tools/' + call['name'], call.get('args', {}))
                    else:
                        reply = {'status': 'ignored', 'message': 'Synthetic soak permits only Auto memory tools.'}
                    self.tool_cache[call['id']] = reply
                    self.record('tool', name=call['name'], args=call.get('args', {}), response=reply)
                await ws.send(json.dumps(tool_response(call, self.tool_cache[call['id']])))
            if 'error' in event:
                raise RuntimeError('LiveError')
            if 'goAway' in event:
                self.goaways += 1
                self.record('goaway', time_left=event['goAway'].get('timeLeft'))
                return

    async def send(self, ws):
        start, initial = time.monotonic(), self.cursor
        total = int(self.seconds * 10)
        while self.cursor < total:
            at = self.cursor / 10
            frame = bytes(3200)
            for i, (begin, name) in enumerate(self.timeline):
                offset = round((at - begin) * 32000)
                pcm = self.clips[name]
                if 0 <= offset < len(pcm):
                    frame = pcm[offset:offset + 3200].ljust(3200, b'\0')
                    if i not in self.sent_clips:
                        self.sent_clips.add(i)
                        self.record('clip_start', index=i, name=name, text=self.texts[name], audio_s=at)
                    if offset + 3200 >= len(pcm):
                        self.completed_clips.add(i)
                    break
            await asyncio.sleep(max(0, start + (self.cursor - initial) / 10 - time.monotonic()))
            await ws.send(json.dumps(media('audio', frame)))
            self.cursor += 1
        await asyncio.sleep(5)  # Drain final transcripts before closing.

    async def run(self):
        started = time.monotonic()
        if self.mode == 'meeting':
            await self.post('/meetings/start', {'meeting_id': self.session_id, 'title': 'Fictional harbour pilot soak'})
        uploader = asyncio.create_task(self.upload())
        failures = 0
        error = None
        while self.cursor < int(self.seconds * 10):
            if self.connections >= connection_limit(self.seconds):
                error = 'ConnectionBudgetExhausted'
                self.record('budget_exhausted')
                break
            connected_at = None
            try:
                if fresh_token(self.token):
                    previous_model = self.token['model'] if self.token else None
                    self.token = await self.post('/token', {'mode': self.mode})
                    if previous_model and previous_model != self.token['model']:
                        raise RuntimeError('ModelChangedDuringSoak')
                    self.record('token_created', model=self.token['model'])
                self.connections += 1
                self.connection_usage = {'usage': []}
                # Bound each attempt to its reserved setup/stream/drain allowance.
                async with asyncio.timeout(630), metered_connection(connect, self.token, self.connection_usage,
                                   ENDPOINT, additional_headers={'Authorization': 'Token ' + self.token['token']},
                                   open_timeout=20, close_timeout=5, max_size=8 * 1024 * 1024) as ws:
                    await ws.send(json.dumps(setup(self.token['model'], self.handle)))
                    async with asyncio.timeout(20):
                        while 'setupComplete' not in json.loads(await ws.recv()):
                            pass
                    connected_at = time.monotonic()
                    self.connection_usage['_usage_started'] = connected_at
                    self.setup_count += 1
                    gap = connected_at - self.pending_gap if self.pending_gap else None
                    if gap is not None:
                        self.gaps.append(gap)
                    self.pending_gap = None
                    self.record('connected', attempt=self.connections, resumed=bool(self.handle), gap_s=gap)
                    tasks = [asyncio.create_task(self.receive(ws)), asyncio.create_task(self.send(ws))]
                    try:
                        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                        for task in done:
                            await task
                    finally:
                        for task in tasks:
                            task.cancel()
                        await asyncio.gather(*tasks, return_exceptions=True)
                if self.cursor >= int(self.seconds * 10):
                    break
                self.pending_gap = time.monotonic()
                if not self.handle:
                    raise RuntimeError('MissingResumptionHandle')
                if connected_at and time.monotonic() - connected_at > 60:
                    failures = 0
            except Exception as exc:
                self.failure_count += 1
                code = getattr(getattr(exc, 'rcvd', None), 'code', None)
                error = type(exc).__name__
                # Never serialize upstream exception text (may contain credentials).
                reason = getattr(getattr(exc, 'rcvd', None), 'reason', '').lower()
                categories = [word for word in ('quota', 'exhausted', 'expired', 'token', 'resum', 'invalid', 'internal', 'permission', 'not found', 'used') if word in reason]
                self.record('failure', kind=error, close_code=code, categories=categories)
                if code == 1008:
                    self.token = None  # Fresh access, same logical session/handle.
                self.pending_gap = self.pending_gap or time.monotonic()
                if connected_at and time.monotonic() - connected_at > 60:
                    failures = 0
                failures += 1
                if failures >= 5 or (self.setup_count and not self.handle) or (isinstance(exc, BackendError) and exc.status in (400, 401, 403, 422)):
                    break
                delay = max(30 if code in (1008, 1011, 1013) or getattr(exc, 'status', None) in (429, 503) else 0, retry_delay(failures))
                self.record('backoff', seconds=delay)
                await asyncio.sleep(delay)
        uploader.cancel()
        await asyncio.gather(uploader, return_exceptions=True)
        try:
            await self.flush()
        except RuntimeError:
            self.record('upload_incomplete', pending=len(self.outbox))
        with self.memory.db() as db:
            stored = [dict(row) for row in db.execute('SELECT seq,speaker,text FROM turns WHERE session_id=? ORDER BY seq', (self.session_id,))]
        result = {'mode': self.mode, 'target_seconds': self.seconds, 'elapsed_seconds': time.monotonic() - started,
                  'audio_timeline_seconds': self.cursor / 10, 'completed': self.cursor >= int(self.seconds * 10),
                  'connections': self.connections, 'setups': self.setup_count, 'goaways': self.goaways,
                  'failures': self.failure_count, 'last_error_type': error, 'reconnect_gaps_seconds': self.gaps,
                  'transcript_chunks': len(self.received), 'stored_chunks': len(stored),
                  'persistence_exact': digest(self.received) == digest(stored), 'transcript_sha256': digest(stored),
                  'planned_clips': len(self.timeline), 'started_clips': len(self.sent_clips),
                  'completed_clips': len(self.completed_clips), 'output_pcm_bytes': self.audio_bytes,
                  'usage_events': len(self.usage), 'last_usage': self.usage[-1] if self.usage else None}
        result['transcription_vs_started_clips'] = transcription_metrics(
            ' '.join(self.texts[self.timeline[i][1]] for i in sorted(self.sent_clips)),
            ''.join(row['text'] for row in stored if row['speaker'] == 'user'))
        if self.mode == 'meeting' and self.outbox:
            result['finalize_error'] = 'UnacknowledgedTranscripts'
        if self.mode == 'meeting' and self.received and not self.outbox:
            for _ in range(100):
                try:
                    finalized = await self.post(f'/meetings/{self.session_id}/end', {})
                    if finalized.get('message') == 'Transcript saved; Gemini retry pending':
                        result['finalize_error'] = 'GeminiUnavailable'
                        break
                    if finalized.get('minutes') or finalized.get('status') == 'needs_confirmation':
                        import meeting_api
                        with self.memory.db() as db:
                            meeting = meeting_api.service._load(db, self.session_id)
                        result['minutes'] = meeting.get('result')
                        result['finalize_phase'] = meeting['phase']
                        break
                except RuntimeError as exc:
                    result['finalize_error'] = type(exc).__name__
                    break
                await asyncio.sleep(1)
        self.record('result', **result)
        self.record.file.close()
        return result


def retention_probe(visual, index):
    old, pinned, recent = [hashlib.md5(f'{index}-{n}'.encode()).hexdigest() for n in range(3)]
    now = time.time()
    with visual.lock, visual.database() as db:
        for id, captured, pin in [(old, now - 400 * 86400, 0), (pinned, now - 400 * 86400, 1), (recent, now, 0)]:
            (visual.directory / f'{id}.jpg').write_bytes(b'synthetic-thumbnail')
            db.execute('INSERT INTO frames VALUES (?,?,?,?,?,?,?,?,?,?)', (id, captured, 'soak', '[]', 'test', '', None, None, pin, 0))
            db.execute('INSERT INTO pending_captions VALUES (?)', (id,))
        db.commit()
    visual.prune()
    with visual.database() as db:
        rows = {row[0] for row in db.execute('SELECT id FROM frames')}
        pending = {row[0] for row in db.execute('SELECT id FROM pending_captions')}
    passed = old not in rows and old not in pending and not (visual.directory / f'{old}.jpg').exists() and {pinned, recent} <= rows
    # Cleanup only this probe's retained fixtures.
    with visual.lock, visual.database() as db:
        visual._delete(db, 'id IN (?,?)', (pinned, recent))
        db.commit()
    return passed


async def load_backend(http, memory, visual, root, seconds):
    record = Recorder(root / 'load.jsonl')
    started = time.monotonic()
    initial_cpu = time.process_time()
    index = 0
    failures = 0
    purges = []
    async def post(path, body):
        response = await asyncio.to_thread(http.post, path, json=body,
                                          headers={'X-App-Secret': os.environ['APP_SHARED_SECRET']})
        return response
    while time.monotonic() - started < seconds:
        stamp = datetime.now(UTC).isoformat()
        turns = [{'seq': index * 10 + n, 'speaker': 'user', 'text': f'Synthetic load {index}-{n}. ' + 'Meeting evidence. ' * 20,
                  'timestamp': stamp} for n in range(10)]
        before = time.monotonic()
        response = await post('/transcripts', {'session_id': 'soak-load', 'turns': turns})
        if response.status_code != 200 or response.json().get('stored') != 10:
            failures += 1
        if index % 30 == 0:
            replay = await post('/transcripts', {'session_id': 'soak-load', 'turns': turns})
            if replay.status_code != 200 or replay.json().get('stored') != 0:
                failures += 1
        logs = [{'id': f'soak-{index}-{n}', 'timestamp': stamp, 'feature': 'soak', 'level': 'INFO',
                 'event': 'Synthetic load event. ' * 20} for n in range(10)]
        response = await post('/logs', {'entries': logs})
        if response.status_code != 200:
            failures += 1
        if index % 60 == 0:
            purges.append(await asyncio.to_thread(retention_probe, visual, index))
            rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
            rss = round(rss / 1024) if sys.platform == 'darwin' else round(rss)
            record('sample', batches=index + 1, peak_rss_kib=rss, cpu_seconds=time.process_time() - initial_cpu,
                   db_bytes=memory.path.stat().st_size, log_bytes=sum(p.stat().st_size for p in (root / 'logs').glob('*')),
                   post_seconds=time.monotonic() - before, failures=failures, purge_ok=purges[-1])
            print(json.dumps({'phase': 'load', 'elapsed_s': round(time.monotonic() - started), 'batches': index + 1,
                              'peak_rss_kib': rss, 'failures': failures}), flush=True)
        index += 1
        await asyncio.sleep(max(0, started + index - time.monotonic()))
    with memory.db() as db:
        stored = db.execute("SELECT COUNT(*) FROM turns WHERE session_id='soak-load'").fetchone()[0]
        integrity = db.execute('PRAGMA integrity_check').fetchone()[0]
    result = {'elapsed_seconds': time.monotonic() - started, 'batches': index, 'expected_turns': index * 10,
              'stored_turns': stored, 'failures': failures, 'retention_probes': len(purges),
              'retention_passed': all(purges), 'integrity_check': integrity,
              'log_files': {p.name: p.stat().st_size for p in (root / 'logs').glob('*')}}
    record('result', **result)
    record.file.close()
    return result


async def run(args):
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    (root / 'run.json').write_text(json.dumps({
        'started_at': datetime.now(UTC).isoformat(),
        'harness_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'meeting_seconds': args.meeting_seconds, 'auto_seconds': args.auto_seconds,
        'load_seconds': args.load_seconds, 'load_only': args.load_only}, indent=2) + '\n')
    if not args.load_only:
        attempts = sum(connection_limit(seconds) for seconds in (args.meeting_seconds, args.auto_seconds))
        if attempts:
            reserve_live([None] * attempts, 600)
    clips, texts = fixtures(root / 'audio') if not args.load_only else ({}, {})
    result = {}
    # Reuse the text evaluator's per-attempt gate and accounting for minutes,
    # including production fallback/retries; retain the shared USAGE_DB.
    from memory_eval.provider import budget
    text_budget = budget(root / 'text-budget.json') if not args.load_only else contextlib.nullcontext()
    with text_budget, isolated_backend(root) as (http, memory, visual):
        load = asyncio.create_task(load_backend(http, memory, visual, root, args.load_seconds))
        try:
            if not args.load_only:
                for mode, seconds in [('meeting', args.meeting_seconds), ('auto', args.auto_seconds)]:
                    if seconds:
                        result[mode] = await LiveSoak(http, memory, root, mode, seconds, clips, texts).run()
                        (root / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
                        print(json.dumps({'phase': mode, 'completed': result[mode]['completed'],
                                          'setups': result[mode]['setups']}), flush=True)
            result['load'] = await load
        finally:
            load.cancel()
            await asyncio.gather(load, return_exceptions=True)
    (root / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'phase': 'done', 'output': str(root)}), flush=True)
    return result


def successful(result):
    load = result['load']
    return (all(r['completed'] and r['persistence_exact'] for kind, r in result.items() if kind != 'load')
            and load['failures'] == 0 and load['stored_turns'] == load['expected_turns']
            and load['retention_passed'] and load['integrity_check'] == 'ok')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--meeting-seconds', type=float, default=7200)
    parser.add_argument('--auto-seconds', type=float, default=1800)
    parser.add_argument('--load-seconds', type=float, default=7200)
    parser.add_argument('--load-only', action='store_true')
    args = parser.parse_args()
    if any(not 0 <= n <= 86400 for n in (args.meeting_seconds, args.auto_seconds, args.load_seconds)):
        parser.error('Durations must be between 0 and 86400 seconds')
    result = asyncio.run(run(args))
    if not successful(result):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
