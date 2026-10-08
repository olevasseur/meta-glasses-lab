"""Offline DSP/proxy checks; no provider calls or downloaded model required."""
import numpy as np
import pytest
from .hfp import (CONDITIONS, Condition, active_rms, app_upsample, band, decode,
                  encode, noise_bed, packet_loss, rms, simulate)
from .hfp_keywords import KeywordDetector, replay


def tone(frequency, seconds=2, rate=16000):
    return .2 * np.sin(2 * np.pi * frequency * np.arange(int(seconds * rate)) / rate)


def test_app_resampler_kotlin_rounding_and_chunk_boundaries():
    samples = np.array([-3, 0, 7, -8, 32767, -32768], dtype='<i2').tobytes()
    full, _ = app_upsample(samples)
    assert np.frombuffer(full, '<i2').tolist() == [-3, -3, -1, 0, 3, 7, 0, -8, 16379, 32767, 0, -32768]
    previous, chunks = None, []
    for start in range(0, len(samples), 4):
        chunk, previous = app_upsample(samples[start:start+4], previous)
        chunks.append(chunk)
    assert b''.join(chunks) == full
    assert app_upsample(b'', 7) == (b'', 7)


def test_passband_and_antialias_reject_rumble_and_high_frequency():
    reference = rms(band(tone(1000), 300, 3400)[1600:])
    assert rms(band(tone(60), 300, 3400)[1600:]) < reference / 100
    assert rms(band(tone(6000), 300, 3400)[1600:]) < reference / 50
    for freq in (1000, 6000):
        _, pcm8, _ = simulate(encode(tone(freq)), Condition('test', codec='none'))
        if freq == 1000:
            base = rms(decode(pcm8)[800:])
        else:
            assert rms(decode(pcm8)[800:]) < base / 10


def test_noise_beds_reproducible_finite_and_distinct():
    beds = []
    for name in ('street', 'cafe', 'wind', 'car', 'kitchen'):
        a = noise_bed(name, 16000, np.random.default_rng(42))
        assert np.array_equal(a, noise_bed(name, 16000, np.random.default_rng(42)))
        assert np.all(np.isfinite(a)) and rms(a) == pytest.approx(1)
        beds.append(a.tobytes())
    assert len(set(beds)) == 5


def test_loss_is_burst_bounded_budget_and_fades_repeated_packet():
    signal = np.ones(80000)
    for fraction in (.01, .03, .05):
        out, lost, count = packet_loss(signal, fraction, np.random.default_rng(42))
        assert len(lost) == round(count * fraction)
        assert any(i+1 in lost for i in lost)
        assert np.max(np.abs(out)) <= 1
        assert np.array_equal(out, packet_loss(signal, fraction, np.random.default_rng(42))[0])
    with pytest.raises(ValueError):
        packet_loss(signal, .1, np.random.default_rng(1))


def test_full_channel_duration_bounds_and_hash_reproducibility():
    pcm = encode(tone(600))
    for condition in CONDITIONS:
        a, narrow, meta = simulate(pcm, condition, bystander=encode(tone(1000)))
        assert len(a) == len(pcm) and len(narrow) == len(pcm)//2
        assert (a, narrow, meta) == simulate(pcm, condition, bystander=encode(tone(1000)))
        assert meta['loss_fraction'] <= .051
        assert rms(decode(a)) > 0
    assert simulate(pcm, CONDITIONS[0])[0] == pcm
    with pytest.raises(ValueError): simulate(b'', CONDITIONS[0])
    with pytest.raises(ValueError): decode(b'\0')


def test_active_reference_does_not_collapse_for_long_pause():
    speech = tone(1000, .5)
    assert active_rms(np.r_[speech, np.zeros(160000)]) == pytest.approx(active_rms(speech))


def test_keyword_port_revisions_grace_and_signoff_segments():
    detector = KeywordDetector()
    detector.transcript('look over', False, 0)
    assert detector.tick(1.199) is None
    detector.transcript('look over there', False, .8)
    assert detector.tick(2) is None
    detector.transcript('answer OVER!', True, 3)
    assert detector.tick(4.2) == 'keyword'
    assert detector.tick(5) is None
    detector = KeywordDetector()
    detector.transcript('over', True, 0)
    detector.transcript('and', True, .4)
    assert detector.transcript('out', True, .9) == 'sign_off'


def test_alias_ablation_demonstrates_older_false_positive():
    events = [{'text': 'my dog is getting older', 'time_s': 1, 'final': True}]
    assert replay(events, 3)['reason'] is None
    assert replay(events, 3, ('ova', 'older'))['reason'] == 'keyword'
    for text in ('clover', 'overhead', 'turn over the page'):
        assert replay([{'text': text, 'time_s': 1, 'final': True}], 3)['reason'] is None


def test_three_provider_failures_stop_sequential_run_without_retries(monkeypatch, tmp_path):
    import json
    import sys
    from types import SimpleNamespace
    from . import hfp_runner
    model = tmp_path / 'model'
    (model / 'conf').mkdir(parents=True)
    (model / 'conf/mfcc.conf').write_text('--allow-upsample=true')
    output = tmp_path / 'result.json'
    monkeypatch.setitem(sys.modules, 'vosk', SimpleNamespace(Model=lambda p: None, SetLogLevel=lambda x: None))
    monkeypatch.setattr(sys, 'argv', ['eval', '--vosk-model', str(model), '--output', str(output), '--live'])
    case = {'id': 'no-tools', 'mode': 'auto', 'feature': 'test', 'expected': []}
    monkeypatch.setattr(hfp_runner, 'cases_and_audio', lambda: ([(case, bytes(3200))], b''))
    monkeypatch.setattr(hfp_runner, 'transcribe', lambda *a: {'exact': {'reason': None}})
    calls, sleeps = [], []
    def fail(case, pcm):
        # Attempt persisted before spending anything.
        calls.append(json.loads(output.read_text())['live_attempts'])
        return {'id': case['id'], 'mode': 'auto', 'error': 'ConnectionClosedError', 'close_code': 1011, 'calls': []}
    monkeypatch.setattr(hfp_runner, 'evaluate_one', fail)
    monkeypatch.setattr(hfp_runner.time, 'sleep', sleeps.append)
    hfp_runner.main()
    report = json.loads(output.read_text())
    assert calls == [1, 2, 3] and sleeps == [15, 30, 60]
    assert report['live_attempts'] == 3 and report['stopped']
    assert len(report['results']) == 7  # Full local coverage retained even on provider failure.


def test_live_receiver_records_transcript_hypotheses_without_credentials(monkeypatch):
    import asyncio
    import json
    from types import SimpleNamespace
    from . import client
    class Socket:
        def __init__(self): self.incoming = asyncio.Queue()
        async def __aenter__(self): return self
        async def __aexit__(self, *args): pass
        async def send(self, message):
            value = json.loads(message)
            if 'setup' in value:
                self.incoming.put_nowait({'setupComplete': {}})
            if 'activityEnd' in value.get('realtimeInput', {}):
                self.incoming.put_nowait({'serverContent': {'inputTranscription': {'text': 'Hello. '}}})
                self.incoming.put_nowait({'serverContent': {'inputTranscription': {'text': 'Over.'}, 'turnComplete': True}})
        async def recv(self): return json.dumps(await self.incoming.get())
    monkeypatch.setattr(client, 'connect', lambda *a, **kw: Socket())
    http = SimpleNamespace(post=lambda *a, **kw: SimpleNamespace(status_code=200, json=lambda: {'token': 'test-only-token', 'model': 'test'}))
    result = asyncio.run(client.evaluate({'id': 'test', 'mode': 'ptt', 'feature': 'test'}, bytes(320), http, 'test-secret', 1, .001))
    assert result['complete']
    assert [e['text'] for e in result['input_transcription_events']] == ['Hello. ', 'Hello. Over.']
    assert 'test-only-token' not in json.dumps(result) and 'test-secret' not in json.dumps(result)


def test_fir_candidate_rejects_image_and_keeps_pcm_length():
    from .hfp_ablation import fir_upsample, word_errors
    from scipy.signal import periodogram
    x = encode(tone(3000, rate=8000))
    linear = decode(app_upsample(x)[0])[1600:]
    filtered = decode(fir_upsample(x))[1600:]
    assert len(filtered) == len(linear)
    f, p = periodogram(filtered, 16000)
    _, old = periodogram(linear, 16000)
    at = lambda freq: np.argmin(abs(f - freq))
    assert p[at(5000)] < old[at(5000)] / 1000
    assert p[at(3000)] > old[at(3000)]
    assert word_errors('Please save a note', 'please safe note') == (2, 4)


def test_odd_narrowband_sample_is_not_dropped_by_adpcm():
    source = encode(tone(1000)[:322])  # 161 narrowband samples, odd ADPCM nibble count.
    pcm, narrow, _ = simulate(source, Condition('hfp'))
    assert len(pcm) == 644 and len(narrow) == 322


@pytest.mark.parametrize('baseline', [
    {'live_attempts': 43, 'metrics': {}},
    {'live_attempts': 42},
    {'live_attempts': 3, 'metrics': {}, 'stopped': 'provider failure'},
])
def test_candidate_refuses_excess_budget_unfinished_or_failed_baseline(monkeypatch, tmp_path, baseline):
    import json
    import sys
    from . import hfp_candidate
    source = tmp_path / 'baseline.json'
    source.write_text(json.dumps(baseline))
    output = tmp_path / 'candidate.json'
    monkeypatch.setattr(sys, 'argv', ['candidate', '--baseline', str(source), '--output', str(output)])
    with pytest.raises(SystemExit): hfp_candidate.main()
    assert not output.exists()
