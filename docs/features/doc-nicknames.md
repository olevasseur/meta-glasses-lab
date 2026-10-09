# Voice-created Google Docs and nicknames

Implemented 2026-10-09. Runtime tools remain Gemini tools; Google credentials stay
on the backend. No new OAuth scopes or Android production changes are needed.

Say “Create a Google Doc titled Travel Plans, nickname trips.” `create_doc` preserves
the exact title. The nickname defaults to the trimmed, casefolded title; supply a
short nickname if the title exceeds 60 characters. After approval succeeds, “Add
to trips: book the train” uses the existing `append_to_doc` tool. Creation does not
include an initial note; a requested first note is a separate append after success.

Say “Set nickname office for Work Log” or “Remove nickname office.”
`set_doc_alias` resolves an existing alias or a complete title ignoring case; it
never uses fuzzy matching. Multiple matching titles return candidates. The user
must choose one before the model submits its `confirmed_doc_id`; that selection
still requires approval. Removing a nickname keeps the Google Doc.

All three actions use the existing tier 1 flow: the phone fetches the immutable
backend preview and shows its summary/details. Nod, capture button or on-screen
Approve authorizes it; shake or timeout denies it. Dashboard → Approvals is the
fallback. Spoken yes, model confirmation fields, and candidate IDs cannot authorize
execution. An overwrite preview identifies the existing nickname and document ID.
A nickname changed since preview requires a new approval; the chosen document ID
cannot drift with title/alias changes. Approvals are consumed once.

Settings → Google Doc aliases lists nicknames, titles and document links. Paste a
Google Docs HTTPS URL or bare ID to add/replace a nickname, or click Remove nickname.
The advanced JSON editor remains available. Names normalize by trimming and
casefolding, must be 1–60 characters without control characters, and must be unique
after normalization. IDs use letters, numbers, hyphens and underscores (1–200
characters). Only `inbox` may be null. Inbox cannot be assigned or removed by voice
or the simple form; advanced JSON may retain its current app-assigned ID or reset
it to null. App-created Inbox IDs are saved automatically. Writes are atomic and
use the same Notes lock across voice, Inbox creation and dashboard changes.

The existing backend alias-file configuration is unchanged. No runtime alias file,
`.env`, Google secrets or Android `local.properties` is modified by this change.
Browser edits retain session authentication, CSRF and origin checks. Settings stay
editable when Google is unavailable, showing “Title unavailable” for affected rows.
Pasting an ID does not grant Google access; the configured account must have access.

Creation checks a fresh, paginated Drive listing both before preview and immediately
before creating. An exact, case-sensitive duplicate title returns `already_exists`
with candidates and offers using that document or assigning a nickname. Partial
Drive searches fail closed. If Google creation succeeds but saving the nickname
fails, the result identifies the created document; set its alias instead of retrying
creation. Never automatically retry writes with uncertain outcomes.

Limitations: the existing app is single-account/single-worker. Google does not make
listing-plus-creation transactional; another app creating the same title concurrently
can still race. Duplicate detection covers documents visible to the authorized Drive
listing (the existing metadata scope lists broadly; drive.file alone is narrower).
Approval state is process-local and expires as in the existing approval flow.

Verified locally: backend tool/approval, validation, dashboard/CSRF and voice-simulator
tests; Android generic routing/approval JVM test and full debug build. Real Google
writes, actual Gemini speech selection for the new cases, and physical glasses
approval remain unverified. No real-API smoke or account mutation was performed.

Sources checked 2026-10-09: [Google Docs documents.create](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/create)
(title-only creation, supported scopes) and [Drive files.list](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list)
(pagination and incomplete-search reporting). See [research notes](../research/doc-nicknames.md)
and the existing [device approvals](device-approvals.md) for the phone flow.
