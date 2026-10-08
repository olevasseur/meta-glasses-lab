# Backend security and human approvals

Implemented **2026-10-08**. This contract supersedes the voice-only confirmations
in the background-jobs, notes/memory, journal and visual-memory feature guides.
Runtime conversation/caption/summary AI remains Gemini. The existing explicitly
requested Codex CLI integration is retained; no new AI provider calls were added.

## Using approvals

Speak the same request, for example “Ask Codex to fix the tests,” “Research this,”
“Add this note to Work,” “Remember this fact,” or “Forget Maple.” The backend
returns `status: needs_confirmation`, an opaque `approval_id`, and a relative
`approval_url`. **Nothing is executed by saying yes, nodding, returning a token,
or setting `confirm=true`.**

Open **http://localhost:8787/dashboard → Approvals** on the Mac (or the dashboard
through the phone's configured backend address). Sign in with the existing app
secret. Open the request, inspect its full contents/destination and visible
single-use confirmation code, then press **Approve this exact action** or
**Reject**. The page displays the outcome; job results still use events/status.
Requests expire five minutes after creation and do not survive restart or secret
rotation. Reloading the review rotates its code; use the newest page.

The protected operations are `codex_task`, `deep_research`, `append_to_doc`,
`forget`, `remember`, `write_journal` and `cancel_job`. Note previews resolve and
bind a specific document ID (or explicit creation of Glasses Inbox); they do not
create a Doc. An ambiguous/missing destination requires a new request naming a
specific document ID, available in its Google Docs URL. Forget previews bind
specific fact versions; an intervening edit invalidates deletion. Codex previews
show the full task and canonical allowlisted repository, checked again at start.
Research/journal approval authorizes generation and publication; the generated
report is not available to inspect before generation.

**Delete last hour**, by voice or button, pauses capture as before, but
`POST /visual/delete` now proposes deletion. Approve it in the dashboard to remove
records and thumbnails, including pins. The displayed interval is fixed when
requested (with the existing one-minute future capture-clock allowance); later
queued captures through its cutoff are rejected. The dashboard's existing manual
single/range deletion buttons remain protected by browser CSRF and confirmation.

Meeting summarization continues normally, but `/meetings/{id}/end` returns an
approval when ready to create/write the document. Review its minutes/destination
and approve export. Repeated phone polls reuse the pending approval. Subsequent
polls return the finished recap as before. Existing scheduled journaling is an
operator-configured automation and is unchanged; use `JOURNAL_ENABLED=false` if
unattended publication is not wanted.

## Configuration and limits

Run **one worker**, on loopback for `adb reverse`:

```sh
cd backend
uv run uvicorn app:app --host 127.0.0.1 --port 8787 --no-access-log
```

`--no-access-log` avoids retaining arbitrary URL/query strings in Uvicorn's access
log. Do not enable upstream SDK debug/body logging with real credentials.

`BACKEND_ALLOWED_HOSTS` defaults to `localhost,127.0.0.1,::1`. For intentional LAN
use, explicitly add the Mac's exact hostname/IP, e.g.
`localhost,127.0.0.1,::1,192.168.1.10`, and arrange HTTPS before transporting the
shared secret off loopback. No wildcard is supported. In-process smoke harnesses
should use `base_url="http://localhost"`; legacy harnesses using `testserver`
need that name explicitly in their test environment.

Fixed single-process bounds:

| Boundary | Limit |
| --- | --- |
| Browser form body | 64,000 bytes |
| Transcript/visual request body | 4,000,000 bytes (existing per-field limits also apply) |
| Other API bodies | 500,000 bytes, including JSON encoding overhead |
| Body receipt | 15 seconds; streamed bytes counted even without Content-Length |
| In-flight requests | 16 total, at most 4 event polls |
| Expensive endpoints | 30/minute combined: token, frame, project generation, session/meeting end, dashboard token test |
| Other API / browser requests | Separate pools of 240/minute each |
| Login / failed API auth | 30 login requests / 60 auth failures per minute |
| Pending approvals | 32, five-minute expiry |
| Pending/active jobs | 32; expired coding previews are retired |
| Durable job archive | 1,000 job records; operator cleanup required at capacity |
| Subprocess log output | 1 MB per command; excess terminates the process group |

Limits return 413/408/429 or a sanitized job/configuration error. No background
retry is added for uncertain remote writes. An approval is consumed before any
side effect; after an error, inspect the destination before requesting another.
Existing queued/pre-confirmation jobs from a pre-security database are invalidated
once on migration and require a fresh request. Completed results are retained.

## Verification and limitations

Backend suite: **422 passed, 2 existing dependency warnings**, including 54
adversarial cases. All new adversarial cases use
`test_sec_*` names. Real Gemini `/token` plus constrained setup succeeded for
Auto, PTT and Over, with resumption ownership and token locking unchanged.
Android source was untouched, so no Android build was required. Existing Android
JSON forwarding carries pending approvals, but dashboard approval does not inject
a new spoken note/delete result into an already completed tool call: read its
result page. Physical voice/buttons, browser rendering, real Docs publication
and real Codex sandbox behavior remain unverified.

The nonce proves a browser action on the reviewed request, not that the text is
benign. The logged-in Mac user and allowlist/settings are trusted. Codex's sandbox
is not a read-isolated container; approved tasks may access readable local files.
Redaction covers configured environment secrets and loaded Google credentials,
not every unknown secret in arbitrary repository files. Archives can grow over
time within request limits; disk capacity/backups remain operator-managed.

Sources verified **2026-10-08**: [OWASP transaction authorization](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html),
[OWASP SSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html),
[Python address classification](https://docs.python.org/3/library/ipaddress.html).
See [findings and residual risks](../security-review.md) and
[research evidence](../research/backend-security.md).
