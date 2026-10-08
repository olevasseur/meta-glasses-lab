"""Run from repo root: PYTHONPATH=scripts uv run --project backend python -m voice_eval."""
import argparse
import asyncio
import hashlib
import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'backend'))
from dotenv import load_dotenv
from .audio import MODEL, assemble, cached_path, read, insert_pause, mix_noise
from .client import evaluate
from .metrics import aggregate, render, score
from .simulator import backend


def run_case(case, prompt, tts_model, timeout, tools_file=None):
    load_dotenv(ROOT/'.env')
    result={'id':case['id'],'feature':case['feature'],'mode':case['mode'],'calls':[],
            'complete':False,'error':None}
    try:
        paths=[cached_path(s,case['voice'],tts_model) for s in case['segments']]
        pcm=assemble([read(p) for p in paths],case['pauses'])
        if case.get('insert_pause'):
            pcm=insert_pause(pcm,case['insert_pause'])
        if case.get('noise_db') is not None:
            pcm=mix_noise(pcm,case['noise_db'])
        pcm=bytes(9600)+pcm  # 300 ms microphone pre-roll before the first word.
        with backend(prompt,tools_file) as http:
            result=asyncio.run(evaluate(case,pcm,http,os.environ['APP_SHARED_SECRET'],timeout))
        result['audio_sha256']=hashlib.sha256(pcm).hexdigest()
        result['audio_files']=[p.name for p in paths]
    except Exception as exc:
        # Exception strings from Google/transports can include credentials. Keep types only.
        result['error']=type(exc).__name__
    result.update(score(case,result))
    return result


def prompt_metrics(prompt, tools_file=None):
    from google import genai
    from tools import DECLARATIONS
    text=Path(prompt).read_text()
    declarations=json.loads(Path(tools_file).read_text()) if tools_file else [d.model_dump(mode='json',exclude_none=True) for d in DECLARATIONS]
    result={'system_bytes':len(text.encode()),'system_sha256':hashlib.sha256(text.encode()).hexdigest(),
            'tools_sha256':hashlib.sha256(json.dumps(declarations,sort_keys=True).encode()).hexdigest(),
            'tool_count':len(declarations),'counter_model':'gemini-3.5-flash-lite'}
    # countTokens on the text model is a tokenizer proxy, not billed Live input usage.
    try:
        with genai.Client(api_key=os.environ['GEMINI_API_KEY'],vertexai=False,
                          http_options={'api_version':'v1beta','timeout':30000}) as client:
            result['system_tokens']=client.models.count_tokens(model=result['counter_model'],contents=text).total_tokens
            result['system_and_serialized_tools_tokens']=client.models.count_tokens(
                model=result['counter_model'],contents=text+'\n'+json.dumps(declarations)).total_tokens
    except Exception as exc:
        result['count_error']=type(exc).__name__
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label',required=True)
    parser.add_argument('--retry-errors',action='store_true',help='Retry only infrastructure errors in this label; retain earlier attempts')
    parser.add_argument('--prompt',default=str(ROOT/'backend/prompts/system.md'))
    parser.add_argument('--tool-declarations',help='Optional declaration snapshot (same registered tool names)')
    parser.add_argument('--cases',default=str(Path(__file__).with_name('cases.json')))
    parser.add_argument('--case',action='append')
    parser.add_argument('--workers',type=int,default=1)
    parser.add_argument('--timeout',type=float,default=45)
    parser.add_argument('--tts-model',default=MODEL)
    parser.add_argument('--output',default=str(ROOT/'docs/evals'/f'voice-eval-{datetime.now().date()}.json'))
    args=parser.parse_args()
    load_dotenv(ROOT/'.env')
    cases=json.loads(Path(args.cases).read_text())
    if args.case:
        unknown=set(args.case)-{c['id'] for c in cases}
        if unknown:
            parser.error('Unknown case IDs: '+', '.join(sorted(unknown)))
        cases=[c for c in cases if c['id'] in args.case]
    if not cases:
        parser.error('No cases selected')
    output=Path(args.output)
    output.parent.mkdir(parents=True,exist_ok=True)
    report=json.loads(output.read_text()) if output.exists() else {'date':str(datetime.now().date()),'runs':[]}
    previous=next((r for r in report['runs'] if r['label']==args.label),None)
    measurements=prompt_metrics(args.prompt,args.tool_declarations)
    if previous is not None:
        if not args.retry_errors:
            parser.error('Label already exists; choose a new label or --retry-errors')
        if any(previous['prompt_metrics'][k]!=measurements[k] for k in ('system_sha256','tools_sha256')) or previous['tts_model']!=args.tts_model or previous['live_model']!=os.getenv('GEMINI_LIVE_MODEL','gemini-3.8-live'):
            parser.error('Retries require the same prompt, tools, Live and TTS models')
        failed={r['id'] for r in previous['results'] if r.get('error')}
        cases=[c for c in previous['cases'] if c['id'] in failed]
        if not cases:
            parser.error('No infrastructure errors to retry')
        run=previous
        run.setdefault('earlier_error_attempts',[]).extend(r for r in run['results'] if r['id'] in failed)
    else:
        if args.retry_errors:
            parser.error('No such label to retry')
        run={'label':args.label,'live_model':os.getenv('GEMINI_LIVE_MODEL','gemini-3.8-live'),
             'tts_model':args.tts_model,'prompt_metrics':measurements,
             'cases':cases,'results':[]}
        report['runs'].append(run)
    def save():
        run['results'].sort(key=lambda r:[c['id'] for c in run['cases']].index(r['id']))
        run['metrics']=aggregate(run['results'])
        temp=output.with_suffix('.tmp')
        temp.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
        temp.replace(output)
        output.with_suffix('.md').write_text(render(report))
    with ProcessPoolExecutor(max_workers=max(1,min(4,args.workers))) as pool:
        futures={pool.submit(run_case,c,str(Path(args.prompt).resolve()),args.tts_model,args.timeout,args.tool_declarations):c for c in cases}
        for completed,future in enumerate(as_completed(futures),1):
            result=future.result()
            run['results']=[r for r in run['results'] if r['id']!=result['id']]
            run['results'].append(result)
            save()
            print(f"{args.label} {completed}/{len(cases)} {result['id']} selection={result['tool_selection_ok']} args={result['arguments_ok']} error={result['error']}",flush=True)
    print(json.dumps(run['metrics'],indent=2))
    raise SystemExit(1 if any(r['error'] for r in run['results']) else 0)

if __name__=='__main__':
    main()
