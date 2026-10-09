# Memory hypotheticals evaluation — 2026-10-08

The change adds explicit assertion modality/subject/durability admission, separates
plans from current facts with review dates, and supplies assertion-aware profile
and recall guidance. Runtime AI remains Gemini only. This report separates
semantic quality, storage-contract compliance and provider availability.

**Original regression gates: passed**
(80/80 profile, 50/50 hit@3, 80/80 recall-assisted).
After the full adversarial run plus the explicitly separated follow-ups below,
40/40 storage checks passed. Text calls: 597/600;
Live sessions: zero.

## Original regression corpus

The unchanged corpus has ten fictional personas, twelve sessions each, and eighty
questions. Baseline for this task is `30b2238004aa93727e3696624f2f3e8c41bc8d33`,
already containing the prior memory-quality fixes. It is not the older baseline
from the first report. The archived target is **80/80 profile answers and 50/50
retrieval hit@3**; that earlier run had 78/80 recall-assisted answers.

| Metric | Fresh before | Recovered after |
| --- | ---: | ---: |
| Profile answer coverage | 80/80 | 80/80 |
| Strict profile answers correct | 79/80 | 80/80 |
| Recall hit@3 | 50/50 | 50/50 |
| Recall-assisted answer coverage | 80/80 | 80/80 |
| Strict recall-assisted answers correct | 73/80 | 80/80 |
| Completed summaries | 115/120 | 120/120 |

Before used Gemini 3.1 Flash-Lite. The initial after run used the same model until
its structured 429 identified daily quota exhaustion. On the first four personas
with returned answers in both same-model phases, profile accuracy was 32/32 in
both; recall-assisted accuracy was **30/32 → 32/32**, with 20/20 retrieval hit@3.
This is an incomplete prefix, not the full regression result.

Recovery uses Gemini 3.5 Flash-Lite probes and retains completed extraction
sessions. Deferred summaries are retried; the ledger identifies all models used.
Consequently, the full before/after table is **not a controlled same-model
comparison**. It establishes observed regression scores for these runs, not a
causal effect size or population-level hallucination rate. Initial partial and
recovery snapshots are retained separately. Provider failures are not successful
abstentions. The original gate is **PASSED: 80/80 profile and 50/50 hit@3**.

## Forty-case adversarial set

Four independently authored cases each cover hypotheticals, conditionals, wishes,
plans versus completion, quoted speech, jokes/sarcasm, negations, questions,
past-but-changed facts, and other people's attributes. Each case has an isolated
SQLite database. Expected labels/spans never enter the model prompt.

Both comparison phases use **Gemini 3.5 Flash-Lite**, the same fixture and scoring
code. Each runs production ingestion/extraction/storage, production profile and
token-configuration serialization, then real recall results and a stateless text
probe. Each probe returns a current-fact answer and a natural recollection. The
recall query is the authored topic keyword, exposing the misleading statement;
this is an interpretation test, not an unassisted retrieval benchmark. Evidence
is dated 2026-10-08, so a newly created plan review is not already overdue.

A small interrupted pilot also asked separate profile-only questions; its results
are archived and excluded from the paired comparison. Dropping that extra probe
preserved all forty cases while reserving budget for original-corpus recovery.
The original eighty-question suite remains the profile-only regression gate.
The first topic-plus-question lookup also missed five user transcripts. Those
results are archived separately; the final pair uses topic-only retrieval, and a
unit check verifies exposure of the user statement for all forty topics. Completed
summaries were reused when rerunning the corrected lookup; inference outcomes were
not selected by answer correctness.

| Metric | Before | After |
| --- | ---: | ---: |
| Completed extraction | 40/40 | 39/40 |
| Storage contract satisfied | 27/40 | 38/40 |
| Recall-assisted answer coverage | 40/40 | 39/40 |
| Strict current answers correct | 39/40 | 36/39 |
| Topic + qualification/attribution marker | 39/40 | 38/39 |
| Current answers, paired available cases | 38/39 | 36/39 |
| Recollections, paired available cases | 38/39 | 38/39 |

Storage scoring requires admitted facts for positive cases, labeled plans with
review dates for plans, and exclusion of the nonfactual topic elsewhere. It checks
the requested storage contract, not just whether the old text already contained
a hedge. Missing extraction counts as unsatisfied, separately from semantic
classification mistakes. Current-answer scoring uses strict spans/negation or
exact UNKNOWN. Recollection scoring requires the topic and an independently
specified marker; valid paraphrases can fail this lexical proxy, and a marker can coexist with an unsupported possessive claim. No model judge
was used. Scores are not calibrated probabilities.

| Category | Before storage | Before current | After storage | After current |
| --- | ---: | ---: | ---: | ---: |
| hypothetical | 4/4 | 4/4 | 4/4 | 4/4 |
| conditional | 3/4 | 4/4 | 4/4 | 4/4 |
| wish | 2/4 | 4/4 | 4/4 | 4/4 |
| plan | 0/4 | 4/4 | 4/4 | 3/4 |
| reported | 3/4 | 4/4 | 3/4 | 3/3 |
| joke | 4/4 | 4/4 | 4/4 | 4/4 |
| negation | 4/4 | 4/4 | 4/4 | 3/4 |
| question | 3/4 | 3/4 | 3/4 | 3/4 |
| changed | 4/4 | 4/4 | 4/4 | 4/4 |
| other | 0/4 | 4/4 | 4/4 | 4/4 |

After-run cases requiring inspection (including unavailable responses):

- `plan-3`: summary=available, storage=True, current=False, recollection=True. See individual response in JSON.
- `reported-3`: summary=GeminiUnavailable, storage=False, current=True, recollection=True. See individual response in JSON.
- `reported-4`: summary=available, storage=True, current=False, recollection=False. See individual response in JSON.
- `negation-2`: summary=available, storage=True, current=False, recollection=True. See individual response in JSON.
- `question-3`: summary=available, storage=False, current=False, recollection=True. See individual response in JSON.
- `question-4`: summary=available, storage=True, current=True, recollection=False. See individual response in JSON.

## Follow-up verification of the question-only guard

The full forty-case after run above still inferred horse ownership in `question-3`.
That is a real semantic failure, not merely a scoring artifact. The final change
therefore declines extracted facts and summaries from simple question-only
conversations, retaining their searchable raw transcripts. All four question
cases were rerun on fresh stores with the final code, not only the failing one:
**4/4 storage**, **4/4
current answers**, and **3/4
strict recollection markers**. These are a separate follow-up measurement;
the forty-case table above is retained without replacing inconvenient outcomes.

Two other full-run answer failures were conservative/scorer misses: `plan-3`
returned UNKNOWN rather than the requested explicit no, while its recollection
correctly said adoption had not happened; `negation-2` correctly said omnivorous
and not vegan, but the strict forbidden-token check rejected the word vegan even
inside a negation. `question-4` correctly retained a question about vegans, but the
exact topic marker vegan did not match the plural. `reported-3` extraction and
`reported-4` answering were unavailable. Recollection markers alone also failed
to detect the baseline's unsupported possessive phrasing about Clover. These
limitations are why raw answers and strict scores are both retained.

The two availability-affected cases were also retried using copies of their
synthetic databases; completed extraction was reused. This took three calls and
was selected by missing work, not by a wrong answer. The six refreshed cases
(all four affected by the new guard, plus those two availability failures) can be
combined with the other 34 measurements as an explicitly **consolidated coverage
check**, not an independent forty-case rerun:

| Metric | Before | Consolidated after |
| --- | ---: | ---: |
| Extraction completed | 40/40 | 40/40 |
| Storage contract | 27/40 | 40/40 |
| Answers available | 40/40 | 40/40 |
| Strict current answer | 39/40 | 38/40 |
| Strict recollection marker | 39/40 | 39/40 |

The strict answer/marker misses described above remain counted as failures.
Every consolidated case links its original measurement artifact. No unchanged
wrong answer was rerun to improve the score.

## Implementation and verification

- Extraction requires explicit modality, subject, durability and confidence in
  provider JSON; missing metadata cannot silently inherit factual defaults.
- Only durable user facts/plans above the existing 0.75 confidence threshold are
  admitted. Relationships such as the user's sister's name remain user facts;
  another person's own diet/location is not the user's attribute.
- Plan keys cannot replace current facts or follow a completed plan's alias into
  one. Plan corrections can supersede other plan keys. Reviews are thirty days
  after evidence time, never proof of completion or automatic reminders.
- Legacy records and verbatim remember/dashboard saves remain reported data.
  Recall exposes assertion metadata and guidance; historical material must retain
  subject, modality, negation and time. The remember declaration preserves these
  distinctions before saving. No new runtime model call is added.
- A late review also fixed forgotten-plan suppression to check the namespaced
  storage key and aliases, preventing paraphrases from resurrecting a forgotten plan.
  Two regression tests first reproduced this failure, then passed.
- Unit tests cover schema/storage rejection, high-confidence hypotheticals, plan
  reviews and aliases, legacy migration, attribution guidance, scoring, daily
  quota stops, and retry selection that does not select by correctness.

```text
cd backend && UV_CACHE_DIR=/tmp/memory-eval-uv uv run pytest
570 passed, 3 warnings in 5.65s
```

Warnings are the existing Starlette/httpx, audioop and experimental token SDK
notices. Android was not changed; no Gradle, device or Live test was run. Local
token-serializer assertions preserve `lock_additional_fields=[]` and client-owned
`sessionResumption`. No token creation, Google Docs writes or production-memory
access occurred in this task.

Source versions: the forty-case API comparison predates the final forgotten-plan
and question-only guards. Its cases contain no forget operations. The final
original API run includes the forgotten-plan fix and predates only the question
guard; none of its 120 sessions triggers that guard. A local final-code replay
checks all recorded original and adversarial recall payloads, memory byte limits,
and this zero-trigger condition without additional API calls. The four fresh
question cases exercise the final guard. Per-run source hashes are retained;
this is not a claim that every call ran the final source revision.

## Budget, reproduction and limits

**597/600 real text attempts**, including failures and interrupted
attempts; **67 recorded API/transport exceptions**; **0 Live sessions**.
Models: gemini-3.1-flash-lite: 227, gemini-3.5-flash-lite: 370.
Calls were serial, with backoff for 429/503. Later runs use a ten-second minimum
interval between completed requests. Known daily exhaustion now stops immediately;
only a sanitized quota period is retained. The SDK retries are disabled and one
persistent ledger covers every phase. No sessions remain open at closeout.

The local usage allowance was explicitly sized for the authorized benchmark
(`USAGE_TEXT_CALLS=2000`, `USAGE_TOTAL_TOKENS=50000000`,
`USAGE_EVAL_PERCENT=30`); the task ledger independently stops at 600. These are local
reservations, not claims about Google's project quotas or billing tier.

Reproduction commands and controls are in [the eval guide](../features/memory-eval.md).
Use `MEMORY_EVAL_BASELINE` with `memory.py`, `memory_summary.py` and
`memory_quality.py` from the baseline revision. Use separate synthetic state/output
paths, one shared ledger, `--limit 600`, and the same available model for a clean
comparison. `--retry-incomplete` retries only deferred extraction or missing probe
batches, reusing available answers if memory did not change. It does not choose
cases because their answers were wrong. Retain the initial JSON before recovery.

Artifacts:

- [Fresh original before](memory-eval-hypotheticals-2026-10-08-original-before.json)
- [Initial original after, quota interrupted](memory-eval-hypotheticals-2026-10-08-original-after.json)
- [Initial recovery snapshot](memory-eval-hypotheticals-2026-10-08-original-after-recovery-initial.json)
- [Recovery before deferred-summary completion](memory-eval-hypotheticals-2026-10-08-original-after-recovered.json)
- [Final original after](memory-eval-hypotheticals-2026-10-08-original-after-final.json)
- [Adversarial before](memory-eval-hypotheticals-2026-10-08-adversarial-before.json) /
  [after](memory-eval-hypotheticals-2026-10-08-adversarial-after.json) /
  [interrupted pilot](memory-eval-hypotheticals-2026-10-08-adversarial-before-pilot.json)
- [Initial before lookup](memory-eval-hypotheticals-2026-10-08-adversarial-before-initial.json) /
  [initial after lookup](memory-eval-hypotheticals-2026-10-08-adversarial-after-initial.json)
- [Fresh final-code question cases](memory-eval-hypotheticals-2026-10-08-questions-after.json)
- [Availability recovery](memory-eval-hypotheticals-2026-10-08-unavailable-after.json)
- [Consolidated coverage with per-case provenance](memory-eval-hypotheticals-2026-10-08-consolidated-after.json)
- [Final-code local replay](memory-eval-hypotheticals-2026-10-08-final-replay.json)
- [Attempt ledger](memory-eval-hypotheticals-2026-10-08-budget.json),
  [fixed forty-case corpus](../../scripts/memory_eval/adversarial.json)

Source/corpus hashes and individual answers are in the JSON artifacts. Remaining
limits: small English set, one sample per completed question, explicit rather
than subtle sarcasm in most joke cases, model-dependent classification, and
lexical scoring. Legacy facts that already lost uncertainty are preserved as
reported data, not repaired. Clock skew/overlapping sessions and semantic key
selection remain imperfect. Live voice capture, tool selection and hardware
answers remain unverified under the zero-Live budget. Follow up with fresh-session
voice tests of a conditional, a wish, a quoted friend and a completed/cancelled plan.

Sources checked 2026-10-08: [Gemini structured outputs](https://ai.google.dev/gemini-api/docs/structured-output),
[existing quota research](../research/gemini-quota.md), and
[assertion research](../research/memory-hypotheticals.md). Schema support does not
establish semantic truth. The unchanged constrained-token contract is documented
in [M1](../research/m1-android-notes.md) and [M2](../research/m2-backend-notes.md).
