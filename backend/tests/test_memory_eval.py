import json
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from google.genai import models

from memory_eval.corpus import specifications
from memory_eval.metrics import aggregate, contains, grade
from memory_eval.provider import budget
from usage import meter


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


@pytest.mark.parametrize('cap', ['text_calls', 'total_tokens', 'model'])
def test_shared_quota_refuses_before_provider_access(monkeypatch, tmp_path, cap):
    if cap == 'model':
        quota = tmp_path / 'quota.json'
        quota.write_text(json.dumps({'fake': {'rpd': 0}}))
        monkeypatch.setenv('GEMINI_QUOTA_FILE', str(quota))
    else:
        monkeypatch.setenv('USAGE_' + cap.upper(), '1')
    request = Mock()
    monkeypatch.setattr(models.Models, 'generate_content', request)
    ledger = tmp_path / 'ledger.json'
    with budget(ledger, interval=0) as counts:
        with pytest.raises(ValueError, match='Evaluation refused'):
            models.Models.generate_content(None, model='models/fake')
        assert counts['text_attempts'] == 0
    request.assert_not_called()
    assert not ledger.exists()
    assert meter.snapshot()['today']['text_calls'] == 0


def test_shared_meter_counts_direct_and_summary_fallback_attempts_once(monkeypatch, tmp_path):
    import gemini_resilience
    from google.genai import types

    response = SimpleNamespace(text='ok', candidates=[], usage_metadata=SimpleNamespace(
        prompt_token_count=20, candidates_token_count=3, total_token_count=23))
    request = Mock(side_effect=[response, TimeoutError(), response])
    monkeypatch.setattr(models.Models, 'generate_content', request)
    monkeypatch.setenv('GEMINI_TEXT_MODELS', 'primary,fallback')
    client = SimpleNamespace(models=SimpleNamespace(generate_content=lambda **kw:
        models.Models.generate_content(None, **kw)))
    with budget(tmp_path / 'ledger.json', interval=0) as counts:
        client.models.generate_content(model='probe')
        assert gemini_resilience.generate_content(client, model='primary', clock=lambda: 0) is response
    assert gemini_resilience.meter is meter
    assert counts['text_attempts'] == 3 and counts['failures'] == 1
    report = meter.snapshot()
    assert report['today']['text_calls'] == 3
    assert report['today']['total_tokens'] == 46
    assert {(r['model'], r['calls'], r['failed']) for r in report['rows']} == {
        ('probe', 1, 0), ('primary', 1, 1), ('fallback', 1, 0)}
    with meter.database() as db:
        assert db.execute("SELECT amount FROM eval_budget WHERE metric='text_calls'").fetchone()[0] == 3
    assert all(call.kwargs['config'].http_options.retry_options.attempts == 1
               for call in request.call_args_list)
    # Production accounting is restored when the evaluation context exits.
    request.side_effect = None
    request.return_value = response
    gemini_resilience.generate_content(client, model='primary', clock=lambda: 0,
                                      config=types.GenerateContentConfig())
    assert meter.snapshot()['today']['text_calls'] == 4


def test_offline_evaluation_does_not_reserve_provider_budget(tmp_path):
    with budget(tmp_path / 'ledger.json', interval=0) as counts:
        assert counts['text_attempts'] == 0
    with meter.database() as db:
        assert db.execute('SELECT COUNT(*) FROM eval_budget').fetchone()[0] == 0
