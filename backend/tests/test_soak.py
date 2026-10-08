"""Soak invariants run offline; no provider quota or long sleeps under pytest."""
import asyncio
from datetime import UTC, datetime
from types import SimpleNamespace

from voice_eval.soak import digest, fresh_token, isolated_backend, load_backend, retention_probe, retry_delay, schedule


def test_token_refresh_keeps_margin_and_backoff_is_bounded():
    assert fresh_token(None)
    token = {'expires_at': datetime.fromtimestamp(1000, UTC).isoformat()}
    assert not fresh_token(token, 900)
    assert fresh_token(token, 950)
    for attempt in range(100):
        assert 0.5 <= retry_delay(attempt) <= 60


def test_schedule_retention_question_is_at_minute_25():
    clips = {f'meeting-{i}': bytes(32000) for i in range(6)}
    meeting = schedule('meeting', 7200, clips)
    assert meeting[0] == (1, 'meeting-0')
    assert all(b[0] > a[0] + 1 for a, b in zip(meeting, meeting[1:]))
    auto = schedule('auto', 1800, clips)
    assert (120, 'context') in auto and (1500, 'retention') in auto
    assert schedule('auto', 10, clips) == []


def test_transcript_hash_detects_lost_reordered_and_duplicate_chunks():
    rows = [{'seq': i, 'speaker': 'user', 'text': 'same phrase'} for i in range(3)]
    assert digest(rows) == digest([dict(r) for r in rows])
    assert digest(rows) != digest(rows[:-1])
    assert digest(rows) != digest(rows[::-1])
    assert digest(rows) != digest(rows + rows[-1:])


def test_load_uses_real_routes_rotation_and_retention(monkeypatch, tmp_path):
    import diagnostic_logs
    monkeypatch.setenv('APP_SHARED_SECRET', 'soak-test')
    with isolated_backend(tmp_path) as (http, memory, visual):
        diagnostic_logs.store.max_bytes = 2000
        result = asyncio.run(load_backend(http, memory, visual, tmp_path, .01))
        assert result['failures'] == 0
        assert result['expected_turns'] == result['stored_turns'] == 10
        assert result['integrity_check'] == 'ok'
        assert result['retention_passed']
        assert len(result['log_files']) > 1
        assert all(size <= 2000 for size in result['log_files'].values())
        assert retention_probe(visual, 99)


def test_reconnect_preserves_transcripts_tools_and_latest_handle(monkeypatch, tmp_path):
    import json
    from voice_eval import soak
    from memory import Memory
    memory = Memory(tmp_path / 'memory.db')
    monkeypatch.setenv('APP_SHARED_SECRET', 'soak-test')
    sockets = []
    active = [0]
    posts = []
    class HTTP:
        def post(self, path, json, **kwargs):
            posts.append((path, json))
            if path == '/token':
                data = {'token': 'synthetic', 'model': 'test', 'expires_at': '2100-01-01T00:00:00Z'}
            elif path == '/transcripts':
                data = memory.ingest(json['session_id'], json['turns'])
            else:
                data = {'status': 'ok'}
            return SimpleNamespace(status_code=200, json=lambda: data)
    class Socket:
        def __init__(self):
            self.messages = []
            self.events = asyncio.Queue()
            self.events.put_nowait({'setupComplete': {}})
            self.events.put_nowait({'sessionResumptionUpdate': {'resumable': True, 'newHandle': 'opaque'}})
            self.events.put_nowait({'usageMetadata': {'promptTokenCount': 10, 'responseTokenCount': 5,
                                                       'totalTokenCount': 15}})
            self.events.put_nowait({'serverContent': {'inputTranscription': {'text': 'Repeated words.'}},
                                   'toolCall': {'functionCalls': [{'id': 'same', 'name': 'remember', 'args': {'fact': 'synthetic'}}]}})
            if not sockets:
                self.events.put_nowait({'goAway': {'timeLeft': '1s'}})
            sockets.append(self)
        async def __aenter__(self):
            active[0] += 1
            assert active[0] == 1
            return self
        async def __aexit__(self, *args): active[0] -= 1
        async def send(self, message): self.messages.append(json.loads(message))
        async def recv(self): return json.dumps(await self.events.get())
    monkeypatch.setattr(soak, 'connect', lambda *a, **kw: Socket())
    async def fake_send(self, ws):
        if len(sockets) == 1:
            await asyncio.Event().wait()
        await asyncio.sleep(.01)
        self.cursor = 10
    monkeypatch.setattr(soak.LiveSoak, 'send', fake_send)
    runner = soak.LiveSoak(HTTP(), memory, tmp_path, 'auto', 1, {}, {})
    result = asyncio.run(runner.run())
    assert result['completed'] and result['setups'] == 2 and result['goaways'] == 1
    assert result['transcript_chunks'] == result['stored_chunks'] == 2
    assert result['persistence_exact']
    assert len([p for p, _ in posts if p == '/tools/remember']) == 1
    assert sockets[1].messages[0]['setup']['sessionResumption'] == {'handle': 'opaque'}
    assert active[0] == 0
    from usage import meter
    usage = meter.snapshot()['today']
    assert usage['live_sessions'] == 2
    assert usage['total_tokens'] == 30  # Each connection's reports counted once.
    assert usage['live_minutes'] > 0


def test_upload_retry_reuses_sequence_after_lost_response(monkeypatch, tmp_path):
    from voice_eval.soak import LiveSoak
    from memory import Memory
    memory = Memory(tmp_path / 'memory.db')
    runner = LiveSoak(None, memory, tmp_path, 'auto', 0, {}, {})
    attempts = []
    async def post(path, body):
        attempts.append(body)
        memory.ingest(body['session_id'], body['turns'])
        if len(attempts) == 1:
            raise RuntimeError('lost response')
        return {'status': 'ok'}
    runner.post = post
    runner.persist('user', 'A transcript chunk.')
    async def exercise():
        import pytest
        with pytest.raises(RuntimeError):
            await runner.flush()
        assert len(runner.outbox) == 1
        await runner.flush()
    asyncio.run(exercise())
    with memory.db() as db:
        assert db.execute('SELECT COUNT(*) FROM turns').fetchone()[0] == 1
    assert attempts[0] == attempts[1] and not runner.outbox
    runner.record.file.close()


def test_recall_joins_long_chunks_without_crossing_speaker_or_session(tmp_path):
    from memory import Memory
    memory = Memory(tmp_path / 'memory.db')
    with memory.db() as db:
        db.executemany('INSERT INTO sessions VALUES (?,?,NULL)', [('a', 'now'), ('b', 'now')])
        db.executemany('INSERT INTO turns VALUES (?,?,?,?,?)',
                       [('a', i, 'user', 'copper ' if i % 2 == 0 else 'otter ', 'now') for i in range(10000)])
        db.executemany('INSERT INTO turns VALUES (?,?,?,?,?)', [
            ('a', 10000, 'model', 'copper model', 'now'),
            ('a', 10001, 'user', 'copper user again', 'now'),
            ('b', 0, 'user', 'copper other session', 'now')])
    matches = memory.recall('copper')['matches']
    assert len(matches) == 4
    long = next(r for r in matches if r['text'].startswith('copper otter'))
    assert long['speaker'] == 'user' and long['id'] == 'a'
    assert long['text'] == ('copper otter ' * 5000)[:2000]
    assert {(r['id'], r['speaker'], r['text']) for r in matches if r is not long} == {
        ('a', 'model', 'copper model'), ('a', 'user', 'copper user again'),
        ('b', 'user', 'copper other session')}


def test_incomplete_or_lossy_soak_is_not_success():
    from voice_eval.soak import successful
    load = {'failures': 0, 'stored_turns': 10, 'expected_turns': 10,
            'retention_passed': True, 'integrity_check': 'ok'}
    assert successful({'load': load})
    assert successful({'load': load, 'auto': {'completed': True, 'persistence_exact': True}})
    assert not successful({'load': load, 'auto': {'completed': False, 'persistence_exact': True}})
    assert not successful({'load': load, 'auto': {'completed': True, 'persistence_exact': False}})
    assert not successful({'load': {**load, 'stored_turns': 9}})


def test_transcription_metrics_distinguish_recognition_from_persistence():
    from voice_eval.soak import transcription_metrics
    exact = transcription_metrics('Copper otter, Cedar Pier.', 'copper otter cedar pier')
    assert exact['word_error_rate'] == 0
    missing = transcription_metrics('one two three four', 'one two four')
    assert missing['deletions'] == 1 and missing['word_error_rate'] == .25
    assert transcription_metrics('', '')['word_error_rate'] is None
    assert transcription_metrics('twenty people', '20 people')['substitutions'] == 1


def test_fuzzy_cutoff_preserves_every_qualifying_score():
    from memory import normalized, score
    from rapidfuzz import fuzz
    for query, text in [('Mapel', 'Maple'), ('harbour', 'We walked near the harbor.'),
                        ('copper otter', 'copper ' + 'unrelated ' * 10000 + 'otter'),
                        ('bicycle', 'Synthetic load meeting evidence. ' * 10000),
                        ('Toronto', 'The user lives in Toronto.'), ('alpha beta', 'alpha gamma')]:
        q, t = normalized(query), normalized(text)
        words = set(q.split())
        previous = max(100 * len(words & set(t.split())) / len(words), fuzz.WRatio(q, t))
        actual = score(query, text)
        assert (actual >= 65) == (previous >= 65)
        if previous >= 65:
            assert actual == previous


def test_fuzzy_work_receives_the_existing_minimum_threshold(monkeypatch):
    import memory
    observed = []
    def weighted_ratio(query, text, *, score_cutoff):
        observed.append(score_cutoff)
        return 0
    monkeypatch.setattr(memory.fuzz, 'WRatio', weighted_ratio)
    assert memory.score('bicycle', 'load ' * 10000) == 0
    assert observed == [65]


def test_meeting_never_finalizes_before_transcript_acknowledgment(monkeypatch, tmp_path):
    from memory import Memory
    from voice_eval.soak import LiveSoak
    monkeypatch.setenv('APP_SHARED_SECRET', 'soak-test')
    class HTTP:
        def post(self, path, **kwargs):
            if path.endswith('/end'):
                raise AssertionError('Unacknowledged meeting was finalized')
            return SimpleNamespace(status_code=503 if path == '/transcripts' else 200,
                                   json=lambda: {'status': 'ok'})
    runner = LiveSoak(HTTP(), Memory(tmp_path / 'memory.db'), tmp_path, 'meeting', 0, {}, {})
    runner.persist('user', 'This chunk must survive an unavailable backend.')
    result = asyncio.run(runner.run())
    assert not result['persistence_exact']
    assert result['finalize_error'] == 'UnacknowledgedTranscripts'
    assert len(runner.outbox) == 1


def test_full_soak_reserves_before_fixtures_or_provider_access(monkeypatch, tmp_path):
    import pytest
    from voice_eval import soak
    from usage import meter
    def forbidden(*args, **kwargs):
        pytest.fail('Refused soak reached fixtures or backend')
    monkeypatch.setattr(soak, 'fixtures', forbidden)
    monkeypatch.setattr(soak, 'isolated_backend', forbidden)
    args = SimpleNamespace(output=tmp_path / 'run', meeting_seconds=7200,
                           auto_seconds=1800, load_seconds=7200, load_only=False)
    with pytest.raises(ValueError, match='Evaluation refused'):
        asyncio.run(soak.run(args))
    with meter.database() as db:
        assert db.execute('SELECT COUNT(*) FROM eval_budget').fetchone()[0] == 0
    assert meter.snapshot()['today']['live_sessions'] == 0


def test_soak_run_shares_text_budget_and_restores_accounting(monkeypatch, tmp_path):
    import gemini_resilience
    from google.genai import models
    from memory_eval import provider
    from usage import meter
    from voice_eval import soak
    reserved = []
    monkeypatch.setenv('APP_SHARED_SECRET', 'soak-test')
    monkeypatch.setattr(soak, 'reserve_live', lambda cases, timeout: reserved.append((len(cases), timeout)))
    monkeypatch.setattr(soak, 'fixtures', lambda path: (
        {f'meeting-{i}': bytes(32000) for i in range(6)}, {}))
    monkeypatch.setattr(provider.time, 'sleep', lambda seconds: None)
    response = SimpleNamespace(text='Synthetic minutes', candidates=[], usage_metadata=SimpleNamespace(
        prompt_token_count=10, candidates_token_count=5, total_token_count=15))
    monkeypatch.setattr(models.Models, 'generate_content', lambda *args, **kwargs: response)
    async def fake_live(self):
        # The same production helper used by meeting map/reduce, with no SDK transport.
        client = SimpleNamespace(models=SimpleNamespace(generate_content=lambda **kwargs:
            models.Models.generate_content(None, **kwargs)))
        assert gemini_resilience.generate_content(client, model='test', contents='fictional') is response
        return {'completed': True, 'persistence_exact': True, 'setups': 1}
    monkeypatch.setattr(soak.LiveSoak, 'run', fake_live)
    args = SimpleNamespace(output=tmp_path / 'run', meeting_seconds=7200,
                           auto_seconds=1800, load_seconds=0, load_only=False)
    assert soak.successful(asyncio.run(soak.run(args)))
    assert reserved == [(25, 600)]  # 12 + 3 normal connections, five spare per mode.
    assert gemini_resilience.meter is meter
    report = meter.snapshot()['today']
    assert report['text_calls'] == 2 and report['total_tokens'] == 30
    with meter.database() as db:
        assert db.execute("SELECT amount FROM eval_budget WHERE metric='text_calls'").fetchone()[0] == 2


def test_load_only_needs_no_provider_allowance(monkeypatch, tmp_path):
    from voice_eval import soak
    def forbidden(*args, **kwargs):
        raise AssertionError('Load-only run requested provider allowance or audio')
    monkeypatch.setenv('APP_SHARED_SECRET', 'soak-test')
    monkeypatch.setenv('USAGE_EVAL_PERCENT', '0')
    monkeypatch.setattr(soak, 'reserve_live', forbidden)
    monkeypatch.setattr(soak, 'fixtures', forbidden)
    args = SimpleNamespace(output=tmp_path / 'run', meeting_seconds=7200,
                           auto_seconds=1800, load_seconds=.01, load_only=True)
    result = asyncio.run(soak.run(args))
    assert set(result) == {'load'} and soak.successful(result)


def test_repeated_short_connections_stop_at_reserved_limit(monkeypatch, tmp_path):
    import json
    from memory import Memory
    from voice_eval import soak
    class Socket:
        def __init__(self):
            self.events = iter([
                {'setupComplete': {}},
                {'sessionResumptionUpdate': {'resumable': True, 'newHandle': 'synthetic'}},
                {'goAway': {'timeLeft': '1s'}},
            ])
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def send(self, message): pass
        async def recv(self): return json.dumps(next(self.events))
    async def blocked_send(self, ws):
        await asyncio.Event().wait()
    async def post(path, body):
        assert path == '/token'
        return {'token': 'synthetic', 'model': 'test', 'expires_at': '2100-01-01T00:00:00Z'}
    monkeypatch.setattr(soak, 'connect', lambda *args, **kwargs: Socket())
    monkeypatch.setattr(soak.LiveSoak, 'send', blocked_send)
    runner = soak.LiveSoak(None, Memory(tmp_path / 'memory.db'), tmp_path, 'auto', 1, {}, {})
    runner.post = post
    result = asyncio.run(runner.run())
    assert result['connections'] == result['setups'] == result['goaways'] == 6
    assert not result['completed']
    assert result['last_error_type'] == 'ConnectionBudgetExhausted'
    assert result['audio_timeline_seconds'] == 0
    from usage import meter
    assert meter.snapshot()['today']['live_sessions'] == 6
