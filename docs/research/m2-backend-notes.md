# M2 backend notes: Gemini ephemeral tokens and Google Docs

- Verified: **2026-10-06 (America/Toronto)**.
- Scope: `backend/`, `scripts/`, and this note. The research index and other project
  docs were left unchanged because the task explicitly excludes editing them.
- Evidence: current official docs, PyPI metadata, installed SDK source, and mocked
  tests. Provider acceptance is separate from documentation/serialization checks.

## Gemini model and token API

`gemini-3.8-live` exists in the current catalog as the stable default Live model
for low-latency voice. The ephemeral-token guide explicitly uses that exact model
in `client.auth_tokens.create(config={"live_connect_constraints": ...})`.
**No fallback was needed**; the backend defaults to it, with `GEMINI_LIVE_MODEL`
as an override. Sources:
[model catalog](https://ai.google.dev/gemini-api/docs/models),
[model detail](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live),
[ephemeral tokens](https://ai.google.dev/gemini-api/docs/ephemeral-tokens).

The current guide uses **v1beta** for ephemeral tokens (creation and Live
connection). A client receives `token.name`, usable via `access_token` or
`Authorization: Token …`. `uses=1` allows one new session; resuming a session does
not consume another use. We explicitly request a 30-minute `expire_time` and a
60-second `new_session_expire_time`; `expires_at` reports the former. Source:
[ephemeral-token guide](https://ai.google.dev/gemini-api/docs/ephemeral-tokens).

PyPI reported **google-genai 2.28.0** on the verification date; `uv sync` installed
that version on Python 3.12.13 and recorded it in `backend/uv.lock`. Its actual
public API is `genai.Client(...).auth_tokens.create(config=types.CreateAuthTokenConfig(...))`.
`live_connect_constraints` is `types.LiveConnectConstraints(model=..., config=types.LiveConnectConfig(...))`.
Sources: [PyPI release](https://pypi.org/project/google-genai/2.28.0/),
[PyPI machine-readable metadata](https://pypi.org/pypi/google-genai/json),
[SDK reference](https://googleapis.github.io/python-genai/).

Installed `google/genai/tokens.py` confirms that omitting `lock_additional_fields`
locks the whole configuration; passing an empty list instead locks only populated
fields. The backend omits it. The SDK translates constraints into
`bidiGenerateContentSetup` and omits `fieldMask` for the global lock. HTTP transport
tests verify this using the real installed SDK. Some nested fields remain in
snake_case in its emitted JSON; application code leaves serialization to the SDK.
The installed async method's docstring still says v1alpha-only; we use the sync
method and the current v1beta guide. Sources: installed 2.28.0 distribution from
[PyPI](https://pypi.org/project/google-genai/2.28.0/) and
[SDK API reference](https://googleapis.github.io/python-genai/).

## Verified session fields

These Python SDK fields validate against the installed typed models and survive
the mocked HTTP transport:

| Purpose | SDK setting |
| --- | --- |
| Spoken output | `response_modalities=["AUDIO"]` |
| Voice | `speech_config.voice_config.prebuilt_voice_config.voice_name="Kore"` |
| Transcripts | `input_audio_transcription={}`, `output_audio_transcription={}` |
| Patient VAD | `realtime_input_config.automatic_activity_detection` with `disabled=False`, `start_of_speech_sensitivity="START_SENSITIVITY_LOW"`, `end_of_speech_sensitivity="END_SENSITIVITY_LOW"`, `prefix_padding_ms=300`, `silence_duration_ms=1200` |
| PTT | `automatic_activity_detection={"disabled": True}`; client sends activity start/end |
| Compression | `context_window_compression={"sliding_window": {}}` |
| Resumption | `session_resumption={}` |
| Tools | `tools=[types.Tool(function_declarations=[types.FunctionDeclaration(...)])]` |

The VAD durations are project choices, not Google's example defaults. The warm,
brief persona and confirmation behavior live in `backend/prompts/system.md` and
are supplied through `system_instruction`. No `thinking_level` is set: the guide
says it is unsupported for the standard 3.8 Live model. Sources:
[Live capabilities](https://ai.google.dev/gemini-api/docs/live-guide),
[session management](https://ai.google.dev/gemini-api/docs/live-session),
[SDK types reference](https://googleapis.github.io/python-genai/).

## Google Docs and OAuth

The selected scopes are `https://www.googleapis.com/auth/documents` (edit Docs)
and `https://www.googleapis.com/auth/drive.metadata.readonly` (list metadata).
The Drive scope is restricted; this is a personal app using an unverified OAuth
client. Sources: [Docs scopes](https://developers.google.com/workspace/docs/api/auth),
[Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth),
[personal-use exception](https://support.google.com/cloud/answer/13464323).

Desktop OAuth uses `InstalledAppFlow.from_client_secrets_file(..., SCOPES)` then
`run_local_server(port=0, access_type="offline", prompt="consent")`. Google Docs
and Drive APIs must be enabled in the Cloud project. Source:
[installed-app OAuth](https://developers.google.com/identity/protocols/oauth2/native-app),
[flow library reference](https://google-auth-oauthlib.readthedocs.io/en/latest/reference/google_auth_oauthlib.flow.html).

Google documents seven-day refresh-token expiry for External apps in Testing
when scopes extend beyond basic identity. Publish to Production before obtaining
the refresh token. The setup script directs the user to re-consent after
publishing so a new token is issued under production status; retroactive lifetime
changes for an already-issued testing token are **not established by the cited
documentation**. Production tokens can still expire or be revoked. Source:
[OAuth refresh-token expiration](https://developers.google.com/identity/protocols/oauth2#expiration).

Append uses `documents().batchUpdate(documentId=..., body={"requests":
[{"insertText": {"endOfSegmentLocation": {"segmentId": ""}, "text": ...}}]})`.
An empty segment ID targets the body, and omitting tab ID targets the first tab.
A plain UTC timestamp line precedes the note. Source:
[InsertTextRequest and EndOfSegmentLocation](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/request).

Drive listing includes the Docs MIME type and excludes trashed files. It requests
`nextPageToken,incompleteSearch,files(id,name)` and follows all pages; a partial
search is rejected instead of guessing a target. Source:
[Drive files.list](https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list).

Matching policy is the requested local contract: exact aliases first, default
Inbox find/create, then normalized RapidFuzz token-set scores with the 85/10/60
thresholds and at most three confirmation candidates. Titles are cached for 300
seconds. Before creating Inbox from a cached miss, fetch fresh metadata. Duplicate
Inbox titles require confirmation. `confirmed_doc_id` bypasses matching. A
process-local lock serializes note operations because the Google client's
httplib2 transport is not thread-safe. Source for that transport constraint:
[Google client thread safety](https://googleapis.github.io/google-api-python-client/docs/thread_safety.html).

## Verification and unverified items

- `uv sync` completed on Python 3.12.13. Tests cover auth, auto/PTT constraints,
  actual SDK serialization with mocked HTTP, matching boundaries, append body,
  alias/confirmation precedence, pagination/cache expiry, Inbox behavior,
  credential persistence, Desktop OAuth flow/instructions, errors, and unknown
  tools. Final `uv run pytest` result: **50 passed, 2 warnings in 0.60s**.
- The real `/token` smoke script **skipped**: no `GEMINI_API_KEY` was present in
  the environment or root `.env`. No real Google Docs calls or OAuth consent ran.
- **Unverified:** project-specific Gemini access/quota, real token acceptance,
  complete locked-configuration acceptance, WebSocket connection and resumption,
  and audio/VAD behavior on the phone/glasses. Doc/SDK support is verified; these
  runtime behaviors are not.
- **Unverified:** real Google consent, refreshed credential acceptance, actual
  note writes, and behavior under the user's account policies and permissions.
- No M2 runtime implementation is stubbed; upstream clients are mocked in tests.
  M3 memory/transcripts are outside scope. The single-worker lock/cache does not
  coordinate separate server processes or other apps creating the same Inbox.
- Dependency warnings observed: Gemini token creation is marked experimental;
  Starlette deprecates httpx-based TestClient in favor of httpx2. Neither warning
  caused a test failure.
