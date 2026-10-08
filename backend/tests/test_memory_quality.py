import sqlite3
from unittest.mock import Mock

import pytest

import memory
from memory import Memory
from memory_quality import PROFILE_BYTES, search_score
from memory_summary import Fact, Summary


@pytest.fixture
def store(tmp_path):
    return Memory(tmp_path / 'memory.db')


def finish(store, monkeypatch, session, timestamp, fact):
    store.ingest(session, [{'seq': 0, 'speaker': 'user', 'text': fact.text, 'timestamp': timestamp}])
    monkeypatch.setattr(memory, 'summarize', Mock(return_value=Summary(summary=fact.text, facts=[fact])))
    return store.end(session)


def test_migrates_old_database_without_losing_facts(tmp_path):
    path = tmp_path / 'old.db'
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE facts (id INTEGER PRIMARY KEY, key TEXT UNIQUE NOT NULL, text TEXT NOT NULL, normalized TEXT UNIQUE NOT NULL, updated_at TEXT NOT NULL)')
        db.execute("INSERT INTO facts VALUES (1, 'sister', 'Ana', 'ana', '2026-01-01T00:00:00+00:00')")
    store = Memory(path)
    for _ in range(2):
        with store.db() as db:
            fact, = store._facts(db)
            assert fact['text'] == 'Ana' and fact['confidence'] == 1
            assert fact['observed_at'] == fact['updated_at']


def test_delayed_session_cannot_undo_later_correction(store, monkeypatch):
    finish(store, monkeypatch, 'new', '2026-02-01T00:00:00Z', Fact(key='sister.name', text='Sister is Ana.'))
    assert finish(store, monkeypatch, 'old', '2026-01-01T00:00:00Z', Fact(key='sister.name', text='Sister is Ann.'))['facts'] == []
    with store.db() as db:
        assert [f['text'] for f in store._facts(db)] == ['Sister is Ana.']
    assert store.recall('sister name')['matches'][0]['text'] == 'Sister is Ana.'


def test_supersedes_alias_but_preserves_other_people(store, monkeypatch):
    finish(store, monkeypatch, 'one', '2026-01-01T00:00:00Z', Fact(key='family.sister', text='Sister is Ann.'))
    store.remember('Brother is Arun.', 'family.brother')
    finish(store, monkeypatch, 'two', '2026-02-01T00:00:00Z', Fact(
        key='sister.name', text='Sister is Ana.', supersedes=['family.sister']))
    with store.db() as db:
        assert {f['text'] for f in store._facts(db)} == {'Sister is Ana.', 'Brother is Arun.'}
    finish(store, monkeypatch, 'three', '2026-01-10T00:00:00Z', Fact(
        key='yet.another.sister', text='Sister is Ann.', supersedes=['sister.name']))
    with store.db() as db:
        assert len(store._facts(db)) == 2


def test_low_confidence_and_forgotten_alias_cannot_return(store, monkeypatch):
    store.remember('Hobby project is pottery.', 'hobby')
    preview = store.forget('hobby project')
    store.forget('hobby project', preview['confirmation_token'])
    finish(store, monkeypatch, 's', '2026-10-09T00:00:00Z', Fact(
        key='craft', text='A ceramic pastime.', supersedes=['hobby']))
    finish(store, monkeypatch, 'uncertain', '2026-10-09T00:00:00Z', Fact(
        key='author', text='Favourite author is Tolkien.', confidence=0.5))
    with store.db() as db:
        assert store._facts(db) == []


def test_retrieval_natural_questions_typos_and_current_fact_precedence(store, monkeypatch):
    store.remember('Dog is named Maple.', 'pet.name')
    assert store.recall("What is my dog's name?")['matches'][0]['text'] == 'Dog is named Maple.'
    assert store.recall('Mapel')['matches']
    assert not store.recall('Who is my favourite author?')['matches']
    finish(store, monkeypatch, 's', '2026-01-01T00:00:00Z', Fact(key='sister', text='Sister Ann.'))
    store.remember('Sister Ana.', 'sister')
    matches = store.recall('sister')['matches']
    assert matches[0]['text'] == 'Sister Ana.' and not matches[0]['historical']
    assert any(m['historical'] for m in matches[1:])
    assert search_score('what is my name', 'the user prefers coffee') == 0


def test_profile_budget_independent_of_app_prompt_and_prioritizes_identity(store):
    with store.db() as db:
        store._upsert(db, 'sister', 'My sister is Ana.', priority=3, observed_at='2026-01-01T00:00:00Z')
        for i in range(120):
            store._upsert(db, str(i), f'Book {i}: ' + '猫' * 150, priority=1,
                          observed_at='2026-02-01T00:00:00Z')
    base = 'System instructions.' * 600
    context = store.context(base)
    assert context.startswith(base)
    assert 'My sister is Ana.' in context[len(base):]
    assert len(context.encode()) - len(base.encode()) <= PROFILE_BYTES


def test_alias_survives_repeated_updates_and_is_forgotten_too(store, monkeypatch):
    finish(store, monkeypatch, 'one', '2026-01-01T00:00:00Z', Fact(key='old.key', text='Sister Ann.'))
    finish(store, monkeypatch, 'two', '2026-02-01T00:00:00Z', Fact(key='new.key', text='Sister Ana.', supersedes=['old.key']))
    finish(store, monkeypatch, 'three', '2026-03-01T00:00:00Z', Fact(key='new.key', text='Sister Ana Maria.'))
    assert not finish(store, monkeypatch, 'late', '2026-01-10T00:00:00Z', Fact(key='old.key', text='Sister Ann.'))['facts']
    preview = store.forget('sister')
    store.forget('sister', preview['confirmation_token'])
    assert not finish(store, monkeypatch, 'forgotten', '2026-04-01T00:00:00Z', Fact(key='old.key', text='Relative called Ann.'))['facts']


def test_same_text_dedupe_does_not_regress_timestamp(store, monkeypatch):
    finish(store, monkeypatch, 'new', '2026-02-01T00:00:00Z', Fact(key='home', text='Home is Toronto.'))
    finish(store, monkeypatch, 'old', '2026-01-01T00:00:00Z', Fact(key='home.city', text='Home is Toronto.'))
    finish(store, monkeypatch, 'middle', '2026-01-10T00:00:00Z', Fact(key='home', text='Home is Montreal.'))
    with store.db() as db:
        assert [f['text'] for f in store._facts(db)] == ['Home is Toronto.']


def test_explicit_remember_restores_forgotten_key_alias_group(store, monkeypatch):
    finish(store, monkeypatch, 'one', '2026-01-01T00:00:00Z', Fact(key='old', text='Sister Ann.'))
    finish(store, monkeypatch, 'two', '2026-02-01T00:00:00Z', Fact(key='new', text='Sister Ana.', supersedes=['old']))
    preview = store.forget('sister')
    store.forget('sister', preview['confirmation_token'])
    store.remember('Sister Ana Maria.', 'new')
    assert 'Ana Maria' in store.context('base')
    assert store.recall('sister')['matches']


def test_bare_value_extractions_remain_searchable_and_forgettable(store):
    store.remember('Maple', 'user.dog_name')
    store.remember('peppermint tea', 'user.beverage_preference')
    store.remember('Copper Comet', 'user.hobby_project.name')
    assert store.recall("What is my dog's name?")['matches'][0]['text'] == 'Maple'
    assert store.recall('Which drink do I prefer now?')['matches'][0]['text'] == 'peppermint tea'
    assert 'user dog_name: Maple' in store.context('base')
    preview = store.forget('hobby project')
    assert preview['status'] == 'needs_confirmation'
    store.forget('hobby project', preview['confirmation_token'])
    assert 'Copper Comet' not in store.context('base')


def test_forget_only_session_can_close_with_empty_structured_summary(store, monkeypatch):
    store.ingest('forget', [{'seq': 0, 'speaker': 'user', 'text': 'Forget my hobby.',
                            'timestamp': '2026-02-01T00:00:00Z'}])
    monkeypatch.setattr(memory, 'summarize', Mock(return_value=Summary(summary='', facts=[])))
    assert store.end('forget') == {'status': 'ok', 'summary': '', 'facts': []}
    assert store.end('forget')['already_ended']


@pytest.mark.parametrize('change', [
    {'confidence': 1.1}, {'confidence': -0.1}, {'priority': 4}, {'priority': 0},
    {'key': 'x' * 121}, {'text': 'x' * 501}, {'supersedes': ['x'] * 11},
    {'supersedes': ['x' * 121]},
])
def test_simplified_provider_schema_keeps_local_fact_bounds(change):
    from pydantic import ValidationError
    from memory_summary import response_schema
    wire = response_schema()['$defs']['Fact']
    assert set(wire['required']) == {'key', 'text', 'confidence', 'priority', 'supersedes'}
    with pytest.raises(ValidationError):
        Fact(**{'key': 'name', 'text': 'Sister Ana.', **change})
