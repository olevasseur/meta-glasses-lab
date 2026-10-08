# Usage meter and quota soft caps

Implemented **2026-10-08**. Gemini only. The backend now records local usage in
SQLite and exposes an authenticated Usage dashboard and app diagnostics warning.

## Use

- Open the existing backend dashboard, sign in, then select **Usage**. It shows
  today and the last seven Pacific quota days, per model and call type: attempts,
  failures, connected Live minutes, and reported input/output/total tokens.
- Phone: **Settings → Diagnostics** automatically refreshes usage/warnings every
  30 seconds while that screen is open. Reading usage never starts a Gemini probe.
- Voice: use conversation/meeting normally; metering starts at Live setup success.
  Say **“Turn on visual memory for 30 minutes”** or **“Remember this”** as before.
  Automatic capture slows from 20 to 60 seconds at 80% of a relevant budget.
  At 100%, captures (including pinned one-shots) are refused with `quota_throttled`;
  automatic mode checks again after 300 seconds. No denied frame is saved or queued.
  Existing pending captions wait for budget to reset. Say **“Turn off visual memory”**
  to stop its loop. There is no new quota-changing voice tool.
- Evaluations use one Live worker. The runner refuses a selected run before token
  counting/Live access if its reservation exceeds the configured daily eval share.
  Cached TTS fixtures bypass generation and consume no new TTS allowance.

## Configuration

`.env` defaults are **local budgets, not published Google free-tier limits**:

```dotenv
USAGE_LIVE_SESSIONS=20
USAGE_LIVE_MINUTES=120
USAGE_TEXT_CALLS=400
USAGE_TOTAL_TOKENS=1000000
USAGE_EVAL_PERCENT=10
GEMINI_BILLING_TIER=unknown
# USAGE_DB=/absolute/private/path/usage.sqlite3
# GEMINI_QUOTA_FILE=/absolute/private/path/gemini-quota.json
```

No credentials belong in the quota file. Copy `backend/gemini-quota.example.json`
to a private file, then fill only values verified in the correct project's
[AI Studio rate-limit page](https://aistudio.google.com/usage?tab=rate-limit).
Use `null` for unknown or unlisted dimensions; **zero means no allowance**.
For example, a model object accepts `as_of`, `rpm`, `tpm`, `rpd`, `tpd`,
`concurrent_sessions` and `live_minutes_per_day`. The example intentionally contains
no invented quota values. Supply a date in `as_of` when verified.

Daily configured quotas are compared with today's per-model calls, tokens and
minutes. RPM/TPM/concurrency limits are shown for reference; this ledger does not
measure provider rolling windows or project-wide active sessions. Visual capture
checks the first selected text model's configured RPD/TPD as well as aggregate
local text/token budgets. Eval reservations use the smaller of local budgets and
configured daily model limits. Null/missing limits never mean unlimited provider
capacity. `GEMINI_BILLING_TIER` is a user-confirmed label, not an API discovery.

Default storage is `backend/data/usage.sqlite3`, gitignored by the existing data
rule. To coordinate all local agents/evals, configure **the same absolute USAGE_DB
in every worktree and backend process**. Otherwise each checkout measures only
its own traffic. The database must be writable; invalid quota JSON/configuration
is an error, not a silently disabled gate. No database or existing preferences are
replaced. This feature adds its own database; it does not alter memory tables.

## What is counted

- Every attempt through the shared text retry/fallback helper, including failed
  attempts, under the model actually called. This covers summaries, captions,
  journal, meeting minutes and coach extraction.
- Each successful app Live setup, including reconnect/resumption connections.
  Token minting alone does not count as a Live session. Health setup probes and
  evaluation connections are also metered.
- Android reports cumulative connection totals at setup, every 30 seconds and
  stop/reconnect. IDs make retries and out-of-order reports idempotent. Elapsed
  time comes from a monotonic clock. The backend splits elapsed intervals at
  Pacific midnight, including DST changes; reported tokens land on receipt day.
- `usageMetadata` token counts, summed across generation reports. Missing usage
  remains unknown in the detailed table. Aggregate tokens are only the reported
  subset. Output counts exclude separately reported thinking tokens, while provider
  total counts can include them. Repeated context may occur in reported totals.
- Evaluation TTS calls, including failures, through the shared fixture synthesizer
  (also used by batch fixture generation). Token counting and ephemeral minting
  are not inference calls in this ledger.

The API uses the existing `X-App-Secret` middleware: `GET /usage` returns the
snapshot and `POST /usage/live` accepts bounded cumulative connection reports.
The dashboard uses the existing cookie login and security headers. No audio,
images, prompts, transcripts, keys, token names or raw provider errors are stored
in this ledger.

## Evaluation allowance

The default daily evaluation share is 10%: two Live connections, 12 connected minutes, 40 TTS calls and
100k estimated total tokens across evaluation reservations. Each Live case reserves
`(timeout + 30) / 60` minutes and 6,000 tokens/minute. Each uncached TTS attempt
reserves one call and `max(2000, 20 × text characters)` tokens. These are deliberately
conservative estimates, not actual provider guarantees. Long context/tool replies
can exceed them. Reservations are atomic across processes sharing SQLite and are
**not refunded** on failures/cancellation, so failed work cannot repeatedly spend
hardware-test headroom. They expire with the quota day. The gate also checks
remaining local daily budget, conservatively including actual usage and outstanding
reservations. It can refuse early because completed evaluations are still reserved.

```sh
PYTHONPATH=scripts uv run --project backend python -m voice_eval \
  --label quota-check --case YOUR_CASE_ID --workers 1
```

Choose a real ID from `scripts/voice_eval/cases.json`. A full suite may be refused;
select a small subset or deliberately configure appropriate budgets after checking
AI Studio. There is no implicit override and no automatic billing change. A Live infrastructure
failure stops the remaining cases; `--retry-errors` includes unattempted cases on a
later invocation after recovery.

## Limitations and verification

This is a local advisory meter, not Google's billing system. Other apps/keys,
uninstrumented smoke scripts, prior usage, separate databases, failed uploads,
phone process death and provider reports never delivered are missing. A crashed
phone is counted only through its last heartbeat; connections do not keep accruing
forever. Final reports are best effort, not a durable phone outbox. Session counts
mean connections, not unique human conversations or provider concurrency quota.

Normal conversations, summaries and meeting minutes continue beyond soft caps;
only automatic/one-shot visual captions and evaluations are gated. Fallback retries
can cross a budget between checks. No existing session is forcibly cut off. Reported
tokens are not dollar totals, and these caps cannot guarantee free operation.

Unit tests cover persistence, idempotency, out-of-order reports, midnight splitting,
missing usage, fallback attempts, simultaneous reservations, quota-file comparisons,
visual throttling/refusal (including pending recovery), authenticated API/dashboard,
eval cleanup on failure, cached fixtures and Android metadata/capture cadence.
Final validation results are recorded below.

Real checks: model catalog **200**, one tiny text call **503**, no billing/quota
headers or error quota details; stopped without retry. **0/5 Live sessions used**.
No physical phone/glasses check or full-day metering/billing comparison was run.
The current key tier, project limits, credits and availability remain unverified.

Sources verified 2026-10-08:

- [Quota research and personal-use estimate](../research/gemini-quota.md)
- [Google rate limits and Pacific reset](https://ai.google.dev/gemini-api/docs/rate-limits)
- [Live usage metadata](https://ai.google.dev/api/live#usagemetadata)
- [Pricing and available free tiers](https://ai.google.dev/gemini-api/docs/pricing)
- [Developer Program AI Pro credits](https://developers.google.com/program/plans-and-pricing)
- [API billing and credit setup](https://ai.google.dev/gemini-api/docs/billing)

Follow up: verify AI Studio's project limits and credit balance, configure shared
storage if agents continue evaluation, then compare one hardware session and a
full day against AI Studio. The existing constrained endpoint, token field locking
(`lock_additional_fields=[]`) and client-owned resumption are unchanged.


### Final automated validation — 2026-10-08

```text
cd backend && uv run pytest
500 passed, 3 warnings in 4.82s

cd android && ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL in 24s
45 actionable tasks: 8 executed, 37 up-to-date
234 JVM tests, 0 failures, 0 errors, 0 skipped
```

Used JDK 17, the installed Android SDK, and writable temporary uv/Gradle/Android
user directories. The initial Android attempt could not write the default debug
keystore outside the sandbox; `ANDROID_USER_HOME=/tmp/quota-android-user` resolved
it. Existing paused-session tests now distinguish observational usage reports from
backend tool calls; the zero-tool-write assertion remains intact.
