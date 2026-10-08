# M3: Long-term memory

**Approval update — 2026-10-08:** Remember/recall remain immediate. Forget uses app-owned nod, capture or Approve; spoken yes and returned tokens never authorize deletion. See [device approvals](device-approvals.md) for configuration, limits and verified evidence.

Implemented and verified **2026-10-07**. Runtime AI remains Gemini only.

## Use it

1. Start a conversation normally (Auto, PTT or Over). User and Gemini
   transcript chunks upload every five seconds, up to 50 chunks per request.
2. Say “Remember that my dog is named Maple.” `remember` immediately saves the
   fact. For a correction, say “Actually, I moved to Montreal; remember that.”
3. Say “What do you remember about my dog?” or “What did we discuss about the
   harbour?” `recall` searches facts, summaries and transcript text, including
   fuzzy keyword matches.
4. Say “Forget that” or “Forget my dog's name.” Gemini resolves the topic, reads
   the matching saved facts, and asks you to nod or press capture. Tap Approve on
   the card as an alternative. Shake/Deny/60-second timeout declines; backend
   requests expire after five minutes. Changed facts require a new request.
   Spoken yes and tool confirmation tokens never authorize deletion. This is
   saved-fact deletion, not raw-history erasure.
5. Tap **Stop** (or capture HOLD to stop while leaving controls armed).
   Backgrounding/rotation keep the service and the same memory session alive.
   Final chunks upload before the backend summarizes. Next **Start** gets updated
   memory in its locked system instruction. No additional memory button is needed.

A pending-upload error means the app retained its outbox. Leave/reopen the app
with the backend reachable to retry. Start is disabled while normal Stop finishes.
A new Start gets a new UUID; Gemini WebSocket reconnections keep the same UUID.

## Backend and configuration

Run the backend from `backend/` using the existing `uv run uvicorn app:app --port
8787` command, **one worker**. Dependencies are unchanged. Repo-root `.env`:

```dotenv
GEMINI_API_KEY=<backend only>
APP_SHARED_SECRET=<existing shared secret>
GEMINI_LIVE_MODEL=gemini-3.8-live
GEMINI_SUMMARY_MODEL=gemini-3.5-flash-lite
```

The default text model is stable and currently has free-tier input/output;
project quotas still apply. The override does not change the Live model. There
are no embeddings or paid fallback models. Summary failures retain transcripts
and leave the session open for a later retry.

SQLite creates `backend/data/memory.db` automatically with owner-only file
permissions; the whole data directory, including SQLite sidecars, is gitignored.
It stores sessions, ordered transcript chunks, session summaries, durable facts,
and forget confirmations/suppression records. It is a single-user database shared
by everyone who has the app secret. No audio or API keys are stored in it.

The Android outbox lives in `noBackupFilesDir/memory-outbox`, outside Android
backup. Atomic file replacement retains chunk IDs across lost replies/restarts.
Successful uploads remove acknowledged chunks; successful session closure removes
the outbox file. On relaunch, unfinished sessions are closed after upload.

All routes require `X-App-Secret` and use the existing no-store response policy.

| Route | Request / behavior |
| --- | --- |
| `POST /transcripts` | `{"session_id":"UUID","turns":[{"seq":0,"speaker":"user","text":"Hello","timestamp":"2026-10-07T12:00:00Z"}]}`; `speaker` is `user` or `model`, 1–50 chunks, max 12,000 characters each, aware timestamp. |
| `POST /sessions/{id}/end` | Empty body or `{}`; returns `status`, `summary`, extracted `facts`. Repeated closure returns cached summary without another Gemini request. Empty sessions need no Gemini request. |
| `POST /tools/remember` | `{"fact":"The user lives in Toronto.","key":"user.home_city"}`. Optional stable key permits updates; normalized identical text deduplicates. |
| `POST /tools/recall` | `{"query":"Toronto"}`; up to ten scored matches with source kind and record/session ID. |
| `POST /tools/forget` | `{"query":"Toronto"}` proposes exact facts; the app/dashboard approves through a separate route. |
| `POST /token` | Existing contract. Adds up to 30 recent facts and the latest three summaries to the base prompt, trimming oldest items to an 8,000 UTF-8-byte combined budget (roughly 2k English tokens). |

`(session_id, seq)` is immutable and unique. Duplicate identical chunks are safe
to replay; conflicting data or new chunks after session end return 409. The app
only calls end after all chunks are acknowledged. There is no automatic tool-call
retry. Existing generic Android forwarding and function-response encoding work
for all four tools without changing GeminiSession or LiveProtocol.

Summaries use `google-genai` structured JSON output and local Pydantic validation.
They extract user-supported durable facts, reuse existing semantic keys for
corrections, and avoid credentials and hypothetical/model-only claims. Fact and
summary commits are transactional. Invalid/blocked/failed responses commit nothing.

## Limits and follow-ups

- No physical Pixel/glasses voice workflow was exercised. Verify spoken remember,
  recall, confirmation, Stop/Start, airplane mode recovery, and process death on
  the target device. Disk writes currently run on Main; profile long offline
  sessions before moving outbox storage onto an IO worker.
- Summary extraction and semantic key reuse are probabilistic; a correction can
  miss an earlier differently keyed fact. Normalized exact text deduplication and
  same-key updates are deterministic. Older summaries remain historical evidence.
- “Forget” deletes saved facts and records suppression terms/keys. Matching
  history is hidden from recall/injection, and matching extracted facts cannot
  reappear automatically. Semantic paraphrases can evade fuzzy suppression. Raw
  transcripts and suppression records remain on disk; this is not a privacy erase.
  A current Gemini session may already contain the fact; restart to refresh its
  context. Explicit `remember` of the same key or exact fact can restore it.
- Recall scans local text and uses keyword overlap plus RapidFuzz; no semantic
  embeddings. It joins adjacent chunks from the same speaker, retains source
  distinctions, returns at most ten results, and truncates each to 2,000 characters.
- Summary input uses the most recent 120,000 transcript characters and up to 100
  current facts. Full chunks remain stored. Consider incremental summaries/FTS
  if personal history grows. One worker serializes memory operations, including
  the bounded Gemini request, to prevent competing summaries/forget races.
- Pending requests retry while the conversation service is alive, every five
  seconds after the preceding flush completes;
  OS termination can interrupt cleanup, and the next launch recovers saved files.
  Storage has no automatic retention or encryption beyond platform permissions.
- The 8,000-byte prompt budget is an approximation, not a tokenizer guarantee.
  Free-tier quota and Google's free-tier data-use terms still apply.

## Validation and sources

- `cd backend && uv run pytest`: **75 passed**, two existing dependency warnings.
- `cd android && ./gradlew assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL**;
  **26 tests, zero failures/errors**. JDK 17 / installed Android SDK, with writable
  temporary Gradle and Android user caches because global caches are sandboxed.
- New tests cover immutable/replayed chunks, summary retries/concurrent end,
  fact updates/deduplication, fuzzy recall, confirmation expiry/change protection,
  forgotten-fact suppression, prompt budget/locks, SDK wire schema, and Android
  batching/relaunch/retry/append-during-upload plus generic tool forwarding.


Real smoke: `cd backend && uv run python ../scripts/smoke_memory.py --live-setup`.
Uses a disposable database and fake transcript only. Verified extracted facts:
“The user's dog is named Maple,” “The user lives in Toronto,” and “The user
prefers short spoken answers.” Recall found Maple. A real `/token` followed by
constrained WebSocket setup returned `setupComplete` with the memory instruction
and all four tools. Tokens and secrets were not printed; the real memory DB was
not modified by this smoke.

The client still uses `Authorization: Token <name>` and minimal setup with
`sessionResumption: {}`. `lock_additional_fields=[]` remains unchanged and memory
is part of the locked `systemInstruction`; session resumption remains client-owned.
This smoke did not exercise a rollover or actual audio/tool invocation by Gemini.

Verified sources, **2026-10-07**:
- [Gemini 3.5 Flash-Lite model](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite): stable model code, text output, structured outputs.
- [API pricing](https://ai.google.dev/gemini-api/docs/pricing): Flash-Lite free-tier availability.
- [Structured outputs](https://ai.google.dev/gemini-api/docs/structured-output) and [Python SDK](https://googleapis.github.io/python-genai/): JSON schema and SDK configuration; actual provider acceptance verified by smoke.
- [Ephemeral tokens](https://ai.google.dev/gemini-api/docs/ephemeral-tokens) and existing [M1](../research/m1-android-notes.md)/[M2](../research/m2-backend-notes.md) notes: preserved Live protocol. See [M3 research](../research/m3-memory-notes.md) for the schema compatibility finding.
