# Memory quality evaluation — 2026-10-08

The revised pipeline answered **80/80 profile-only probes correctly**, compared
with **54/72 returned baseline answers**. Recall hit@3 rose from **34/50 to 50/50**;
forgotten values returned in **2/9 baseline probes and 0/10 revised probes**.
Recall-assisted answers still made two hypothetical-as-fact errors. The complete
counts, missing coverage and controlled storage tests are reported separately below.

## Dataset and method

- Baseline: `73b2493d69f0d0da403dab4609b5ed269109b32c`, original `memory.py` and
  `memory_summary.py`, imported from an isolated snapshot. Shared Gemini retry
  helper, system instructions and token serializer are the same for both phases.
- Ten fictional personas × twelve sessions = **120 transcripts**. Gemini generated
  dialogue from independent authored facts/corrections. Six personas were generated
  with Gemini 2.5 Flash, four with Gemini 3.1 Flash-Lite. Original factual seeds
  are retained verbatim to prevent drift, sometimes repeating generated wording.
- Every persona gets separate SQLite storage. The harness runs real ingestion,
  session-end Gemini summarization, fact persistence, approved internal forget,
  and recall. Forget approval is simulated only in these isolated databases,
  after the session containing the forget request. No app approval bypass changed.
- Each persona gets eight profile-only questions in one fresh stateless Gemini
  text request, then the same eight questions with actual `recall()` results.
  Both phases use Gemini 3.1 Flash-Lite. Expected answers never enter these prompts.
  The system instruction comes from the production memory builder and
  `gemini.token_config`; existing locks and client-owned resumption are asserted.
- Strict span scoring requires all expected values and rejects old values even
  if qualified as historical. Unknown/forgotten answers must be exactly `UNKNOWN`.
  Recall hit@3 requires all expected values somewhere in the first three results.
  This is a narrow scripted test, not a general hallucination benchmark.
- Gemini returned 429, 503 and 504 during the run. Calls back off, preserve successful
  sessions and use a persistent global attempt ledger. Some baseline sessions
  needed retries before completing. For quality testing, transport deadlines were
  extended to 60 seconds **in the evaluator only**. Production deadline/retry logic
  is unchanged; these scores do not establish production completion latency.
- Invalid or unavailable summaries remain open with transcripts retained. The
  evaluator records final deferred summaries while continuing later sessions.
  Missing probe responses are excluded from accuracy denominators and reported
  separately as answer coverage, never scored as correct abstentions.
  Cached sessions are not regenerated on resume. Unavailable historical context
  sizes are marked null, not reconstructed from a later database state.

## Real Gemini before/after results

The first six quality rows use profile-only answers. Accuracy denominators include
only returned answers; coverage is shown explicitly. Retrieval metrics are measured
on the same fifty known-fact questions regardless of provider answer availability.

| Metric | Before | After |
| --- | --- | --- |
| Answer coverage | 72/80 (90.0%) | 80/80 (100.0%) |
| Saved-fact recall accuracy | 14/18 (77.8%) | 20/20 (100.0%) |
| Latest correction wins | 20/27 (74.1%) | 30/30 (100.0%) |
| Forgotten answer correctly withheld | 7/9 (77.8%) | 10/10 (100.0%) |
| Forgotten fact returned (lower is better) | 2/9 (22.2%) | 0/10 (0.0%) |
| Unknown-detail hallucination (lower is better) | 5/18 (27.8%) | 0/20 (0.0%) |
| Summary completion | 115/120 (95.8%) | 115/120 (95.8%) |
| Recall hit@3 | 34/50 (68.0%) | 50/50 (100.0%) |
| Recall-assisted overall accuracy | 65/72 (90.3%) | 78/80 (97.5%) |

Across all profile questions, **54/72 correct before → 80/80 after**. On the
72 questions answered in both phases, the comparison is 54/72 → 72/72. Missing
before answers were one whole profile batch; one different recall-assisted batch
was also unavailable. No unavailable answer is treated as a successful abstention.

| Recall-assisted metric | Before | After |
| --- | --- | --- |
| Answer coverage | 72/80 (90.0%) | 80/80 (100.0%) |
| Saved-fact recall accuracy | 18/18 (100.0%) | 20/20 (100.0%) |
| Latest correction wins | 27/27 (100.0%) | 30/30 (100.0%) |
| Forgotten answer correctly withheld | 7/9 (77.8%) | 10/10 (100.0%) |
| Forgotten fact returned (lower is better) | 2/9 (22.2%) | 0/10 (0.0%) |
| Unknown-detail hallucination (lower is better) | 5/18 (27.8%) | 2/20 (10.0%) |

The two remaining revised recall-assisted failures were **Blair and Indigo**:
Gemini answered `Nimbus` as the name of a real horse even though the archived user
statement explicitly described a hypothetical and denied owning a horse. The
profile-only answers correctly returned `UNKNOWN`. Current-fact ranking and
historical labels reduce this risk but do not eliminate it. On the 72 paired
recall-assisted questions, accuracy improved from 65/72 to 70/72.

The baseline returned `Copper Comet 4, pottery` after Ellis's forget request.
The revision withheld all ten forgotten hobby-project answers, both with and
without recall results. Casey's holiday, which was `UNKNOWN` from the old profile,
was correctly answered as `Lima in December` from the new profile.

Five summaries remained deferred in **each** phase: three provider failures and
two local validation failures. The raw transcripts remain stored and retryable.
The revision improves the observed memory answers; summary completion stayed
at 115/120 in both phases. The wire schema is simpler and local fact validation
remains strict. Runtime availability, retries, and this small single-sample corpus
limit causal/statistical conclusions.

## Deterministic before/after checks

These use controlled summary outputs through the real storage pipeline. They are
not presented as Gemini extraction accuracy.

| Check | Before | After |
| --- | --- | --- |
| Natural question retrieves `Maple` stored under `user.dog_name` | Fail | Pass |
| Profile preserves the attribute for a bare-value fact | Fail | Pass |
| Forget finds `Copper Comet` under `user.hobby_project.name` | Fail | Pass |
| Approved forgotten value absent from injected profile | Fail | Pass |
| Delayed older session cannot overwrite newer sister name | Fail | Pass |

**0/5 → 5/5.** The bare-value issue also occurred in real baseline extraction:
Gemini emitted values such as `Maple`, `Ana`, `Copper Comet 0`, and `pottery` with
meaning encoded only in their keys. Search/forget previously ignored those keys.
One baseline forget-only summary also emitted two empty fact texts, rejected by
validation. The revised prompt explicitly omits such entries and allows an empty
structured summary when nothing should be retained; approval still owns deletion.

## One user's 105-session context growth

A separate deterministic stress fixture finalizes 105 sessions with 105 distinct
long book entries and one enduring sister-name fact. The summarizer is controlled
here to isolate budget/ranking behavior; no extra model calls are used.

| Sessions | Before memory bytes | After memory bytes | Sister retained before / after |
| ---: | ---: | ---: | --- |
| 1 | 595 | 626 | Yes / Yes |
| 10 | 566 | 4,194 | No / Yes |
| 50 | 568 | 7,745 | No / Yes |
| 100 | 568 | 7,745 | No / Yes |
| 105 | 570 | 7,758 | No / Yes |

Both databases retain all 106 facts. The original profile retained the sister
fact in only the first two snapshots; the revision retains it in all 105.
The memory portion remains below 8,000 UTF-8 bytes. Original maximum: 595;
revised maximum: 7,758. At session 105 the total system instruction is
7,736 bytes before and 14,924 after. This deliberate increase gives memory its
own approximate 2k-token budget instead of sharing it with the 7,166-byte app
instructions. Byte size is not an exact Gemini token count.

## Implementation and limitations

The change adds evidence-time ordering, confidence and priority metadata,
supersession aliases, key-aware word retrieval, historical-result labels and
ranked profile packing. Exact text dedupe remains deterministic; semantic key
selection and supersession still depend on Gemini. Corrections and forgetting
follow aliases, including delayed uploads. The expanded constrained schema initially returned HTTP 400. A simpler wire
schema was accepted; all range/length/count limits are still validated locally
before writes. The exact rejected constraint was not identified.
The extraction input selects relevant
older facts alongside recent ones instead of only the latest 100.

Limits: small template-based English corpus; one sample per question and no
statistical confidence intervals; model-produced confidence is uncalibrated;
lexical suppression can miss paraphrases. Current-fact priority can reduce
historical retrieval diversity. Timestamps use the last user turn in a session,
so overlapping sessions and wrong phone clocks remain imperfect. Forgetting is
not raw-history erasure. Hardware capture, spoken tool selection and Live answer
quality are outside this text evaluation.

## Validation and real API budget

```text
uv run --project backend pytest backend/tests -q
512 passed, 3 warnings in 4.96s
```

Warnings are existing Starlette/httpx, Python audioop deprecation, and experimental
Gemini token SDK notices. Android was not changed; Gradle and physical-device tests
were not run. New tests cover migration, stale uploads, alias corrections/forget,
confidence filtering, real-question search, byte budgets, provider-schema/local
validation separation, evaluation scoring and persistent request limits.

**315 real text attempts** total (including generation, both evaluation phases,
retries and schema diagnostics), **30 API/transport failures** counted, plus one
successful real production token-creation call. Models: 302 attempts on Gemini
3.1 Flash-Lite, 8 on Gemini 2.5 Flash, 2 on Gemini 3.5 Flash-Lite, and 3 on Gemini
3.8 Flash. Text calls were serial; 429/503 responses backed off. An authenticated
model-catalog read also succeeded. **0 Live sessions**,
no Google Docs writes and no access to production memory. The text-call budget
was 1,500 and the Live allowance was 20; no sessions remain open.

Follow-ups: test assertion-aware handling of hypothetical recall results, broaden
the corpus beyond these templates/English phrases, and verify voice corrections,
forget approval and fresh-session recall on Pixel/glasses. Per-session timestamps
also need adversarial overlapping/delayed-reaffirmation evaluation; absence of a
changed extraction does not necessarily refresh a fact's evidence timestamp.

## Artifacts and reproduction

- [Real results before](memory-eval-2026-10-08-before.json) / [after](memory-eval-2026-10-08-after.json), with source/corpus hashes and individual answers.
- [Request ledger](memory-eval-2026-10-08-budget.json).
- [Corpus](../../scripts/memory_eval/corpus.json) and [runner](../../scripts/memory_eval/__main__.py).
- [Growth before](memory-eval-2026-10-08-growth-before.json) / [after](memory-eval-2026-10-08-growth-after.json).
- [Deterministic checks before](memory-eval-2026-10-08-regressions-before.json) / [after](memory-eval-2026-10-08-regressions-after.json).
- [Token check](memory-eval-2026-10-08-token.json): production token creation accepted synthetic memory; **zero Live sessions**.
- [Feature/configuration guide](../features/memory-eval.md) and [research/source URLs](../research/memory-eval-notes.md), verified 2026-10-08.

Sources: [Gemini structured outputs](https://ai.google.dev/gemini-api/docs/structured-output)
and [ephemeral-token constraints](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens).
