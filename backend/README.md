# The glasses app backend — M2 notes

Python 3.12 / FastAPI, managed with uv. Issues Gemini Live ephemeral tokens and
appends notes to Google Docs using a single personal Google account.

## Setup and run

Install [uv](https://docs.astral.sh/uv/getting-started/installation/), then:

```sh
cd backend
uv sync
```

Set these in **repo-root `.env`** (one directory above `backend/`) or export them
in the server's environment. The service uses `python-dotenv` with an absolute
path; exported values take precedence. Root `.env.example` is not updated by this
milestone.

| Variable | Required | Meaning |
| --- | --- | --- |
| `GEMINI_API_KEY` | For `/token` | Google AI Studio Gemini API key, held only on the backend. |
| `APP_SHARED_SECRET` | For every request | Random shared secret; send as `X-App-Secret` from the app. |
| `GEMINI_LIVE_MODEL` | No | Defaults to `gemini-3.8-live`; must support Live and ephemeral tokens. |

Example **values to fill in locally**, never commit:

```dotenv
GEMINI_API_KEY=your-google-ai-studio-key
APP_SHARED_SECRET=your-random-shared-secret
GEMINI_LIVE_MODEL=gemini-3.8-live
```

Generate a secret locally with `uv run python -c 'import secrets; print(secrets.token_urlsafe(32))'`.

From `backend/`:

```sh
uv run uvicorn app:app --host 127.0.0.1 --port 8787
```

In another terminal, with the phone connected over USB:

```sh
adb reverse tcp:8787 tcp:8787
```

The phone uses `http://127.0.0.1:8787`. Run one server worker: the Drive cache,
Google clients, and Inbox creation lock are process-local. Restart after changing
environment variables or completing Google consent. Alias edits are read on each
request.

## Google setup

1. Create a project in the [Google Cloud console](https://console.cloud.google.com/).
2. Enable **Google Docs API** and **Google Drive API**.
3. Configure the OAuth consent screen / Google Auth Platform Audience as **External**.
4. **PUBLISH TO PRODUCTION** (`Publish app` → `In production`) before consent.
   External apps in Testing get seven-day refresh tokens for these scopes.
   Personal use can remain unverified and display Google's unverified-app screen.
   See [Google's refresh-token rules](https://developers.google.com/identity/protocols/oauth2#expiration)
   and [personal-use verification exceptions](https://support.google.com/cloud/answer/13464323).
5. Create an OAuth client of type **Desktop app**. Save the downloaded client JSON
   at `backend/secrets/client_secret.json` (`mkdir -p backend/secrets` from the
   repository root).
6. From `backend/`, run:

   ```sh
   uv run python ../scripts/google_auth.py
   ```

7. Grant both `https://www.googleapis.com/auth/documents` and
   `https://www.googleapis.com/auth/drive.metadata.readonly`. The script opens a
   local browser flow and writes `backend/secrets/token.json` with owner-only
   permissions. Both files are ignored by Git.
8. **Re-consent after publishing** if you previously authorized in Testing:
   re-run the script, then restart the backend. Publishing does not guarantee a
   permanent refresh token; revocation and other expiration rules still apply.

The service refreshes and atomically saves expired Google credentials. Google
credentials stay on this machine; the phone only receives Gemini ephemeral
tokens and tool results.

## API

Every API request requires `X-App-Secret` (the `/dashboard` browser UI uses its own
login session and CSRF protection). Missing or incorrect secrets return 401;
an unconfigured server secret returns 503. Comparison uses `hmac.compare_digest`.
Interactive docs and the OpenAPI route are disabled. Authenticated responses
carry `Cache-Control: no-store`.

### `POST /token`

Send `{}` (dashboard default, initially automatic VAD), `{"mode":"auto"}`, or
`{"mode":"ptt"}`, or `{"mode":"over"}`. Explicit mode overrides the dashboard default. Response:

```json
{"token":"auth_tokens/…","model":"gemini-3.8-live","expires_at":"2026-10-06T12:30:00+00:00"}
```

The backend locks the model, [system prompt](prompts/system.md), Kore voice,
audio responses, both transcriptions, tool declaration, context compression,
session resumption, and VAD. Auto VAD uses LOW start/end sensitivity, 300 ms prefix
padding, and 1200 ms silence. PTT disables automatic detection; the phone must
send `activityStart` / `activityEnd`.

The token is single-use for a new session. Start within **60 seconds**; the token
expires after **30 minutes** (`expires_at`). Session resumption does not count as
another use. Connect with Gemini Live **v1beta**, using the returned model and a
minimal setup; send the ephemeral token as `access_token` or `Authorization: Token
<token>`. The app must manage audio, tool calls, and reconnection/resumption.

Google's current [model catalog](https://ai.google.dev/gemini-api/docs/models)
and [ephemeral-token guide](https://ai.google.dev/gemini-api/docs/ephemeral-tokens)
both support `gemini-3.8-live`; no fallback is applied. A model override is used
as supplied. Missing API key returns 503; upstream failures return a sanitized 502.

### `POST /tools/append_to_doc`

The body is the Gemini function call's `args` object:

```json
{"doc_name":"Inbox","text":"Remember the spare charging cable."}
```

`text` is required and nonblank (up to 100,000 characters); `doc_name` defaults to
`Inbox`. The optional `confirmed_doc_id` bypasses name matching after the user
selects a candidate.

Resolution order:

1. A confirmed ID, then an exact case-insensitive alias in `doc_aliases.json`.
2. `{"inbox": null}` finds a Doc titled **Glasses Inbox**, creating one if absent.
   Duplicate exact titles require confirmation. A cached miss is refreshed before
   creation. To use a renamed Inbox reliably, set `"inbox"` to its document ID.
3. Otherwise, RapidFuzz `token_set_ratio` matches normalized titles from the
   paginated Drive list, cached for five minutes. Score **≥85** and **≥10** ahead
   of the runner-up appends; score **≥60** without that margin requires
   confirmation (up to three candidates); score **<60** returns `not_found`.

Example alias configuration:

```json
{"inbox":null,"work log":"paste-your-document-id-here"}
```

Tool responses follow the app contract:

```json
{"status":"ok","doc_title":"Glasses Inbox"}
{"status":"needs_confirmation","candidates":[{"id":"document-id","title":"Work Log"}]}
{"status":"not_found","suggestion":"create_or_inbox"}
```

On confirmation, the app/model resubmits the original text with the selected
`confirmed_doc_id`. The append is one Docs `batchUpdate` insert at the end of the
**first tab's body**, with a UTC timestamp on its own line. It never rewrites the
document. Confirmation and no-match responses do not append or create anything.

Invalid input returns 422; missing/invalid Google credentials or alias configuration
returns 503; Google operation failures return a sanitized 502. Check the document
before retrying a failed write: network timeouts can leave the result uncertain,
and appends have no idempotency key. Unknown `POST /tools/{name}` routes return 404.

## Browser dashboard

Open **http://localhost:8787/dashboard** and sign in with `APP_SHARED_SECRET`.
Use the same `adb reverse tcp:8787 tcp:8787` command for the Pixel browser.
Conversations, memory CRUD, notes history, settings, and status are included.
Run one worker on localhost using the command above. Full usage, storage formats,
extension hooks, and verification limits: [dashboard feature](../docs/features/dashboard.md).

## Checks

```sh
cd backend  # from the repository root
uv sync
uv run pytest
uv run python ../scripts/smoke_token.py
```

Tests mock Google clients and Gemini's network boundary; they exercise both
the configuration objects and real SDK HTTP serialization. The smoke script calls
the real `/token` handler in-process only when `GEMINI_API_KEY` is present in the
environment or root `.env`; otherwise it reports a skip. It never prints tokens
and never calls Docs. If necessary, it uses a temporary in-process app secret.

Current known dependency warnings: Google marks token creation experimental, and
Starlette deprecates its httpx-based TestClient in favor of httpx2. Tests pass with
the locked versions. See [dated verification and unverified items](../docs/research/m2-backend-notes.md).

No M2 runtime functions are stubbed. Live token issuance, OAuth consent, real Docs
writes, and phone/glasses integration still need credentials and device testing.
Memory and transcript endpoints belong to M3 and are not implemented here.
