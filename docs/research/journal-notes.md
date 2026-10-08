# Journal verification

Date: **2026-10-07**. Official documentation, installed SDK serialization tests,
and a real Gemini fake-data smoke were checked for the end-of-day journal.

## Google Docs replacement

- [documents.get](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/get):
  omitting `includeTabsContent` returns the first tab's body in `Document.body`.
- [Document structure](https://developers.google.com/workspace/docs/api/concepts/structure):
  indices use UTF-16 code units; supplementary emoji take two units. Tests cover
  emoji before/inside a journal block and markers split across text runs.
- [Requests](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/request):
  `deleteContentRange`, `insertText` and `updateParagraphStyle` support replacing
  text and applying `HEADING_1` to the date. The document's final newline must be
  retained. Implementation keeps a separate trailing blank line.
- [batchUpdate](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/batchUpdate):
  request batches are applied atomically; `requiredRevisionId` rejects stale
  writes. The app reads fresh markers/indices and uses this precondition. It does
  not blindly resend an append after an uncertain response.

Application choice: visible per-day begin/end markers plus a SQLite primary key
on date. A crash after Docs success but before the local success stamp is recovered
by replacing the same marked range. Exact-title document resolution avoids fuzzy
matches. Only plain paragraph bodies are supported. The existing Notes lock is
shared because its Google transport is not thread-safe (see M2).

## Gemini text generation and Live setup

[Structured outputs](https://ai.google.dev/gemini-api/docs/structured-output)
supports schema-constrained JSON. As verified in [M3](m3-memory-notes.md), use
`response_json_schema=Entry.model_json_schema()` rather than the API Schema
conversion. Real `gemini-3.5-flash-lite` accepted diary/highlights/people/todos
with nullable ISO due dates and Pydantic limits.

`scripts/smoke_journal.py --live-setup` generated fake day data (harbour walk,
Alex, groceries completed, email due tomorrow), stored/read it in disposable
memory, found the journal in recall, and included the email in the next morning's
due list. The real constrained WebSocket accepted all seven registered tools and
returned `setupComplete`; no protocol/locking change was required. No Google Docs
writes or real-memory changes were made, and credentials were not printed.

## Unverified

- Real Docs OAuth, permissions, document creation, write/readback and formatting.
- Physical glasses/phone voice selection and audio, long-running Live resumption.
- Perfect narrative faithfulness: the smoke added subjective filler (e.g. relief
  after groceries) despite the no-invented-feelings instruction. Extraction and
  task reconciliation are probabilistic; no stronger accuracy claim is made.
- Multiple server workers, Drive creation visibility after a lost response,
  very large histories and manual deletion/movement of the marker blocks.
- Scheduler operation while the Mac is asleep (it requires a running process);
  deterministic schedule/DST/restart behavior is covered by unit tests only.
