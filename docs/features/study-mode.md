# Study mode

Implemented **2026-10-10** on `feat/study`. Study one Google Doc by nickname or normalized exact title, review it in short spoken chunks, ask questions grounded in its text, and practice concepts with persistent quiz history. The Google Doc is never modified.

## Voice flow

In Over mode, finish each request or quiz answer with **“over.”**

- “Let's study my Atomic Habits notes. Over.” The assistant names the section count/top headings and offers review, questions, or quiz. If previously opened, it offers the saved section. Duplicate titles require a spoken selection; there is no nod/button approval.
- “What did I write about identity-based habits? Over.” Answers name the source heading. If the passages do not support the answer: “Your notes don't cover that.” This studies your notes, not outside knowledge of the book.
- “Review. Over.” Then “next,” “go back,” “repeat,” or “go to the chapter on environment.” Next/previous move one audio chunk, crossing headings as needed. The assistant waits after each chunk. Long sections have multiple chunks; the saved position includes both.
- “Quiz me. Over.” One question at a time: recall, explanation in your own words, an application/example, or comparison. Answer naturally, then say “over.” Live grades the answer against the source, gives brief feedback and the correct idea, then asks another question. A weak answer gets a follow-up. No answer/silence is not graded.
- “How am I doing? Over.” Gives unseen/due counts, strengths and weak spots. Scores estimate understanding; they are not objective exam results.
- “Stop quiz. Over.” Returns to normal conversation, retaining the bookmark and history. “Continue studying. Over.” resumes the last document, including across backend restarts/days. On a fresh connection the assistant retrieves saved state; a pending quiz question is re-asked rather than assuming an unheard answer.

No new Android buttons or settings. Existing Auto/PTT/Over, tool-response playback and reconnect mechanisms apply. Tools are backend-owned and Tier 0. A saved bookmark records the last requested chunk, not the last word actually heard; use “repeat” after interrupted audio.

## Storage and tool contract

Default storage: `backend/data/study.sqlite3`, under the existing gitignore. No new environment variables, OAuth scopes, dependencies, embedding requests or text-model calls at runtime. The existing backend credentials read Docs; Google keys never move onto Android.

| Tool | Arguments | Result |
|---|---|---|
| `study_open` | optional `doc_name`, optional chosen `confirmed_doc_id` | Outline, saved position, resume flag; omit name for last doc |
| `study_section` | `action`: current/next/previous/repeat/goto; `target` for goto | One chunk, heading, section/chunk counts, start/end boundary |
| `study_search` | `question` | Up to three locally ranked passages with headings; no evidence → not_covered |
| `quiz_next` | `action`: next/stop | Stable pending attempt/concept IDs, source, question kind, prior performance; or caught_up |
| `quiz_record` | returned `attempt_id`, `concept_id`, integer `score` 0–3, optional `note` | Saved grade/due time; duplicate grades do not count twice |
| `study_progress` | optional `offset` | Saved position/mode, aggregate mastery and up to six concepts, `next_offset` |

Every successful tool payload is JSON `{status, source_policy, data}`. Candidate and empty/no-document outcomes use the same envelope. Unknown fields/blank arguments/out-of-range grades are rejected. Tool responses are bounded to 3,600 serialized UTF-8 bytes, targeting <=~1,200 prose tokens. Exact tokenizer counts vary. Navigation passages are split rather than truncated.

Each operation checks revision metadata; changed content is re-parsed, including all child tabs. Without revision IDs, content hashing requires a full read. Empty heading-only sections are navigable but not quizzed. Changed passages reset mastery, unchanged passages retain it; history is kept. Pending grades from an old revision are refused. Connectivity failures do not silently return cached content.

Quiz passage units use a simple Leitner schedule: 0 wrong/unknown, 1 partial, 2 correct idea, 3 clear explanation/application. Scores 0/1 are immediately due; 2/3 promote through 1/3/7/14/30-day intervals. Due weak units are first, overdue learned units next, unseen units next. Two successes mark a strength. Compare questions carry a second passage; comparison grades belong to the primary concept. One active document is shared by this personal backend; simultaneous independent study sessions are not supported.

## Verification and limits

See the [2026-10-10 evaluation](../evals/study-mode-2026-10-10.md) for reproducible commands/results, and [research](../research/study-mode.md) for design choices, dated official source links, and unverified items.

Local search can miss synonyms. Native headings work best; a heading-free document becomes an Introduction with chunks. Tables become cell-order text; images, footnotes, headers, footers and tables of contents are omitted. Full Docs responses above 2 MB or more than 4,000 chunks are refused. Text remains untrusted even if it claims user approval. Actual Gemini grounding/injection resistance needs live verification.

The optional Study dashboard is deferred. Backlog item 3 (questions across multiple documents) remains separate.
