import json
from unittest.mock import Mock

import pytest
from google.genai import models

from memory_eval.corpus import specifications
from memory_eval.metrics import aggregate, contains, grade
from memory_eval.provider import budget


def test_corpus_has_independent_oracle_and_correction_forget_cases():
    personas = list(specifications())
    assert len(personas) == 10
    assert all(8 <= len(p['seeds']) <= 15 for p in personas)
    assert all({q['kind'] for q in p['probes']} == {'recall', 'correction', 'forget', 'unknown'} for p in personas)
    assert len({p['id'] for p in personas}) == 10


def test_scoring_does_not_confuse_ana_ann_or_reward_stale_answers():
    probe = {'kind': 'correction', 'expected': ['Ana'], 'forbidden': ['Ann']}
    assert grade(probe, 'Ana')['correct']
    assert not grade(probe, 'Ana, previously Ann')['correct']
    assert not contains('Annabelle', 'Ann')
    assert not grade({'kind': 'unknown', 'expected': [], 'forbidden': []}, 'Tolkien')['correct']
    assert grade({'kind': 'forget', 'expected': [], 'forbidden': ['pottery']}, 'pottery')['resurrected']
    assert aggregate([])['recall_accuracy'] == {'count': 0, 'total': 0}


def test_budget_persists_and_counts_failures(monkeypatch, tmp_path):
    request = Mock(side_effect=ValueError('synthetic failure'))
    monkeypatch.setattr(models.Models, 'generate_content', request)
    ledger = tmp_path / 'ledger.json'
    with budget(ledger, limit=1, interval=0):
        with pytest.raises(ValueError):
            models.Models.generate_content(None, model='fake')
    assert json.loads(ledger.read_text())['failures'] == 1
    with budget(ledger, limit=1, interval=0):
        with pytest.raises(RuntimeError, match='exhausted'):
            models.Models.generate_content(None, model='fake')
    assert request.call_count == 1


def test_optional_eval_timeout_does_not_change_production_config(monkeypatch, tmp_path):
    from google.genai import types
    from types import SimpleNamespace
    response = SimpleNamespace(text='ok', candidates=[])
    request = Mock(return_value=response)
    monkeypatch.setattr(models.Models, 'generate_content', request)
    monkeypatch.setenv('MEMORY_EVAL_TIMEOUT_MS', '60000')
    original = types.GenerateContentConfig(http_options=types.HttpOptions(timeout=20000))
    with budget(tmp_path / 'ledger.json', interval=0):
        assert models.Models.generate_content(None, model='fake', config=original) is response
    assert request.call_args.kwargs['config'].http_options.timeout == 60000
    assert original.http_options.timeout == 20000


def test_provider_failure_is_missing_coverage_not_a_correct_abstention():
    probe = {'kind': 'unknown', 'expected': [], 'forbidden': []}
    missing = {'kind': 'unknown', **grade(probe, None)}
    assert not missing['available'] and not missing['correct']
    result = aggregate([missing])
    assert result['answer_coverage'] == {'count': 0, 'total': 1}
    assert result['hallucination_rate'] == {'count': 0, 'total': 0}
