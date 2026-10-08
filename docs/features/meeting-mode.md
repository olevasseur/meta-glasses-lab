# Meeting / lecture mode

Implemented **2026-10-07**. Gemini Live listens; Gemini text models produce minutes.

## Voice and buttons

- In a conversation, say **“Start a meeting called Harbour pilot.”** The
  `start_meeting(title?, doc_name?)` tool switches to a fresh meeting token.
- Or tap **Start meeting**. The optional title defaults to “Untitled”. Leave the
  document field empty for a new Doc; enter an **exact unique title or alias** to
  append to an existing Doc. The same optional destination works by voice.
- **Phone mic on table** defaults on for meetings. Turn it off before starting
  to use the existing glasses/Bluetooth route. Phone meeting capture uses the
  general MIC source. Glasses microphones focus on the wearer; other participants
  may be faint. Put the phone near the group and check the live transcript.
- While recording, ordinary discussion cannot play assistant audio or run tools.
  Say **“Hey assistant, …”** (or **“Assistant, …”**) to request one response.
  Enable **Allow ‘over’ to address assistant** before starting if you prefer to
  finish a request with “over”. This is a meeting-specific option; idle silence
  never counts as a request. Ordinary phrases ending in “over” can trigger it.
- Say **“Hey assistant, stop the meeting”** (`stop_meeting()`) or tap
  **Stop meeting & prepare minutes**. The app drains final transcription for
  three seconds, uploads all saved chunks, and returns to a normal conversation.
  Once minutes and the Doc are saved, Gemini reads the two-sentence recap.
- The main **Stop** button also finalizes an active meeting but leaves audio
  stopped. A finished recap appears on screen and can be spoken on the next Start.
  Backgrounding stops capture, as with existing foreground conversations.

The screen stays awake while recording. Keep it open, powered and online for a
long meeting. The input transcript captures everything Gemini recognizes on the
selected microphone, including other audible speakers. There is no speaker
identification or guaranteed verbatim/overlap transcription. Only recognized
names/owners are used; unspecified owners remain null.

## What is saved

A new document is **“Meeting – <title> – <UTC date>”**, in **“Glasses Meetings”**.
An existing destination receives an appended, dated meeting section. Sections:

1. Summary
2. Decisions
3. Action items, with owners and due dates when mentioned
4. Open questions

Full ordered transcript chunks stay in the existing SQLite memory database.
The structured meeting result and processing checkpoints live in its `meetings`
table; readable minutes are stored in `summaries` for normal recall and session
context. Third-party meeting speech is **not** promoted to personal facts.
Model replies to addressed requests are stored as model transcript chunks and
excluded from the meeting summary input.

Android reuses the private atomic disk outbox: stable UUID and sequence IDs,
upload batches of at most fifty 12,000-character chunks, retry after lost replies,
and recovery/finalization after process restart. Starting another Live connection
never changes the active meeting UUID.

## Configuration and backend

```dotenv
GEMINI_LIVE_MODEL=gemini-3.8-live
GEMINI_SUMMARY_MODEL=gemini-3.5-flash-lite
# Optional separate text model override for meeting minutes:
GEMINI_MEETING_MODEL=gemini-3.5-flash-lite
```

Existing `GEMINI_API_KEY` and `APP_SHARED_SECRET` stay unchanged. No credentials
are added to Android. **Re-run `scripts/google_auth.py` and grant all scopes**
if previous consent lacks `drive.file`; creating the folder requires it in
addition to the existing Docs and Drive metadata scopes. No OAuth flow or
production Google Doc write was run as part of verification.

Run the backend with **one worker**. Meeting finalization shares its Notes
transport lock. All routes use the existing app secret and no-store middleware.

| Route | Behavior |
| --- | --- |
| `POST /token`, `{"mode":"meeting"}` | Automatic transcription plus locked meeting-context prompt; compression/resumption contract preserved. |
| `POST /tools/start_meeting` / `stop_meeting` | Validated client actions; Android switches capture. Calling these alone does not start microphone recording on the backend. |
| `POST /meetings/start` | `{"meeting_id":"UUID","title":"optional","doc_name":"optional"}`; idempotent metadata registration. |
| `POST /transcripts` | Existing memory chunk contract, using the meeting UUID. |
| `POST /meetings/{id}/end` | Call only after all transcript acknowledgements. Each call advances one map/reduce/export step; poll while `status=processing`. `status=ok` includes `minutes` and `doc_url`. |

The outbox polls every five seconds after its previous request. A map uses up to
24,000 transcript characters; reductions merge up to four partials at a time,
recursively. **No recent-transcript cutoff or meeting duration cap** is applied.
A failed step retains its checkpoint, already-completed summaries and transcript.
A backend restart needs the Android outbox (or an explicit end poll) to resume
processing; there is no detached job worker.

Document creation has a meeting-ID marker; the selected document ID is persisted
before append. An atomic, revision-guarded Docs batch writes both minutes and a
retry marker. Repeated end requests do not repeat a completed append. This is a
single-worker recovery design, not a distributed exactly-once guarantee.

## Silence and long-session behavior

Manual activity held open produced no transcript in a real test, so it is not
used. A scribe prompt sometimes generated audio despite instructions. Therefore
Android checks recognized input **before** accepting output, discards unaddressed
audio, and rejects unaddressed tool calls. The final meeting-context prompt allows normal responses; silence is enforced
by the client, so discarded provider generation can still incur Gemini usage.
A recognized wake request that gets no
model response is replayed once as explicit text after two seconds of transcript
quiet. This counters overly silent scribe behavior without treating ordinary
meeting speech as a request. The replay response window is bounded to thirty
seconds. Missing/clipped wake words fail closed; repeat “Hey assistant” or use
the UI. Provider response can still be absent; the text fallback is best effort.
Prompt obedience is never the audio gate.

Live goAway/disconnect resumption, refreshed tokens and sliding-window compression
support continued capture beyond two hours. A thirty-second buffer holds unsent
PCM across reconnects. Overflow produces a capture-gap marker; already-sent but
untranscribed audio cannot be reconstructed. Rejected resumption can start a fresh
scribe connection while retaining meeting storage. Resumption context may lose
old details; backend summarization still reads the full saved transcript.

## Validation, limits and follow-ups

- Backend: `cd backend && uv run pytest` — **86 passed** at verification;
  existing SDK experimental-token and Starlette warnings only.
- Android: `cd android && ./gradlew assembleDebug testDebugUnitTest` —
  **BUILD SUCCESSFUL**, 35 JVM tests, including meeting gate, audio buffer, token selection,
  registration retries, restart recovery and finalization polling tests.
- Backend tests cover every character of a synthetic multi-hour transcript,
  multiple reduction levels, checkpoint recovery, immutable registration,
  post-stop ingest rejection, summary retention after Docs errors, exact
  destination matching, atomic retry markers and local JSON validation.
- Real Gemini: a **2,291-word fake 20-minute meeting**, two map calls and one
  reduction, produced the [recorded JSON output](meeting-mode-smoke.json). It
  includes the corrected Friday launch, $200 cap, assigned tasks, open questions
  and exactly two recap strings. Reproduce with
  `cd backend && uv run python ../scripts/smoke_meeting.py --live` on macOS.
- Real Live audio experiments tested transcription, silence limitations, wake
  behavior and accepted saved-handle resumption. See the [research record](../research/meeting-mode.md)
  for failures as well as successes. No secrets or personal data were printed.
- **Unverified:** physical glasses/Pixel capture, room pickup, spoken tool selection,
  end-to-end Doc/recap playback, natural goAway and fresh-token rollovers, and a
  two-hour real-time endurance run. Follow up on-device with at least two audible
  people, both mic routes, network interruption, Stop/Start and renewed Google consent.
- Capture is foreground-only. Network loss, process death, overlapping speakers,
  ASR errors and the bounded final drain can lose speech. No raw audio archive is
  stored. Summaries/owner attribution are probabilistic and should be reviewed.
- Missing quota, missing consent or a non-unique/incorrect destination leaves the
  durable job pending with a visible retry message. Fix backend configuration or
  the configured Google alias, then leave/reopen the app to finish. The current
  UI does not edit a stopped job's destination.

Verified sources, **2026-10-07**:

- [Live capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities)
  and [WebSocket reference](https://ai.google.dev/api/live): PCM, transcription,
  activity and client-content messages.
- [Ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens)
  and [session management](https://ai.google.dev/gemini-api/docs/live-api/session-management):
  preserved `Authorization: Token`, constrained setup, client-owned resumption
  and `lock_additional_fields=[]`.
- [Structured outputs](https://ai.google.dev/gemini-api/docs/structured-output):
  JSON schema, followed by local validation.
- [Drive folders](https://developers.google.com/workspace/drive/api/guides/folder),
  [app properties](https://developers.google.com/workspace/drive/api/guides/properties)
  and [Docs batch update](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/batchUpdate):
  destination creation and retry-safe append within the stated single-worker limits.
