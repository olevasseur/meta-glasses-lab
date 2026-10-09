"""Bounded real-Gemini text selection probe; no tools are executed.

Run from backend: uv run python ../scripts/voice_eval/voice_stop.py
Spoken Live cases use the normal voice_eval runner with --case voice-stop-... .
"""
import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'backend'), str(ROOT / 'scripts')]


def main():
    from dotenv import load_dotenv
    from google import genai
    from google.genai import types
    from gemini import assistant_instruction
    from memory_eval.provider import budget
    from tools import DECLARATIONS

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/evals/voice-stop-2026-10-09-text.json')
    args = parser.parse_args()
    cases = [c for c in json.loads(Path(__file__).with_name('voice-stop-cases.json').read_text())
             if c['feature'] == 'voice-stop']
    if not 1 <= args.repeats <= 5 or len(cases) * args.repeats > 75:
        parser.error('Use 1–5 repeats and at most 75 text calls (reserve 5 for TTS).')
    if args.output.exists():
        parser.error('Output exists; choose a new path to preserve earlier evidence.')
    load_dotenv(ROOT / '.env')
    model = os.getenv('VOICE_STOP_EVAL_MODEL', 'gemini-3.5-flash-lite')
    prompt = assistant_instruction('auto')
    report = {'model': model, 'kind': 'text tool selection, not audio/hardware',
              'system_sha256': hashlib.sha256(prompt.encode()).hexdigest(),
              'tools_sha256': hashlib.sha256(json.dumps([d.model_dump(mode='json', exclude_none=True)
                  for d in DECLARATIONS], sort_keys=True).encode()).hexdigest(), 'results': []}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with budget(args.output.with_suffix('.budget.json'), limit=75, interval=3.2), genai.Client(
        api_key=os.environ['GEMINI_API_KEY'], vertexai=False,
        http_options=types.HttpOptions(api_version='v1beta', timeout=30000,
                                       retry_options=types.HttpRetryOptions(attempts=1)),
    ) as client:
        for repeat in range(args.repeats):
            for case in cases:
                result = {'id': case['id'], 'repeat': repeat + 1, 'expected': [e['name'] for e in case['expected']]}
                try:
                    response = client.models.generate_content(model=model,
                        contents=case.get('context', '') + '\n' + ' '.join(case['segments']),
                        config=types.GenerateContentConfig(system_instruction=prompt,
                            tools=[types.Tool(function_declarations=DECLARATIONS)],
                            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
                            temperature=0, max_output_tokens=1024))
                    calls = [{'name': c.name, 'args': c.args} for c in response.function_calls or []]
                    result.update(calls=calls, passed=sorted(c['name'] for c in calls) == sorted(result['expected']),
                                  text=response.text or '')
                except Exception as exc:
                    result.update(error=type(exc).__name__, passed=False)
                report['results'].append(result)
                args.output.write_text(json.dumps(report, indent=2) + '\n')
                print(f"{case['id']} repeat={repeat + 1} passed={result['passed']}", flush=True)
                if result.get('error'):
                    raise SystemExit('Stopped after provider failure; see sanitized report.')
    print(f"Passed {sum(r['passed'] for r in report['results'])}/{len(report['results'])}")


if __name__ == '__main__':
    main()
