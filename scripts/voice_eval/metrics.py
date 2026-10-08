"""Explicit, deterministic tool/argument scoring; no model-as-judge."""
import re
import statistics


def matches(actual, expected):
    if isinstance(expected,dict):
        if 'any_of' in expected:
            return any(matches(actual,item) for item in expected['any_of'])
        if 'contains' in expected:
            return isinstance(actual,str) and expected['contains'].casefold() in actual.casefold()
        if 'nonempty' in expected:
            return isinstance(actual,str) and bool(actual.strip())
        return isinstance(actual,dict) and all(k in actual and matches(actual[k],v) for k,v in expected.items())
    if isinstance(expected,str):
        return isinstance(actual,str) and re.sub(r'\s+',' ',actual.strip()).casefold()==expected.casefold()
    return type(actual) is type(expected) and actual==expected or (type(actual) in (int,float) and type(expected) in (int,float) and actual==expected)


def score(case, result):
    calls=result.get('calls',[])
    expected=case['expected']
    required={e['name'] for e in expected}
    if case.get('required_followup'):
        required.add(case['required_followup'])
    allowed=required | set(case.get('allowed',[]))
    observed={c['name'] for c in calls}
    forbidden=set(case.get('forbidden',[]))
    bad_args=False
    for call in calls:
        for rule in case.get('forbidden_args',{}).get(call['name'],[]):
            key,_,value=rule.partition(':')
            if key in call['args'] and (not value or str(call['args'][key]).lower()==value):
                bad_args=True
    selected=required<=observed and observed<=allowed and not observed & forbidden
    arguments=all(any(c['name']==e['name'] for c in calls) and
                  all(matches(c['args'],e['args']) for c in calls if c['name']==e['name'])
                  for e in expected)
    arguments=arguments and not bad_args and all(c.get('valid',False) for c in calls)
    # A missing post-image reading call is a selection failure, even if initial mode was right.
    complete=result.get('complete',False) and not result.get('error')
    return {'tool_selection_ok':selected and complete,'arguments_ok':arguments and selected and complete,
            'false_trigger':bool(observed-allowed or observed & forbidden or bad_args),
            'negative':not expected or case.get('negative',False),
            'completed':complete,'observed_tools':sorted(observed)}


def aggregate(rows):
    def rate(key, subset=rows):
        return {'numerator':sum(bool(r.get(key)) for r in subset),'denominator':len(subset),
                'rate':sum(bool(r.get(key)) for r in subset)/len(subset) if subset else None}
    negatives=[r for r in rows if r.get('negative')]
    latencies=[r['first_audio_latency_s'] for r in rows if r.get('first_audio_latency_s') is not None]
    return {'cases':len(rows),'completed':sum(r.get('completed',False) for r in rows),
            'tool_selection_accuracy':rate('tool_selection_ok'),'argument_correctness':rate('arguments_ok'),
            'false_trigger_rate':rate('false_trigger',negatives),
            'unexpected_tool_rate':rate('false_trigger'),
            'premature_response_rate':{mode:rate('premature',[r for r in rows if r['mode']==mode and r.get('premature') is not None]) for mode in ('over','auto','ptt')},
            'first_audio_latency_s':{'n':len(latencies),'median':statistics.median(latencies) if latencies else None,
                                     'p95':sorted(latencies)[max(0,__import__('math').ceil(.95*len(latencies))-1)] if latencies else None}}


def render(report):
    lines=['# Voice evaluation — '+report['date'],'',
           'Real Gemini Live, synthetic Gemini TTS input. External effects and Android tools simulated.', '',
           '| Run | Complete | Tool selection | Arguments | False triggers (negative cases) | Over premature | Auto premature | Median first audio |',
           '|---|---:|---:|---:|---:|---:|---:|---:|']
    def ratio(v):
        return f"{v['numerator']}/{v['denominator']}" if v['denominator'] else 'n/a'
    for run in report['runs']:
        m=run['metrics']
        latency=m['first_audio_latency_s']['median']
        lines.append(f"| {run['label']} | {m['completed']}/{m['cases']} | {ratio(m['tool_selection_accuracy'])} | {ratio(m['argument_correctness'])} | {ratio(m['false_trigger_rate'])} | {ratio(m['premature_response_rate']['over'])} | {ratio(m['premature_response_rate']['auto'])} | {f'{latency:.2f} s' if latency is not None else 'n/a'} |")
    if report.get('analysis'):
        lines+=['','## Findings','']
        lines += [f'- {finding}' for finding in report['analysis']]
    for run in report['runs']:
        pm=run['prompt_metrics']
        lines+=['',f"## {run['label']}",'',
                f"System prompt: **{pm.get('system_tokens','unavailable')} tokens**, {pm['system_bytes']} UTF-8 bytes. "
                f"System plus serialized tools: **{pm.get('system_and_serialized_tools_tokens','unavailable')} tokens** "
                f"({pm['counter_model']} tokenizer proxy; hashes and Live usage in JSON).",'',
                '| Case | Tools | Selection | Args | Premature | Error |','|---|---|---|---|---|---|']
        for r in run['results']:
            lines.append(f"| {r['id']} | {', '.join(r.get('observed_tools',[])) or 'none'} | {r['tool_selection_ok']} | {r['arguments_ok']} | {r.get('premature','n/a')} | {r.get('error') or ''} |")
    lines+=['','## Scope and interpretation','',
        'Raw results retain earlier infrastructure-error attempts when explicitly retried. '
        'Selection misses are not retried by --retry-errors. Audio hashes support paired comparisons.', '',
        'Every requested case stays in the selection/argument denominator, including infrastructure errors. '
        'Premature rates include only sessions with an observed input boundary; completion coverage is reported separately. '
        'False triggers use no-tool and image-injection cases; unexpected-tool rate across all cases is in JSON. '
        'Arguments use declared exact/subset/substring checks, not full semantic equivalence. '
        'Latency is first received audio minus final input-audio time; it can be negative on interruption and is not acoustic playback latency.', '',
        'Over activityEnd uses the known fixture endpoint plus 1.2 seconds grace; this tests Gemini manual activity, '
        'not DAT/Android keyword recognition or the silence detector. Auto uses 2000 ms patience, so 3–6 second '
        'mid-sentence pauses deliberately exceed its setting. Fresh sessions, deterministic initial state, and cached audio '
        'are shared across prompt variants; model sampling remains nondeterministic. Mode-switch cases end after the accepted '
        'tool response, as Android opens a fresh session. Meeting silence gates, continuous watch, rollover/resumption, '
        'Bluetooth routing, physical gestures/timers and real Google/Codex work are not verified. '
        'Dashboard/hands-free cases test absence of fabricated tools, not UI correctness.', '',
        'Sources checked 2026-10-08: [Live protocol](https://ai.google.dev/api/live), '
        '[ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens), '
        '[Gemini TTS](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation), '
        '[free-tier availability](https://ai.google.dev/gemini-api/docs/pricing).', '']
    return '\n'.join(lines)


if __name__=='__main__':
    # Recompute a report offline with the current scorer and its saved case rubric.
    import argparse
    import json
    from pathlib import Path
    parser=argparse.ArgumentParser(description='Re-score saved raw results; no API calls.')
    parser.add_argument('report',type=Path)
    path=parser.parse_args().report
    report=json.loads(path.read_text())
    for run in report['runs']:
        cases={c['id']:c for c in run['cases']}
        for row in run['results']:
            row.update(score(cases[row['id']],row))
        run['metrics']=aggregate(run['results'])
    temp=path.with_suffix('.tmp')
    temp.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
    temp.replace(path)
    path.with_suffix('.md').write_text(render(report))
