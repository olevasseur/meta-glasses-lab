# Background jobs from the glasses

**Approval update — 2026-10-08:** Codex requires capture or screen (no nod); research and cancellation require Tier 1 device approval. Dashboard fallback remains. See [device approvals](device-approvals.md) for configuration, limits and verified evidence.

Implemented and checked **2026-10-07 (America/Toronto)**. Gemini Live remains the
voice assistant. The explicitly requested developer/research tools launch the
Mac's logged-in Codex CLI; no OpenAI API client, API key, or voice provider is added.

## Claude and both agents (2026-10-08)

`codex_task` and `deep_research` now accept `agent: codex|claude|both` (default
Codex). Say “tell Claude to …”, “have Claude research …”, or “have both try …”.
Both gets isolated worktrees and a Gemini spoken comparison; research merges
sources and notes disagreements. Coding remains Tier 2 for every agent, with
agent/repo/exact instruction bound to approval. Dashboard Jobs/Approvals shows
agent selection; Settings controls enablement/model. See [Claude jobs](claude-jobs.md)
for restrictions, deadlines, persistence, configuration and the external smoke.

## Voice and buttons

- “Ask Codex to fix the failing tests in valiant.” Gemini previews the exact repo
  and task, asks you to review the phone and press capture or tap Approve. Changing the
  task requires another preview. The device waits 60 seconds; server approval expires after five minutes.
- “Research the capital of Canada with one official source.” A job starts in the
  background and normally appends its sourced report to **Glasses Research**.
  “Research … and save it in my travel notes” selects another Doc title or alias.
- “How are my jobs doing?” lists running jobs and recent results. “Cancel that
  job” stops its process group and keeps any files already produced.
- Use the existing **Start** button for voice. Tap **Permissions** to allow phone
  notifications. Completed/failed/cancelled jobs are announced through Gemini
  when it is ready and idle; during manual turns, replies, call/focus pauses or
  pending gesture confirmations, the phone shows a notification that opens the app.
  The existing “Last tool result” area also shows the latest event.
- Research accepts an app-owned nod; Codex requires capture or screen. Model-held
  confirmation tokens and spoken yes never count.

## Configuration

Run the backend as the Mac user who has already logged in to `codex`, with
`codex` and `git` on PATH. CLI flags were checked against **codex-cli 0.160.0**.
Run **one Uvicorn worker**; a file lock rejects a second job runner using the same
data directory. The existing `X-App-Secret` authentication protects every tool
and the event stream.

`backend/codex_repos.json` ships empty, so no repository is authorized by default.
Populate it explicitly with spoken aliases and **absolute repository root paths**:

```json
{"valiant": "/Users/aiagent/repos/valiant"}
```

Aliases match exactly first, then use the notes module's RapidFuzz thresholds.
Ambiguous matches ask for an alias; arbitrary paths are rejected. The preview
records the canonical path, alias, and instruction. An approval binds that stored request; only the app or dashboard consumes it.
Repeated approval submissions cannot execute again.
The allowlist is checked again immediately before worktree creation.

Optional repo-root `.env` settings (restart after changes):

| Setting | Default | Meaning |
| --- | --- | --- |
| `JOBS_CONCURRENCY` | `3` | Maximum concurrent jobs, 1–32; additional jobs queue. |
| `CODEX_JOB_TIMEOUT_SECONDS` | `3600` | Active coding job deadline, including worktree setup. |
| `RESEARCH_JOB_TIMEOUT_SECONDS` | `1800` | Active research deadline, including Docs save. |

Google credentials use the existing `backend/secrets/token.json` setup. The default
**Glasses Research** document is found by exact title or created if absent;
duplicate exact titles need attention. Other names use the existing alias/fuzzy
matching. Missing credentials skip Docs and clearly report that the result is
local only. A failed or ambiguous save retains the report and its document status;
it never silently selects a different Doc or retries an uncertain append.

## API and storage

All requests require `X-App-Secret`. The four function declarations are locked
into Gemini tokens along with the existing tools; `lock_additional_fields=[]`
and client-owned `sessionResumption` are preserved.

```text
POST /tools/codex_task   {repo, instruction, agent?: "codex", confirm?: false, job_id?: string}
POST /tools/deep_research {topic, agent?: "codex", doc_name?: "Glasses Research"}
POST /tools/job_status   {job_id?: string}
POST /tools/cancel_job   {job_id}
GET /events?after=0&wait=20
```

Coding and research previews return `needs_confirmation`, `approval_id`, tier
and exact review details. The device/dashboard endpoint consumes that approval;
model confirmation fields always produce a fresh preview. Successful human
approval queues work and returns `status: queued`.

`job_status` returns the job or up to 20 jobs, active ones first. States are
`needs_confirmation`, `queued`, `running`, `completed`, `failed`, `cancelled`.
An event response contains `events: [{id, job_id, status, summary}]` and `cursor`.
It waits up to 20 seconds, returns at most 100 terminal events, and can replay
from any cursor. The phone stores its last delivered ID per backend URL in the existing
conversation Preferences DataStore, migrating any older `job_events` preferences. It does
not advance past an event when notification permission is denied; starting a
conversation or granting permission allows later delivery.

`backend/data/` is gitignored:

- `jobs.db`: SQLite job requests, summaries, state, and durable completion events.
- `jobs/<id>/codex.log`, `git.log`, `repo-root.log`: private local subprocess logs.
- `jobs/<id>/result.json`, `report.md`, `result-schema.json`: structured output.
- `jobs/<id>/worktree`: coding checkout on `glasses/<alias>-<UTC timestamp>-<id>`.
- `jobs/<id>/research`: empty working directory for read-only research.

Codex runs `gpt-6-astra`, high reasoning, stdin `/dev/null`, explicit argv (no
shell), `workspace-write` for coding or `read-only` for research. Research enables
both the requested `tools.web_search=true` and current `web_search="live"` setting.
The CLI receives a JSON output schema for a 2–3 sentence summary and full report.
User CLI config is ignored while its existing login remains available. Backend
secrets are excluded from the subprocess environment. Coding shell network access
is disabled, approvals are disabled, and the task explicitly prohibits push,
merge, commits, publishing, deployment, and edits to other checkouts. The job
runner only invokes Git for root validation and creation of a new worktree; it
never invokes push or merge. Worktree creation disables repository Git hooks.

Queued jobs resume on backend startup. An interrupted running job becomes failed
and emits an event; it is never automatically repeated. Graceful shutdown stops
running process groups and preserves queued work. SIGTERM is followed by SIGKILL
for cancellation/timeout. Worktrees, branches, reports, and logs are retained for
manual inspection and cleanup, including partial/failed work.

## Checks and limitations

Run `cd backend && uv run pytest` and
`cd android && ./gradlew assembleDebug testDebugUnitTest`.
A credential-safe real smoke is `cd backend && uv run python ../scripts/smoke_jobs.py`.
It creates a tiny Canada research job through authenticated ASGI endpoints,
waits for its durable event/status, and prints only the result summary. It skips
Docs automatically when no Google token exists. It cancels after three minutes.

Local results: **62 backend tests passed** (mocked subprocess/Docs); Android build
passed with **23 JVM tests**, including three event/protocol/HTTP tests. The real
Gemini `/token` check returned **HTTP 200** with the expanded declarations.
The real Codex research smoke **failed before model execution**: installed CLI
reported `failed to initialize in-process app-server client: Operation not permitted`
in this restricted worktree session. A retry using supported temporary log/state
paths failed the same way. No research summary or real Docs append was produced.

Known boundaries:

- Polling follows the existing conversation service-owned ViewModel, including
  screen-off conversations and Activity recreation. Stop/disarming releases the
  service when no Activity is bound, so polling then stops. Outstanding events
  replay when the service is recreated. Android can still kill the process;
  immediate delivery while it is killed would require push notifications.
- WebSocket `send()` acknowledges enqueueing, not audible playback. A disconnect
  immediately after enqueue can lose the spoken announcement; `job_status` still
  retains the result. A process death between delivery and cursor persistence can
  repeat an announcement. This is not an exactly-once audio contract.
- Approval binds the exact stored agent/repo/task and expiry. Only app-owned
  device input or authenticated dashboard review authorizes execution; spoken
  yes and model confirmation fields do not.
- Sandbox restrictions provide write/network boundaries, not a container for
  hostile repositories. Read access is not restricted to the working directory.
  Use trusted allowlisted repos; review generated changes locally before taking
  any Git action. Automatic cleanup, commits, push, and merge are absent.
- Cancellation cannot undo existing file edits or a Docs request already in flight.
  An abrupt backend kill may leave an OS child running; restart marks its job
  failed, but does not kill saved numeric PIDs that may have been reused.
- An ambiguous/failed Docs save requires inspecting the local report and using
  the existing note flow/manual append after choosing the document. There is no
  automatic save retry or report retrieval endpoint.
- No phone/glasses hardware, audible event injection, physical nod confirmation, real Docs
  write, or successful real Codex execution was verified in this environment.

## Combined merge verification

Checked 2026-10-07 after integration with main: backend **117 passed, 2 dependency
warnings**; Android `assembleDebug testDebugUnitTest` **BUILD SUCCESSFUL**, **98 JVM
tests, zero failures/errors/skips**. Used JDK 17, the installed Android SDK and
writable temporary uv/Gradle/Android caches. No assertions were weakened: exact
tool-set checks now require all eleven tools in Auto, PTT and Over, with the same
three client-owned tools. Added regressions for job polling during blocking
memory/Docs work and per-backend cursor persistence across settings/service
recreation. Tests isolate the job runner from personal queued work.

Jobs use main's registry, memory routes and service-owned conversation lifecycle.
`lock_additional_fields=[]` and client-owned `sessionResumption` are unchanged.
Tiered device approval can approve the exact job preview; event announcements fall
back to notifications while speech/replies/confirmations or call pauses are active.
Physical combined workflows and real Codex/Docs execution were not re-tested.

## Verified sources

Accessed **2026-10-07**, plus installed CLI help and local tests:

- [Codex CLI reference](https://developers.openai.com/codex/cli/reference): exec,
  model, sandbox, working directory, output file/schema and noninteractive options.
- [Codex configuration reference](https://developers.openai.com/codex/config-reference):
  reasoning effort, approval policy, network restrictions and live web search.
- [Gemini Live WebSocket reference](https://ai.google.dev/api/live): text
  `clientContent` messages with `turns` and `turnComplete`.
- [Android notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission):
  Android 13+ `POST_NOTIFICATIONS` declaration and runtime permission.
- [Existing verified Docs API notes](../research/m2-backend-notes.md), including
  [Docs insertText](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/request).

See [the research record](../research/background-jobs-notes.md) for unverified items.
