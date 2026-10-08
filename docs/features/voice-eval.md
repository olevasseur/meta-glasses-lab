# Voice end-to-end evaluation

Implemented **2026-10-08**. A headless Python client sends real-time synthetic speech
to real Gemini Live through the production backend token and tool endpoints. It
covers all 42 assistant tools with 68 cases across all 14 feature documents, three
voices, noisy speech, small talk and a looked-at image that tries to launch Codex.
Dashboard and hands-free features have no voice tools: their cases check that
questions about controls do not invent or trigger actions.

## Run

From the repository root, with the existing gitignored `.env` containing
`GEMINI_API_KEY` and `APP_SHARED_SECRET`:

```sh
# Optional: regenerate missing fixtures with a free-tier-eligible Gemini TTS model.
PYTHONPATH=scripts uv run --project backend python -m voice_eval.batch_tts

# Reproduce the baseline (both prompt and declaration snapshots).
PYTHONPATH=scripts uv run --project backend python -m voice_eval --label before \
  --prompt scripts/voice_eval/baseline-system.md \
  --tool-declarations scripts/voice_eval/baseline-tools.json \
  --tts-model gemini-3.8-flash-tts --workers 3

# Evaluate the current prompt and descriptions against the same audio.
PYTHONPATH=scripts uv run --project backend python -m voice_eval --label after \
  --tts-model gemini-3.8-flash-tts --workers 3

cd backend && uv run pytest
```

Use a new `--label` or `--output /tmp/my-eval.json` when rerunning: existing labels
are rejected, never silently replaced. `--retry-errors` explicitly reruns only failed
infrastructure attempts with matching prompt/tool hashes and TTS model; earlier
error attempts remain in the JSON. Semantic misses are never retried by that flag. Default output is
`docs/evals/voice-eval-<date>.json` plus a Markdown report, checkpointed after each
case. `--case ID` is repeatable; `--timeout` bounds waiting after audio input.
Workers are separate processes so each has independent backend patches/storage.
The default is one worker; three were used for the recorded comparison.

For individual regeneration, e.g. after a batch is rejected:

```sh
PYTHONPATH=scripts uv run --project backend python -m voice_eval.generate \
  --model gemini-3.8-flash-tts --case note-inbox
```

Generation stops retrying a daily quota error. Minute-limit retries are bounded.
Batch requests are spaced 22 seconds apart by default. Model quotas and availability
are account-specific; no automatic paid-tier switch or billing change is made.
The 65 runnable WAVs and generation metadata are committed under
`scripts/voice_eval/audio/` (**6.3 MB**, below the 20 MB limit), so the recorded
comparison can reuse identical audio without spending TTS quota. Raw/failed source
batches live in its ignored `batches/` subdirectory; these can grow beyond 20 MB. Cache names
hash transcript, model, voice, style and format version. Pause/noise transformations
are deterministic. Batched speech must have the expected number of long silence
boundaries; otherwise it is retained for inspection and that batch fails. The
recorded duplicate-sentence correction is tied to its exact source audio hash.
Regeneration can differ because TTS sampling is nondeterministic; a new ambiguous
batch needs inspection or individual regeneration, not guessed boundaries.

## Voice scenarios and controls

No new Android button or runtime voice command is added. This developer harness
plays examples of the app's existing voice actions: “Remember my dog is Maple,”
“Set a pasta timer for eight minutes,” “Translate Spanish,” “Read this menu,” and
“Ask Codex to fix the tests in valiant.” Cases declare expected tools and argument
constraints, optional follow-ups, forbidden calls and synthetic prior context.
The oracle allows harmless Harbor/Harbour spelling variants for a spoken title.

Long rambling notes include a precisely measured 3, 4.5 or 6 second pause and finish
with “over”. Matched Auto and Over cases use identical audio, including a noisy pair.
Over sends activityStart, all PCM (including the pause), 1.2 seconds of sign-off
grace, then activityEnd. Auto sends PCM and continuous silence for real server VAD.
This tests Gemini's response timing; the harness knows the sign-off location and
therefore does not measure recognition of the keyword on a phone.

## Protocol and isolation

**Security merge — 2026-10-08:** the current harness uses the production device
approval boundary. Sensitive tool calls return proposals; model confirmation fields
cannot execute them. Forget/Codex cases reject `await_gesture_confirmation` as an
authorization path. Visual deletion uses `/visual/delete`, as Android does.
Approval state and diagnostic logs are disposable alongside memory. Offline checks
exercise approval/denial policy and single-use consumption with synthetic services;
the live voice cases stop at the proposal unless they ask an ordinary gesture question.
The recorded before/after results below remain historical evidence for their saved
prompt and tool snapshots; the merged prompt has not had a new credentialed evaluation.

The client mirrors `LiveProtocol.kt`: backend-issued ephemeral token in
`Authorization: Token ...`, constrained v1beta WebSocket, minimal model plus
client-owned sessionResumption setup, camelCase messages, 20 ms PCM16/16 kHz chunks with 300 ms microphone pre-roll.
A monotonic schedule paces audio and reports maximum upload lag. The receiver runs
concurrently, collecting input/output transcripts, all audio parts, tool calls,
usage, cancellations and resumption-update counts. No audio output is played.
A five-second quiet interval after turnComplete allows tool-response continuations
to arrive before closing a case. Duplicate call IDs reuse a response; cancelled calls do not deliver later responses.
Images are sent as realtime video and attached to the matching function response,
as Android does. Captured-image successes are not replayed from the response cache.

Each case calls the real FastAPI `/token` and `/tools/{name}` handlers, authentication,
Pydantic validation and dispatcher using an in-process TestClient. It does not enter
production lifespan or start jobs/schedulers/retention. Memory and project navigation
use disposable SQLite databases. Notes, Codex/research, journal generation, recipe
extraction/URL fetches and visual search use deterministic fixtures. All app-owned
tools are simulated with argument checks, fixture JPEGs, reading state, timers,
workouts and pending gesture questions. Timers never schedule actual OS alarms.
Mode-switch cases finish after an accepted command, where Android opens a fresh
session; subsequent mode behavior is outside these cases.

## Metrics and limits

- Tool selection: required calls present, no unallowed/forbidden tools, completed
  session. Reading requires the image-to-submit_reading follow-up.
- Arguments: declared exact/subset/substring checks plus backend/client validation.
  This is not a semantic judge of every possible argument. Errors and missing calls
  remain failures in the overall denominator.
- False triggers: unexpected tools in no-tool and image-injection cases; a separate
  unexpected-tool rate includes all cases. Premature confirmation arguments fail.
- Premature response: any audio, output transcript or tool call before activityEnd
  in Over, or before the final spoken fixture ends in Auto. Auto's long-pause cases
  exceed its 2000 ms patience; that is a mode limitation, not proof of a VAD bug.
- First-audio latency: arrival time minus end of input audio; negative means early
  output. Median/p95/sample count exclude responses with no audio and do not measure
  audible speaker latency. Mode switches can legitimately have no audio.
- Prompt size: system UTF-8 bytes, real Flash-Lite countTokens proxy for system and
  serialized declarations, hashes, plus raw provider Live usage. The proxy is not
  an exact accounting of Live's tool-schema encoding or billable input.

Recorded result: **63/68 → 68/68** tool selection and argument checks; negative
false triggers **1/10 → 0/10**; system prompt **1574 → 1435** tokens. Over remained
**0/3** premature; Auto still responded early in **2/3** deliberate long-pause cases.
All 42 tools were observed, with identical paired input hashes across 136 completed
Live sessions. See the [before/after report](../evals/voice-eval-2026-10-08.md).
A single stochastic
run on a development set cannot establish statistical significance or production
reliability. Real glasses hardware, UI controls, meeting output gates, continuous
watch, rollover/reconnect, physical gestures/alarms, acoustic quality and real
Google/Codex side effects remain unverified. No Android code changed.

Verified official sources, **2026-10-08**:
[Live protocol](https://ai.google.dev/api/live),
[ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens),
[TTS API/models](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation),
[free-tier eligibility](https://ai.google.dev/gemini-api/docs/pricing),
[token counting](https://ai.google.dev/gemini-api/docs/tokens).
See [research evidence](../research/voice-eval-notes.md).

Offline re-scoring (uses saved rubrics and raw results, no Gemini calls):

```sh
PYTHONPATH=scripts uv run --project backend python -m voice_eval.metrics \
  docs/evals/voice-eval-2026-10-08.json
```
