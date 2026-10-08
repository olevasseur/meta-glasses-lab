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

The same change fixes long-history recall by joining adjacent transcript chunks
once and supplying the existing 65-point threshold to RapidFuzz. Matching results
at or above that threshold remain unchanged; search is still linear in history.

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
