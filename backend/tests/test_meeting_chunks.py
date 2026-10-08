import io
import json
import wave
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient
import app
import meeting_api
import meeting_chunks
import meetings
from gemini_resilience import GeminiUnavailable
from memory import Memory, MemoryConflict
from meetings import Meetings
from test_meetings import summary, finish


def wav(seconds=2):
    buf = io.BytesIO()
    with wave.open(buf, 'wb') as w:
        w.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        w.writeframes(b'\0\0' * int(seconds * 16000))
    return buf.getvalue()


def segment(text, start=0, end=1000):
    return dict(start_ms=start, end_ms=end, speaker='Speaker A', text=text)


@pytest.fixture
def service(tmp_path):
    svc = Meetings(Memory(tmp_path / 'memory.db'), Mock())
    svc.start('meeting', 'Pilot', chunked=True)
    return svc


def test_stitch_only_boundary_overlap_preserves_later_repetitions():
    old = [segment('We agreed launch Friday.', 57000, 60000)]
    new = [segment('launch Friday. Budget is two hundred.', 0, 4000), segment('launch Friday.', 5000, 6000)]
    result = meeting_chunks.stitch(old, new, 58000, 60000)
    assert result[0]['text'] == 'Budget is two hundred.'
    assert result[1]['text'] == 'launch Friday.'
    assert result[1]['start_ms'] == 63000
    assert meeting_chunks.stitch(old, [segment('Different words.')], 58000, 60000)[0]['text'] == 'Different words.'
    assert meeting_chunks.stitch(old, [segment('launch Friday.', 3000, 4000)], 58000, 60000)[0]['text'] == 'launch Friday.'


def test_idempotent_durable_upload_offline_order_and_seal(service, monkeypatch):
    c = service.chunks
    c.upload('meeting', 1, 58000, wav(4))
    assert 'transcribed_index' not in c.process('meeting')
    receipt = c.upload('meeting', 0, 0, wav(60))
    assert receipt == c.upload('meeting', 0, 0, wav(60))
    with pytest.raises(MemoryConflict): c.upload('meeting', 0, 0, wav(2))
    with pytest.raises(MemoryConflict): c.seal('meeting', 3)
    assert service.step('meeting')['phase'] == 'transcribing'
    c.seal('meeting', 2)
    with pytest.raises(MemoryConflict): c.upload('meeting', 2, 60000, wav())
    monkeypatch.setattr(meeting_chunks, 'transcribe', Mock(side_effect=GeminiUnavailable('offline')))
    with pytest.raises(GeminiUnavailable): c.process('meeting')
    assert 'transcribed_index' not in c.process('meeting')  # Durable cooldown, no hot-loop.
    recovered = Meetings(service.memory, service.notes)
    with service.memory.db() as db:
        assert db.execute('SELECT count(*) FROM meeting_chunks WHERE audio IS NOT NULL').fetchone()[0] == 2
        db.execute('UPDATE meeting_chunks SET retry_at=0')
    monkeypatch.setattr(meeting_chunks, 'transcribe', Mock(side_effect=[
        [segment('launch Friday', 58000, 60000)], [segment('launch Friday. Budget approved.', 0, 4000)]]))
    assert recovered.chunks.process('meeting')['transcribed_index'] == 0
    assert recovered.chunks.process('meeting')['transcribed_index'] == 1
    assert c.upload('meeting', 0, 0, wav(60)) == receipt  # Receipt survives audio cleanup.
    monkeypatch.setattr(meetings, 'summarize_meeting', lambda text: summary(text))
    monkeypatch.setattr(meetings, 'prepare_document', lambda *args: {'id': 'doc'})
    monkeypatch.setattr(meetings, 'write_minutes', Mock())
    result = finish(recovered)
    assert 'Budget approved' in result['minutes']['summary']
    assert result['minutes']['summary'].count('launch Friday') == 1
    with service.memory.db() as db:
        assert db.execute('SELECT count(*) FROM meeting_chunks WHERE audio IS NOT NULL').fetchone()[0] == 0
    with pytest.raises(MemoryConflict): c.seal('meeting', 1)


def test_authenticated_bounded_wav_routes(service, monkeypatch):
    monkeypatch.setattr(meeting_api, 'service', service)
    monkeypatch.setenv('APP_SHARED_SECRET', 'test')
    with TestClient(app.app) as client:
        url = '/meetings/meeting/chunks/0?start_ms=0'
        assert client.post(url, content=wav()).status_code == 401
        headers = {'X-App-Secret': 'test', 'Content-Type': 'audio/wav'}
        assert client.post(url, headers=headers, content=wav(60)).status_code == 200
        assert client.post(url, headers=headers, content=wav(60)).status_code == 200
        assert client.post(url, headers=headers, content=b'invalid').status_code == 422
        assert client.post(url, headers=headers, content=b'0' * 2_000_001).status_code == 413
        assert client.post('/meetings/meeting/end', headers=headers).status_code == 409


def test_two_hours_are_processed_one_chunk_at_a_time(service, monkeypatch):
    monkeypatch.setattr(meeting_chunks, 'transcribe', lambda _: [segment('A decision.')])
    for i in range(125):
        service.chunks.upload('meeting', i, i * 58000, wav(60))
        assert service.chunks.process('meeting')['transcribed_index'] == i
    service.chunks.seal('meeting', 125)
    with service.memory.db() as db:
        assert db.execute('SELECT count(*) FROM turns').fetchone()[0] == 125
        assert db.execute('SELECT count(*) FROM meeting_chunks WHERE audio IS NOT NULL').fetchone()[0] == 0
        assert service.chunks.ready(db, service._load(db, 'meeting'))


def test_bad_audio_and_noncontiguous_timeline_are_rejected(service, monkeypatch):
    for data in (b'bad', wav()[:-2], wav(63)):
        with pytest.raises((ValueError, EOFError, wave.Error)): meeting_chunks.wav_duration(data)
    service.chunks.upload('meeting', 0, 5000, wav())
    with pytest.raises(MemoryConflict): service.chunks.process('meeting')


def test_provider_seconds_are_converted_and_invalid_timing_retains_audio(monkeypatch):
    client = Mock()
    client.__enter__ = Mock(return_value=client)
    client.__exit__ = Mock(return_value=False)
    monkeypatch.setenv('GEMINI_API_KEY', 'test')
    monkeypatch.setattr(meeting_chunks.genai, 'Client', Mock(return_value=client))
    response = Mock(text=json.dumps({'segments': [dict(start_seconds=1.25, end_seconds=1.9, speaker='Speaker B', text='Budget approved.')]}))
    generate = Mock(return_value=response)
    monkeypatch.setattr(meeting_chunks, 'generate_content', generate)
    audible = wav(2)[:-2] + b'\x01\x00'
    result = meeting_chunks.transcribe(audible)
    assert result == [dict(start_ms=1250, end_ms=1900, speaker='Speaker B', text='Budget approved.')]
    assert generate.call_args.kwargs['contents'][0].inline_data.mime_type == 'audio/wav'
    response.text = json.dumps({'segments': [dict(start_seconds=1, end_seconds=3, speaker='Speaker B', text='Wrong timestamp')]})
    with pytest.raises(ValueError): meeting_chunks.transcribe(audible)


def test_end_poll_never_runs_transcription_and_minutes_in_same_request(service, monkeypatch):
    monkeypatch.setattr(meeting_api, 'service', service)
    monkeypatch.setenv('APP_SHARED_SECRET', 'test')
    service.chunks.upload('meeting', 0, 0, wav())
    monkeypatch.setattr(meeting_chunks, 'transcribe', lambda _: [segment('Launch Friday.')])
    summarize = Mock(return_value=summary())
    monkeypatch.setattr(meetings, 'summarize_meeting', summarize)
    with TestClient(app.app) as client:
        url = '/meetings/meeting/end?chunk_count=1'
        headers = {'X-App-Secret': 'test'}
        assert client.post(url, headers=headers).json()['phase'] == 'transcribing'
        summarize.assert_not_called()
        assert client.post(url, headers=headers).json()['phase'] == 'map'
        summarize.assert_called_once()


def test_exact_digital_silence_needs_no_provider(monkeypatch):
    generate = Mock()
    monkeypatch.setattr(meeting_chunks, 'generate_content', generate)
    assert meeting_chunks.transcribe(wav(60)) == []
    generate.assert_not_called()
