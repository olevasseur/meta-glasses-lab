"""Offline macOS speech guardrail challenge; no Gemini sessions or audio uploads."""
import argparse
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

from .audio import read
from .hfp import CONDITIONS, simulate
from .hfp_keywords import transcribe

PHRASES = [
    ('Please save all of that. Over.', 'keyword'),
    ('Over.', 'keyword'),
    ('Thank you. Over and out.', 'sign_off'),
    ("I am getting older.", None),
    ('Roll over there.', None),
    ('Please read the overview.', None),
    ('The clover is green.', None),
    ('Turn over the page.', None),
    ('Is it over?', None),  # Semantic ambiguity: exact trailing keyword still ends a question.
    ('Your turn.', None),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vosk-model', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    from vosk import Model, SetLogLevel
    SetLogLevel(-1)
    model = Model(args.vosk_model)
    report = {'source': 'macOS say, Samantha, 175 words/minute', 'results': []}
    with tempfile.TemporaryDirectory(prefix='hfp-keywords-') as temp:
        for text, expected in PHRASES:
            path = Path(temp)
            subprocess.run(['say', '-v', 'Samantha', '-r', '175', '-o', str(path/'speech.aiff'), text], check=True)
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(path/'speech.aiff'),
                            '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(path/'speech.wav')], check=True)
            source = bytes(9600) + read(path/'speech.wav')
            for condition in CONDITIONS:
                _, pcm8, _ = simulate(source, condition)
                result = transcribe(model, pcm8)
                row = {'text': text, 'expected': expected, 'condition': condition.name,
                       'source_sha256': hashlib.sha256(source).hexdigest(), 'vosk': result}
                report['results'].append(row)
                args.output.write_text(json.dumps(report, indent=2)+'\n')
            print(text, 'done', flush=True)


if __name__ == '__main__':
    main()
