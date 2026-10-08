# Long-session soak evaluation

Added 2026-10-08. This is an opt-in developer evaluation using Gemini Live and
production backend routes, with fictional speech and isolated SQLite/log storage.

Run from `backend/` on macOS (Python dependencies managed by uv):

```sh
PYTHONPATH=../scripts:. uv run python -m voice_eval.soak \
  --output /tmp/glasses-soak-new
```

The output directory must not already exist. Root `.env` supplies the existing
backend-only `GEMINI_API_KEY` and `APP_SHARED_SECRET`; credentials and resumption
handles are never written into the evaluation ledger. Allow about 2.5 hours plus
reconnect delays and minutes generation. Default durations are 7,200 seconds of
meeting audio, then 1,800 seconds of Auto audio. A concurrent 7,200-second backend
load posts ten transcript chunks and ten diagnostic entries each second, with
periodic exact transcript retries. At most one Live socket is open at a time.

`--meeting-seconds`, `--auto-seconds`, and `--load-seconds` override durations;
`--load-only` skips all Gemini and TTS calls. A shortened test is not endurance
evidence. macOS `say`/`afconvert` provide cached 16 kHz mono PCM speech, avoiding
shared Gemini TTS quota. Meeting clips loop with 8-second pauses and a 25-second
pause between rounds. Auto places a conversation-only fact at minute 2 and asks
for it at minute 25, interspersing questions and isolated remember/recall tools.

The runner uses main's shared [evaluation allowance](usage-meter.md) and
`USAGE_DB`. Before fixtures or provider access, it reserves the whole Live run:
one connection per started ten-minute interval plus five spare connections per
enabled mode, each budgeted at 10.5 minutes and 6,000 tokens/minute. The default
run reserves 25 attempts and 262.5 minutes; the default daily allowance refuses
it. Configure appropriate shared budgets deliberately before running. Every
attempt consumes a reserved slot, including failed connections; each has a
630-second deadline. Exhausting slots stops that mode as an incomplete soak.
Successful connections, including resumption, record actual duration and their
own usage metadata once through the common client meter.

Meeting map/reduce reuses the memory evaluator's text budget wrapper: every SDK
attempt reserves text/tokens before access, including production fallbacks and
retries, and records actual usage once. `text-budget.json` retains the sanitized
attempt ledger. Shared allowances are not refunded after failure. `--load-only`
needs no provider reservation. The isolated archives do not replace `USAGE_DB`.

No phone buttons are required. The equivalent hardware paths are **Start meeting**
and **Stop meeting**, or their voice commands, and an ordinary **Auto** conversation.
The headless test does not exercise Android controls, microphone capture, meeting
speaker/tool gates, Bluetooth, battery, or app process survival.

The runner reuses the headless client's constrained WebSocket helpers and backend
`/token`; backend configuration and `lock_additional_fields=[]` are unchanged.
It saves resumption handles in memory, reconnects after `goAway`, refreshes tokens
near expiry or access rejection, and backs off before retrying provider closes.
It stops after five consecutive failures. A failed resumption remains a failed
soak; no claim of uninterrupted context is made from a fresh session.

Incoming transcript chunks receive stable sequence numbers before asynchronous
batch uploads. Retrying an uncertain upload preserves the exact payload. The final
sequence/speaker/text hash compares received Gemini chunks with stored SQLite rows.
This measures persistence; speech-recognition omissions require comparing the TTS
manifest and actual input transcripts separately. A reconnect pauses the synthetic
audio timeline, unlike a real microphone: the run measures recovery and retention,
not lossless capture of speech during outages.

The CLI returns nonzero for incomplete Live capture, transcript persistence loss,
or failed backend load checks. A zero exit code alone does not establish speech
accuracy, conversational context retention, or successful minutes: inspect those
measurements separately. Lexical WER counts spelling and number-format changes;
it compares complete expected text for started clips, so an interrupted partial
clip can also count as missing words.

Long-history recall joins adjacent transcript chunks once, preserving main's
word-based retrieval, current-fact ranking, correction aliases and historical
labels. The legacy fuzzy scorer used by forget/suppression supplies its existing
65-point minimum to RapidFuzz; qualifying scores remain unchanged. Search still
scans history.

Artifacts include `meeting.jsonl`, `auto.jsonl`, `load.jsonl`, `summary.json`, a TTS
manifest, WAV fixtures, and isolated databases/logs. They contain only fictional
test material but should stay outside Git; commit the sanitized evaluation report.
Resource samples cover the combined backend/headless process. Peak RSS comes from
`getrusage` (current RSS via `ps` is unavailable in the execution sandbox), and CPU
is cumulative process CPU time. This is not an isolated server-process profile.

Production log rotation is exercised at its default five backups plus current,
1 MiB per file. Visual retention probes seed expired, pinned, and recent synthetic
rows, thumbnails and pending-caption markers, then call the real purge function.
Pinned/recent fixtures are checked and cleaned up. No AI caption calls occur.
Conversation transcripts have no time-based purge; their database should grow.
The production lifespan is disabled to prevent unrelated jobs/journal/AI replay;
retention scheduling and backend restart behavior are outside this measurement.

Meeting finalization waits until every queued transcript chunk is acknowledged.
It uses real Gemini map/reduce and stops when local minutes
exist, before approval or Google Docs export. Only Auto remember/recall tools may
execute, against isolated memory. Other tool calls receive an ignored response.

Verified sources (2026-10-08):

- https://ai.google.dev/gemini-api/docs/live-api/session-management — connection
  lifetime, `goAway`, compression and session resumption.
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens — constrained
  access tokens and expiry.
- https://rapidfuzz.github.io/RapidFuzz/Usage/fuzz.html#rapidfuzz.fuzz.WRatio — fuzzy
  score cutoff semantics.
- Existing local protocol verification: `docs/research/m1-android-notes.md`,
  `m2-backend-notes.md`, `meeting-mode.md`, and `voice-eval-notes.md`.

Actual outcomes and remaining limits belong in `docs/evals/soak-2026-10-08.md`.

## Combined merge verification — 2026-10-08

Merged with main's memory-quality and usage-meter features. Main's tool registry,
DataStore settings, device approvals, `lock_additional_fields=[]` and client-owned
`sessionResumption` remain intact. All 15 decision rows and all 33 research links
are retained. Tests cover long-chunk boundaries, qualifying fuzzy scores, exact
transcript retries, whole-run quota refusal, connection-slot exhaustion, separate
resumption usage, shared text accounting and provider-free load runs. No existing
assertions were weakened.

```text
cd backend && uv run pytest
575 passed, 3 warnings in 7.61s

cd android && ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL in 25s
45 actionable tasks: 45 executed
245 JVM tests, 0 failures, 0 errors, 0 skipped
```

Used JDK 17, the installed SDK and writable temporary uv/Gradle/Android caches.
No new credentialed soak, Google Docs, emulator or physical-device checks ran;
the original evaluation artifacts remain historical evidence.
