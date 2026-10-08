# Memory quality evaluation

Implemented 2026-10-08. Gemini remains the only runtime AI. The evaluator uses
fictional people and isolated SQLite databases, never the user's stored memory.

## Use by voice and buttons

No new app button or mode is required. Say “My sister is Ann,” stop the session,
start another, and say “Actually, my sister is Ana, not Ann.” On the next Start,
ask “What is my sister's name?” Say “Remember that …” for an immediate explicit
save. Ask “What do you remember about …?” to search older records with `recall`.

Forgetting keeps the existing approval flow: say “Forget my hobby project,” then
nod, press capture, or tap Approve on the app's card. Start a fresh conversation
before testing absence: an already-connected Gemini session retains its context.
The evaluator simulates an approved internal service call on synthetic data only;
it does not change the app's authorization boundary.

## Pipeline changes

- Facts retain the extracting session's last user transcript timestamp
  (`observed_at`), write timestamp
  (`updated_at`), extraction confidence, priority, and superseded semantic keys.
  Existing SQLite databases migrate in place without losing facts; legacy facts use
  their existing write timestamp until updated.
- Corrections reuse stable relationship/attribute keys. Explicit supersession
  merges renamed keys, retains aliases, and prevents a delayed older summary
  from overwriting newer evidence. Exact normalized text still deduplicates.
- The provider receives a compact typed JSON schema; local Pydantic validation
  enforces every size, count and range bound before storage. Empty structured
  summaries are allowed when no content should be retained; blank fact texts
  remain invalid and never stand in for an approved deletion.
- Extraction excludes confidence below 0.75. Confidence is a model estimate,
  not a calibrated probability. Identity, relationships and enduring preferences
  rank above plans, which rank above episodic details. Explicit remember has
  confidence 1 and highest priority.
- The injected **memory portion** has an 8,000-byte UTF-8 cap, approximately 2k
  English tokens. App instructions have a separate size. Previously the app's
  7,166-byte prompt left only 834 bytes for memory. Ranked facts take precedence
  over the newest three summaries; large items are skipped to fit smaller ones.
- Extraction sees at most 100 existing facts, including 40 selected for relevance
  to the transcript so older relationships can still be corrected.
- Recall strips question boilerplate, matches words with limited typo tolerance,
  searches fact keys as well as text, prioritizes current facts, and labels
  archived results `historical`. It still searches summaries, journals and
  transcripts; it does not silently rewrite history.
- Forget suppression also follows superseded key aliases. Explicit remember of
  a forgotten key restores that key's suppression group. Dashboard deletion and
  explicit re-saving use the same alias suppression/restoration as voice, while
  retaining browser authentication, CSRF and stale-edit checks.

## Reproduce the evaluation

From the repository root, with the existing root `.env` configured:

```sh
UV_CACHE_DIR=/tmp/memory-eval-uv uv run --project backend python scripts/memory_eval generate \
  --output scripts/memory_eval/corpus.json
MEMORY_EVAL_RETRIES=0 MEMORY_EVAL_TIMEOUT_MS=60000 \
  MEMORY_EVAL_MODEL=gemini-3.1-flash-lite GEMINI_TEXT_MODELS=gemini-3.1-flash-lite \
  UV_CACHE_DIR=/tmp/memory-eval-uv uv run --project backend python scripts/memory_eval run \
  --output /tmp/memory-after.json --state /tmp/memory-after
UV_CACHE_DIR=/tmp/memory-eval-uv uv run --project backend python scripts/memory_eval growth \
  --output /tmp/memory-growth.json --state /tmp/memory-growth
```

The committed corpus contains ten personas with twelve sessions each. Generation
is resumable; use a new output path to create a new corpus. An independent fixture
supplies answer keys; Gemini writes dialogue, never the scoring oracle. The seed
is retained verbatim alongside generated dialogue to prevent factual drift, so
some sentences repeat. Probes test names, preferences, changing plans, forgetting,
hypotheticals and unknown details. Each persona has separate memory storage.

`run` calls the production ingest/end/extraction/forget/recall methods. Fresh,
stateless Gemini text probes receive the exact memory context and system
instruction produced by `gemini.token_config`, the production token serializer.
Each run compares profile-only answers with a second probe supplied real recall
results. It does not test Live tool selection, audio, or create Live sessions.
`regressions` runs five deterministic storage/search checks against either
pipeline revision without a provider. `growth` is a deterministic 105-session
**summary replay** through
real ingestion, finalization, storage and context: it tests the byte cap and
ranking independently of provider availability, not extraction accuracy.

`MEMORY_EVAL_TIMEOUT_MS=60000` optionally extends only evaluation transport
deadlines; production keeps its existing retry budget. `MEMORY_EVAL_RETRIES=0`
disables extra evaluator retries (production retries and rate-limit backoff still
apply). The run records failed summaries and continues later sessions; inspect
summary completion together with answer accuracy. Probe transport failures are
recorded as missing coverage, never as successful abstentions or fabricated
answers; later personas still run.

`MEMORY_EVAL_MODEL` selects the probe/generation Gemini text model;
`GEMINI_TEXT_MODELS` selects the production summarizer's configured model list.
Use the same models/corpus for comparisons. `--state` holds synthetic SQLite
checkpoints; never point it at production data. `--ledger` defaults to
`/tmp/glasses-memory-eval-budget.json`, persists all text attempts including
failures, and stops at 1,500. Calls are serial, paced, with 429/503 backoff and
bounded retries. Provider exception bodies and credentials are never printed.
Use separate state/output paths for before and after; retain one shared ledger.

The evaluator also uses the shared [usage meter](usage-meter.md) and its configured
`USAGE_DB`. Before each text attempt, including summary fallbacks/retries, it
atomically reserves one text call and an estimated token allowance (serialized
request bytes plus the output cap, with a 2,000-token minimum). Reservations are
not refunded. All actual successes/failures and reported tokens are recorded once,
including direct corpus/probe calls. SDK retries are disabled so each attempt is
reserved. The normal summary retry/fallback policy remains intact.

The smaller shared daily allowance can stop a run before its 1,500-call ledger
limit; retained checkpoints allow resuming after quota reset. Use the same absolute
`USAGE_DB` across worktrees to share headroom with voice/HFP evaluations and app
traffic. Configure budgets deliberately before a full provider run. Unlike fixed
voice suites, this resumable evaluator reserves per attempt, not the whole run.
`growth` and `regressions` use no provider calls or quota reservations. The token
estimate is conservative accounting, not a billing/tokenizer guarantee.

Baseline source revision and results are in
[the evaluation report](../evals/memory-eval-2026-10-08.md). To reproduce baseline,
extract `backend/memory.py` and `backend/memory_summary.py` from that revision
into a temporary directory and set `MEMORY_EVAL_BASELINE` to that directory.
All other helpers remain from the current checkout. Output JSON includes
individual answers, recall results, stored facts and aggregate counts.

## Limitations and sources

Strict answer-span scoring penalizes answers that also mention old values;
unknown probes must return exactly `UNKNOWN`. This measures these scripted
questions, not arbitrary conversational truth or a calibrated hallucination
rate. Ten template-based personas are a regression corpus, not population-level
validation. Semantic key selection, confidence and supersession remain
probabilistic; unrelated-key merges are possible. No embeddings were added.
Search remains linear and primarily English; multilingual semantic retrieval is
unverified. The byte cap is approximate token accounting, not a tokenizer count.
Timestamps use the phone's transcript clock; incorrect clocks can affect order.
They are per session, not per fact utterance; interleaved overlapping sessions
still rely on extraction to identify the genuinely newer statement.

Forgotten paraphrases may evade lexical suppression. Raw history and suppression
records remain on disk; this is saved-memory suppression, not privacy erasure.
Hardware voice capture, approval input, and Live answer quality are unverified
by the text harness. Production token locking/resumption behavior is unchanged.

Sources verified 2026-10-08:

- https://ai.google.dev/gemini-api/docs/structured-output — structured schemas;
  local Pydantic validation remains required for application semantics.
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens — token constraints.
- https://generativelanguage.googleapis.com/v1beta/models — authenticated model
  availability check; project quotas and runtime health are separate.
- [Existing memory protocol research](../research/m3-memory-notes.md).

## Combined merge verification — 2026-10-08

Integrated with main's usage meter, summary retry/fallback helper, authenticated
dashboard and shared memory service. Dashboard deletion/restoration follows key
aliases; existing approval, CSRF and stale-edit checks remain. Evaluation attempts
use shared quota reservations and exactly-once local accounting per SDK attempt.
The tool registry, Android DataStore, `lock_additional_fields=[]` and client-owned
`sessionResumption` are unchanged. All 15 decision rows and both branches' research
entries are retained.

```text
cd backend && uv run pytest
537 passed, 3 warnings in 5.52s

cd android && ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL in 26s
45 actionable tasks: 45 executed
245 JVM tests, 0 failures, 0 errors, 0 skipped
```

Used JDK 17, the installed Android SDK and writable temporary uv/Gradle/Android
caches. Added regressions for shared quota refusal, summary fallback accounting,
restored production metering and dashboard/voice alias suppression. Assertions
were not weakened. No credentialed provider, Google Docs, emulator or physical
phone/glasses checks ran during this merge.
