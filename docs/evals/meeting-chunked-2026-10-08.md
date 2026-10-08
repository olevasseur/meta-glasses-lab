# Chunked meeting evaluation — 2026-10-08

**Pipeline passed: all 21 chunks stored/transcribed, overlaps stitched, and real
Gemini minutes generated without a meeting Live session.** Recognition/minutes
quality has limits below. This is not a two-hour hardware endurance claim.
Machine-readable transcript, minutes, usage and attempt audit:
[meeting-chunked-2026-10-08.json](meeting-chunked-2026-10-08.json).

## Fixture and execution

Used `scripts/fixtures/meeting-20min.txt`: **2,230 spoken reference words**, four
macOS voices (Samantha, Daniel, Karen, Moira), 140 requested words/minute, five
seconds between speaker passages, and trailing digital silence to exactly
**1,200 seconds**. Voice labels/timestamps in the source were not read aloud.
The last four chunks are entirely silent; this is not twenty minutes of continuous
speech. All people and events are fictional. Waveform/reference hashes are in JSON.

The evaluator fed 16 kHz mono PCM16 WAV into the production `Meetings` and
`MeetingChunks` services, using 60-second cuts and two-second overlap. Hard cuts
intentionally crossed speech; Android's separate silence-boundary behavior is
unit-tested. Authenticated HTTP upload/receipt behavior is covered by route tests,
not a physical phone upload in this run. Isolated meeting DB/audio live under
`/tmp/meeting2-eval`; Google credentials stayed in the ignored environment.

All inference was serial. **34 non-Live audio attempts**, **one minutes call**,
**zero Live sessions**, **zero TTS API calls**, **zero Google Docs writes**.
The shared production retry/fallback helper and usage ledger were used. The
runner waits between calls, backs off 30 seconds immediately on 429/503, and
waits another 60 seconds before retrying retained work; three consecutive provider
failures stop that invocation. Later invocations resume the same durable queue.

- Initial pilot exposed seconds being returned in millisecond-named fields.
  Four successful pilot transcriptions were discarded and rerun after the schema
  changed to explicit fractional seconds with local conversion/validation.
- One later response failed timing validation and was retried without losing its
  queued WAV. The final prompt also states the audio duration explicitly.
- **Nine 503s** from Flash-Lite and **three 429s** from the verified 2.5 Flash
  fallback occurred. A fallback-first invocation transcribed one chunk. The
  primary eventually recovered and completed the remainder.
- Exact all-zero PCM now produces an empty transcript locally: final chunks
  17–20 made no Gemini calls. Nonzero room noise is not treated as digital silence.
- Conservative local reservations exhausted the default 10% eval allowance before
  the final minutes request. The final invocation explicitly used
  `USAGE_EVAL_PERCENT=11` (process only), retaining all previous reservations and
  the task's 150-audio-call limit. Production `.env` and provider quotas were not
  changed. The shared ledger contains one failed local reservation attempt that
  **never reached Google**, so ledger calls total 36 versus 35 actual API calls.

## Accuracy and minutes

| Measurement | Result |
| --- | --- |
| Uploaded/finalized chunks | 21 / 21 |
| WAV payload including overlap/headers | 39,680,924 bytes (39.68 MB) |
| Reference / recognized words | 2,230 / 2,223 |
| Substitutions / deletions / insertions | 90 / 40 / 33 |
| Raw lexical word error rate | **7.31%** (1 − WER = 92.69%) |
| Exact repeated boundary phrases, ≥3 normalized words | **0 words** |
| Final queued raw audio BLOBs | 0 |
| Final minutes | Generated from the stored stitched transcript |

WER lowercases and removes punctuation; numeral formatting and spelling variants
still count. It is not semantic accuracy. Zero exact boundary repeats is not proof
of perfect dedupe: near-matches, two-word repeats and genuine repeated phrases
remain ambiguous. The initial flawed timestamp pilot had a four-word repeated
boundary; the corrected run eliminated that measured duplicate.

Minutes correctly retained the conditional **Friday October 9** two-branch pilot,
**$200 cap**, 40 adults, privacy/staff-test prerequisites, success thresholds and
October 19 review. Two recap sentences were produced. **Owner attribution is
incomplete:** Jordan is named for the guide review, but most action owners are
null; the accessibility audit question is also missing. The audio transcription
contains name/word errors. Do not treat these minutes as an exhaustive or verified
assignment record. Chunk-local speaker hints deliberately do not infer identities
from synthetic or real voices. No extra model call was used to “repair” the score.

## Usage, cost and two-hour extrapolation

Reported usage across the entire engineering evaluation, including discarded
pilot output and the malformed response:

| Model | Ledger calls / failures | Input tokens | Candidate output | Total tokens |
| --- | --- | --- | --- | --- |
| gemini-3.5-flash-lite | 32 / 10* | 40,798 | 14,330 | 55,128 |
| gemini-2.5-flash | 4 / 3 | 2,062 | 576 | 2,881 |

`*` Includes the local reservation refusal; nine failures reached the provider.
Missing usage on failed requests is unknown, not proven free. Model 2.5 reports
243 additional tokens beyond input + candidate output; estimates price the whole
reported non-input remainder as output, including thinking. Its input is
conservatively priced entirely at the audio rate.

Using the [standard price table](https://ai.google.dev/gemini-api/docs/pricing)
checked **2026-10-08**: Flash-Lite input $0.30/M, output $2.50/M; 2.5 Flash audio
input $1/M, output $2.50/M. These are **paid-equivalent estimates**, not charges or
proof of this account's billing tier. Listed free tiers would have zero inference
charge within available allowance.

- Entire evaluation, including pilot/invalid-response overhead: **$0.0522**.
- The 17 successful non-silent transcriptions used in the final transcript:
  **$0.0381**. Final minutes: **$0.0040**. Combined useful output: **$0.0421**.
- A two-hour capture with **125 non-silent chunks** at the measured average cost
  projects to **$0.2801 for transcription**. This deliberately gives no credit for
  the synthetic fixture's silent tail. Minutes add roughly $0.02–$0.04 if six to
  seven maps and hierarchical reductions have similar token costs; **about
  $0.30–$0.32 total** is an estimate, not a two-hour billing measurement. Retries,
  speech density, models and summary structure can change it.
- Two offline hours need approximately **238.3 MB** WAV storage with this overlap.
  Per-chunk phone/backend RAM is bounded; accumulated queue storage needs disk.

Successful non-silent chunk turnaround (including local pacing) was **17.76 s
median**, **16.77 s mean**, **26.70 s maximum**. At that mean, serial catch-up of
125 already-recorded chunks is about **35 minutes**, plus retries and minutes
work. During a healthy live recording, transcription usually finishes before the
next chunk is ready; the newest text still trails recording by a chunk duration.

Actual summed runner wall time was **1,306.27 s (21.77 minutes)**, including failed
attempts/backoffs and pilot reruns, excluding manual time between invocations.
Thus this outage-affected run was not faster than the source audio despite feeding
chunks without real-time sleeps. Two-hour end-to-end latency is **unverified**;
provider outages can leave any duration of backlog, safely queued for recovery.
No backend/phone RSS or battery endurance claim is made.

## Validation and reproduction

```text
cd backend && uv run pytest
585 passed, 3 warnings in 8.14s

cd android && ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL in 24s
45 actionable tasks: 45 executed
251 JVM tests, 0 failures, 0 errors, 0 skipped
```

Used JDK 17, installed Android SDK, writable temporary caches and `--no-daemon`
because a pre-existing Gradle daemon lacked this worktree's write permissions.
New tests cover byte-exact overlap, silence/hard cap, final partials, 2+ hour chunk
sequences, on-disk restart recovery, lost HTTP replies/offline catch-up, durable
idempotency/cooldown, manifest completeness, timestamp validation, digital silence,
one-operation end polls, minutes from chunks, wake/button handoff, timeout/playback
closure, wake preference persistence and normal-token translation exclusion.

Reproduce/resume with macOS voices installed:

```sh
uv run --project backend python scripts/eval_meeting_chunked.py --live
```

The script reuses saved audio/checkpoints and enforces the call counter across
runs. It also enforces the usage meter's local evaluation allowance; do not assume
150 calls are all available on the provider. Ordinary offline fixture generation
uses the same command without `--live`. Do not delete the call ledger to reset the
budget. The final minutes retry here used the explicit process-only allowance
noted above. No sessions or evaluator processes remain running.

Remaining checks: physical Pixel/glasses and both microphone routes, wake accuracy
with screen off, HFP echo while answering, network loss on-device, call/focus
pauses, disk pressure, two-hour battery/thermals, natural overlapping speakers,
owner/decision completeness, and real Docs export with existing Tier 1 approval.

Sources, verified **2026-10-08**:

- https://ai.google.dev/gemini-api/docs/audio
- https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite
- https://generativelanguage.googleapis.com/v1beta/models
- https://ai.google.dev/gemini-api/docs/pricing
- https://developer.android.com/reference/android/speech/RecognizerIntent
- [Protocol/source research](../research/meeting-chunked.md)

The catalog also confirmed `gemini-3.8-flash` during outage investigation; no
inference used it. No token locking or client-owned resumption fields changed.

## Files changed

- `.env.example`
- `android/app/src/main/java/com/olevasseur/glassesapp/BackendClient.kt`
- `android/app/src/main/java/com/olevasseur/glassesapp/ConversationService.kt`
- `android/app/src/main/java/com/olevasseur/glassesapp/ConversationSettings.kt`
- `android/app/src/main/java/com/olevasseur/glassesapp/ConversationViewModel.kt`
- `android/app/src/main/java/com/olevasseur/glassesapp/GeminiSession.kt`
- `android/app/src/main/java/com/olevasseur/glassesapp/MeetingAssistant.kt`
- `android/app/src/main/java/com/olevasseur/glassesapp/MeetingCapture.kt`
- `android/app/src/main/java/com/olevasseur/glassesapp/ui/MeetingControls.kt`
- `android/app/src/test/java/com/olevasseur/glassesapp/ConversationSettingsTest.kt`
- `android/app/src/test/java/com/olevasseur/glassesapp/GeminiSessionTest.kt`
- `android/app/src/test/java/com/olevasseur/glassesapp/MeetingCaptureTest.kt`
- `backend/meeting_api.py`
- `backend/meeting_chunks.py`
- `backend/meetings.py`
- `backend/resilience_work.py`
- `backend/security_boundary.py`
- `backend/tests/test_meeting_chunked_eval.py`
- `backend/tests/test_meeting_chunks.py`
- `docs/architecture.md`
- `docs/evals/meeting-chunked-2026-10-08.json`
- `docs/evals/meeting-chunked-2026-10-08.md`
- `docs/features/meeting-chunked.md`
- `docs/features/meeting-mode.md`
- `docs/research/README.md`
- `docs/research/meeting-chunked.md`
- `scripts/eval_meeting_chunked.py`
