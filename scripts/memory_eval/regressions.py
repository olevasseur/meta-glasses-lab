"""Deterministic before/after storage checks; explicitly not LLM quality scores."""
from pathlib import Path
from unittest.mock import patch

from memory import Memory
from memory_summary import Fact, Summary


def run(directory: Path):
    store = Memory(directory / 'regressions.db')
    checks = {}
    store.remember('Maple', 'user.dog_name')
    checks['natural_question_retrieves_bare_value'] = any(
        row['text'] == 'Maple' for row in store.recall("What is my dog's name?")['matches'])
    checks['profile_retains_attribute_for_bare_value'] = 'dog_name' in store.context('base')
    store.remember('Copper Comet', 'user.hobby_project.name')
    proposal = store.forget('hobby project')
    checks['forget_finds_keyed_bare_value'] = proposal['status'] == 'needs_confirmation'
    if proposal['status'] == 'needs_confirmation':
        store.forget('hobby project', proposal['confirmation_token'])
    checks['forgotten_value_absent_from_profile'] = 'Copper Comet' not in store.context('base')
    for identifier, stamp, value in [('new', '2026-02-01T00:00:00Z', 'Ana'),
                                     ('delayed', '2026-01-01T00:00:00Z', 'Ann')]:
        text = f'Sister is {value}.'
        store.ingest(identifier, [{'seq': 0, 'speaker': 'user', 'text': text, 'timestamp': stamp}])
        summary = Summary(summary=text, facts=[Fact(key='user.sister.name', text=text)])
        with patch('memory.summarize', return_value=summary):
            store.end(identifier)
    with store.db() as db:
        checks['latest_evidence_wins_over_upload_order'] = any(f['text'] == 'Sister is Ana.' for f in store._facts(db))
    return {'mode': 'deterministic summary replay, no provider', 'checks': checks,
            'passed': sum(checks.values()), 'total': len(checks)}
