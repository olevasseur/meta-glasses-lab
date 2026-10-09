# Over latency — 2026-10-09

## Scope and outcome

Four real `gemini-3.8-live` constrained Live sessions, three consecutive manual
turns each. **12/12 produced audio and turnComplete; zero tool calls.** Median
activityEnd→first audio **1.095 s**, range **0.840–6.427 s**; **10/12 under 2 s**.
The 16.739-second hardware outlier was not reproduced or conclusively fixed.
The client warm-up bug is fixed in code, with physical-device validation pending.

The oracle emits activityEnd after the WAV plus the unchanged 1.2-second grace.
This measures the boundary after a sign-off is committed, not Android speech
recognition time. WAV-end→audio median was **2.296 s**. No speaker playback is
included. “Typical <2 s” is met for End→audio in this small protocol sample,
not for spoken-word→audible-reply or every request.

## Measurements

All values below are milliseconds after activityEnd except WAV→audio. First model
content coincided with first audio in every turn. Times were captured by the
concurrent socket receiver, not transcript storage/UI callbacks.

| Session / PCM | Turn | First input transcription | First model/audio | turnComplete | WAV→audio |
|---|---|---:|---:|---:|---:|
| 1 / clean16k | single | 247 | 1119 | 5042 | 2319 |
| 1 / clean16k | immediate_next_turn | 2016 | 3073 | 6407 | 4274 |
| 1 / clean16k | missed_first_over | 5377 | 6427 | 9946 | 7628 |
| 2 / clean16k | single | 494 | 1176 | 2855 | 2377 |
| 2 / clean16k | immediate_next_turn | 357 | 937 | 2711 | 2138 |
| 2 / clean16k | missed_first_over | 649 | 1183 | 3370 | 2384 |
| 3 / hfp8k-fir16k | single | 381 | 840 | 2660 | 2041 |
| 3 / hfp8k-fir16k | immediate_next_turn | 362 | 887 | 1663 | 2087 |
| 3 / hfp8k-fir16k | missed_first_over | 404 | 933 | 3095 | 2135 |
| 4 / hfp8k-fir16k | single | 966 | 1490 | 3585 | 2691 |
| 4 / hfp8k-fir16k | immediate_next_turn | 219 | 1071 | 3155 | 2272 |
| 4 / hfp8k-fir16k | missed_first_over | 319 | 923 | 2612 | 2124 |

The missed-first-over case streams the same request, eight seconds of silence,
and the request again within one activity, ending only after the second “over”.
It does not leave the protocol in an inconsistent state. The 6.427 s slow turn
already waited 5.377 s for input transcription; first audio followed 1.050 s
later. Later sessions with the same repeated request returned within 1.2 s.
Every input transcription arrived after activityEnd: a transcription-only
sign-off detector would deadlock these turns until the silence timeout.

## Root causes and fix

- **Verified in source:** Over previously destroyed recognition after every turn,
  retried DAT on each new turn, and discarded PCM before its pipe existed. DAT
  readiness waits have an 8-second timeout. The observed 8.233/8.230 s intervals
  strongly match this path; the exact DAT wait that expired is inferred.
- **Fixed:** Over starts on-device shared-PCM recognition directly, keeps the
  segmented source alive with zeros during playback, and only resets turn
  hypotheses/silence. A 512 KB / 16 s queue preserves startup/restart and partial
  writes; sustained failure is visible. Service-driven restarts retain unsent PCM.
  Gemini transcription can supply a suffix while local recognition is preparing
  or unavailable, but is not relied upon to protect startup audio.
- **Verified in source/JVM integration:** End is queued synchronously at committed
  sign-off, before any recognizer cleanup; audio is gated under the same lock.
  Repeated End is ignored; the next Start precedes the next audio. No extra
  audioStreamEnd or duplicate prompt is sent.
- **Unresolved hardware cause:** The 16.739 s metric starts after End was queued.
  Recognition cleanup occurs before that point and cannot explain it. Network
  backlog, server input processing and provider scheduling remain hypotheses.
  Real tests demonstrate upstream/transport delay despite correct boundaries;
  they cannot identify which caused the reported incident.
- **New diagnostics:** sign-off commit, activityStart/End accepted by OkHttp,
  queue bytes, input bytes, first input transcription, first model content/audio
  and turnComplete. UTC and monotonic timestamps; no transcript contents.
  “Sent” is enqueue acceptance, not server receipt. Socket arrival logs precede
  Main-thread dispatch to expose UI delay separately.

## Reproduction and budget

Used local macOS Samantha synthesis, not another runtime AI provider:

```sh
say -v Samantha -r 165 -o /tmp/mgl-overlatency.aiff 'Can you hear me? Over.'
afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/mgl-overlatency.aiff /tmp/mgl-overlatency.wav
PYTHONPATH=scripts USAGE_EVAL_PERCENT=100 UV_CACHE_DIR=/tmp/mgl-overlatency-uv \
  uv run --project backend --with numpy --with scipy python -m voice_eval.over_latency \
  --wav /tmp/mgl-overlatency.wav --env /path/to/private/.env \
  --output /tmp/mgl-overlatency-live.json
```

The worktree had no `.env`; the existing main checkout credential file was read
without printing/copying its contents. The initial local 10% quota reservation
refused four sessions before opening any connection. Process-local
`USAGE_EVAL_PERCENT=100` permitted the explicitly authorized check; existing daily
soft caps remained in effect. **4 Live sessions used of the authorized maximum 40**;
no automatic retry, TTS API calls, or real tool writes. Token configuration is
production `/token` with disposable simulator memory and the full tool registry.
Raw timing/usage output is `/tmp/mgl-overlatency-live.json`.

PCM SHA-256 after conversion:

- clean16k: `2e5b538475475a981444b2515192cb38c8bce8812dd2c4667e4761421c7d1a5e`
- hfp8k-fir16k: `49aaba028981b5761d4401f0c1976bff6f3729bb22ae2b6e82751062a42cebc3`

The HFP case uses the existing seeded CVSD-like 8 kHz simulator and the FIR
upsampler port. It is not a physical Bluetooth codec/capture measurement. WAV
duration is about 1.498 seconds; maximum observed send lag was 7.0 ms.

## Validation and remaining work

Android command (JDK 17, Android SDK, writable caches under `/tmp`):
`cd android && ./gradlew assembleDebug testDebugUnitTest`

```text
BUILD SUCCESSFUL in 26s
261 JVM tests; 0 failures, 0 errors, 0 skipped
```

Backend: `cd backend && uv run pytest`

```text
620 passed, 3 warnings in 12.43s
```

Warnings: existing Android deprecated overrides/native-library stripping; backend
Starlette TestClient deprecation, audioop deprecation and experimental token API.
New tests cover repeated warm recognition, first-second sign-offs, buffering and
partial writes, early transcription fallback, immediate End/no trailing PCM,
privacy-safe boundary logs, and balanced multi-turn eval protocol.

Hardware remains untested here. Run [OVER-LATENCY-1](../hardware-test-plan.md#over-latency-retest--2026-10-09)
for persistent Pixel recognition, repeated identical phrases, delayed callbacks,
echo, service restart, reconnect and true sign-off→speaker latency. Incoming
Gemini transcript chunks lack a turn ID; rare late prior-turn chunks during a
local-service restart remain a fallback tradeoff. No claim is made that the
16.739-second incident or hardware p95 is fixed.
