"""Nickname forms use the existing authenticated, CSRF-protected dashboard boundary."""
import json
from unittest.mock import Mock

import pytest

import app
import doc_nicknames
from test_dashboard import client, login, post


def test_alias_form_add_list_replace_remove(client, monkeypatch):
    token = login(client)
    monkeypatch.setattr(app.notes, '_connect', Mock())
    monkeypatch.setattr(app.notes, '_list_documents', Mock(return_value=[{'id': 'doc-id', 'title': 'Trips <script>data</script>'}]))
    assert post(client, '/settings/alias-add', token, alias=' Travel ',
                destination='https://docs.google.com/document/d/doc-id/edit?usp=sharing').status_code == 303
    assert doc_nicknames.read_aliases(app.notes) == {'inbox': None, 'travel': 'doc-id'}
    page = client.get('/dashboard/settings')
    assert 'https://docs.google.com/document/d/doc-id/edit' in page.text
    assert 'Trips &lt;script&gt;data&lt;/script&gt;' in page.text
    assert '<summary>Advanced: aliases JSON</summary>' in page.text
    assert 'name="destination"' in page.text
    assert post(client, '/settings/alias-add', token, alias='TRAVEL', destination='other-id').status_code == 303
    assert doc_nicknames.read_aliases(app.notes)['travel'] == 'other-id'
    assert post(client, '/settings/alias-remove', token, alias='travel').status_code == 303
    assert doc_nicknames.read_aliases(app.notes) == {'inbox': None}


@pytest.mark.parametrize('section', ['alias-add', 'alias-remove'])
def test_forms_require_csrf_and_same_origin(client, section):
    token = login(client)
    path = '/dashboard/settings/' + section
    before = app.notes.aliases_path.read_bytes()
    fields = {'alias': 'work', 'destination': 'work-id'}
    assert client.post(path, data=fields).status_code == 403
    assert client.post(path, data={**fields, 'csrf': 'wrong'}).status_code == 403
    assert client.post(path, data={**fields, 'csrf': token}, headers={'Origin': 'https://evil.test'}).status_code == 403
    assert client.post(path, data={**fields, 'csrf': token}, headers={'Sec-Fetch-Site': 'cross-site'}).status_code == 403
    assert app.notes.aliases_path.read_bytes() == before


@pytest.mark.parametrize('alias,destination', [('inbox', 'id'), ('x'*61, 'id'), (' ', 'id'),
                                            ('work', 'https://evil.test/document/d/id/edit')])
def test_form_validation_does_not_write(client, alias, destination):
    token = login(client)
    before = app.notes.aliases_path.read_bytes()
    assert post(client, '/settings/alias-add', token, alias=alias, destination=destination).status_code == 422
    assert app.notes.aliases_path.read_bytes() == before


def test_advanced_validation_and_reserved_inbox(client):
    token = login(client)
    for value in [{'x'*61: 'id'}, {'x': 'id', ' X ': 'other'}, {'inbox': 'arbitrary'}, {'x': None}]:
        assert post(client, '/settings/aliases', token, json=json.dumps(value)).status_code == 422
    assert post(client, '/settings/alias-remove', token, alias='inbox').status_code == 422
    assert post(client, '/settings/aliases', token, json='{"inbox":null," WORK ":"id"}').status_code == 303
    assert doc_nicknames.read_aliases(app.notes) == {'inbox': None, 'work': 'id'}


def test_unavailable_google_and_malformed_file_remain_editable(client):
    token = login(client)
    assert post(client, '/settings/alias-add', token, alias='work', destination='work-id').status_code == 303
    assert 'Title unavailable' in client.get('/dashboard/settings').text
    app.notes.aliases_path.write_text('{broken')
    page = client.get('/dashboard/settings')
    assert page.status_code == 200 and '{broken' in page.text
    assert post(client, '/settings/aliases', token, json='{"inbox":null}').status_code == 303
