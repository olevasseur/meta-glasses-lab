# Dashboard implementation research

Date: **2026-10-07**.

## Sources verified

- https://fastapi.tiangolo.com/advanced/templates/ — FastAPI exposes Starlette's
  `Jinja2Templates`; render with request, template name, and context. Added only
  Jinja2 (plus its transitive MarkupSafe) to the existing backend dependencies.
- https://starlette.dev/responses/ — response cookies support HttpOnly, SameSite,
  Secure, path and max-age. This local HTTP app uses an opaque server-side session
  rather than putting authenticated state or the shared secret in a browser token.
- https://htmx.org/docs/ — CDN version 2.0.11 and published SHA-384 SRI; `hx-get`,
  `hx-target`, `hx-select`, and `hx-swap` support an enhanced search form while
  preserving plain HTML submission. History cache is disabled for private pages;
  evaluation/script injection is disabled. The base template uses the documented
  pinned CDN URL and integrity digest.
- https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html
  — synchronizer tokens tied to a session, login CSRF, and SameSite/origin checks
  as additional protection. Implemented server-side random tokens and explicit
  checks for every dashboard mutation, including login and Gemini token tests.

## Local verification

106 backend tests pass, including 31 dashboard cases. Real Gemini token creation
through dashboard login + CSRF passed on a disposable memory DB. No credentials
were printed/rendered; no Google Docs writes. `/token` configuration still uses
`lock_additional_fields=[]`, and session resumption remains client-owned.

## Unverified

- Browser/CDN interaction, visual mobile/dark layout, Pixel/adb reverse: Chromium
  launch was blocked by the sandbox's macOS Mach process-registration policy.
- Real Google auth refresh/Docs appends and actual Live WebSocket/audio/resumption
  during this feature's checks (prior protocol verification remains in M1–M3).
- Integration of `codex_repos.json` name→absolute-path mapping and
  `data/dashboard_settings.json` journal time with parallel feature consumers.
- Multi-worker use, large-history search performance, remote/public deployment.
