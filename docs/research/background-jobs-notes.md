# Background jobs verification

Date: **2026-10-07 (America/Toronto)**.

## Evidence

- Installed `codex-cli 0.160.0`, `codex exec --help`: supports `-m`, `-c`,
  `-s read-only|workspace-write`, `-C`, `-o`, `--output-schema`, `--ephemeral`,
  `--ignore-user-config`, and `--skip-git-repo-check`. Authentication still uses
  the existing Codex home when user config is ignored. The requested
  `gpt-6-astra` and high effort are preserved, with no fallback model.
- Official [CLI reference](https://developers.openai.com/codex/cli/reference)
  (redirects to `https://learn.chatgpt.com/docs/developer-commands?surface=cli`)
  and [config reference](https://developers.openai.com/codex/config-reference)
  (redirects to `https://learn.chatgpt.com/docs/config-file/config-reference`)
  were opened. `web_search="live"` is the current mode setting; the requested
  `tools.web_search=true` is also a supported tool configuration. Explicit
  `approval_policy="never"` prevents unattended prompts. The backend passes no
  OpenAI API key and uses the Mac's Codex subscription login.
- [Gemini Live WebSocket reference](https://ai.google.dev/api/live) supports
  `clientContent` updates. Android encodes a user text turn with
  `turnComplete=true`; the text labels the serialized event as result data, not
  instructions/consent. This preserves the existing token/setup/resumption path.
- [Android notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
  requires runtime permission on Android 13+. The listener keeps undelivered
  events pending when permission/channel is disabled.
- Existing [M2 notes](m2-backend-notes.md) verify the
  [Google Docs append API](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/request).
  Research reuses `Notes.append` and its account lock, matching, and no-retry
  behavior; only exact default-title find/create is added.

## Validation

- Backend `uv run pytest`: **62 passed, 2 warnings**. New tests mock subprocess
  creation and cover allowlist traversal/root validation, fuzzy ambiguity,
  confirmation preview/binding/expiry/replay, revocation, concurrency, running
  and queued cancellation, process-group signals, timeouts, persistence,
  orphan recovery, single-runner lock, graceful restart, Docs result states,
  authenticated events/tool validation and token declarations.
- Android `./gradlew assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL**,
  **23 JVM tests**. New tests cover cursor replay/persistence, result-data wire
  framing, and authenticated event polling. Writable temporary Gradle/Android
  caches and a temporary copy of the existing debug keystore were needed in
  the restricted agent workspace. No build configuration/keystore was committed.
- Real `scripts/smoke_token.py`: **HTTP 200**, `gemini-3.8-live`, expanded tool
  declarations accepted during token creation. Tokens and keys were not printed.
- Real `scripts/smoke_jobs.py`: queued via authenticated tool, launched installed
  CLI with requested model/sandbox, persisted failure and completion event.
  **No model result**: CLI's in-process app server failed with `Operation not
  permitted`. A separate retry directing `log_dir` and `sqlite_home` to writable
  temporary paths failed likewise. Do not mistake the mocked Ottawa summary in
  tests for a real model result. Google token was absent; no Docs append ran.

## Unverified

- Successful Codex model execution/account access under a normal Mac shell;
  rerun `cd backend && uv run python ../scripts/smoke_jobs.py` there.
- Actual coding worktree edits and sandbox enforcement against push/merge in
  the installed CLI; process argv/allowlist logic is tested with mocks.
- Real research source correctness, summary quality, and Google append.
- Gemini audible response to an injected event; physical Android notification
  permission/display and background process behavior; nod confirmation.
- Guaranteed notification while app is killed (not implemented), exactly-once
  audible playback (not claimed), automatic cleanup/retry (not implemented).
