# Backend security and human approvals

Implemented **2026-10-08**. This contract supersedes the voice-only confirmations
in the background-jobs, notes/memory, journal and visual-memory feature guides.
Runtime conversation/caption/summary AI remains Gemini. The existing explicitly
requested Codex CLI integration is retained; no new AI provider calls were added.

## Using approvals

Updated **2026-10-08** for decision 20: [device approvals](device-approvals.md)
supersede the initial dashboard-only policy. Model tool arguments still cannot
approve actions. Routine `remember` and appends to existing resolved Docs/Inbox
execute immediately; creating Inbox requires Tier 1 approval. Existing meeting
destinations export without approval; new meeting documents require Tier 1.

For forget, research, journal replacement, visual deletion and job cancellation,
Gemini asks you to **nod or press capture**. Android displays a notification/card
and allows an on-screen Approve tap. Shake, Deny or a 60-second timeout rejects.
**Codex requires capture or screen, never nod**, and displays the full canonical
repo and instruction. The dashboard remains a fallback with browser session,
CSRF and single-use nonce protection. See the device guide for notification-size
and permission fallback behavior, gesture setup and meeting export denial.

The new authenticated `/approvals/{id}/device` endpoint accepts only
`{"method":"nod|button|screen"}` and enforces the tier itself. `/tools` has no
approval endpoint/tool: `confirm=true`, returned tokens or injected approval IDs
never execute a sensitive action. Snapshots and displayed details are hashed and
checked; the request is consumed before execution. Five-minute expiry, restart
invalidation, secret rotation and rate/size limits remain. Android polls dashboard
outcomes and informs Gemini without repeating the tool.

Existing scheduled journaling remains operator-configured automation; use
`JOURNAL_ENABLED=false` to disable unattended publication.

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

Backend suite: **443 passed, 2 existing dependency warnings**. Android
`assembleDebug testDebugUnitTest` passes (**194 tests**, zero failures/errors/skips). Real Gemini `/token`, constrained setup,
spoken hardware confirmation and app approval/denial result turns passed for
Auto, PTT and Over with isolated synthetic memory. No Google Docs writes, real
Codex execution or physical-device checks ran. Token locking/resumption stay unchanged.

A browser nonce or app-owned input binds consent to the reviewed request, not
proof that its text is benign. The device endpoint trusts the shared app secret. The logged-in Mac user and allowlist/settings are trusted. Codex's sandbox
is not a read-isolated container; approved tasks may access readable local files.
Redaction covers configured environment secrets and loaded Google credentials,
not every unknown secret in arbitrary repository files. Archives can grow over
time within request limits; disk capacity/backups remain operator-managed.

Sources verified **2026-10-08**: [OWASP transaction authorization](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html),
[OWASP SSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html),
[Python address classification](https://docs.python.org/3/library/ipaddress.html).
See [findings and residual risks](../security-review.md) and
[research evidence](../research/backend-security.md).
