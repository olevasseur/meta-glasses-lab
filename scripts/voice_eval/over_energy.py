"""Offline Over gate audit on existing quiet-HFP fixtures, including attenuation.

PYTHONPATH=scripts uv run --project backend --with numpy --with scipy \
  python -m voice_eval.over_energy > /tmp/over-energy.json
No API calls. This mirrors SpeechEnergyDetector; it is not Android ASR or hardware.
"""
import json

import numpy as np
from .hfp_runner import cases_and_audio
from .hfp import Condition, simulate
from .hfp_ablation import fir_upsample


def measure(pcm, gain, start_rms, hold_rms):
    samples = np.rint(np.frombuffer(pcm, dtype='<i2').astype(float) * gain)
    onset = weak = 0
    speaking = False
    accepted, levels = [], []
    for offset in range(0, len(samples) - 319, 320):
        frame = samples[offset:offset + 320].copy()
        frame -= frame.mean()
        rms = float(np.sqrt(np.mean(frame * frame)))
        rate = np.count_nonzero((frame[1:] >= 0) != (frame[:-1] >= 0)) / 320
        speech_like = .008 <= rate <= .20
        if speech_like and rms >= start_rms:
            onset = min(onset + 320, 1600)
            weak = 0
            if onset >= 1600:
                speaking = True
        else:
            onset = 0
            weak = min(weak + 320, 3200)
            if not (speech_like and rms >= hold_rms) or weak >= 3200:
                speaking = False
        if speaking:
            accepted.append(round(offset / 16000, 3))
        if rms > 100:
            levels.append(rms)
    return dict(gain=gain, active_frame_rms_p10_p50_p90=[round(float(x)) for x in np.percentile(levels, [10, 50, 90])],
                detected_frames=len(accepted), first_s=accepted[0] if accepted else None,
                last_s=accepted[-1] if accepted else None)


def main():
    rows, _ = cases_and_audio()
    prepared = []
    for case, source in rows:
        _, pcm8, _ = simulate(source, Condition('hfp-quiet'))
        prepared.append((case['id'], fir_upsample(pcm8)))
    report = {}
    for name, start_rms, hold_rms in (('baseline', 700, 450), ('candidate', 350, 225)):
        report[name] = dict(thresholds=dict(onset_rms=start_rms, hold_rms=hold_rms, attack_ms=100),
                            channel='synthetic hfp-quiet 8k + app FIR 16k', results=[
            dict(case=case, **measure(pcm, gain, start_rms, hold_rms))
            for case, pcm in prepared for gain in (1, .25, .1)])
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
