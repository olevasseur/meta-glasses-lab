"""Generate cached utterances; quota failures leave earlier fixtures reusable."""
import argparse
import json
import os
import time
import threading
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

from dotenv import load_dotenv
from google import genai
from google.genai.errors import APIError
from .audio import MODEL, synthesize


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', default=str(Path(__file__).with_name('cases.json')))
    parser.add_argument('--case', action='append', help='Only these case IDs (repeatable)')
    parser.add_argument('--model', default=MODEL)
    parser.add_argument('--workers', type=int, default=1)
    args = parser.parse_args()
    load_dotenv(Path(__file__).resolve().parents[2] / '.env')
    cases = json.loads(Path(args.cases).read_text())
    tasks = {(segment, case['voice']) for case in cases if not args.case or case['id'] in args.case
             for segment in case['segments']}
    exhausted=threading.Event()
    def generate(task):
        if exhausted.is_set():
            return False
        text, voice = task
        with genai.Client(api_key=os.environ['GEMINI_API_KEY'], vertexai=False,
                          http_options={'api_version':'v1beta','timeout':120000}) as client:
            for attempt in range(4):
                try:
                    path = synthesize(client, text, voice, args.model)
                    print(f'OK {voice} {path.name[:12]}', flush=True)
                    return True
                except APIError as exc:
                    details=json.dumps(exc.details or {})
                    daily='PerDay' in details
                    if daily:
                        exhausted.set()
                    if exc.code == 429 and not daily and attempt < 3 and not exhausted.is_set():
                        time.sleep(30)
                        continue
                    print(f'FAILED TTS {type(exc).__name__} code={exc.code}', flush=True)
                    return False
                except Exception as exc:
                    print(f'FAILED TTS {type(exc).__name__}', flush=True)
                    return False
    with ThreadPoolExecutor(max_workers=max(1,min(4,args.workers))) as pool:
        results = list(pool.map(generate, sorted(tasks)))
    print(f'TTS: {sum(results)}/{len(results)} unique utterance/voice pairs cached')
    raise SystemExit(0 if all(results) else 1)

if __name__ == '__main__':
    main()
