"""Forty independently authored assertion tests, using the production memory pipeline."""
import hashlib
import json
import re
from pathlib import Path

from memory_eval.metrics import contains, grade
from memory_eval.provider import generate


def score_answer(case, answer):
    if not isinstance(answer, dict):
        return {'available': False, 'current_correct': False, 'recollection_correct': False}
    current = answer.get('current')
    correct = grade({'kind': 'recall', **case}, current)['correct']
    if case['current_negations'] and isinstance(current, str):
        correct = any(contains(current, term) for term in case['current_negations'])
    recollection = answer.get('recollection', '')
    return {'available': isinstance(current, str), 'current_correct': correct,
            'recollection_correct': contains(recollection, case['topic']) and any(re.search(r'\b' + re.escape(marker.casefold()), recollection.casefold())
                                       for marker in case['recollection_markers'])}


def storage_score(case, facts):
    # Relationship identity remains useful user information; it must not import others' attributes.
    relevant = [f for f in facts if contains(f['text'], case['topic'])]
    if case['storage'] == 'no_fact':
        return not relevant
    modality = case['storage']
    return bool(relevant) and all(f.get('modality', 'fact') == modality for f in relevant) and (
        modality != 'plan' or all(f.get('review_date') for f in relevant))


def run(corpus_path, output, state, harness):
    cases = json.loads(corpus_path.read_text())
    digest = hashlib.sha256(corpus_path.read_bytes()).hexdigest()
    hashes = {m.__name__: hashlib.sha256(Path(m.__file__).read_bytes()).hexdigest()
              for m in (harness.memory, harness.memory_summary)}
    result = json.loads(output.read_text()) if output.exists() else {
        'corpus_sha256': digest, 'source_sha256': hashes,
        'oracle_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'support_sha256': {name: hashlib.sha256(Path(harness.sys.modules[name].__file__).read_bytes()).hexdigest()
                          for name in ('memory_quality', 'memory_assertions') if name in harness.sys.modules},
        'model': harness.os.getenv('MEMORY_EVAL_MODEL', 'gemini-3.5-flash-lite'),
        'live_sessions': 0, 'cases': []}
    assert result['corpus_sha256'] == digest and result['source_sha256'] == hashes
    assert result['oracle_sha256'] == hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    assert result['model'] == harness.os.getenv('MEMORY_EVAL_MODEL', 'gemini-3.5-flash-lite')
    schema = {'type': 'object', 'properties': {'current': {'type': 'string'},
              'recollection': {'type': 'string'}}, 'required': ['current', 'recollection']}
    for case in cases[len(result['cases']):]:
        store = harness.Memory(state / (case['id'] + '.db'))
        error = None
        try:
            # 2026-10-08 in the shared harness's January epoch; plan reviews are not overdue.
            harness.close_session(store, case['id'], {'user': case['user'], 'model': 'Understood.'}, 280)
        except (harness.GeminiUnavailable, harness.ValidationError) as exc:
            error = type(exc).__name__
        system = harness.instruction(store)
        recall = store.recall(case['topic'])
        answers = {}
        for mode in ('recall',):
            payload = {'current_question': case['query'],
                       'recollection_question': f'What did I actually say about {case["topic"]}?',
                       'format': 'Answer current with only the current factual answer; UNKNOWN if not established. '
                                 'For a yes/no completion question say no if explicitly not done. '
                                 'Answer recollection naturally in one sentence, preserving what was said. '
                                 'Do not guess or conflate a mention with an assertion.'}
            if mode == 'recall':
                payload['recall_tool_results'] = recall
            try:
                answer = harness.retry(lambda: generate(json.dumps(payload), schema, system))
            except Exception as exc:
                if not (harness.transient(exc) or isinstance(exc, (harness.GeminiUnavailable, json.JSONDecodeError))):
                    raise
                answer = None
            answers[mode] = {'answer': answer, **score_answer(case, answer)}
        with store.db() as db:
            facts = store._facts(db)
        result['cases'].append({'id': case['id'], 'category': case['category'], 'facts': facts,
            'summary_error': error, 'storage_correct': error is None and storage_score(case, facts),
            'recall_results': recall, **answers})
        rows = result['cases']
        result['metrics'] = {'storage': sum(r['storage_correct'] for r in rows),
            'summary_completion': sum(r['summary_error'] is None for r in rows), 'total': len(rows),
            **{mode: {key: sum(r[mode][key] for r in rows)
                      for key in ('available', 'current_correct', 'recollection_correct')}
               for mode in ('recall',)}}
        harness.save(output, result)
        print(case['id'], json.dumps(result['metrics']), flush=True)
