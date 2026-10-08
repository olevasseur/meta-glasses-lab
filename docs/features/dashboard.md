# Web dashboard

Implemented and verified **2026-10-07**. Runtime AI remains Gemini only. No Android
files or Gemini Live locking/resumption code changed.

## Open it

```sh
cd backend
uv sync
uv run uvicorn app:app --host 127.0.0.1 --port 8787
```

Open **http://localhost:8787/dashboard**. Enter the backend's `APP_SHARED_SECRET`.
For the Pixel, run `adb reverse tcp:8787 tcp:8787` and visit the same address in
Chrome. Use one backend worker. The existing documented run command binds to
localhost; this feature adds no network listener or LAN exposure.

The UI uses server-rendered Jinja2, a pinned htmx CDN script with SRI, and small
inline CSS. There is no frontend build. Search progressively enhances with htmx;
ordinary forms and links also work without JavaScript or CDN access. Colors follow
the system light/dark preference; navigation wraps for narrow screens.

## Pages and controls

- **Conversations:** list sessions, search literal case-insensitive transcript
  phrases/session IDs, open timestamped transcript chunks in sequence order.
  Search joins chunks so a word split by Gemini still matches. Lists show 50
  sessions per page. This archive can include information subsequently forgotten.
- **Meetings:** list recordings and processing state, then open minutes, the full
  ordered transcript and a Google Doc link. Uses the page registry and existing
  browser authentication. Lists show 50 meetings per page; pending minutes remain
  visible. Viewing never triggers generation or a Google write.
- **Memory:** expand **Add a fact**, enter a topic key and fact. **Save fact** edits
  its text while retaining the key. **Delete this fact… → Confirm delete** removes
  that exact fact and adds the existing memory suppression record. Stale edits or
  deletions return 409; reload before retrying. Duplicate keys/text return 409.
  The latest 100 historical summaries appear below the facts. Changes inform new
  Gemini sessions; restart a running conversation to refresh its context.
- **Notes:** latest 100 successful note and background-research writes with timestamps, text,
  and Google Docs links. Say **“Add a note to Inbox”** in the app to create one.
  Only appends after installation are recorded. No Google write happens from
  visiting this page. Unconfirmed/failed appends are not recorded. If Google
  succeeds but local history storage fails, the tool still reports success and
  Status reports the history failure, avoiding an unsafe automatic retry.
- **Settings:** independent **Save aliases**, **Save allowlist**, and **Save
  preferences** forms. JSON input is validated and files replaced atomically with
  owner-only permissions. Missing allowlist files display `{}` without creating
  them until save; existing malformed alias/allowlist JSON is shown for repair.
- **Status:** backend/storage health, local Google credential presence/expiry,
  **Test Gemini token**, and the latest 50 sanitized server errors since restart.
  The button makes one real Gemini token request and discards the result; it never
  renders the token. Google status does not refresh or test credentials remotely.

There are no new voice commands to navigate the dashboard. Existing **remember**,
**recall**, **forget**, and **append_to_doc** voice tools supply its data.

## Configuration and storage

Existing `.env` keys remain backend-only: `APP_SHARED_SECRET`, `GEMINI_API_KEY`,
and optional `GEMINI_LIVE_MODEL`. No new key or runtime AI provider is needed.

| File | Format / behavior |
| --- | --- |
| `backend/doc_aliases.json` | Name → document ID, e.g. `{"inbox":null,"work":"document-id"}`. Only Inbox may be null. Notes reads it on each append. |
| `backend/codex_repos.json` | Name → absolute repository directory, e.g. `{"glasses":"/path/to/repo"}`. Missing/empty means no allowlisted repos. The running jobs service reads this file for requests and rechecks it before queued work starts. Directory existence is not checked. |
| `backend/data/dashboard_settings.json` | `{"journal_time":"21:30","default_mode":"auto"}`. Saved `journal_time` overrides `JOURNAL_TIME` for the journal scheduler on its next check, in the configured journal timezone. Without a saved value, the default is `JOURNAL_TIME` or 21:30. `default_mode` is `auto`, `ptt`, or `over`, used by `/token` only when mode is omitted. Android's explicit mode takes precedence. Invalid/unreadable optional preferences fall back to automatic for token requests and the environment default for journal scheduling. |
| `backend/data/memory.db` | Existing memory tables, plus `note_history(id,doc_id,title,text,created_at)`. Private `backend/data/` remains gitignored. |

Sessions and recent errors are bounded, process-local state. Restart signs everyone
out and clears errors. Note history remains on disk. Runtime allowlists/aliases
contain personal configuration; review Git changes before committing local edits.

## Authentication and CSRF

Every `/dashboard` route except login requires the dashboard cookie; even a valid
`X-App-Secret` header cannot bypass browser login. Other API routes still require
that header and do not accept the cookie.

Login compares the entered secret with `hmac.compare_digest`. A random opaque
session ID replaces the pre-login ID, and an independent CSRF token is associated
with the server-side session. Cookies are HttpOnly, SameSite=Strict, scoped to
`/dashboard`, and Secure on HTTPS (plain HTTP is supported for local development).
Authenticated sessions expire after eight hours; pre-login sessions after ten
minutes. Logout revokes the session server-side, and changing the app secret
invalidates existing sessions.

All mutations, including login/logout/token tests, require a session-bound CSRF
form token. Cross-origin / cross-site submissions are also rejected. Form bodies
are limited to 64 KB. Responses prohibit caching/framing; templates escape stored
text, restrict scripts through CSP, and disable htmx history storage/evaluation.
Error history contains only fixed route/operation names, status codes, and UTC
timestamps, never raw exception text, query strings, request bodies, or credentials.

## Extension points

At server startup, call:

```python
dashboard.register_page("jobs", "Jobs", "jobs.html", load_jobs)
```

`load_jobs(request)` returns template data. Place the trusted template at
`backend/templates/dashboard/jobs.html`, extending `dashboard/base.html`. It
appears in navigation and at `/dashboard/jobs`. Meetings, journal, and places can
use the same hook. Register any mutation routes on `dashboard.router` **before**
`dashboard.install(...)` includes it, or on an additional `/dashboard` router.
The prefix middleware enforces cookie/CSRF checks for either approach. Templates
must submit hidden `csrf={{ csrf }}` fields; validated form values are available
as `request.state.form`. Never register user-supplied templates or loaders.

The merged Codex jobs service consumes the allowlist above and shares the Notes
instance, so successful research appends appear in Notes history and journal
activity. Journal time is consumed by the merged journal scheduler; the backend
must be running with `JOURNAL_ENABLED=true`. Dashboard saves do not enable it.

## Combined merge verification

Checked **2026-10-07** after integration with main: backend `uv run pytest`
**177 passed, 2 dependency warnings in 0.98s**; Android
`assembleDebug testDebugUnitTest` **BUILD SUCCESSFUL in 18s**, **45 tasks executed**,
**107 JVM tests, zero failures/errors/skips**. Used JDK 17, the installed Android
SDK and writable temporary uv/Gradle/Android caches.

Main's job lifespan, event route, thirteen-tool assistant registry, two-tool
translator, memory isolation and Auto/PTT/Over settings remain intact. Dashboard
defaults apply only when the caller omits mode; Android's DataStore settings and
explicit requests retain precedence. `lock_additional_fields=[]` and client-owned
`sessionResumption` are preserved. Regressions cover all three dashboard defaults,
translator isolation, live allowlist edits, API authentication and research note
history. Additional test browsers share the existing server lifespan; assertions
were not weakened. The dashboard smoke does not start personal queued jobs.

All 13 decision rows and all research entries from both branches were retained.
No new credentialed API, real Codex/Docs, browser or physical-device checks ran.

## Original branch verification and limits

- `cd backend && UV_CACHE_DIR=/tmp/mgl-dashboard-uv uv run pytest`: **106 passed**,
  two existing dependency warnings (Starlette TestClient/httpx and experimental
  Gemini token creation). Includes 31 new dashboard cases for auth, expiry,
  revocation, CSRF, CRUD/suppression/conflicts, escaping, search/chunk ordering,
  pagination, settings, notes history, token redaction, and extension auth.
- `cd backend && UV_CACHE_DIR=/tmp/mgl-dashboard-uv uv run python
  ../scripts/smoke_dashboard.py`: real-secret login plus CSRF-protected real Gemini
  token button passed. Disposable memory DB; no tokens/keys printed or rendered;
  no Google Docs writes. Live setup/audio/resumption were not exercised here.
- Chromium visual checks were attempted but blocked at process launch by the
  sandbox's macOS Mach registration permission. Desktop/mobile rendering, htmx
  behavior in an actual browser, system dark mode, and Pixel/adb reverse remain
  unverified. Templates and form flows are exercised through TestClient.
- No Android files changed; Android build/tests were not run.
- Personal/local use only: one worker, linear transcript search, no retention
  policy or full-history erasure. Memory deletion retains raw archives and fuzzy
  suppression has the limitations described in [long-term memory](long-term-memory.md).
- Google credential inspection cannot establish current remote permission or token
  revocation. Re-consent, real Docs writes, journal scheduling and Codex execution
  remain separate feature/device integration checks.

Verified sources, **2026-10-07**:
- [FastAPI templates](https://fastapi.tiangolo.com/advanced/templates/): Jinja2Templates rendering with request/context.
- [Starlette responses](https://starlette.dev/responses/): response cookie options.
- [htmx documentation](https://htmx.org/docs/): pinned CDN/SRI, HTML swaps, progressive enhancement and history/security settings.
- [OWASP CSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html): synchronizer tokens and origin/SameSite defense in depth.
- Existing [M1](../research/m1-android-notes.md), [M2](../research/m2-backend-notes.md), and [M3](../research/m3-memory-notes.md) verification for preserved Gemini and memory behavior.
