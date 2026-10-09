# Google Doc creation and nicknames

Date: 2026-10-09.

- [documents.create](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/create),
  checked today: creates a blank document with the supplied title; content fields
  are ignored. Accepts the existing documents scope or drive.file. Implementation
  reuses the existing Docs service and appends separately after successful approval.
- [files.list](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list),
  checked today: supports paginated metadata listing and reports incomplete searches.
  Reuse Notes' pagination/incomplete-search handling with a fresh listing for each
  creation check. Title uniqueness is enforced by this app, not a Google transaction.
- Local code evidence: `DeviceApprovals.offer` fetches the backend snapshot by ID;
  `GeminiSession` routes backend tools generically and offers each response to that
  controller. The new JVM test covers all three names and all tier 1 input methods.
  No Android production change is required; Over-mode turn handling is untouched.
- `meeting_docs.py` already resolves aliases and full titles ignoring case.
  New alias assignment uses the same exact-title policy, with explicit candidate
  selection on ambiguity. Legacy normal append fuzzy matching is unchanged.

## Unverified

- Real account permission, Google writes, lost-response behavior and listing
  visibility under narrower OAuth grants.
- Concurrent external clients creating the same title between our list/create calls.
- New spoken phrases with the real Gemini Live model and actual glasses inputs.

See [feature usage and limits](../features/doc-nicknames.md). Unit/integration checks
use temporary alias files and synthetic Google services; no personal files are edited.
