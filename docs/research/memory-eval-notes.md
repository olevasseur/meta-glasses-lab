# Memory evaluation research

Date: **2026-10-08**. Scope: synthetic memory quality, production Python pipeline,
Gemini text probes, and deterministic context-budget stress.

## Verified sources

- https://ai.google.dev/gemini-api/docs/structured-output — Gemini supports JSON
  Schema output; schema validity alone does not establish semantic correctness.
  Continue using `response_json_schema` plus local Pydantic validation, consistent
  with the earlier real-provider finding in m3-memory-notes.md.
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens — constrained
  tokens bind session configuration. This evaluation uses the actual production
  token configuration's system instruction for stateless text-model probes.
- https://generativelanguage.googleapis.com/v1beta/models — authenticated list
  confirmed `gemini-3.1-flash-lite`, `gemini-3.5-flash-lite`, `gemini-2.5-flash`
  and the current Live model. Catalog presence does not guarantee availability.

Runtime observation: corpus generation succeeded on Gemini text models, but
3.5 Flash-Lite returned 503 and 2.5 Flash returned 429. The evaluator backs off,
keeps a persistent call ledger, and resumes completed sessions. No credentials
or provider error bodies are logged. See the dated evaluation report for final
counts and outcomes rather than treating successful catalog lookup as a smoke.

## Local findings

The original context cap included the entire app instruction. The current base
prompt is 7,166 UTF-8 bytes, leaving only 834 of the old 8,000-byte total for all
memory. Treating 8,000 bytes as the memory budget avoids growth in unrelated app
instructions evicting the user profile. This increases total system context.

The old whole-string fuzzy recall scorer returned 64.6 for the natural question
“What is my dog's name?” against “The user's dog is named Maple,” below its 65
threshold. Word matching after removing question boilerplate fixes that case.
Corrections also need evidence-time ordering: completion/upload order can differ
from when the user actually spoke. Supersession aliases must survive subsequent
updates and forgetting so an older key cannot return through delayed extraction.

## Unverified

- Arbitrary semantic corrections and forgotten paraphrases; extraction confidence
  is a model estimate, not calibrated, and supersession decisions can be wrong.
- Multilingual relevance, large-history performance beyond the small local stress
  fixture, clock-skew recovery, and raw-history/privacy erasure.
- Hardware microphone/transcript quality, spoken tool selection, Live answers,
  and provider availability/quota outside the recorded calls.
- Strict scripted probe scoring is not a general hallucination benchmark.

Additional sources inspected on the same date:
https://ai.google.dev/gemini-api/docs/thinking and
https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite.
The thinking guide distinguishes output caps from reasoning levels. No production
thinking configuration or shared retry-helper change was made. The optional
`MEMORY_EVAL_TIMEOUT_MS` only extends benchmark transport deadlines; it does not
establish that the production 25-second retry budget will finish those requests.

## Expanded-schema provider acceptance

The expanded Pydantic JSON schema initially returned HTTP 400 `INVALID_ARGUMENT`
with a generic invalid-argument diagnostic. Removing default values alone still
failed. Retaining types, required fields, object shape and references while
omitting wire-only length/count/range constraints was accepted by real Gemini
3.1 Flash-Lite. All Pydantic limits remain enforced on the returned response
before database writes; regression tests exercise confidence, priority, key/text
length and supersession bounds. The exact provider constraint responsible is
**unverified**; this is a measured compatibility workaround, not evidence that
all Gemini schemas reject those keywords.
