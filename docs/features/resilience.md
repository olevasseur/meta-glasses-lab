# Gemini outage resilience

Implemented and verified **2026-10-08**. Gemini remains the only runtime AI.

## Use by voice and buttons

Start the conversation normally. During transient Gemini failures, the connection
label shows the cause and retry delay. Leave the conversation on: retries continue
without the old eight-attempt cutoff, including during idle outages. **Stop** ends
reconnection and offline microphone capture. Audio interruption/call and existing
service controls still gate the microphone.

In an ordinary assistant conversation, local TTS says **“Gemini is busy,
retrying”** and **“Back online”**, with a short earcon. Each phrase is limited to
once per minute. Communication audio follows the existing glasses route (phone
fallback when that route is unavailable). Speech input is suppressed during local
announcements to avoid recording them as notes. With no installed local TTS voice,
the earcon and UI remain available.

Wait for **Offline voice ready** in the tool/status area, then say:

- **“Add a note buy milk”** — append `buy milk` to the existing **Inbox** document.
- **“Remember my keys are in the kitchen”** — save the fact directly on the backend.
- Anything else — save as **ask Gemini later**, without executing a local tool.

These utterances are transcribed by Android's **on-device** recognizer, using the
same PCM microphone stream. Offline capture needs Android 13+ and an installed
on-device English speech model. It operates during outages in Auto/PTT/Over;
it does not wait for a Gemini turn or a PTT button. Meeting and translator modes
keep their existing capture/role behavior and do not run the offline grammar.

**Diagnostics → Review offline captures** displays pending utterances and any
uncertain saves. **Diagnostics → Check Gemini health** runs the backend probe.
The web dashboard's **Status → Check Gemini Live (cached 1 minute)** uses the same
probe and reports availability, check time and cache status.

## Recovery and model configuration

| Failure | Behavior |
| --- | --- |
| 1011/internal, 1013/try-again, quota/overload (including 1008), 429, 5xx, network loss/timeouts | Retry with equal-jitter exponential delay |
| Expired token / Gemini 401 | Obtain a fresh token, retaining a still-valid same-model handle |
| Explicit invalid/expired resumption | Start a fresh context |
| Unknown 1008 | Retry fresh access; do not label it a field-mask error |
| Explicit invalid configuration, model/access rejection, backend 401 | Stop and show a configuration/authentication error |

Delay ceilings are 1, 2, 4, 8, 16, 32, **60 seconds**, with each actual delay chosen
between half and all of the ceiling. After three consecutive failures, Android
requests the next model with `POST /token {"avoid_models":[...]}`. After every
candidate has failed, it cycles through the configured list again. A connection
must remain established for 60 seconds to reset failure counters, so a rapid
setup-success/1011 loop does not reset backoff. A model change clears the resumption
handle and old tool-response state; it never replays old tool writes into a new
context. Existing token field locking and client-owned resumption are unchanged.

Backend `.env`:

```dotenv
GEMINI_LIVE_MODELS=gemini-3.8-live,gemini-3.1-flash-live-preview
GEMINI_TEXT_MODELS=gemini-3.5-flash-lite,gemini-2.5-flash
```

Lists are ordered and deduplicated. Explicit lists take precedence over legacy
single-model environment settings. Without a list, a legacy model is tried first,
then the defaults. Android holds no Google API keys or refresh tokens.

`GET /health/gemini` requires `X-App-Secret`. It creates an ephemeral token and
waits for a cheap, audio-free Live `setupComplete`, trying the fallback if needed.
It caches successful **and failed** results for 60 seconds under a lock. Returned
fields are `status`, `checked_at`, `cached` and per-model statuses/close codes;
upstream exception text and credentials are excluded. The dashboard requires its
existing login and CSRF protection.

## Durable work and replay

All summary, caption, journal, meeting-minutes and recipe extraction requests use
one shared text retry/fallback helper: at most two rounds through the model list,
with jitter and an approximately 25-second overall HTTP budget. Gemini requires
at least ten seconds per explicit deadline; smaller remaining budgets defer work. SDK nested retries
are disabled. Permanent configuration errors are not treated as transient work.

- Conversation transcripts remain saved; failed summaries get a durable backend
  pending reference and retry in the background every minute.
- Meeting transcript and map/reduce progress remain saved. The phone's existing
  durable outbox keeps polling `end`, including after a phone restart. Exports
  retain normal approval requirements.
- Journal inputs remain saved; failed generations queue a date. Background retry
  regenerates a local draft from current data, respecting forgotten topics. It
  does not silently replay a protected Docs overwrite; normal scheduling or an
  approved journal request exports it.
- Failed visual captions save a thumbnail and a pending marker (`status=ok`,
  `caption_status=pending`, so the phone recognizes that the frame was saved). Background retries
  caption that thumbnail. Retention/deletion removes pending work too, preventing
  deleted frames from returning. Thumbnail retries have less detail than the
  original frame.
- Failed recipe extraction retains source text and kind in SQLite. Background
  retry prepares the result without replacing a newer active project. Ask to
  start the same project again to use the prepared result. Multiple pending
  sources are retained.

Offline utterances live in atomic private phone files and survive process death.
Only `remember` and append to an **existing resolved Inbox** execute directly via
`/tools` with an `X-Offline-Id` receipt. New-document creation, ambiguous targets
and all protected actions remain outside Tier 0. Configure Inbox before relying
on outage notes. Backend unavailability keeps pending items on the phone.

A durable server receipt binds each ID to its exact tool/payload. Repeated
requests return the saved result. An uncertain external write is marked **unknown**
and is never automatically repeated: inspect Inbox before repeating it. This
prevents duplicate notes, but a crash between claiming a receipt and writing can
require manual recovery. Text needing review stays on the phone even after Gemini
has been told about it.

When Gemini is ready and idle, it receives bounded batches beginning
`[while you were offline: …]`, including saved results and deferred questions.
The queue acknowledges these after a completed Gemini turn, not merely after
sending the WebSocket frame. A disconnect can repeat a recap; saved tool calls
are explicitly marked already handled. Interrupted recaps remain pending until
recovery. Utterances are limited to 2,000 characters; `remember` text over 500
characters is deferred to Gemini instead of sent as an invalid tool request.

## Validation, sources and remaining checks

The backend suite passes **484 tests** (three existing dependency warnings);
Android debug assembly and **226 unit tests** pass (zero failures/errors/skips). Added tests
cover failure classification, jitter/cap, retries beyond eight attempts, fallback
ordering, valid resumption/token refresh, local grammar, rate-limiting, durable
replay, receipt mismatch/uncertain outcomes, Tier 0 enforcement, caption deletion,
recipe source retention and authenticated/cached health.

Real API checks on **2026-10-08**: models list HTTP 200 confirmed both text defaults;
both configured Live models completed constrained ephemeral setup; a harmless
text generation initially succeeded through the shared helper. A later text probe
returned `GeminiUnavailable` within the retry budget and correctly deferred work. Google's earlier reported
outage was not active during those checks. No real Google Docs writes were made.

Verified source URLs (2026-10-08):

- https://ai.google.dev/gemini-api/docs/troubleshooting
- https://ai.google.dev/gemini-api/docs/live-api/session-management
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens
- https://generativelanguage.googleapis.com/v1beta/models (authenticated check)
- https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1
- https://developer.android.com/reference/android/speech/SpeechRecognizer
- https://developer.android.com/reference/android/speech/RecognizerIntent
- https://developer.android.com/reference/android/speech/tts/TextToSpeech

Unverified: actual glasses TTS/earcon routing, offline STT availability and accuracy,
HFP echo behavior, and recovery during an active real-world outage. Follow up with
one Pixel/glasses session: force a simulated 1011, say both local commands and a
question, restore service, verify exactly one Inbox append and the recap. Health
cache and background retry coordination target the existing single backend worker.
