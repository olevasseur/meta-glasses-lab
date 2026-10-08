"""Render the saved HFP measurements without provider calls."""
import argparse
import json
from pathlib import Path


def ratio(rows, field):
    return f'{sum(bool(r.get(field)) for r in rows)}/{len(rows)}'


def render(report, ablation, candidate=None, challenge=None):
    lines = ['# HFP audio evaluation — ' + report['date'], '',
        f"**{report['live_attempts']} baseline Live attempts**, one connection at a time; "
        f"{candidate['live_attempts'] if candidate else 0} additional FIR checks. "
        'Fresh constrained Gemini sessions (`gemini-3.8-live` configuration), cached synthetic speech, simulated tool side effects. '
        'No new TTS calls or real Google Docs writes.', '',
        'Six utterance/mode cases × seven conditions = 42 planned trials. The long picnic '
        'dictation is repeated in Auto and Over, so these are **five distinct utterances**, '
        'not 42 independent speakers/commands. All conditions use the same source waveforms '
        'and seed. The two no-tool utterances are the false-trigger controls. '
        'Noise includes a quieter bystander repeatedly requesting a pasta timer.', '',
        '| Condition | Completed | Tool + args (4 positive cases) | False tools (2 negatives) | Unexpected tools (all) | Premature Auto | Premature Over |',
        '|---|---:|---:|---:|---:|---:|---:|']
    for condition in report['conditions']:
        name = condition['name']
        rows = [r['live'] for r in report['results'] if r['condition'] == name and 'live' in r]
        positives = [r for r in rows if not r.get('negative')]
        negatives = [r for r in rows if r.get('negative')]
        modes = {mode: [r for r in rows if r['mode'] == mode and r.get('premature') is not None] for mode in ('auto', 'over')}
        lines.append(f"| {name} | {ratio(rows, 'completed')} | {ratio(positives, 'arguments_ok')} | "
                     f"{ratio(negatives, 'false_trigger')} | {ratio(rows, 'false_trigger')} | "
                     f"{ratio(modes['auto'], 'premature')} | {ratio(modes['over'], 'premature')} |")
    lines += ['', 'Accuracy requires completed generation, required tools, valid schema and the '
        'existing exact/subset/substring argument rubric. Timeouts count as accuracy failures; '
        'premature responses and incorrect writes before timeout remain failures too. '
        '“Unexpected tools” also includes tool calls on positive cases that were not requested. '
        'Premature means a tool/text/audio response arrived before the full input ended '
        '(or before activityEnd in Over); it is not measured acoustic playback latency.', '',
        '## Keyword detection', '',
        '| Condition | Vosk Over hit | Vosk false ends (4 non-keyword clips) | Gemini Over hit eventually | Gemini hit before oracle end | Vosk delay after audio end |',
        '|---|---:|---:|---:|---:|---:|']
    for condition in report['conditions']:
        rows = [r for r in report['results'] if r['condition'] == condition['name']]
        over = next(r for r in rows if r['id'] == 'over-pause-1')
        negatives = [r for r in rows if not r['keyword_expected']]
        local = over['vosk']['exact']
        delay = f"{local['time_s'] - over['speech_end_s']:.2f} s" if local['reason'] else 'miss'
        gemini = str(int(bool(over['gemini_keyword']['reason']))) + '/1' if 'live' in over else 'not run'
        early = str(int(bool(over['gemini_keyword_before_end']['reason']))) + '/1' if 'live' in over else 'not run'
        lines.append(f"| {condition['name']} | {int(bool(local['reason']))}/1 | "
            f"{sum(bool(r['vosk']['exact']['reason']) for r in negatives)}/{len(negatives)} | {gemini} | {early} | {delay} |")
    lines += ['', 'Vosk 0.3.44, `vosk-model-small-en-us-0.15` (Apache-2.0), receives actual '
        '8 kHz audio with `--allow-upsample=true`; its wideband feature pipeline internally '
        'upsamples to 16 kHz. Clean Live input stays 16 kHz, but its local proxy is band-limited '
        'to 8 kHz too. Vosk partial/final events replay the app’s exact trailing-word rule '
        'and 1.2 s grace. Delays use audio time, not measured wall-clock recognition latency. '
        'Gemini incremental transcripts are accumulated and replayed separately. “Eventually” '
        'can include text received only after the oracle closed the activity. **It cannot '
        'validate a real-time on-device keyword gate.** Android/DAT recognition was not run.', '',
        'The main Live harness still ends Over at the known fixture endpoint plus 1.2 s; '
        'the proxy result does not close its socket activity. Thus tool success does not '
        'imply on-device keyword success. Each condition has only one distinct positive '
        'keyword recording. Expanding to `ova`/`older` supplied no demonstrated need; '
        'the regression “my dog is getting older” shows why broad aliases introduce false ends.', '',
        '## Resampler ablation', '',
        'Same narrowband PCM, local Vosk at 16 kHz: original linear uplink versus causal '
        '31-tap FIR uplink. Counts are Levenshtein word errors/reference words and can '
        'include insertions from the bystander; they are not Gemini accuracy estimates.', '',
        '| Condition | Linear word errors | FIR word errors |', '|---|---:|---:|']
    for condition in report['conditions'][1:]:
        rows = [r for r in ablation['results'] if r['condition'] == condition['name']]
        n = sum(r['linear']['words'] for r in rows)
        lines.append(f"| {condition['name']} | {sum(r['linear']['errors'] for r in rows)}/{n} | {sum(r['fir']['errors'] for r in rows)}/{n} |")
    if candidate:
        lines += ['', 'Targeted provider checks (development data, no retries or confidence '
            'claim). Provider sampling is not deterministic; one result per cell cannot '
            'establish a causal accuracy gain.', '',
            '| Condition / case | Baseline tool + args | FIR tool + args | FIR premature | FIR error |',
            '|---|---:|---:|---:|---|']
        for row in candidate['results']:
            old = next(r['live'] for r in report['results'] if r['condition'] == row['condition'] and r['id'] == row['id'])
            new = row.get('live', {})
            lines.append(f"| {row['condition']} / {row['id']} | {old['arguments_ok']} | {new.get('arguments_ok')} | {new.get('premature')} | {new.get('error') or ''} |")
    lines += ['', '## Failures retained for inspection', '']
    failures = [r for r in report['results'] if 'live' in r and (not r['live']['arguments_ok'] or r['live'].get('premature'))]
    for row in failures:
        live = row['live']
        lines.append(f"- **{row['condition']} / {row['id']}**: error={live.get('error')}; "
                     f"premature={live.get('premature')}; tools={', '.join(live['observed_tools']) or 'none'}. "
                     'Full transcript, arguments and timestamps are in the JSON.')
    if not failures:
        lines.append('- No measured failures in completed cells.')
    if challenge:
        lines += ['', '## Independent short-keyword challenge (offline only)', '',
            'Ten additional utterances synthesized locally with macOS Samantha at 175 '
            'words/minute, seven channels, 70 Vosk trials. No Gemini calls. Three '
            'positive phrases are “Please save all of that. Over”, isolated “Over”, and '
            '“Thank you. Over and out”. Seven negatives include older/overview/clover, '
            '“turn over the page”, “your turn”, and the ambiguous question “Is it over?”. '
            'Environmental noise is included; this challenge omits the timer bystander.', '',
            '| Condition | Correct keyword/sign-off (3) | Exact false ends (7) | Ova/older false ends (7) |',
            '|---|---:|---:|---:|']
        for condition in report['conditions']:
            rows = [r for r in challenge['results'] if r['condition'] == condition['name']]
            positives = [r for r in rows if r['expected']]
            negatives = [r for r in rows if not r['expected']]
            hits = sum(r['vosk']['exact']['reason'] == r['expected'] for r in positives)
            false = sum(bool(r['vosk']['exact']['reason']) for r in negatives)
            aliases = sum(bool(r['vosk']['aliases']['reason']) for r in negatives)
            lines.append(f"| {condition['name']} | {hits}/{len(positives)} | {false}/{len(negatives)} | {aliases}/{len(negatives)} |")
        lines += ['', 'Isolated “Over” became “however” in café/wind; some noisy sign-offs '
            'were lost. Adding ova/older recovered none of these misses and introduced '
            'false ends for “I am getting older” in every condition. Exact matching '
            'already cannot distinguish a trailing keyword from the question “Is it over?”. '
            'Use the existing configurable “your turn” phrase if that ambiguity is common. '
            'No fuzzy alias is enabled in the app. The longer original positive clip '
            'therefore overstates short-keyword robustness. See '
            '[raw challenge](hfp-keyword-challenge-2026-10-08.json).', '']
    lines += ['', '## Hardware test recommendations', '',
        '1. Check Diagnostics for the actual **Bluetooth HFP** input route and 8 kHz request; '
        'phone fallback is a different test. Record firmware/phone/route before comparing results.',
        '2. In quiet, then café/street/wind, say “Remember my dog is named Maple” and '
        '“Add a note to Inbox: buy oat milk tomorrow.” Inspect the saved content and any '
        'unexpected camera/reading/tool activation. Synthetic scores are not a guarantee.',
        '3. Dictate the picnic note with a three-second mid-sentence pause in Auto and '
        'Over. Verify umbrellas and blanket survive in the saved note. Check for an early '
        'write as well as an audible interruption. Prefer Over for long dictation.',
        '4. Say “over”, “over and out”, “clover”, “over there”, and “my dog is getting older”. '
        'Check exactly one end, correct sign-off behavior and no alias false positives. '
        'Try a quiet final word and a bystander saying “over”; −12 dB beamforming is not '
        'speaker identity verification.',
        '5. Stop speaking without the keyword and time the 10-second silence fallback in '
        'each noise bed. The energy detector may classify narrowband background/bystanders '
        'as speech. This eval does not validate that fallback.',
        '6. Test speaker echo/barge-in while Gemini talks, walking/head motion, phone in '
        'pocket, capture controls and a Bluetooth reconnect. Check actual AEC/NS state '
        'on the recording session before enabling additional suppression.', '',
        '## Scope, reproducibility and sources', '',
        'Noise is procedural CC0 spectral/temporal stress audio, not field recordings. '
        'SNR is **pre-filter active wearer speech to environmental noise**; the −12 dB '
        'bystander is additional interference. Wind’s low-frequency energy is partly '
        'removed by the 300 Hz cutoff. No physical wind saturation, room impulse response '
        'or measured beamformer is modeled. Codec approximations use IMA ADPCM or block '
        'quantization, not bit-exact CVSD/mSBC. mSBC itself is wideband.', '',
        'The simulator covers pauses inside the utterance. The inherited Live harness '
        'sends digital silence after the clip (five seconds in Auto, 1.2 seconds in Over), '
        'so persistent ambient noise during the final VAD wait and idle false triggers '
        'remain unverified. Fresh sessions and simulated effects also exclude real '
        'SCO echo, multi-hour operation, resumption and real external writes. '
        'No actual Pixel/glasses recording or on-device recognizer was available.', '',
        'See [feature and reproduction commands](../features/hfp-audio.md), '
        '[dated source verification](../research/hfp-audio.md), '
        '[raw baseline measurements](hfp-audio-eval-2026-10-08.json), and '
        '[local ablation](hfp-resampler-ablation-2026-10-08.json). '
        'Verified 2026-10-08: [Vosk model/license](https://alphacephei.com/vosk/models), '
        '[polyphase filtering](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.resample_poly.html), '
        '[Android NS](https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor), '
        '[Android AEC](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler).', '']
    over = [r['live'] for r in report['results'] if r['id'] == 'over-pause-1' and 'live' in r]
    auto = [r['live'] for r in report['results'] if r['id'] == 'auto-pause-1' and 'live' in r]
    lines[2:2] = [f"**Use Over for long dictation:** full-note tool/argument success was "
        f"{ratio(over, 'arguments_ok')} in Over versus {ratio(auto, 'arguments_ok')} in Auto. "
        'Quiet HFP kept tool accuracy in this small set; noisy memory commands sometimes '
        'became the wrong intent. Short sign-offs need a separate hardware check.', '']
    if candidate and candidate.get('stopped'):
        lines += ['## Provider limit reached', '',
            f"Stopped the candidate after {candidate['live_attempts']} attempts: {candidate['stopped']}. "
            'Timeout, close 1011, then timeout caused 15/30/60-second backoff and a stop, '
            'with no case retries. Total Live attempts stayed at '
            f"{report['live_attempts'] + candidate['live_attempts']} of the allowed 50; "
            'the final planned candidate was not attempted. All sockets were closed.', '']
    if report.get('implementation_notes'):
        lines += [report['implementation_notes']]
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('ablation', type=Path)
    parser.add_argument('--candidate', type=Path)
    parser.add_argument('--challenge', type=Path)
    args = parser.parse_args()
    args.baseline.with_suffix('.md').write_text(render(json.loads(args.baseline.read_text()),
        json.loads(args.ablation.read_text()), json.loads(args.candidate.read_text()) if args.candidate else None,
        json.loads(args.challenge.read_text()) if args.challenge else None))


if __name__ == '__main__':
    main()
