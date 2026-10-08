# Claude Code background jobs

Implemented **2026-10-08**. Gemini remains the voice assistant and writes spoken
comparisons. Codex and Claude are explicitly requested background CLI tools using
the Mac's subscription logins; neither receives backend API keys.

## Voice and approval

- “Tell Claude to fix the settings label in glasses.” Or “Ask Claude Code to …”.
- “Have Claude research battery care.” Research uses web search/fetch and saves
  the report to **Glasses Research**, or the requested Doc alias/title.
- “Have both try fixing the settings label in glasses.” Or “Compare Codex and
  Claude on …”. Both run the same task in separate worktrees, as slots permit.
- “Have both research …” merges findings, deduplicates source URLs and asks
  Gemini to identify disagreements. Each original report remains on the Mac.
- “How are my jobs doing?” and “Cancel that job” use the existing tools.

Coding is Tier 2 for either agent or both: review the full agent/repo/instruction
on the phone, then **capture button or screen Approve**. Nod cannot authorize it.
Research is Tier 1: nod, capture or screen. Dashboard Approvals is the fallback.
Approval hashes include the normalized agent. A changed agent/task needs fresh
approval. Model arguments or spoken yes never approve.

The existing Android card/notification renders backend summary and full details;
no Android code changed. Long instructions or unavailable notifications require
screen/dashboard review under the existing presentation policy.

## Configuration

Run the backend as the Mac user logged in to `claude` (v2.1.292 help verified) and
`codex`, with both CLIs and git on PATH. Keep one backend worker.

Dashboard **Settings → Background job agents** controls each agent's enabled
state and model; saved values take precedence over environment defaults:

| Setting | Default |
| --- | --- |
| `CLAUDE_JOBS_ENABLED` | `true` |
| `CODEX_JOBS_ENABLED` | `true` |
| `CLAUDE_JOB_MODEL` | `claude-opus-5-5` |
| `CODEX_JOB_MODEL` | `gpt-6-astra` |
| `JOBS_CONCURRENCY` | `3` CLI runs, shared across agents |
| `RESEARCH_JOB_TIMEOUT_SECONDS` | `1800` per research run |
| `CODEX_JOB_TIMEOUT_SECONDS` | `3600` for Codex coding |

Claude coding has a fixed **60-minute** active deadline, including worktree setup.
Agent enablement and repo allowlisting are checked again at execution. Disabling
an agent blocks new/queued runs; it does not cancel an already-running process.
Both requires both enabled. With concurrency one, its agents run sequentially.

Both agents share `backend/codex_repos.json` (spoken aliases to absolute repository
roots), edited through Settings. No repo is implicitly authorized. Saved agent
preferences live in `backend/data/job-settings.json`.

## Execution and results

The existing `codex_task` and `deep_research` args gain optional `agent`:
`codex` (default), `claude`, or `both`. Case-insensitive Claude Code/compare aliases
normalize to claude/both. Codex's default flags, model, prompts, sandbox and branch
naming remain unchanged.

Claude uses documented `-p`, JSON schema/output, `dontAsk`, restricted and safe
modes, explicit allow/deny tool lists and no extra directory access. Coding allows
Read/Grep/Glob/Edit/Write only. All Bash is denied, including push, remote changes,
rm, curl and wget; web tools are denied. Restricted mode confines file tools to
the worktree. **Claude cannot execute tests** under this policy; its report and
Jobs page explicitly say so. Research allows only WebSearch/WebFetch/Read/Grep/Glob
inside a fresh research directory, with no edits or shell execution.

Claude coding branches are `glasses/claude-<repo-slug>-<UTC timestamp>-<id>`.
Jobs never push, merge, commit, publish or deploy code. Worktrees/branches remain
for review. JSON stdout and stderr have separate private logs and share a 1 MB
limit; malformed/error envelopes fail closed. Child stdin is `/dev/null`, no
shell wraps the CLI, and cancellation/timeouts stop process groups.

Single jobs retain `jobs/<id>/...`; both stores artifacts under
`jobs/<id>/{codex,claude}/...` plus a parent comparison report. SQLite persists
agent, child results and branch/worktree paths. A successful peer remains visible
if the other fails. Restart fails interrupted runs without replay; queued work
resumes. Research publishes once only after both complete successfully. Missing
Google credentials retains local reports; uncertain writes are never retried.

Dashboard **Jobs** shows both summaries, tracked diff stats, untracked file names,
and reported checks. Gemini compares completeness in 2–3 spoken sentences using
these as untrusted evidence; it cannot verify test claims. If Gemini is unavailable,
both original reports survive and the fallback explicitly says disagreements
were not assessed. It still saves merged research with a deduplicated source list.

## Validation and limits

Unit/integration tests mock CLI subprocesses, Gemini and Docs. They cover documented
flags/deny lists, private cwd/environment, worktree/branch separation, concurrent
and sequential both-mode, cancellation/timeouts, partial failures, report merging,
agent-bound Tier 2 approvals, settings, and restart persistence.

Outside the Codex sandbox, run:

```sh
cd backend
uv run python ../scripts/smoke_claude_jobs.py
```

This runs two sequential tiny Claude jobs in a private temp directory and asserts
a sourced JSON report plus an isolated file edit. Its allowlist is disposable;
the real allowlist is never changed. Artifacts are retained for inspection. No
Google Docs write or Gemini call is made. Default smoke timeout is 240 seconds
per job. The smoke was **not run here**; real model availability, permissions,
comparison quality, Docs publication and hardware behavior remain unverified.

CLI permissions are not an OS container. Use trusted repos/tasks and review edits.
Managed Mac policy can affect CLI behavior; the provider connection itself requires
network access. New files are listed separately from tracked diff stats.

Verified sources accessed **2026-10-08**: [headless mode](https://code.claude.com/docs/en/headless),
[CLI flags](https://code.claude.com/docs/en/cli-reference),
[permissions](https://code.claude.com/docs/en/permissions),
[settings](https://code.claude.com/docs/en/settings), and the
[local help/research record](../research/claude-code-headless.md).

Local verification on **2026-10-08**:

```text
cd backend && UV_CACHE_DIR=/tmp/claudejobs-uv-cache uv run pytest -q
555 passed, 3 warnings in 5.44s
```

Warnings are the existing Starlette/httpx, Python audioop and experimental Gemini
SDK notices. Smoke script `--help` passed without launching any CLI. Android was
unchanged, so no Android build was required. No real API checks ran.
