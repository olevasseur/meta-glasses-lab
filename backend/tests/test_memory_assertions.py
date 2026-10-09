import json
from pathlib import Path
from unittest.mock import Mock

import pytest
from pydantic import ValidationError

import memory
from memory import Memory
from memory_assertions import ANSWER_GUIDANCE, questions_only
from memory_summary import Fact, Summary, response_schema
from memory_eval.adversarial import score_answer, storage_score


def finish(store, monkeypatch, identifier, facts, timestamp='2026-10-08T12:00:00Z'):
    store.ingest(identifier, [{'seq': 0, 'speaker': 'user', 'text': 'Synthetic evidence.', 'timestamp': timestamp}])
    monkeypatch.setattr(memory, 'summarize', Mock(return_value=Summary(summary='', facts=facts)))
    return store.end(identifier)


@pytest.mark.parametrize('metadata', [
    {'modality': 'hypothetical'}, {'modality': 'wish'}, {'modality': 'reported'},
    {'subject': 'other person'}, {'durable': False}, {'confidence': 0.74},
])
def test_nonasserted_candidates_never_become_durable_facts(tmp_path, monkeypatch, metadata):
    store = Memory(tmp_path / 'm.db')
    result = finish(store, monkeypatch, 's', [Fact(key='home', text='Lives in Paris.', **metadata)])
    assert result['facts'] == []
    with store.db() as db:
        assert store._facts(db) == []


def test_question_only_session_cannot_promote_model_inferred_ownership(tmp_path, monkeypatch):
    store = Memory(tmp_path / 'questions.db')
    store.ingest('s', [{'seq': 0, 'speaker': 'user',
                        'text': 'How expensive is owning a horse named Clover?',
                        'timestamp': '2026-10-08T12:00:00Z'}])
    monkeypatch.setattr(memory, 'summarize', Mock(return_value=Summary(
        summary='The user owns a horse named Clover.',
        facts=[Fact(key='pet', text='Owns a horse named Clover.')])))
    result = store.end('s')
    assert result['facts'] == [] and result['summary'] == ''
    assert store.context('base') == 'base'
    assert {m['kind'] for m in store.recall('Clover')['matches']} == {'transcript'}


@pytest.mark.parametrize('text, expected', [
    ('Do vegans need supplements? What about iron?', True),
    ('I own a dog. What food should I buy?', False),
    ('I live in Toronto', False),
    ('', False),
])
def test_question_guard_keeps_explicit_statement_sessions(text, expected):
    assert bool(questions_only([{'speaker': 'user', 'text': text},
                               {'speaker': 'model', 'text': 'You own a dog.'}])) is expected


def test_plan_has_review_date_and_cannot_supersede_current_fact(tmp_path, monkeypatch):
    store = Memory(tmp_path / 'm.db')
    finish(store, monkeypatch, 'home', [Fact(key='home', text='Lives in Toronto.')])
    finish(store, monkeypatch, 'plan', [Fact(key='home', text='Might move to Paris.', modality='plan', supersedes=['home'])])
    with store.db() as db:
        facts = {f['key']: f for f in store._facts(db)}
    assert facts['home']['text'] == 'Lives in Toronto.'
    assert facts['plan home']['modality'] == 'plan'
    assert facts['plan home']['review_date'] == '2026-11-07'
    assert 'completion unknown' in store.context('base')
    match = store.recall('Paris')['matches'][0]
    assert match['modality'] == 'plan' and match['subject'] == 'user'
    assert match['review_date'] == '2026-11-07' and match['confidence'] == 1
    finish(store, monkeypatch, 'done', [Fact(key='home', text='Lives in Paris.', supersedes=['plan home'])],
           '2026-11-08T12:00:00Z')
    with store.db() as db:
        assert [f['key'] for f in store._facts(db)] == ['home']


def test_high_confidence_does_not_turn_hypothetical_into_fact(tmp_path, monkeypatch):
    store = Memory(tmp_path / 'm.db')
    finish(store, monkeypatch, 's', [Fact(key='pet', text='Would like a dog.', modality='hypothetical', confidence=1)])
    assert store.context('base') == 'base'


def test_negation_and_relationship_fact_survive(tmp_path, monkeypatch):
    store = Memory(tmp_path / 'm.db')
    facts = [Fact(key='drink', text='No longer drinks coffee; prefers tea.'),
             Fact(key='sister.name', text='Sister is Ana.')]
    finish(store, monkeypatch, 's', facts)
    with store.db() as db:
        assert {f['text'] for f in store._facts(db)} == {f.text for f in facts}


def test_explicit_notes_are_not_silently_certified_as_facts(tmp_path):
    store = Memory(tmp_path / 'm.db')
    store.remember('If I moved to Paris, I would cycle.', 'home')
    with store.db() as db:
        assert store._facts(db)[0]['modality'] == 'reported'
    assert 'modality=reported' in store.context('base')
    assert store.recall('Paris')['answer_guidance'] == ANSWER_GUIDANCE


def test_raw_recall_carries_assertion_guidance_even_without_profile(tmp_path):
    store = Memory(tmp_path / 'm.db')
    store.ingest('s', [{'seq': 0, 'speaker': 'user', 'text': 'If I moved to Paris...',
                       'timestamp': '2026-10-08T12:00:00Z'}])
    result = store.recall('Paris')
    assert result['matches'][0]['historical']
    assert result['matches'][0]['speaker'] == 'user'
    assert 'not proof' in result['answer_guidance']
    assert 'review date' in result['answer_guidance']


@pytest.mark.parametrize('fields', [{'modality': 'certain'}, {'subject': 'assistant'}, {'confidence': 2}])
def test_schema_validates_assertion_metadata(fields):
    with pytest.raises(ValidationError):
        Fact(key='k', text='v', **fields)
    assert set(response_schema()['$defs']['Fact']['properties']['modality']['enum']) == {
        'fact', 'plan', 'wish', 'hypothetical', 'reported'}


def test_adversarial_oracle_coverage_and_scoring():
    cases = json.loads((Path(__file__).resolve().parents[2] / 'scripts/memory_eval/adversarial.json').read_text())
    assert len(cases) >= 40 and len({c['id'] for c in cases}) == len(cases)
    assert len({c['category'] for c in cases}) == 10
    case = cases[0]
    assert not score_answer(case, None)['available']
    assert score_answer(case, {'current': 'UNKNOWN', 'recollection': 'You mentioned a hypothetical move.'})['current_correct']
    assert not score_answer(case, {'current': 'Paris', 'recollection': 'You live in Paris.'})['current_correct']
    assert not score_answer(case, {'current': 'Paris', 'recollection': 'You live in Paris.'})['recollection_correct']
    assert not storage_score(case, [{'text': 'Lives in Paris.', 'modality': 'fact'}])
    plan = next(c for c in cases if c['storage'] == 'plan')
    assert not storage_score(plan, [{'text': plan['topic'], 'modality': 'fact'}])


def test_new_plan_cannot_follow_completed_plan_alias(tmp_path, monkeypatch):
    store = Memory(tmp_path / 'm.db')
    finish(store, monkeypatch, 'plan', [Fact(key='home', text='Plans to move to Paris.', modality='plan')])
    finish(store, monkeypatch, 'done', [Fact(key='home', text='Lives in Paris.', supersedes=['plan home'])],
           '2026-11-08T12:00:00Z')
    finish(store, monkeypatch, 'next', [Fact(key='home', text='May move to Rome.', modality='plan')],
           '2026-12-08T12:00:00Z')
    with store.db() as db:
        facts = {f['key']: f for f in store._facts(db)}
    assert facts['home']['text'] == 'Lives in Paris.'
    assert facts['plan home']['text'] == 'May move to Rome.'


def test_legacy_rows_migrate_conservatively(tmp_path):
    import sqlite3
    path = tmp_path / 'legacy.db'
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE facts (id INTEGER PRIMARY KEY, key TEXT UNIQUE, text TEXT, normalized TEXT UNIQUE, updated_at TEXT)')
        db.execute("INSERT INTO facts VALUES (1,'home','Might move to Paris.','might move to paris','2026-01-01T00:00:00Z')")
    store = Memory(path)
    for _ in range(2):
        with store.db() as db:
            fact, = store._facts(db)
            assert fact['modality'] == 'reported' and fact['review_date'] is None
            assert fact['text'] == 'Might move to Paris.'


def test_plan_duplicate_of_legacy_note_does_not_crash_or_upgrade_it(tmp_path, monkeypatch):
    store = Memory(tmp_path / 'm.db')
    store.remember('Might move to Paris.', 'home')
    finish(store, monkeypatch, 'plan', [Fact(key='home', text='Might move to Paris.', modality='plan')])
    with store.db() as db:
        row, = store._facts(db)
        assert row['modality'] == 'reported' and row['key'] == 'home'


def test_plan_correction_can_merge_old_plan_keys_only(tmp_path, monkeypatch):
    store = Memory(tmp_path / 'm.db')
    finish(store, monkeypatch, 'home', [Fact(key='city', text='Lives in Toronto.')])
    finish(store, monkeypatch, 'first', [Fact(key='city', text='Plans a move to Paris.', modality='plan')])
    finish(store, monkeypatch, 'changed', [Fact(key='destination', text='Plans a move to Rome.', modality='plan',
                                             supersedes=['city'])], '2026-10-09T12:00:00Z')
    with store.db() as db:
        facts = {f['key']: f for f in store._facts(db)}
    assert set(facts) == {'city', 'plan destination'}
    assert facts['city']['text'] == 'Lives in Toronto.'
    assert facts['plan destination']['text'] == 'Plans a move to Rome.'
    finish(store, monkeypatch, 'delayed', [Fact(key='city', text='Plans a move to Paris.', modality='plan')])
    with store.db() as db:
        assert {f['key'] for f in store._facts(db)} == {'city', 'plan destination'}


def test_provider_cannot_omit_metadata_and_inherit_fact_defaults():
    value = {'summary': '', 'facts': [Fact(key='home', text='Lives in Paris.').model_dump()]}
    for field in ('modality', 'subject', 'durable', 'confidence'):
        missing = json.loads(json.dumps(value))
        del missing['facts'][0][field]
        with pytest.raises(ValidationError, match='assertion metadata'):
            Summary.model_validate_json(json.dumps(missing))
    assert Summary.model_validate_json(json.dumps(value)).facts[0].modality == 'fact'


def test_remember_declaration_preserves_assertion_status():
    from tools import REMEMBER
    assert 'hypothetical' in REMEMBER.description
    assert 'separate key for a plan' in REMEMBER.description
    assert 'modality' in REMEMBER.parameters.properties['fact'].description


def test_quota_period_does_not_expose_provider_error_details():
    from google.genai.errors import APIError
    from memory_eval.provider import quota_period
    assert quota_period(APIError(429, {'error': {'message': 'private', 'details': [
        {'violations': [{'quotaId': 'GenerateRequestsPerDayPerProjectPerModel-FreeTier',
                         'quotaDimensions': {'project': 'private'}}]}]}})) == 'daily'
    assert quota_period(APIError(429, {'error': {'details': [
        {'violations': [{'quotaId': 'GenerateRequestsPerMinutePerProjectPerModel-FreeTier'}]}]}})) == 'minute'
    assert quota_period(APIError(429, {'error': {'message': 'private'}})) == 'unknown'


def test_known_daily_quota_stops_without_more_provider_attempts(monkeypatch, tmp_path):
    from google.genai import models
    from google.genai.errors import APIError
    from memory_eval.provider import budget, DailyQuotaExceeded
    failure = APIError(429, {'error': {'message': 'private', 'details': [
        {'violations': [{'quotaId': 'GenerateRequestsPerDayPerProjectPerModel-FreeTier'}]}]}})
    request = Mock(side_effect=failure)
    monkeypatch.setattr(models.Models, 'generate_content', request)
    ledger = tmp_path / 'budget.json'
    with budget(ledger, limit=1, interval=0):
        with pytest.raises(DailyQuotaExceeded):
            models.Models.generate_content(None, model='fake')
    assert request.call_count == 1
    stored = ledger.read_text()
    assert json.loads(stored)['last_error']['quota_period'] == 'daily'
    assert 'private' not in stored and 'quotaId' not in stored


def test_eval_retry_selection_does_not_cherry_pick_wrong_answers():
    from memory_eval.__main__ import incomplete_evaluation
    wrong = {'probes': [{'correct': False}], 'summary_failures': [],
             'probe_errors': {'profile': None, 'retrieval': None}}
    assert not incomplete_evaluation(wrong)
    assert incomplete_evaluation({**wrong, 'probe_errors': {'profile': 'ServerError'}})
    assert incomplete_evaluation({**wrong, 'summary_failures': [{'session': 3, 'error_type': 'GeminiUnavailable'}]})


def test_retry_keeps_available_answers_even_when_wrong_and_rechecks_changed_memory():
    from memory_eval.__main__ import reusable_answers, measured_answers
    prior = {'probe_errors': {'profile': None, 'retrieval': 'ServerError'},
             'probes': [{'id': 'city', 'answer': 'Wrong City', 'retrieval_answer': None}]}
    cached = reusable_answers(prior, 'profile', changed=False)
    assert cached == {'city': 'Wrong City'}
    assert measured_answers('', [], cached=cached) == (cached, None)
    assert reusable_answers(prior, 'retrieval', changed=False) is None
    assert reusable_answers(prior, 'profile', changed=True) is None


def test_every_adversarial_topic_exposes_the_user_statement(tmp_path):
    cases = json.loads((Path(__file__).resolve().parents[2] / 'scripts/memory_eval/adversarial.json').read_text())
    for case in cases:
        store = Memory(tmp_path / (case['id'] + '.db'))
        store.ingest('s', [{'seq': 0, 'speaker': 'user', 'text': case['user'],
                           'timestamp': '2026-10-08T12:00:00Z'}])
        assert any(m.get('speaker') == 'user' for m in store.recall(case['topic'])['matches']), case['id']


@pytest.mark.parametrize('alias', [False, True])
def test_forgotten_plan_key_blocks_paraphrases_and_plan_aliases(tmp_path, monkeypatch, alias):
    store = Memory(tmp_path / 'm.db')
    finish(store, monkeypatch, 'first', [Fact(key='user.home_city', text='Might move to Paris.', modality='plan')])
    preview = store.forget('Paris')
    store.forget('Paris', preview['confirmation_token'])
    candidate = Fact(key='user.relocation' if alias else 'user.home_city',
                     text='Relocation is under consideration.', modality='plan',
                     supersedes=['user.home_city'] if alias else [])
    assert finish(store, monkeypatch, 'later', [candidate], '2026-10-09T12:00:00Z')['facts'] == []
