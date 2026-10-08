from datetime import datetime
from types import SimpleNamespace
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

import app
import usage
from usage import UsageMeter, PACIFIC


def test_report_idempotent_out_of_order_and_model_immutable(tmp_path):
    meter = UsageMeter(tmp_path / 'usage.db', lambda: 1800003600)
    meter.live('id', 'model', 60, 100, 20, 120)
    meter.live('id', 'model', 60, 100, 20, 120)
    meter.live('id', 'model', 30, 50, 10, 60)
    assert meter.snapshot()['today'] == dict(live_sessions=1, live_minutes=1, text_calls=0, total_tokens=120)
    with pytest.raises(ValueError):
        meter.live('id', 'another', 80)
    assert UsageMeter(tmp_path / 'usage.db', lambda: 1800003600).snapshot()['today']['live_minutes'] == 1


def test_midnight_splits_minutes_and_rolls_daily_budget(tmp_path, monkeypatch):
    now = [datetime(2026, 10, 8, 23, 59, 30, tzinfo=PACIFIC).timestamp()]
    meter = UsageMeter(tmp_path / 'usage.db', lambda: now[0])
    meter.live('id', 'model', 0)
    now[0] += 60
    meter.live('id', 'model', 60)
    report = meter.snapshot()
    assert report['today']['live_minutes'] == .5
    assert report['today']['live_sessions'] == 0
    assert sum(row['minutes'] for row in report['rows']) == 1
    assert all(row['total_tokens'] is None for row in report['rows'])
    meter.reserve_eval({'live_minutes': 12})
    with pytest.raises(ValueError):
        meter.reserve_eval({'live_minutes': .1})
    now[0] += 86400
    meter.reserve_eval({'live_minutes': 12})


def test_reported_tokens_missing_tokens_and_attempts(tmp_path):
    meter = UsageMeter(tmp_path / 'usage.db')
    meter.record('text', response=SimpleNamespace(usage_metadata=SimpleNamespace(
        prompt_token_count=30, candidates_token_count=10, total_token_count=45)))
    meter.record('text', failed=True)
    row = meter.snapshot()['rows'][0]
    assert (row['calls'], row['failed'], row['total_tokens'], row['unreported']) == (2, 1, 45, 1)


def test_visual_throttle_atomic_at_eighty_percent_and_stop_at_cap(tmp_path, monkeypatch):
    monkeypatch.setenv('USAGE_TEXT_CALLS', '10')
    now = [1800003600]
    meter = UsageMeter(tmp_path / 'usage.db', lambda: now[0])
    for _ in range(8):
        meter.record('text')
    assert meter.visual_interval() == 60
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(lambda _: meter.allow_capture()[0], range(2)))
    assert sum(results) == 1
    now[0] += 60
    assert meter.allow_capture()[0]
    for _ in range(2):
        meter.record('text')
    now[0] += 600
    assert meter.allow_capture() == (False, 300)


def test_eval_reservations_atomic_and_rollback(tmp_path):
    meter = UsageMeter(tmp_path / 'usage.db')
    def reserve(_):
        try:
            meter.reserve_eval({'live_minutes': 10})
            return True
        except ValueError:
            return False
    with ThreadPoolExecutor(2) as pool:
        assert sum(pool.map(reserve, range(2))) == 1
    with pytest.raises(ValueError):
        meter.reserve_eval({'live_minutes': 1, 'text_calls': 41})
    meter.reserve_eval({'live_minutes': 2})  # Failed reservation changed nothing.


def test_api_auth_validation_and_dashboard(monkeypatch):
    monkeypatch.setenv('APP_SHARED_SECRET', 'usage-test')
    headers = {'X-App-Secret': 'usage-test'}
    with TestClient(app.app) as client:
        assert client.get('/usage').status_code == 401
        assert client.post('/usage/live', json={'id': 'a'}).status_code == 401
        assert client.get('/usage', headers=headers).status_code == 200
        body = {'id': 'a', 'model': 'gemini-3.8-live', 'seconds': 60, 'total_tokens': 120}
        assert client.post('/usage/live', headers=headers, json=body).status_code == 200
        assert client.post('/usage/live', headers=headers, json=body).status_code == 200
        assert client.get('/usage', headers=headers).json()['today']['live_minutes'] == 1
        assert client.post('/usage/live', headers=headers, json={**body, 'seconds': -1}).status_code == 422
        assert client.post('/usage/live', headers=headers, json={**body, 'model': 'another'}).status_code == 409
        assert client.get('/dashboard/usage', follow_redirects=False).status_code == 303
        import re
        login = client.get('/dashboard/login')
        csrf = re.search(r'name="csrf" value="([^"]+)"', login.text)[1]
        client.post('/dashboard/login', data={'secret': 'usage-test', 'csrf': csrf})
        page = client.get('/dashboard/usage')
        assert page.status_code == 200
        assert 'Last 7 quota days' in page.text and 'gemini-3.8-live' in page.text


def test_text_fallback_counts_each_attempt():
    from gemini_resilience import generate_content
    class Missing(Exception):
        code = 404
    class Models:
        def generate_content(self, model, **kwargs):
            if model == 'first':
                raise Missing()
            return SimpleNamespace(usage_metadata=None)
    generate_content(SimpleNamespace(models=Models()), model='first')
    rows = usage.meter.snapshot()['rows']
    assert sum(r['calls'] for r in rows) == 2
    assert sum(r['failed'] for r in rows) == 1


def test_eval_gate_and_cached_tts_does_not_reserve(tmp_path, monkeypatch):
    from voice_eval.quota import reserve_live
    with pytest.raises(ValueError):
        reserve_live([{}] * 10, 60)
    with pytest.raises(ValueError):
        reserve_live([{}], float('nan'))
    from voice_eval.audio import synthesize, cached_path
    import wave
    path = cached_path('hi', 'Kore', cache=tmp_path)
    with wave.open(str(path), 'wb') as wav:
        wav.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        wav.writeframes(b'\0\0' * 160)
    assert synthesize(None, 'hi', 'Kore', cache=tmp_path) == path
    with usage.meter.database() as db:
        assert db.execute('SELECT count(*) FROM eval_budget').fetchone()[0] == 0


@pytest.mark.parametrize('samples,allowed', [(1, False), (1, True), (2, True)])
def test_latency_probe_reserves_all_modes_before_live_access(tmp_path, monkeypatch, samples, allowed):
    import json
    import sys
    from contextlib import contextmanager
    from voice_eval import latency

    monkeypatch.setattr('dotenv.load_dotenv', lambda *a: None)
    monkeypatch.setenv('APP_SHARED_SECRET', 'latency-test')
    if allowed:
        monkeypatch.setenv('USAGE_EVAL_PERCENT', '100')
    output = tmp_path / 'latency.json'
    monkeypatch.setattr(sys, 'argv', ['latency', '--label', 'test', '--samples', str(samples), '--output', str(output)])
    monkeypatch.setattr(latency, 'read', lambda path: bytes(320))
    monkeypatch.setattr(latency.time, 'sleep', lambda seconds: None)
    attempted = []

    @contextmanager
    def backend():
        assert allowed, 'Quota refusal must precede backend/token access'
        with usage.meter.database() as db:
            reserved = dict(db.execute('SELECT metric, amount FROM eval_budget'))
        assert reserved == {'live_sessions': samples * 3, 'live_minutes': samples * 3,
                            'total_tokens': samples * 18000}
        yield object()

    async def evaluate(case, pcm, http, secret, timeout, **kwargs):
        attempted.append(case['mode'])
        assert timeout == 30
        return {'mode': case['mode'], 'complete': True, 'error': None, 'audio_bytes': 4}

    monkeypatch.setattr(latency, 'backend', backend)
    monkeypatch.setattr(latency, 'evaluate', evaluate)
    monkeypatch.setattr(latency, 'waterfall', lambda result: result)
    if allowed:
        latency.main()
        assert attempted == ['auto', 'ptt', 'over'] * samples
        assert len(json.loads(output.read_text())['runs'][0]['results']) == samples * 3
    else:
        with pytest.raises(SystemExit) as exc:
            latency.main()
        assert exc.value.code == 2
        assert attempted == [] and not output.exists()
        with usage.meter.database() as db:
            assert db.execute('SELECT count(*) FROM eval_budget').fetchone()[0] == 0


def test_known_limits_warn_throttle_and_gate_evals(tmp_path, monkeypatch):
    import json
    path = tmp_path / 'quotas.json'
    path.write_text(json.dumps({'gemini-3.5-flash-lite': {'rpd': 10, 'tpm': None, 'as_of': '2026-10-08'}}))
    monkeypatch.setenv('GEMINI_QUOTA_FILE', str(path))
    meter = UsageMeter(tmp_path / 'usage.db')
    for _ in range(8):
        meter.record('gemini-3.5-flash-lite')
    assert meter.visual_interval() == 60
    assert any('configured project limit' in w for w in meter.snapshot()['warnings'])
    with pytest.raises(ValueError):
        meter.reserve_eval({'text_calls': 2}, model='gemini-3.5-flash-lite')
    for _ in range(2):
        meter.record('gemini-3.5-flash-lite')
    assert meter.allow_capture() == (False, 300)


def test_visual_endpoint_does_not_caption_or_queue_when_capped(monkeypatch):
    import visual_api
    monkeypatch.setenv('APP_SHARED_SECRET', 'usage-test')
    monkeypatch.setenv('USAGE_TEXT_CALLS', '1')
    usage.meter.record('gemini-3.5-flash-lite')
    def unexpected(*args):
        pytest.fail('Capped capture must not invoke caption or save a frame')
    monkeypatch.setattr(visual_api.memory, 'capture', unexpected)
    with TestClient(app.app) as client:
        response = client.post('/visual/frames', headers={'X-App-Secret': 'usage-test'}, json={
            'jpeg': 'unused', 'captured_at': datetime.now(PACIFIC).isoformat(), 'pinned': True})
        assert response.json() == {'status': 'quota_throttled', 'capture_interval_seconds': 300}
    from visual_memory import caption_image
    from gemini_resilience import GeminiUnavailable
    with pytest.raises(GeminiUnavailable):
        caption_image(b'unused')  # Pending-caption recovery must honor the cap too.


def test_eval_connection_meter_closes_and_records_on_error(monkeypatch):
    import asyncio
    from contextlib import asynccontextmanager
    from voice_eval.quota import metered_connection
    closed = []
    @asynccontextmanager
    async def connect(*args, **kwargs):
        try:
            yield object()
        finally:
            closed.append(True)
    async def run():
        import time
        result = {'usage': [{'totalTokenCount': 120}]}
        with pytest.raises(RuntimeError):
            async with metered_connection(connect, {'model': 'live'}, result):
                result['_usage_started'] = time.monotonic() - 60
                raise RuntimeError('interrupted')
        assert '_usage_started' not in result
    asyncio.run(run())
    assert closed == [True]
    assert usage.meter.snapshot()['today']['live_sessions'] == 1
    assert usage.meter.snapshot()['today']['total_tokens'] == 120


def test_runner_stops_after_first_infrastructure_failure(tmp_path, monkeypatch):
    import json
    import sys
    from voice_eval import runner
    cases = tmp_path / 'cases.json'
    cases.write_text(json.dumps([{'id': 'one'}, {'id': 'two'}]))
    output = tmp_path / 'report.json'
    monkeypatch.setattr(sys, 'argv', ['voice_eval', '--label', 'test', '--cases', str(cases), '--output', str(output)])
    monkeypatch.setattr(runner, 'prompt_metrics', lambda *a: {})
    monkeypatch.setattr(runner, 'aggregate', lambda *a: {})
    monkeypatch.setattr(runner, 'render', lambda *a: '')
    attempted = []
    class Pool:
        def __init__(self, **kwargs):
            assert kwargs['max_workers'] == 1
        def __enter__(self):
            return self
        def __exit__(self, *args):
            pass
        def submit(self, fn, case, *args):
            attempted.append(case['id'])
            return SimpleNamespace(result=lambda: {'id': case['id'], 'error': 'ServiceUnavailable',
                'tool_selection_ok': False, 'arguments_ok': False})
    monkeypatch.setattr(runner, 'ProcessPoolExecutor', Pool)
    with pytest.raises(SystemExit) as exc:
        runner.main()
    assert exc.value.code == 1
    assert attempted == ['one']
    assert len(json.loads(output.read_text())['runs'][0]['results']) == 1
