# Memory assertion research

Date: 2026-10-08. Scope: extraction and text-only recall evaluation.

## Sources

https://ai.google.dev/gemini-api/docs/structured-output (read 2026-10-08)
documents schema-constrained extraction/classification and Pydantic support.
A schema restricts the output shape, not whether a statement describes the real
world. The local schema therefore retains validation and storage applies a
separate modality/subject/durability/confidence admission rule.

https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens is the existing
verified token contract (M1/M2 and memory-eval notes). This task changes only memory
content, preserves `lock_additional_fields=[]` and client-owned resumption, and
uses no Live sessions. Local serializer assertions check the unchanged fields.

## Findings

The prior report found 80/80 correct profile answers and 50/50 recall hit@3, but
78/80 recall-assisted answers: historical transcript mentions were sometimes
promoted into current facts. Search relevance and historical labels do not encode
assertion status. The new schema separates assertion modality from confidence;
high confidence in classifying an imagined scenario must not establish the scenario.

Plans need separate storage keys as well as wording. Otherwise a correctly labeled
plan can overwrite the current home address, or follow a completed plan's retained
alias back into the address fact. Review dates never mean an action happened.
Existing unclassified data is migrated as reported, preserving text without claiming
retroactive verification. Direct remember saves stay verbatim and reported.

The new adversarial run exposed an additional question-only inference: asking
about horse ownership costs became an ownership fact despite valid schema
metadata. A conservative storage guard now rejects extracted facts and summaries
from simple question-only conversations; raw transcripts remain searchable. Mixed
declarative/question turns still require model interpretation. This guard depends
on punctuation and can decline statements embedded inside a question.

Real text evidence and reproducible source/corpus hashes are recorded in
[the evaluation report](../evals/memory-eval-hypotheticals-2026-10-08.md).

## Unverified

- Arbitrary language, indirect sarcasm and ambiguous intentions; schema validity
  cannot certify model semantics, and confidence remains uncalibrated.
- Live answer behavior, tool selection and Pixel/glasses audio; zero Live budget.
- Repairing legacy facts whose original uncertainty was already lost.
- Event-clock skew and overlapping transcript sessions; review uses session time.

## Provider availability during measurement

The real `gemini-3.1-flash-lite` run encountered structured HTTP 429 metadata
identifying a **daily** quota. No raw errors, quota IDs or project identifiers are
retained. That model was stopped; later runs use Gemini 3.5 Flash-Lite and record
that comparison limitation. HTTP 503s also caused missing batches/deferred
summaries. The evaluator now records a sanitized quota period, stops known daily
exhaustion and supports targeted retry of incomplete original-corpus work without
selecting by correctness. Completed summaries remain cached. See the request
ledger and report for final totals rather than inferring availability from one
successful request.

Sources: existing [project quota research](gemini-quota.md),
https://ai.google.dev/gemini-api/docs/rate-limits, and the installed `google-genai`
2.28.0 error representation from https://github.com/googleapis/python-genai.
The SDK stores structured error JSON in `APIError.details`; only recognized period
categories are extracted. A generic 429 alone does not establish a daily limit.
