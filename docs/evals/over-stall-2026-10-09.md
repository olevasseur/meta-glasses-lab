# Over stalled-turn evaluation — 2026-10-09

The supplied hardware logs expose a client deadlock: the old detector counted
silence before any speech, closed the manual activity exactly ten seconds after
listening began, then gated all uplink while waiting indefinitely for Gemini.
There was no deadline for a server that supplied no reply events. This client
failure is reproduced by JVM tests. The reason Gemini supplied no events on that
particular Pixel 10 Pro / Ray-Ban Meta HFP turn remains unverified.

## Fix and automated coverage

A fresh turn cannot end for silence until speech is detected. Changing local
recognition hypotheses reset the PCM silence counter; energy supplies a fallback.
Only ten continuous seconds since the latest speech (or the selected sign-off)
ends the turn. Missing PCM is not silence. There is no maximum speaking duration.

After End, eight seconds without relevant events reopens the activity. Input-only
progress has a 45-second bound. Empty non-tool completion reopens immediately.
Tool calls retain the wait through their result and spoken continuation, bounded
at 45 seconds from End. Timeout cancels local tool work and suppresses late
results/duplicate call IDs without retrying side effects. Tool timeout plays the
existing 80 ms earcon; no-reply/empty completion recovery is silent. Stop and
reconnect cancel the watchdog; audio suspension defers recovery until resume.

JVM tests exercise the real `GeminiSession` socket send gate with fake sockets and
clock: End → rejected PCM → no server events → new Start at 8 s → accepted PCM;
empty completion; input-only progress; >8 s tool work; early tool-generation
completion; tool result + actual reply; 45 s timeout and late-result suppression;
suspension and Stop; unchanged PTT behavior. Detector/controller tests cover
three-minute silence before speech, three-minute continuous speech, fresh silence
intervals, quiet partials with duplicate suppression, first/last speech logs
without transcript text, and low-level 8→16 kHz PCM through the actual FIR.

## Real Gemini Live probe: 2 sessions, 5 activities

Used the existing voice-eval production `/token` handler and constrained v1beta
WebSocket, `gemini-3.8-live`, header authorization, locked configuration and minimal
model/sessionResumption setup. No real tools, Google Docs writes or new TTS calls.
The cached `small-joke` fixture supplies control speech. Digital silence is exactly
320,000 zero PCM bytes (10 seconds, mono PCM16/16 kHz), paced in 20 ms frames,
between Start and End, with no post-End PCM. Both sessions closed normally.

Times below are seconds after activityEnd. First model content included audio.

| Session / activity | First input transcription | First model/audio | turnComplete | Audio bytes |
|---|---:|---:|---:|---:|
| Warm / healthy speech control | 0.237 | 0.974 | 4.871 | 188,160 |
| Warm / digital silence | 0.916 | 8.161 | 12.341 | 197,762 |
| Warm / subsequent speech, same socket | 0.333 | 4.188 | 9.011 | 232,800 |
| Fresh / digital silence | 0.436 | 0.962 | 4.746 | 180,960 |
| Fresh / subsequent speech, same socket | 0.427 | 0.885 | 4.833 | 186,242 |

**Gemini replied to both silent activities. The provider stall was not reproduced.**
Even digital silence elicited transcription events; content was not retained.
These measurements support treating input transcription as progress before the
first audio (one reply exceeded 8 seconds). They do not guarantee that every empty
activity gets a reply. Both subsequent Start/audio/End exchanges worked, but the
silence activities had already completed: actual recovery from a missing server
completion is verified by JVM fault injection, not claimed from these API runs.

The warm session allowed 20 seconds of observation after silent End; the fresh
session allowed 8 seconds. A response ended observation early in both. Total
Live session budget used: **2 of the user's maximum 10**, no retries.

[Sanitized raw timing/usage results](over-stall-2026-10-09-live.json).
Reproduce once with an unused output path:

```sh
PYTHONPATH=scripts UV_CACHE_DIR=/private/tmp/mgl-overstall-uv \
  uv run --project backend python -m voice_eval.over_stall \
  --env /path/to/.env --output /tmp/over-stall-new.json
```

## HFP energy audit

Reused the six case/mode rows from the existing HFP evaluation (five distinct
synthetic utterances; the long dictation is duplicated across modes). Applied
`hfp-quiet` at the existing seed, then the app-equivalent causal FIR from 8 to
16 kHz. Tested original level and additional ×0.25 / ×0.1 PCM gain. Computed
DC-removed RMS and crossing rate per 20 ms frame with the same 100 ms attack and
bounded 200 ms release. Active-frame statistics exclude frames below 100 RMS;
these are synthetic signal levels, not wearer measurements.

| Gain | Old 700/450: clips with detected speech | Over 350/225: clips with detected speech |
|---|---:|---:|
| 1.0 | 6/6 | 6/6 |
| 0.25 (−12 dB) | 6/6 | 6/6 |
| 0.1 (−20 dB) | 0/6 | 6/6 |

At original level, per-clip median active-frame RMS was 1,944–3,350; at ×0.1 it
was 334–377. The old threshold missed every attenuated clip. Over now uses
350/225; other features retain the default 700/450. Recognizer partials remain
speech evidence regardless of energy. Crossings and attack/release guards remain.
The JVM noise regressions still reject DC, clicks, broadband/HFP hiss and weaker
background tone; a 500-amplitude voiced tone is deliberately within the new gate.

The offline Python audit's accepted-frame counts were checked against the
**compiled production Kotlin `SpeechEnergyDetector`**, feeding exactly the same
18 quantized waveforms into both threshold pairs: **36/36 counts matched**.
[Raw energy results](over-stall-2026-10-09-energy.json).

```sh
PYTHONPATH=scripts UV_CACHE_DIR=/private/tmp/mgl-overstall-uv \
  uv run --project backend --with numpy --with scipy \
  python -m voice_eval.over_energy > /tmp/over-energy.json
```

## Required checks

```text
cd android && ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL
280 JVM tests, 0 failures, 0 errors, 0 skipped

cd backend && uv run pytest
626 passed, 3 warnings in 11.86s
```

Build environment: JDK 17 and the installed Android SDK. Redirected Gradle cache
and Android user home to writable `/private/tmp` locations because the sandbox
cannot create the default debug keystore under `~/.android`. No build configuration
or production signing settings changed. Backend tests used a writable uv cache.

## Unverified / next hardware check

No physical device, HFP field recording, installed on-device recognizer, acoustic
echo, or Google Docs write was exercised. Synthetic detection is not recognition
accuracy or speaker identity; the lower gate can increase background false speech.
Actual no-event recovery on the Pixel/glasses, delayed provider callbacks without
user-turn IDs, tools with ambiguous side effects, and call/focus interruption need
the [hardware checklist](../hardware-test-plan.md#over-stalled-turn-recovery--2026-10-09).
A tool-generation completion is ambiguous around a fast result, so tool turns
wait for actual reply content or the overall deadline instead of treating that
early completion as an empty assistant answer.
