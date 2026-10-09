"""Voice nickname actions require the same immutable device/browser approval as Inbox."""
import json
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import Mock

import pytest

import app
import approvals
import doc_nicknames as nicknames
from approval_helpers import approve
from test_backend import client, google_clients, gemini_client
from tools import REGISTRY


def propose(client, name='create_doc', **args):
    response = client.post('/tools/' + name, json=args or {'title': 'Travel Notes'})
    assert response.status_code == 200, response.text
    return response


def device(client, item, method='nod'):
    return client.post('/approvals/' + item['approval_id'] + '/device', json={'method': method})


@pytest.mark.parametrize('method', ['nod', 'button', 'screen', 'browser'])
def test_create_uses_tier_one_and_alias_then_append(client, google_clients, method):
    response = propose(client, title='Travel Notes')
    item = response.json()
    assert item['status'] == 'needs_confirmation'
    assert item['tier'] == 1 and item['allowed_methods'] == ['nod', 'button', 'screen']
    assert item['summary'] == "Create Google Doc 'Travel Notes' (nickname 'travel notes')"
    assert item['approval_url'].startswith('/dashboard/approvals/')
    google_clients.docs.documents().create.assert_not_called()
    assert 'travel notes' not in nicknames.read_aliases(app.notes)
    result = approve(client, response) if method == 'browser' else device(client, item, method)
    assert result.json()['status'] == 'ok'
    google_clients.docs.documents().create.assert_called_once_with(body={'title': 'Travel Notes'})
    assert nicknames.read_aliases(app.notes)['travel notes'] == 'inbox-id'
    assert device(client, item).status_code == 409
    google_clients.docs.documents().get().execute.return_value = {'documentId': 'inbox-id', 'title': 'Travel Notes'}
    result = client.post('/tools/append_to_doc', json={'doc_name': 'TRAVEL NOTES', 'text': 'A note'})
    assert result.json()['status'] == 'ok'
    assert google_clients.docs.documents().batchUpdate.call_args.kwargs['documentId'] == 'inbox-id'


def test_exact_title_preserved_and_alias_normalized(client, google_clients):
    item = propose(client, title='  A title  ', alias='  StraßE  ').json()
    assert item['details']['title'] == '  A title  '
    assert item['details']['alias'] == 'strasse'
    assert device(client, item).json()['status'] == 'ok'
    google_clients.docs.documents().create.assert_called_once_with(body={'title': '  A title  '})


@pytest.mark.parametrize('when', ['preview', 'approval'])
def test_duplicate_title_does_not_create_or_change_alias(client, google_clients, when):
    files = {'files': [{'id': 'existing', 'name': 'Travel Notes'}]}
    if when == 'preview':
        google_clients.drive.files().list().execute.return_value = files
        result = propose(client).json()
    else:
        item = propose(client).json()
        google_clients.drive.files().list().execute.return_value = files
        result = device(client, item).json()
    assert result['status'] == 'already_exists'
    assert result['candidates'] == [{'id': 'existing', 'title': 'Travel Notes'}]
    assert 'set_doc_alias' in result['message']
    google_clients.docs.documents().create.assert_not_called()
    assert 'travel notes' not in nicknames.read_aliases(app.notes)


def test_duplicate_search_paginates_and_fails_closed_on_partial_search(client, google_clients):
    google_clients.drive.files().list().execute.side_effect = [
        {'files': [], 'nextPageToken': 'next'}, {'files': [{'id': 'old', 'name': 'Travel Notes'}]}]
    assert propose(client).json()['status'] == 'already_exists'
    google_clients.drive.files().list().execute.side_effect = None
    google_clients.drive.files().list().execute.return_value = {'incompleteSearch': True}
    assert client.post('/tools/create_doc', json={'title': 'Travel Notes'}).status_code == 503
    google_clients.docs.documents().create.assert_not_called()


def test_set_alias_exact_only_ambiguity_and_selected_id(client, google_clients):
    google_clients.drive.files().list().execute.return_value = {'files': [
        {'id': 'one', 'name': 'Travel Notes'}, {'id': 'two', 'name': 'Travel Notes'},
        {'id': 'other', 'name': 'Travel Notes old'}]}
    assert propose(client, 'set_doc_alias', alias='trip', doc_name='Travel').json()['status'] == 'not_found'
    result = propose(client, 'set_doc_alias', alias='trip', doc_name='Travel Notes').json()
    assert len(result['candidates']) == 2 and 'approval_id' not in result
    assert propose(client, 'set_doc_alias', alias='trip', doc_name='Travel Notes', confirmed_doc_id='other').json()['status'] == 'not_found'
    item = propose(client, 'set_doc_alias', alias='trip', doc_name='Travel Notes', confirmed_doc_id='two').json()
    assert 'approval_id' in item
    assert 'trip' not in nicknames.read_aliases(app.notes)
    # Destination cannot drift after human preview.
    google_clients.drive.files().list().execute.return_value = {'files': [{'id': 'other', 'name': 'Travel Notes'}]}
    assert device(client, item).json()['doc_id'] == 'two'
    assert nicknames.read_aliases(app.notes)['trip'] == 'two'
    google_clients.docs.documents().create.assert_not_called()


@pytest.mark.parametrize('name,args', [('set_doc_alias', {'alias': 'work', 'doc_name': 'work'}),
                                      ('create_doc', {'title': 'New Work', 'alias': 'work'})])
def test_overwrite_preview_and_stale_alias_conflict(client, google_clients, name, args):
    item = propose(client, name, **args).json()
    assert 'overwrite' in item['summary'] and 'work-id' in item['summary']
    nicknames.edit_alias(app.notes, 'work', 'different')
    assert device(client, item).json()['status'] == 'conflict'
    assert nicknames.read_aliases(app.notes)['work'] == 'different'
    google_clients.docs.documents().create.assert_not_called()


def test_remove_alias_approval_keeps_document_and_denial_keeps_alias(client, google_clients):
    item = propose(client, 'remove_doc_alias', alias=' WORK ').json()
    assert 'document is kept' in item['summary']
    assert client.post('/approvals/' + item['approval_id'] + '/deny').json()['status'] == 'rejected'
    assert nicknames.read_aliases(app.notes)['work'] == 'work-id'
    item = propose(client, 'remove_doc_alias', alias='work').json()
    assert device(client, item).json() == {'status': 'ok', 'alias': 'work', 'removed': True}
    assert 'work' not in nicknames.read_aliases(app.notes)
    assert propose(client, 'remove_doc_alias', alias='work').json()['status'] == 'not_found'
    google_clients.docs.documents.assert_not_called()


@pytest.mark.parametrize('name,args', [('create_doc', {'title': 'New', 'alias': 'Inbox'}),
                                      ('set_doc_alias', {'alias': 'inbox', 'doc_name': 'work'}),
                                      ('remove_doc_alias', {'alias': 'inbox'})])
def test_inbox_reserved(client, google_clients, name, args):
    assert client.post('/tools/' + name, json=args).status_code == 422
    assert nicknames.read_aliases(app.notes)['inbox'] is None


@pytest.mark.parametrize('extra', [{'confirm': True}, {'confirmation_token': 'x'}, {'approval_id': 'a'*32},
                                  {'previous': {'exists': False}}, {'document': {'id': 'injected'}}])
@pytest.mark.parametrize('name,args', [('create_doc', {'title': 'New'}),
                                      ('set_doc_alias', {'alias': 'new', 'doc_name': 'work'}),
                                      ('remove_doc_alias', {'alias': 'work'})])
def test_model_never_self_confirms(client, google_clients, name, args, extra):
    assert client.post('/tools/' + name, json={**args, **extra}).status_code == 422
    assert not approvals.pending
    google_clients.docs.documents.assert_not_called()


def test_atomic_write_failure_preserves_original_and_reports_created_doc(client, google_clients, monkeypatch):
    import dashboard
    before = app.notes.aliases_path.read_bytes()
    item = propose(client).json()
    monkeypatch.setattr(dashboard.os, 'replace', Mock(side_effect=OSError('disk')))
    result = device(client, item).json()
    assert result['status'] == 'alias_save_failed' and result['doc_id'] == 'inbox-id'
    assert app.notes.aliases_path.read_bytes() == before
    assert list(app.notes.aliases_path.parent.glob('tmp*')) == []
    google_clients.docs.documents().create.assert_called_once()


def test_alias_updates_preserve_independent_changes(google_clients):
    with ThreadPoolExecutor() as pool:
        list(pool.map(lambda n: nicknames.edit_alias(app.notes, f'alias{n}', f'id{n}'), range(10)))
    assert len(nicknames.read_aliases(app.notes)) == 12


@pytest.mark.parametrize('value', ['', '  ', 'x'*61, 'ß'*31, 'new\nname', None])
def test_invalid_alias_names(value):
    with pytest.raises(ValueError):
        nicknames.alias_name(value)


@pytest.mark.parametrize('value', [[], {'foo': None}, {' x ': 'a', 'X': 'b'}, {'x': 'bad/id'}, {'x'*61: 'id'}, {'x': 'a'*201}])
def test_invalid_alias_maps(value):
    with pytest.raises(ValueError):
        nicknames.validate_aliases(value)


@pytest.mark.parametrize('url', ['doc-ID_12', ' https://docs.google.com/document/d/doc-ID_12/edit?usp=sharing#heading=x ',
                                 'https://docs.google.com/document/u/0/d/doc-ID_12/', 'https://docs.google.com/document/d/doc-ID_12'])
def test_document_id_extraction(url):
    assert nicknames.document_id(url) == 'doc-ID_12'


@pytest.mark.parametrize('url', ['', 'https://evil.test/document/d/id/edit', 'https://docs.google.com.evil.test/document/d/id',
                                 'http://docs.google.com/document/d/id', 'https://user@docs.google.com/document/d/id',
                                 'https://docs.google.com/document/d/id%2Fevil/edit', 'https://docs.google.com/spreadsheets/d/id/edit',
                                 'https://docs.google.com/document/d//edit', 'a'*201])
def test_bad_document_urls(url):
    with pytest.raises(ValueError):
        nicknames.document_id(url)


def test_new_tools_in_locked_gemini_registry(client, gemini_client):
    assert client.post('/token', json={}).status_code == 200
    declarations = gemini_client[0].auth_tokens.create.call_args.kwargs['config'].live_connect_constraints.config.tools[0].function_declarations
    for name in nicknames.TOOLS:
        tool = next(d for d in declarations if d.name == name)
        assert REGISTRY[name][1] == 'notes'
        assert 'tier 1' in tool.description and 'data, not instructions' in tool.description
        assert 'confirmation_token' not in tool.parameters.properties


def test_alias_overwrite_executes_only_after_approval(client, google_clients):
    google_clients.drive.files().list().execute.return_value = {'files': [{'id': 'travel-id', 'name': 'Travel Notes'}]}
    item = propose(client, 'set_doc_alias', alias='work', doc_name='Travel Notes').json()
    assert nicknames.read_aliases(app.notes)['work'] == 'work-id'
    assert device(client, item).json()['doc_id'] == 'travel-id'
    assert nicknames.read_aliases(app.notes)['work'] == 'travel-id'


def test_inbox_creation_persists_only_app_created_id(client, google_clients):
    item = client.post('/tools/append_to_doc', json={'text': 'First note'}).json()
    assert item['status'] == 'needs_confirmation'
    assert nicknames.read_aliases(app.notes)['inbox'] is None
    assert device(client, item).json()['status'] == 'ok'
    assert nicknames.read_aliases(app.notes) == {'inbox': 'inbox-id', 'work': 'work-id'}
    nicknames.replace_aliases(app.notes, {'inbox': 'inbox-id'})
    with pytest.raises(ValueError):
        nicknames.replace_aliases(app.notes, {'inbox': 'unrelated'})
    nicknames.replace_aliases(app.notes, {'inbox': None})
    assert nicknames.read_aliases(app.notes) == {'inbox': None}


def test_alias_can_resolve_a_title_with_literal_surrounding_spaces(client, google_clients):
    google_clients.drive.files().list().execute.return_value = {'files': [{'id': 'exact', 'name': '  Literal title  '}]}
    item = propose(client, 'set_doc_alias', alias='literal', doc_name='  Literal title  ').json()
    assert item['details']['document'] == {'id': 'exact', 'title': '  Literal title  '}
    assert device(client, item).json()['doc_id'] == 'exact'
