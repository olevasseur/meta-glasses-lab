# End-of-day journal

**Approval update — 2026-10-08:** On-demand journal replacement uses Tier 1 device approval. Scheduled journal automation is unchanged. See [device approvals](device-approvals.md) for configuration, limits and verified evidence.

Implemented and verified **2026-10-07**. Runtime AI is Gemini only. No Android
changes or new dependencies: the existing app forwards the three new voice tools.

## Use it

- Say **“Write my journal”** or **“Write my journal for yesterday.”** The backend
  gathers recorded activity, generates a warm first-person diary, and writes it
  to **Glasses Journal**, with a date heading and bullets for highlights, people
  mentioned, and to-dos carried over. Repeating it regenerates and replaces that
  date's block, including manual edits inside the block.
- Say **“What did I do last Tuesday?”** to read that saved journal. Dates accept
  `YYYY-MM-DD`, `today`, `yesterday`, or `last <weekday>`. “Last Tuesday” means the
  most recent Tuesday strictly before today. Future/invalid dates are rejected.
  A missing entry returns `not_found`; ask to write that day's journal first.
- Say **“Give me my morning brief.”** It returns yesterday's remaining tasks and
  recorded tasks due today or overdue. If yesterday's entry is missing or was
  generated before midnight, this generates/refreshes it in local memory without
  a Docs write. A saved entry for today supersedes yesterday's task snapshot.
- Normal Start/Stop buttons still control conversation/transcript upload. There
  is no new journal button. Stop finishes session summaries; unfinished sessions'
  already-uploaded transcripts can still be journaled.
- The existing `recall` tool also searches saved journal text, with source kind
  `journal` and the date as its ID.

## Run and configure

Use the existing Google OAuth setup (`uv run python ../scripts/google_auth.py`
from `backend/`) and run **one backend worker**:

```sh
cd backend
uv run uvicorn app:app --port 8787
```

Repo-root `.env` (alongside the existing backend-only credentials):

```dotenv
JOURNAL_ENABLED=true
JOURNAL_TIME=21:30
JOURNAL_TIMEZONE=America/Toronto
JOURNAL_DOC_ID=
GEMINI_JOURNAL_MODEL=gemini-3.5-flash-lite
```

The scheduler is enabled by default, polls every 30 seconds, and writes today's
entry at/after 21:30 local. `JOURNAL_TIME` requires 24-hour `HH:MM`; timezone uses
an IANA name, or the host's local timezone if omitted. Local day bounds use DST
rules. After midnight it finalizes yesterday once more to pick up late-evening
activity; this also catches up yesterday after downtime. Older missed dates can
be requested by voice. An early manual write doesn't suppress the evening run.
Pending Docs drafts retry without another model request; other failed scheduled
operations retry after five minutes. Restart the backend after changing config.

The dashboard's saved **Journal time** overrides `JOURNAL_TIME` and takes effect
on the next scheduler check. Without a saved preference, `JOURNAL_TIME` defaults
to 21:30. Malformed/unreadable optional dashboard settings fall back to that
environment setting. The dashboard shows the same default.

`JOURNAL_ENABLED=false` disables scheduling only. Voice tools and activity capture
still work. The journal model falls back to `GEMINI_SUMMARY_MODEL`, then
`gemini-3.5-flash-lite`. Live model, token locking and client-owned resumption are
unchanged. The backend must be running/awake; no OS alarm or notification is added.

Without `JOURNAL_DOC_ID`, the backend finds the exact title **Glasses Journal** or
creates it. Duplicate titles fail rather than guess; configure an explicit ID.
The selected ID is saved in memory and reused. Existing day entries remain bound
to their original document if config changes. Keep the first tab as ordinary
text paragraphs. Visible `[glasses-journal:DATE:begin/end]` lines delimit the day;
do not remove/duplicate them or the final blank separator. Writes re-read the
document, calculate UTF-16 indices, and atomically replace/style the block with
a required revision. Concurrent external edits cause a retryable failure instead
of overwriting unrelated text. Manual changes inside a day's block are replaced
on regeneration. New/backfilled days append at the end, not in date order.

## Memory and tool contract

All tools are `POST /tools/<name>` with the existing `X-App-Secret` header and
no-store policy. `write_journal` takes `{"date":"yesterday"}` or `{}`;
`read_journal` requires `date`; `morning_brief` takes `{}`. Read/write success
returns `date`, structured `entry`, rendered `text`, `generated_at`, and
`doc_status` (`synced` or `pending`). Only `synced` means Docs acknowledged the
write. An upstream error can leave a local pending draft; an explicit retry
regenerates and replaces the same day. Failed generation preserves the last entry.

The existing private `backend/data/memory.db` gains `journal_entries` (one row
per local date) and `journal_events` (timestamped JSON activity). Sources are:

- Uploaded transcript turns within the local day, with speaker labels and local
  timestamps. Model turns are context, not evidence of user activity.
- Session summaries whose complete set of turns falls within the day. Sessions
  crossing midnight use only the appropriate day's transcript turns.
- Successful notes and background research appends from this backend, including text and title.
  Uncertain/failed appends aren't claimed as saved. If local event recording
  fails after a successful Docs append, the response stays `ok` and includes
  `journal_tracking: failed`, avoiding a misleading invitation to duplicate it.
- Facts saved or corrected through memory, logged transactionally. Before this
  feature, only each surviving fact's latest version/timestamp is available.
- Remaining tasks from the most recent earlier journal. Gemini reconciles them
  with explicit completions/cancellations and resolves stated due dates.

Extension point: future producers can call
`journal_data.record_event(db, "job_finished", {"text": ...}, aware_timestamp)`
or `"place_seen"` while holding `memory.lock` and using `memory.db()`. Unknown
event kinds are already included as journal `events`; no job/place producer or
tracking service is introduced here. Successful research Docs appends are included
through the shared Notes callback; job completion events themselves are not added.

## Limits and follow-ups

- Morning briefs cover recorded journal tasks, **not Google Calendar**. New task
  completions or dates from today's conversations require writing today's journal
  before they appear in the brief. Changing an older journal doesn't rebuild
  later task snapshots automatically. This is not a dedicated task manager.
- Input is capped at 120,000 serialized characters, favoring prior tasks,
  summaries and events before transcripts. Omitted input is flagged to Gemini;
  long days may lose detail. Bullet counts and output lengths are bounded.
  Generation remains probabilistic: first-person prose may add subjective
  phrasing, and task extraction/completion/due-date accuracy needs human review.
- Only uploads already received are available. Notes made before installation,
  direct edits in other Docs clients, offline uploads arriving after finalization,
  and older overwritten facts cannot be reconstructed. Explicitly rewrite affected
  dates after late uploads. Note append plus local event logging isn't a distributed
  transaction; a process crash between them can omit that note from the journal.
- Forget suppression applies to collection, generated output, reads, recall and
  briefs. A matching saved entry is hidden as a whole. As with existing memory,
  this is **not archive erasure**: raw DB history and already-published Docs remain.
  Rewrite the day after forgetting to replace the published entry with filtered
  content. Fuzzy suppression doesn't guarantee every semantic paraphrase is hidden.
- One process serializes journal generation and Docs publishing using the memory
  lock and existing Notes transport lock. Long external requests delay other memory
  calls. Multi-worker/multi-host scheduling is unsupported. Use `JOURNAL_DOC_ID`
  to pin the destination; creation recovery relies on an exact Drive title lookup.
- Real Google OAuth/write/readback and Pixel/glasses voice testing remain follow-ups.
  Tests verify request construction, not account-specific Docs acceptance. Rich
  objects/tables in the journal body and damaged markers fail closed.

## Validation and sources

Combined merge verification **2026-10-07**: backend `uv run pytest` **267 passed,
2 dependency warnings**; Android `assembleDebug testDebugUnitTest` **BUILD
SUCCESSFUL in 19s**, **45 tasks executed**, **135 JVM tests, zero failures/errors/skips**.
Builds used JDK 17, the installed SDK and writable temporary caches.

Journal joins main's registry with **20 assistant tools**, retaining six local
tools, exactly two translator tools, and Auto/PTT/Over configuration. Both the
job runner and journal scheduler share the application lifespan and shut down
cleanly. Blocking journal work runs in a worker thread so job events stay responsive.
Notes and research record both dashboard history and journal activity after
releasing the Google transport lock, avoiding a lock-order deadlock with journal
publishing. Tests cover these paths, schedule edits, failure handling and exact
tool sets without weakening assertions. All decision rows and research entries
are retained; `lock_additional_fields=[]` and client-owned resumption are unchanged.
No new credentialed API or physical-device check ran during integration.

Original branch validation:

`cd backend && uv run pytest`: **114 passed**, two existing dependency warnings.
Tests include local dates/DST, source collection/history, clipping, replacement,
lost Docs replies, relative dates, scheduling/concurrency, morning carryover,
forget suppression, auth/error sanitization, and actual SDK JSON serialization.
Android was not modified, so no Android build was needed.

Real check: `cd backend && uv run python ../scripts/smoke_journal.py --live-setup`.
Real Gemini generated a fake harbour walk with Alex, highlights, and an email
task due the next day; local journal read/recall and morning due filtering passed.
Real ephemeral token + constrained WebSocket setup returned `setupComplete` with
all **seven** tools. The smoke uses a disposable DB, never writes Google Docs,
and never prints secrets. Audio, tool selection and resumption rollover untested.

Verified official sources, **2026-10-07**:
- [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output):
  schema-constrained generation; actual journal schema accepted in the real smoke.
- [Google Docs get](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/get),
  [structure](https://developers.google.com/workspace/docs/api/concepts/structure),
  [requests](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/request),
  and [batchUpdate](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/batchUpdate):
  first-tab content, UTF-16 indices, text replacement/styling and revision preconditions.
- Existing verified protocol/model notes: [M1](../research/m1-android-notes.md),
  [M2](../research/m2-backend-notes.md), [M3](../research/m3-memory-notes.md).
  Journal-specific findings: [research](../research/journal-notes.md).
