# Backend adversarial security review

Reviewed and fixed **2026-10-08**, branch `feat/secreview`. Scope: all 14 merged
feature guides, backend routes/services, dashboard templates and their callers.
Threats include authenticated model arguments derived from untrusted speech,
signs/pages, browser attackers and request floods. The Mac account, backend code,
local settings and operator-approved repository list are trusted. One worker is
required. The initial review changed only the backend; the follow-up below also
changes Android. Current API behavior is documented in
[the security feature contract](features/backend-security.md).

## Follow-up: decision 20 device approvals (2026-10-08)

The original dashboard-only findings below are historical. Current enforcement
uses [tiered device approvals](features/device-approvals.md): Tier 0 existing-Doc
appends and remember are intentionally immediate. Tier 1 sensitive actions accept
app-owned nod/button/screen; Tier 2 Codex accepts button/screen only. The dashboard
remains available. Speech/model confirmation fields never approve.

`approvals.py` prepares an immutable action before returning its ID, hashes name,
args and human-visible details, validates methods and consumes before awaiting.
Device routes require the app secret and are absent from the tool registry.
Android verifies the snapshot, shows its full details and gives hardware events
priority over model gesture questions and cooking navigation. Only one card is
active; 60-second timeout/shake denies. Server expiry remains five minutes.
Meeting snapshots reject changes; denied exports stop repeated outbox proposals.

Validation: **443 backend tests pass**, Android assemble and **194 JVM tests** pass; new tests
exercise method/tier restrictions, model argument spoofing, snapshot alteration,
expiry, replay, browser/device races, stale taps, capture priority and timeout.
Real Gemini Auto/PTT/Over token/setup plus synthetic forget confirmation and
approval-result turns pass. The phone presentation, real glasses sensors, Google
writes and Codex execution remain unverified. The shared secret authenticates the
app; it is not remote hardware attestation. Notification truncation/permissions
force screen/dashboard review when hardware presentation is unavailable.

## Findings and fixes

Locations below refer to the resulting source, including new enforcement points.

| Severity | Location | Concrete exploit / finding | Fix | Regression evidence |
| --- | --- | --- | --- | --- |
| **Critical** | `backend/app.py:165`, `backend/approvals.py:87` | A photographed sign tells Gemini to call `codex_task` twice, then `confirm=true` with the returned job ID. The old preview verified payload equality, not a human action; arbitrary tasks could launch in an allowlisted repo. | Tools only propose. Separate authenticated browser review shows the full canonical repo/task and a server nonce; CSRF + session-bound, expiring, single-use nonce consumes the immutable request before execution. Existing CLI allowlist/revalidation stays in place. | `test_sec_tool_confirmation_is_never_authority`, `test_sec_nonce_bound_to_browser_exact_action_one_use`, `test_sec_codex_approved_snapshot_and_allowlist_revocation` |
| **High** | `backend/approvals.py:15`, `backend/approval_actions.py:14` | Injected content can queue research (and publish its result), append attacker-chosen text/Doc ID, replay a returned forget token, poison explicit memory, overwrite journals or cancel jobs. Model-held tokens do not establish consent. | Independent browser approval for all these tools. Forget binds fact versions; append resolves a concrete document ID without creating/writing at preview time. Caller confirmation fields cannot authorize execution. Research/journal approval explicitly includes generation/publication. | `test_sec_tool_confirmation_is_never_authority`, `test_sec_real_forget_preview_snapshot_and_no_token_replay`, existing note/forget/journal tests now use the real approval flow |
| **High** | `backend/meeting_api.py:34`, `backend/visual_api.py:49` | Protecting only `/tools/append_to_doc` or `forget` would leave meeting finalization able to publish a model-chosen destination and client-owned visual deletion able to erase files. | Meeting export waits for human review of minutes/destination. Visual deletion proposes a fixed time interval, including pins; approval deletes records/files and installs a replay cutoff. Repeated meeting polls reuse a pending approval. | `test_sec_meeting_export_cannot_bypass_docs_approval`, `test_sec_visual_delete_requires_human_and_deletes_pins_files` |
| **Medium** | `backend/coach_source.py:31` | `is_global` alone accepts multicast and certain IPv6 translation/tunnel forms. A configured NAT64/6to4 route can encode a private IPv4 target behind an apparently global IPv6 URL, bypassing the intended SSRF policy. | Reject non-global, multicast, reserved, scoped/translation/tunnel destinations and control-bearing/oversized URLs. Retain validation of every DNS answer, pinned socket, hostname TLS verification, no redirects, no proxies/cookies, bounded bytes/time. | `test_sec_ssrf_nonpublic_and_transition_addresses`, `test_sec_ssrf_url_syntax`, `test_sec_ssrf_all_dns_answers_checked_and_pinned_socket`, `test_sec_ssrf_redirect_not_followed` |
| **High** | `backend/security_boundary.py:28`, `backend/jobs.py:102` | Pydantic field limits apply after JSON allocation. Huge/chunked bodies, repeated expensive requests, long polls and unlimited research jobs could exhaust memory, provider quota, pending tasks or disk. | Authenticate before API body buffering; byte/time limits, bounded rate windows and in-flight counts, 32 pending approvals/jobs, 1,000 archived jobs, 1 MB subprocess logs followed by process-group termination. Retire expired previews. | `test_sec_request_bounds_validation_redaction_and_host`, `test_sec_chunked_body_limit_before_parser`, `test_sec_rate_and_concurrency_limits`, `test_sec_allowlist_symlink_swap_and_queue_bound`, `test_sec_subprocess_logs_bounded_private_and_redacted` |
| **Medium** | `backend/app.py:232`, `backend/dashboard.py:146`, `backend/jobs.py:117`, `backend/secret_redaction.py:7` | Default validation errors echo input; unexpected exceptions can reach server tracebacks; child output/model summaries can repeat credentials into logs/events. Jobs DB/log modes previously depended on directory/umask protection. | Fixed validation/internal error messages. Redact configured environment and loaded Google secrets in command logs and finished job results/events; owner-only DB/log/result/report files, no-follow log opens. Private source text is not copied into error history. | `test_sec_unexpected_exception_not_logged_or_echoed`, `test_sec_job_events_redact_known_secrets`, `test_sec_subprocess_logs_bounded_private_and_redacted` |
| **Medium** | `backend/security_boundary.py:35`, `backend/dashboard.py:78` | Unlimited login attempts and arbitrary Host acceptance made local-browser deployment unnecessarily exposed to guessing/rebinding attempts. Pre-login sessions could evict authenticated sessions. | Exact Host allowlist, bounded login/auth attempt windows, prefer evicting unauthenticated sessions. Existing constant-time comparison, login rotation, expiry, strict cookies and CSRF remain. | `test_sec_request_bounds_validation_redaction_and_host`, `test_sec_session_fixation_and_transcript_escaping`, existing dashboard authentication/CSRF/expiry tests |
| **Low / defense in depth** | `backend/notes.py:123`, `backend/visual_memory.py:133`, `backend/visual_memory.py:193` | A malformed local alias file could pass path-like strings as Doc IDs; a malicious/corrupt visual DB identifier or locally planted thumbnail symlink could escape intended file access. External frame IDs were already validated, so the latter needs trusted-local-state compromise. | Validate alias/explicit Doc IDs and stored frame IDs; read thumbnails with `O_NOFOLLOW`; delete links themselves rather than their targets. Job results/logs also reject symlink paths. | `test_sec_alias_ids_cannot_be_paths`, `test_sec_visual_traversal_symlinks_and_sql_literals` |
| **Migration** | `backend/jobs.py:62` | Pre-upgrade queued work could execute after deployment without passing the new human gate. | One-time DB version migration marks legacy queued/unconfirmed jobs failed; they require a fresh request. Previously completed artifacts remain. | `test_sec_legacy_queued_jobs_require_fresh_approval` |

## Attack surfaces already protected

- **CLI argument/command injection:** existing `create_subprocess_exec` argv, no
  shell, `--` before the entire prompt, generated UUID directories, sanitized
  branch slugs, exact repository-root check and disabled Git hooks prevented
  shell/option injection. Shell-looking instruction text stays one prompt
  argument. That does **not** make natural-language instructions safe; the
  independent approval fixes that separate boundary.
  `test_sec_codex_arguments_branch_and_env` covers hostile instruction/alias text.
- **Allowlist traversal:** paths, slash/backslash and dot/tilde prefixes are
  rejected; canonical paths are checked at confirmation and execution.
  Symlink target replacement invalidates the preview. Duplicate normalized alias
  names now fail closed. Job IDs cannot select arbitrary log paths; there is no
  HTTP job-log download endpoint.
- **Dashboard XSS/CSRF/fixation:** stored transcripts/captions use escaped Jinja
  output, no user-selected template, no `safe`/raw HTML rendering; htmx evaluation
  is disabled. Cookie authentication cannot call app APIs, and an app header
  cannot approve dashboard forms. CSP/frame denial, session rotation and CSRF
  already held; security regressions protect these properties, including new
  approval text containing markup. Existing visual-dashboard tests cover captions.
- **SQL injection/deserialization:** query values are bound parameters. Dynamic
  SQL clauses come from fixed server strings, never tool args. All untrusted
  structured payloads use JSON and local validation, not pickle/yaml/eval.
  Document names are API data; they are never filesystem paths. Literal SQL-like
  visual IDs/searches do not broaden access.
- **Retention:** the lifespan already starts immediate/60-second pruning, and
  unpinned captures older than seven days lose rows and thumbnails. Pins survive
  automatic retention, not explicit deletion. `test_sec_retention_loop_really_purges_files`
  drives the actual loop with a controlled clock; deletion tests verify filesystem
  removal and rejection of queued pre-deletion captures. No claim of forensic
  SSD/backup or remote-provider erasure is made.
- **Timing:** app authentication, dashboard login, CSRF and approval nonce checks
  use `hmac.compare_digest`; no prefix-by-prefix secret comparisons were found.

## Validation

`cd backend && UV_CACHE_DIR=/tmp/security-uv-cache uv run pytest` passed
**422 tests**, including **54 adversarial cases**, with the two pre-existing
Starlette/httpx and experimental Gemini SDK warnings. Tests isolate all local
archives, disable scheduled publication and use fake remote services.

Real API checks on **2026-10-08**: authenticated `/token` returned HTTP 200 and the
constrained Gemini WebSocket returned `setupComplete` in **Auto, PTT and Over**.
Used a temporary memory DB and no application lifespan, so no personal queued
jobs, visual cleanup or scheduled journal writes ran. Tokens/keys were not printed.
No real Codex job, Google Docs write, hardware check or Android build ran.
`lock_additional_fields=[]`, client-owned `sessionResumption`, and the supplied
Gemini model/endpoint/auth protocol remain unchanged.

Sources, accessed **2026-10-08**: [OWASP transaction authorization](https://cheatsheetseries.owasp.org/cheatsheets/Transaction_Authorization_Cheat_Sheet.html),
[OWASP SSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html),
[Python address classification](https://docs.python.org/3/library/ipaddress.html).
Source interpretation and unverified items are recorded in
[research](research/backend-security.md).

## Residual risks and follow-ups

1. **Codex is not a hostile-code container.** Its workspace-write/read-only
   settings do not restrict all local reads. An approved prompt or malicious
   allowlisted repository can influence the CLI, read available files and consume
   local resources. Shell network access is disabled for coding, but research web
   search is enabled. Use trusted repositories/tasks; an OS account/container with
   isolated credentials, filesystem and egress is needed before treating hostile
   jobs as safe. Real sandbox enforcement was not exercised here.
2. **User review is still required.** An attacker can request a misleading action
   or put persuasive instructions in the displayed text. The nonce establishes a
   real click on the full stored action, not comprehension or benign semantics.
   Spoken yes is unavailable as authority; app-owned gestures are allowed only
   for Tier 1. Phone/browser rendering and physical input still need device testing.
3. **Single-user secret and transport.** Whoever actually obtains the app secret
   can log in as the owner; these changes distinguish model tool args from a
   browser action, not two human identities. Loopback/adb remains the default.
   Configure HTTPS and exact LAN hosts before network exposure. Rate limits are
   global to one worker and can cause temporary denial of service under attack.
4. **Storage and upstream lifetimes.** Request/job/log bounds stop burst allocation
   and unbounded task queues, not indefinite archive growth, arbitrary files
   written by approved coding jobs or all OS-level connection/resource exhaustion.
   Provider quotas, disk monitoring/cleanup and service supervision remain needed.
   Local deletion cannot erase backups, Google data, raw transcript copies or an
   already connected Gemini context. Generic `forget` still suppresses facts;
   it is not full archive erasure.
5. **Model outputs remain untrusted.** Read/recall tools expose authorized personal
   context to Gemini. Summaries, generated journal/research prose and factual
   claims can be poisoned; explicit memory requests are Tier 0 by user decision;
   automatic extraction and configured journal scheduling retain their documented
   semantics. Redaction is limited to configured/loaded secrets, not arbitrary
   unknown credentials in files. Do not enable verbose SDK or request-body logs.
6. **Operational follow-ups:** test real browser approval/rejection/expiry and
   Android pending-result display; test real Docs preview/export and CLI sandbox
   in the user's normal environment; migrate legacy smoke harnesses to a localhost
   TestClient base URL. Existing UI/API callers must not treat `needs_confirmation`
   as a completed write/delete. Keep one worker; inspect uncertain remote outcomes
   before requesting a replacement approval.
