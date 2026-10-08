"""Offline paired resampler ablation; Vosk at 16 kHz sees the exact app uplink."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from scipy.signal import firwin, lfilter
from .hfp import CONDITIONS, decode, encode, simulate
from .hfp_keywords import normalize
from .hfp_runner import cases_and_audio

# Causal 31-tap half-band interpolation, <1 ms group delay at 16 kHz.
TAPS = firwin(31, 4000, fs=16000, window='hamming') * 2


def fir_upsample(pcm8):
    signal = decode(pcm8)
    expanded = np.zeros(len(signal) * 2)
    expanded[::2] = signal
    return encode(lfilter(TAPS, [1], expanded))


def word_errors(expected, observed):
    left, right = normalize(expected).split(), normalize(observed).split()
    previous = list(range(len(right) + 1))
    for i, x in enumerate(left, 1):
        current = [i]
        for j, y in enumerate(right, 1):
            current.append(min(current[-1]+1, previous[j]+1, previous[j-1]+(x != y)))
        previous = current
    return previous[-1], len(left)


def recognize(model, pcm):
    from vosk import KaldiRecognizer
    recognizer = KaldiRecognizer(model, 16000)
    parts = []
    for offset in range(0, len(pcm), 640):
        if recognizer.AcceptWaveform(pcm[offset:offset+640]):
            parts.append(json.loads(recognizer.Result()).get('text', ''))
    parts.append(json.loads(recognizer.FinalResult()).get('text', ''))
    return ' '.join(parts).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vosk-model', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    from vosk import Model, SetLogLevel
    SetLogLevel(-1)
    model = Model(args.vosk_model)
    rows, chatter = cases_and_audio()
    results = []
    for condition in CONDITIONS[1:]:
        for case, source in rows:
            linear, pcm8, _ = simulate(source, condition, bystander=chatter)
            row = {'condition': condition.name, 'id': case['id'], 'reference': ' '.join(case['segments'])}
            for label, pcm in [('linear', linear), ('fir', fir_upsample(pcm8))]:
                transcript = recognize(model, pcm)
                errors, words = word_errors(row['reference'], transcript)
                row[label] = {'text': transcript, 'errors': errors, 'words': words,
                              'pcm_sha256': hashlib.sha256(pcm).hexdigest()}
            results.append(row)
            args.output.write_text(json.dumps({'taps': TAPS.tolist(), 'results': results}, indent=2)+'\n')
            print(condition.name, case['id'], row['linear']['errors'], row['fir']['errors'], flush=True)


if __name__ == '__main__':
    main()
