import json
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

import app
import gemini
import meeting_api
import meetings
from meeting_summary import MeetingSummary
from memory import Memory, MemoryConflict
from meetings import Meetings
from meeting_docs import prepare_document, write_minutes


def summary(text='Discussed a Friday pilot.'):
    return dict(summary=text, decisions=['Pilot on Friday'],
                action_items=[dict(task='Send budget', owner='Alex', due='tomorrow')],
                open_questions=['Who approves security?'], recap=['The pilot is Friday.', 'Alex will send the budget.'])


@pytest.fixture
def service(tmp_path):
    return Meetings(Memory(tmp_path / 'memory.db'), Mock())


def ingest(service, text, seq=0):
    service.memory.ingest('meeting', [dict(seq=seq, speaker='user', text=text, timestamp='2026-10-07T12:00:00Z')])


def finish(service, limit=100):
    for _ in range(limit):
        result = service.step('meeting')
        if result['status'] == 'ok':
            return result
    pytest.fail('Reduction did not converge')


def test_two_hour_transcript_maps_every_character_and_recovers(service, monkeypatch):
    # 130 minutes at 150 words/minute, including early and late decisions.
    full = 'EARLY launch Friday.\n' + ('Maya: review the rollout budget and support plan.\n' * 2800) + 'LATE Alex owns support.\n'
    service.start('meeting', 'Pilot review')
    for seq, offset in enumerate(range(0, len(full), 12000)):
        ingest(service, full[offset:offset+12000], seq)
    maps, reductions = [], []
    def summarize(text, reducing=False):
        (reductions if reducing else maps).append(text)
        return summary()
    monkeypatch.setattr(meetings, 'summarize_meeting', summarize)
    monkeypatch.setattr(meetings, 'prepare_document', Mock(return_value={'id': 'doc', 'title': 'Pilot'}))
    write = Mock()
    monkeypatch.setattr(meetings, 'write_minutes', write)
    assert service.step('meeting')['status'] == 'processing'
    # A new service instance continues from the durable map checkpoint.
    recovered = Meetings(service.memory, service.notes)
    result = finish(recovered)
    assert ''.join(maps) == full
    assert all(len(t) <= meetings.CHUNK_CHARS for t in maps)
    assert len(maps) > meetings.REDUCE_FAN_IN and len(reductions) >= 2
    assert result['doc_url'].endswith('/doc/edit')
    assert len(result['minutes']['recap']) == 2
    write.assert_called_once()
    assert recovered.step('meeting') == result
    assert service.memory.recall('Pilot')['matches']
    with service.memory.db() as db:
        assert db.execute('SELECT count(*) FROM facts').fetchone()[0] == 0
        assert db.execute('SELECT sum(length(text)) FROM turns').fetchone()[0] == len(full)
    with pytest.raises(MemoryConflict):
        ingest(service, 'too late', 10000)


def test_failure_keeps_map_checkpoint_and_summary_survives_docs_failure(service, monkeypatch):
    service.start('meeting', 'Review')
    ingest(service, 'Alex owns budget.')
    generate = Mock(side_effect=[RuntimeError('upstream'), summary()])
    monkeypatch.setattr(meetings, 'summarize_meeting', generate)
    with pytest.raises(RuntimeError):
        service.step('meeting')
    service.step('meeting')
    service.step('meeting')
    service.step('meeting')
    monkeypatch.setattr(meetings, 'prepare_document', Mock(side_effect=OSError('credentials')))
    with pytest.raises(OSError):
        service.step('meeting')
    assert generate.call_count == 2
    assert service.memory.recall('budget')['matches']
    with service.memory.db() as db:
        assert json.loads(db.execute('SELECT data FROM meetings').fetchone()[0])['phase'] == 'document'


def test_start_replay_and_conflicting_title(service):
    assert service.start('meeting', 'Lecture') == service.start('meeting', 'Lecture')
    with pytest.raises(MemoryConflict):
        service.start('meeting', 'Different')
    with pytest.raises(MemoryConflict):
        service.step('missing')


def test_empty_meeting_needs_no_gemini(service, monkeypatch):
    service.start('meeting', 'Empty')
    generate = Mock(side_effect=AssertionError('No request for empty recording'))
    monkeypatch.setattr(meetings, 'summarize_meeting', generate)
    monkeypatch.setattr(meetings, 'prepare_document', Mock(return_value={'id': 'doc', 'title': 'Empty'}))
    monkeypatch.setattr(meetings, 'write_minutes', Mock())
    assert finish(service)['minutes']['action_items'] == []
    generate.assert_not_called()


def test_routes_validate_auth_and_hide_upstream_errors(service, monkeypatch):
    monkeypatch.setenv('APP_SHARED_SECRET', 'test')
    monkeypatch.setattr(meeting_api, 'service', service)
    with TestClient(app.app) as c:
        assert c.post('/meetings/start', json={}).status_code == 401
        c.headers['X-App-Secret'] = 'test'
        assert c.post('/meetings/start', json={'meeting_id': '../bad'}).status_code == 422
        assert c.post('/meetings/start', json={'meeting_id': 'meeting', 'title': ' '}).status_code == 422
        assert c.post('/meetings/start', json={'meeting_id': 'meeting'}).status_code == 200
        ingest(service, 'Hello')
        monkeypatch.setattr(meetings, 'summarize_meeting', Mock(side_effect=RuntimeError('private-upstream-value')))
        response = c.post('/meetings/meeting/end')
        assert response.status_code == 502 and 'private-upstream-value' not in response.text
        assert response.headers['cache-control'] == 'no-store'
        assert c.post('/tools/start_meeting', json={'title': 'Planning'}).json()['client_action'] == 'start_meeting'
        assert c.post('/tools/stop_meeting', json={}).json()['client_action'] == 'stop_meeting'
        assert c.post('/tools/stop_meeting', json={'title': 'invalid'}).status_code == 422


def test_meeting_token_retains_resumption_contract_and_auto_transcription():
    config = gemini.token_config(gemini.DEFAULT_MODEL, 'meeting')
    live = config.live_connect_constraints.config
    assert config.lock_additional_fields == []
    assert live.session_resumption is None
    assert live.realtime_input_config.automatic_activity_detection.disabled is False
    assert live.input_audio_transcription is not None
    assert 'Meeting capture is active' in live.system_instruction
    assert {'start_meeting', 'stop_meeting'} <= {d.name for d in live.tools[0].function_declarations}


def test_doc_create_recovery_and_folder(service):
    notes = service.notes
    from threading import Lock
    notes.lock = Lock()
    files = notes.drive.files.return_value
    files.list.return_value.execute.side_effect = [{'files': []}, {'files': []}]
    files.create.return_value.execute.side_effect = [{'id': 'folder'}, {'id': 'doc'}]
    m = dict(id='meeting', title='Review', doc_name=None, started_at='2026-10-07')
    assert prepare_document(notes, m) == {'id': 'doc', 'title': 'Meeting – Review – 2026-10-07'}
    body = files.create.call_args.kwargs['body']
    assert body['parents'] == ['folder'] and body['appProperties']['glassesMeeting'] == __import__('hashlib').sha256(b'meeting').hexdigest()
    files.list.return_value.execute.side_effect = [{'files': [{'id': 'doc', 'name': 'Existing'}]}]
    assert prepare_document(notes, m)['id'] == 'doc'
    assert files.create.call_count == 2


def test_doc_retry_marker_and_revision_guard(service):
    notes = service.notes
    from threading import Lock
    notes.lock = Lock()
    docs = notes.docs.documents.return_value
    docs.get.return_value.execute.side_effect = [
        {'revisionId': 'r1', 'body': {}},
        {'revisionId': 'r2', 'body': {'content': [{'text': '[glasses-meeting:meeting]'}]}},
    ]
    m = dict(id='meeting', title='Review', started_at='2026-10-07')
    write_minutes(notes, m, {'id': 'doc'}, summary())
    write_minutes(notes, m, {'id': 'doc'}, summary())
    docs.batchUpdate.assert_called_once()
    body = docs.batchUpdate.call_args.kwargs['body']
    assert body['writeControl']['requiredRevisionId'] == 'r1'
    text = body['requests'][0]['insertText']['text']
    assert all(x in text for x in ['Summary', 'Decisions', 'Action items', 'Open questions', 'Alex', '[glasses-meeting:meeting]'])


def test_summary_schema_rejects_missing_recap():
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        MeetingSummary.model_validate({**summary(), 'recap': ['Only one.']})


def test_real_request_uses_structural_schema_with_local_validation(monkeypatch):
    import meeting_summary
    from google.genai import types
    monkeypatch.setenv('GEMINI_API_KEY', 'fake')
    client = Mock()
    client.models.generate_content.return_value.text = json.dumps(summary())
    context = Mock()
    context.__enter__ = Mock(return_value=client)
    context.__exit__ = Mock(return_value=False)
    monkeypatch.setattr(meeting_summary.genai, 'Client', Mock(return_value=context))
    assert meeting_summary.summarize_meeting('Alex owns budget.') == summary()
    config = client.models.generate_content.call_args.kwargs['config']
    assert isinstance(config, types.GenerateContentConfig)
    wire = json.dumps(config.response_json_schema)
    assert 'maxItems' not in wire and 'minLength' not in wire
    assert config.response_mime_type == 'application/json'
    assert config.automatic_function_calling.disable
    client.models.generate_content.return_value.text = json.dumps({**summary(), 'recap': ['Invalid.']})
    with pytest.raises(ValueError):
        meeting_summary.summarize_meeting('Alex owns budget.')


def test_named_document_requires_exact_unambiguous_match(service, tmp_path):
    from threading import Lock
    notes = service.notes
    notes.lock = Lock()
    aliases = tmp_path / 'aliases.json'
    aliases.write_text('{"work":"known-doc"}')
    notes.aliases_path = aliases
    notes._document.return_value = {'id': 'known-doc', 'title': 'Work log'}
    m = dict(id='meeting', title='Review', doc_name='work', started_at='2026-10-07')
    assert prepare_document(notes, m)['id'] == 'known-doc'
    notes._list_documents.return_value = [{'id': 'one', 'title': 'Work logs'}]
    with pytest.raises(ValueError):
        prepare_document(notes, {**m, 'doc_name': 'Work log'})
    notes._list_documents.return_value = [{'id': 'one', 'title': 'Work log'}, {'id': 'two', 'title': 'Work log'}]
    with pytest.raises(ValueError):
        prepare_document(notes, {**m, 'doc_name': 'Work log'})
    notes.drive.files.assert_not_called()
