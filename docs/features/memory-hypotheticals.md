# Memory assertion handling

Implemented 2026-10-08. Runtime AI remains Gemini only.

## Voice and buttons

Use the normal conversation controls. Say “I'm thinking about moving to Paris,”
then **Stop** to summarize and **Start** to load the revised profile. Ask “Where
do I live?” or “What did I say about Paris?” The assistant should distinguish a
current address from “you mentioned you might move to Paris.” Saying “I wish I
had a dog” must not establish dog ownership. Other people's quotes, questions,
jokes, conditionals and wishes must retain that meaning during recall.

Say “I actually moved to Paris” when a plan is completed. Explicit corrections
retain the existing stable-key/latest-evidence behavior. “I don't drink coffee
anymore” retains its negation. The user's relationship identities, such as
“my sister is Ana,” remain useful user facts; the sister's diet is her own.

No new buttons, permissions or settings. Remember/recall remain immediate;
forget keeps its existing device approval. Start a fresh session to refresh an
already-connected Gemini context.

## Storage and configuration

The extraction schema adds `modality` (`fact`, `plan`, `wish`, `hypothetical`,
`reported`), `subject` (`user`, `other person`) and `durable`, alongside existing
confidence/priority/supersession metadata. Storage admits only durable user
facts or plans with confidence at least 0.75. High confidence in a hypothetical
does not make its outcome factual. Negations and corrected present state are
preserved by the extraction instruction.

Plans use separate keys and receive a server-calculated `review_date`, 30 days
after the evidence timestamp. They cannot replace or follow aliases into current
facts. A completed/cancelled plan can be superseded by an explicit new assertion.
Forgetting a plan suppresses later extraction under its plan key or aliases,
including paraphrases of its text.
Review dates are reminders to recheck in conversation, not automatic notifications,
expiration, or evidence of completion. No extra scheduler or model calls were added.

Existing SQLite databases migrate in place. Old rows and verbatim explicit
`remember`/dashboard saves receive `modality=reported`; they are not retroactively
certified by extraction. Their text is retained. Profile entries expose modality,
subject and review date; recall also returns confidence and shared answer guidance.
Historical transcripts, summaries and journals remain searchable but must be read
for actual assertion, speaker, negation and time before use. Explicit user facts
still take priority over old history. The memory portion remains capped at 8,000
UTF-8 bytes. Gemini token locks and client-owned resumption are unchanged.

A conservative guard saves no extracted facts or summaries when every nonempty
user turn consists only of question-mark-terminated sentences. Raw questions
remain available to recall. This prevents question-only ownership inferences even
when Gemini incorrectly labels a candidate as factual. Mixed statement/question
conversations still use extraction. The guard can decline an assertion embedded
inside a question; it is not a parser for all languages or speech punctuation.

Existing `GEMINI_TEXT_MODELS`/`GEMINI_SUMMARY_MODEL` configuration applies. No new
runtime configuration or dependencies. The evaluation CLI adds `--limit` to cap
all attempts, including failed attempts and fallbacks; this task uses 600.

## Validation and limitations

See [dated evaluation](../evals/memory-eval-hypotheticals-2026-10-08.md) for before/
after original-corpus and 40-case adversarial measurements, budget and test tail.
The adversarial corpus is authored independently of Gemini outputs and covers ten
categories. Extraction, recall-assisted current-fact answers and natural recollections are scored
separately; the original corpus retains the profile-only regression check. It uses isolated synthetic SQLite stores and zero Live sessions.

Classification and answer wording remain model-dependent. Schema validation
cannot prove semantics. A model that strips uncertainty before an explicit
`remember` call cannot be reconstructed from that call's text alone. Previously
mis-extracted facts are preserved as reported legacy data, not automatically
repaired. Dates use the existing last-user-turn clock; clock skew and overlapping
sessions remain limitations. English marker/span scores are narrow proxies;
multilingual, implicit sarcasm and actual glasses speech remain unverified.

Verified sources, 2026-10-08:

- https://ai.google.dev/gemini-api/docs/structured-output — structured categories
  and JSON schema; application semantics still need validation.
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens — existing
  constrained token contract, preserved; no new Live verification in this task.
- [Assertion research and unverified items](../research/memory-hypotheticals.md).
