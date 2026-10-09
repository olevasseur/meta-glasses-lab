# Tool confirmations and connection messages

Updated **2026-10-09**. No new settings or environment variables.

In Over mode, say “Add buy milk to my notes. Over.” The user activity stays closed
while the tool runs and Gemini speaks its confirmation. Listening resumes after
the final completion and playback drain; another spoken turn is not needed to
release a tool result. Auto and push-to-talk use the same tool response path.
Existing notes/approval rules still apply, and uncertain writes are never retried
automatically.

Tool execution is capped at 45 seconds per pending batch. After the last response
is sent, the normal eight-second no-reply timer starts fresh. Input-only progress
allows 45 seconds; active model output refreshes the 45-second stall timer. Tool
recovery retains the short earcon, cancellation and late-result suppression.

The assistant defaults to English, including confirmations, and changes language
only for clear user intent to converse in another language. Translator mode is
unchanged. Native-audio Live does not support a language-code setting, so this
preference is enforced in the system instruction.

A backend network failure displays and announces “Can't reach your Mac.” Check
that the Mac backend is running and both devices can reach each other over
Tailscale. Connection retries retain their backoff. Provider capacity errors use
“Gemini is busy, retrying”; other errors keep their own messages. With no offline
Android voice installed, the existing earcon is the audible fallback.

Regression coverage includes the supplied 22:58:04–07 append sequence, combined
and separate completion events, missing early completion, playback drain,
Auto/PTT responses, delayed/multiple tool results, network exception types and
backend persona configuration. Physical-device confirmation and noisy HFP language
adherence remain unverified; no live API calls were made for this patch.

See [research, dated sources and limitations](../research/tool-reply-language.md),
including Google's [Live voice/language documentation](https://ai.google.dev/gemini-api/docs/live-api/capabilities#change-voice-and-language)
(verified 2026-10-09).

## Validation

- Android: `./gradlew --no-daemon testDebugUnitTest assembleDebug` — BUILD
  SUCCESSFUL, **291 tests, zero failures/errors/skips**. JDK 17, installed Android
  SDK and writable Gradle/Android user caches under `/private/tmp`.
- Backend: `uv run --no-sync pytest` — **633 passed, 3 warnings**. Used the
  existing local backend virtualenv with an identical `uv.lock` after fresh
  dependency downloads stalled; no dependencies or lockfile changed. Warnings
  concern Starlette/httpx, Python audioop and experimental Gemini token creation.
