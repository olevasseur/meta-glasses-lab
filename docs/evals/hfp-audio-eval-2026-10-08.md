# HFP audio evaluation — 2026-10-08

**Use Over for long dictation:** full-note tool/argument success was 7/7 in Over versus 2/7 in Auto. Quiet HFP kept tool accuracy in this small set; noisy memory commands sometimes became the wrong intent. Short sign-offs need a separate hardware check.

**42 baseline Live attempts**, one connection at a time; 7 additional FIR checks. Fresh constrained Gemini sessions (`gemini-3.8-live` configuration), cached synthetic speech, simulated tool side effects. No new TTS calls or real Google Docs writes.

Six utterance/mode cases × seven conditions = 42 planned trials. The long picnic dictation is repeated in Auto and Over, so these are **five distinct utterances**, not 42 independent speakers/commands. All conditions use the same source waveforms and seed. The two no-tool utterances are the false-trigger controls. Noise includes a quieter bystander repeatedly requesting a pasta timer.

| Condition | Completed | Tool + args (4 positive cases) | False tools (2 negatives) | Unexpected tools (all) | Premature Auto | Premature Over |
|---|---:|---:|---:|---:|---:|---:|
| clean | 6/6 | 4/4 | 0/2 | 0/6 | 0/5 | 0/1 |
| hfp-quiet | 6/6 | 4/4 | 0/2 | 0/6 | 1/5 | 0/1 |
| street-20dB-1pct | 5/6 | 3/4 | 0/2 | 0/6 | 1/5 | 0/1 |
| cafe-10dB-3pct | 5/6 | 2/4 | 0/2 | 1/6 | 1/5 | 0/1 |
| wind-0dB-5pct | 5/6 | 3/4 | 0/2 | 0/6 | 1/5 | 0/1 |
| car-10dB-3pct | 6/6 | 2/4 | 0/2 | 1/6 | 1/5 | 0/1 |
| kitchen-5dB-1pct | 5/6 | 2/4 | 0/2 | 0/6 | 0/5 | 0/1 |

Accuracy requires completed generation, required tools, valid schema and the existing exact/subset/substring argument rubric. Timeouts count as accuracy failures; premature responses and incorrect writes before timeout remain failures too. “Unexpected tools” also includes tool calls on positive cases that were not requested. Premature means a tool/text/audio response arrived before the full input ended (or before activityEnd in Over); it is not measured acoustic playback latency.

## Keyword detection

| Condition | Vosk Over hit | Vosk false ends (4 non-keyword clips) | Gemini Over hit eventually | Gemini hit before oracle end | Vosk delay after audio end |
|---|---:|---:|---:|---:|---:|
| clean | 1/1 | 0/4 | 1/1 | 0/1 | 1.32 s |
| hfp-quiet | 1/1 | 0/4 | 0/1 | 0/1 | 1.32 s |
| street-20dB-1pct | 1/1 | 0/4 | 1/1 | 0/1 | 2.04 s |
| cafe-10dB-3pct | 1/1 | 0/4 | 0/1 | 0/1 | 1.32 s |
| wind-0dB-5pct | 1/1 | 0/4 | 0/1 | 0/1 | 2.04 s |
| car-10dB-3pct | 1/1 | 0/4 | 1/1 | 0/1 | 1.32 s |
| kitchen-5dB-1pct | 1/1 | 0/4 | 1/1 | 0/1 | 1.32 s |

Vosk 0.3.44, `vosk-model-small-en-us-0.15` (Apache-2.0), receives actual 8 kHz audio with `--allow-upsample=true`; its wideband feature pipeline internally upsamples to 16 kHz. Clean Live input stays 16 kHz, but its local proxy is band-limited to 8 kHz too. Vosk partial/final events replay the app’s exact trailing-word rule and 1.2 s grace. Delays use audio time, not measured wall-clock recognition latency. Gemini incremental transcripts are accumulated and replayed separately. “Eventually” can include text received only after the oracle closed the activity. **It cannot validate a real-time on-device keyword gate.** Android/DAT recognition was not run.

The main Live harness still ends Over at the known fixture endpoint plus 1.2 s; the proxy result does not close its socket activity. Thus tool success does not imply on-device keyword success. Each condition has only one distinct positive keyword recording. Expanding to `ova`/`older` supplied no demonstrated need; the regression “my dog is getting older” shows why broad aliases introduce false ends.

## Resampler ablation

Same narrowband PCM, local Vosk at 16 kHz: original linear uplink versus causal 31-tap FIR uplink. Counts are Levenshtein word errors/reference words and can include insertions from the bystander; they are not Gemini accuracy estimates.

| Condition | Linear word errors | FIR word errors |
|---|---:|---:|
| hfp-quiet | 46/137 | 19/137 |
| street-20dB-1pct | 105/137 | 99/137 |
| cafe-10dB-3pct | 109/137 | 111/137 |
| wind-0dB-5pct | 99/137 | 102/137 |
| car-10dB-3pct | 86/137 | 94/137 |
| kitchen-5dB-1pct | 113/137 | 109/137 |

Targeted provider checks (development data, no retries or confidence claim). Provider sampling is not deterministic; one result per cell cannot establish a causal accuracy gain.

| Condition / case | Baseline tool + args | FIR tool + args | FIR premature | FIR error |
|---|---:|---:|---:|---|
| hfp-quiet / note-inbox | True | True | False |  |
| hfp-quiet / memory-save | True | True | False |  |
| hfp-quiet / over-pause-1 | True | True | False |  |
| street-20dB-1pct / over-pause-1 | True | True | False |  |
| cafe-10dB-3pct / memory-save | False | False | False | ResponseTimeout |
| wind-0dB-5pct / memory-save | True | False | False | ConnectionClosedError |
| kitchen-5dB-1pct / memory-save | False | False | False | ResponseTimeout |

## Failures retained for inspection

- **hfp-quiet / auto-pause-1**: error=None; premature=True; tools=append_to_doc. Full transcript, arguments and timestamps are in the JSON.
- **street-20dB-1pct / auto-pause-1**: error=ResponseTimeout; premature=True; tools=append_to_doc. Full transcript, arguments and timestamps are in the JSON.
- **cafe-10dB-3pct / memory-save**: error=None; premature=False; tools=read_text, submit_reading. Full transcript, arguments and timestamps are in the JSON.
- **cafe-10dB-3pct / auto-pause-1**: error=ResponseTimeout; premature=True; tools=append_to_doc. Full transcript, arguments and timestamps are in the JSON.
- **wind-0dB-5pct / small-thanks**: error=ResponseTimeout; premature=False; tools=none. Full transcript, arguments and timestamps are in the JSON.
- **wind-0dB-5pct / auto-pause-1**: error=None; premature=True; tools=append_to_doc. Full transcript, arguments and timestamps are in the JSON.
- **car-10dB-3pct / memory-save**: error=None; premature=False; tools=recall. Full transcript, arguments and timestamps are in the JSON.
- **car-10dB-3pct / auto-pause-1**: error=None; premature=True; tools=none. Full transcript, arguments and timestamps are in the JSON.
- **kitchen-5dB-1pct / memory-save**: error=None; premature=False; tools=remember. Full transcript, arguments and timestamps are in the JSON.
- **kitchen-5dB-1pct / auto-pause-1**: error=ResponseTimeout; premature=False; tools=none. Full transcript, arguments and timestamps are in the JSON.

## Independent short-keyword challenge (offline only)

Ten additional utterances synthesized locally with macOS Samantha at 175 words/minute, seven channels, 70 Vosk trials. No Gemini calls. Three positive phrases are “Please save all of that. Over”, isolated “Over”, and “Thank you. Over and out”. Seven negatives include older/overview/clover, “turn over the page”, “your turn”, and the ambiguous question “Is it over?”. Environmental noise is included; this challenge omits the timer bystander.

| Condition | Correct keyword/sign-off (3) | Exact false ends (7) | Ova/older false ends (7) |
|---|---:|---:|---:|
| clean | 3/3 | 1/7 | 2/7 |
| hfp-quiet | 3/3 | 1/7 | 2/7 |
| street-20dB-1pct | 3/3 | 1/7 | 2/7 |
| cafe-10dB-3pct | 1/3 | 0/7 | 1/7 |
| wind-0dB-5pct | 2/3 | 1/7 | 2/7 |
| car-10dB-3pct | 3/3 | 1/7 | 2/7 |
| kitchen-5dB-1pct | 2/3 | 0/7 | 2/7 |

Isolated “Over” became “however” in café/wind; some noisy sign-offs were lost. Adding ova/older recovered none of these misses and introduced false ends for “I am getting older” in every condition. Exact matching already cannot distinguish a trailing keyword from the question “Is it over?”. Use the existing configurable “your turn” phrase if that ambiguity is common. No fuzzy alias is enabled in the app. The longer original positive clip therefore overstates short-keyword robustness. See [raw challenge](hfp-keyword-challenge-2026-10-08.json).


## Hardware test recommendations

1. Check Diagnostics for the actual **Bluetooth HFP** input route and 8 kHz request; phone fallback is a different test. Record firmware/phone/route before comparing results.
2. In quiet, then café/street/wind, say “Remember my dog is named Maple” and “Add a note to Inbox: buy oat milk tomorrow.” Inspect the saved content and any unexpected camera/reading/tool activation. Synthetic scores are not a guarantee.
3. Dictate the picnic note with a three-second mid-sentence pause in Auto and Over. Verify umbrellas and blanket survive in the saved note. Check for an early write as well as an audible interruption. Prefer Over for long dictation.
4. Say “over”, “over and out”, “clover”, “over there”, and “my dog is getting older”. Check exactly one end, correct sign-off behavior and no alias false positives. Try a quiet final word and a bystander saying “over”; −12 dB beamforming is not speaker identity verification.
5. Stop speaking without the keyword and time the 10-second silence fallback in each noise bed. The energy detector may classify narrowband background/bystanders as speech. This eval does not validate that fallback.
6. Test speaker echo/barge-in while Gemini talks, walking/head motion, phone in pocket, capture controls and a Bluetooth reconnect. Check actual AEC/NS state on the recording session before enabling additional suppression.

## Scope, reproducibility and sources

Noise is procedural CC0 spectral/temporal stress audio, not field recordings. SNR is **pre-filter active wearer speech to environmental noise**; the −12 dB bystander is additional interference. Wind’s low-frequency energy is partly removed by the 300 Hz cutoff. No physical wind saturation, room impulse response or measured beamformer is modeled. Codec approximations use IMA ADPCM or block quantization, not bit-exact CVSD/mSBC. mSBC itself is wideband.

The simulator covers pauses inside the utterance. The inherited Live harness sends digital silence after the clip (five seconds in Auto, 1.2 seconds in Over), so persistent ambient noise during the final VAD wait and idle false triggers remain unverified. Fresh sessions and simulated effects also exclude real SCO echo, multi-hour operation, resumption and real external writes. No actual Pixel/glasses recording or on-device recognizer was available.

See [feature and reproduction commands](../features/hfp-audio.md), [dated source verification](../research/hfp-audio.md), [raw baseline measurements](hfp-audio-eval-2026-10-08.json), and [local ablation](hfp-resampler-ablation-2026-10-08.json). Verified 2026-10-08: [Vosk model/license](https://alphacephei.com/vosk/models), [polyphase filtering](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.resample_poly.html), [Android NS](https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor), [Android AEC](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler).

## Provider limit reached

Stopped the candidate after 7 attempts: Three consecutive errors. Timeout, close 1011, then timeout caused 15/30/60-second backoff and a stop, with no case retries. Total Live attempts stayed at 49 of the allowed 50; the final planned candidate was not attempted. All sockets were closed.

## Shipped change and verification

Android uses the tested causal half-band FIR for 8→16 kHz capture; 16 kHz phone
PCM is unchanged. It adds 0.94 ms delay and rejects interpolation images while
preserving the narrowband passband. Quiet Vosk word errors dropped from 46/137 to
19/137; pooled noisy errors were essentially flat/slightly worse (512/685 versus
515/685). **No noisy Gemini tool-accuracy improvement is established.** The seven
candidate attempts scored 4/7 versus 5/7 for their matched baseline; three
candidate attempts had timeouts/provider failure, including 1011. This small,
unrepeated comparison cannot attribute the difference to the resampler. The FIR
is a signal-quality improvement, not a claimed cure for wrong intents or noise.

Compiled Kotlin and Python FIR waveforms matched exactly on 5,592,960 samples.
The app adds AEC enabled/attachment and NS availability to Settings → Diagnostics;
SCO effectiveness and platform NS state remain unverified. Keyword matching stays
exact; no broad alias, pre-emphasis or additional platform NS is enabled.

```text
cd android && ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL in 11s
45 actionable tasks: 7 executed, 38 up-to-date
234 JVM tests, 0 failures, 0 errors, 0 skipped

cd backend && uv run pytest
488 passed, 3 warnings in 7.03s

HFP + existing voice-eval offline tests
35 passed, 2 warnings in 0.86s
```

No physical device, acoustic echo recording, Android/DAT STT or Google Docs write
was tested. Next: one controlled wearer session comparing quiet/café/wind,
short sign-offs, the three-second dictation pause, the ten-second silent fallback,
and speaker echo. Avoid drawing a population accuracy estimate from five original
synthetic utterances and the ten additional synthetic keyword phrases.
