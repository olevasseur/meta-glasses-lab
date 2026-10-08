# Live translator mode

Implemented and checked **2026-10-07**. Gemini interprets between English and a
selected language: other-language input becomes spoken English; English input
becomes the other language. Questions are translated rather than answered.

## Use

Start a conversation, then say **“translate Spanish”** (or another supported
language). Say **“stop translation”** / **“stop translating”** to return to the
assistant. These invoke `start_translation(other_language)` and
`stop_translation()`. Translated content otherwise stays inside the interpreter;
the translator has no Google Docs tool.

On screen, select a language and tap **Translate**. Tap **Assistant** to leave.
The chooser works before Start and while connected with audio available. Auto,
Push-to-talk and Over remain independent of persona. Switching keeps microphone capture active but pauses
forwarding during connection setup; wait for **Connected** before speaking.

Current chooser: Spanish, French, German, Italian, Brazilian Portuguese,
Japanese, Korean, Simplified Chinese, Arabic, Hindi, Russian and Ukrainian.
English is always the user's language. Each spoken output starts with an audible
English-name language label, such as “English” or “Spanish”.

By default, audio follows the existing glasses route (phone fallback when
glasses are absent). Enable **Other-language translation on phone speaker** to
let the other person hear the translation. This requires Android 12+ and is off
by default. English output stays on the normal route. Public playback buffers
the full response first, so there is extra delay. Wait until playback finishes
before speaking; microphone forwarding pauses during phone output. Tap **Stop**
to cancel it. Missing/unknown language labels stay on the normal route.

## Configuration and integration

No new secrets, dependencies or environment variables. Existing
`GEMINI_API_KEY`, `APP_SHARED_SECRET` and optional `GEMINI_LIVE_MODEL` apply;
the verified model is `gemini-3.8-live`.

`POST /token`, with the existing `X-App-Secret` header:

```json
{"mode":"auto","persona":"translator","language":"es"}
```

Use `mode: "ptt"` for push-to-talk or `mode: "over"` for sign-off/silence turn
control. Auto keeps the saved `silence_ms` patience. Omit persona/language for the normal
assistant (existing callers remain compatible). The backend accepts chooser
codes or English names and normalizes them. Unknown languages, English as the
other language and invalid persona/language combinations return HTTP 422.

`POST /tools/start_translation` accepts `{"other_language":"Spanish"}` and
returns `{"status":"ok","persona":"translator","language":"es"}`;
`POST /tools/stop_translation` accepts `{}` and returns the assistant command.
The Android client executes these commands by fetching a new token and opening
a **fresh** Live session. The tools don't mutate backend-global state. Old
tokens, resumption handles, tool jobs and buffered playback are discarded.
Network reconnects within one persona retain the existing resumption behavior.
The locked system prompt changes only through the new token, never through a
client prompt override. Toggling phone playback also starts a fresh session.

The transcript UI retains display history and the existing memory outbox keeps
uploading transcripts through persona changes. Prior context and saved memory
are not injected into the interpreter. Returning to Assistant obtains updated
memory context and all thirteen tools from the shared backend registry.
Language/persona and phone-speaker preferences persist in the existing Preferences
DataStore alongside Auto/PTT/Over, patience, sign-off, idle timeout and job cursors.

The hands-free foreground service continues to own the microphone, DAT inputs,
memory uploads and job polling across Activity recreation and screen-off use.
Capture HOLD, double-tap and notification turn controls keep their existing
mode-specific behavior. Persona switches stop watch mode and clear pending
gesture confirmations. Vision and gesture tools are available again in Assistant;
the interpreter exposes only start/stop translation. Background jobs keep running
and use phone notifications during translation, without injecting job reports into
the interpreter. Over waits for both normal and phone playback to drain before
opening another user turn. Calls/focus loss cancel phone playback and discard the
entire interrupted translation, including any remainder received after resuming.

## Combined merge verification

Checked 2026-10-07: backend `uv run pytest` **141 passed, 2 dependency warnings**;
Android `assembleDebug testDebugUnitTest` **BUILD SUCCESSFUL**, **107 JVM tests,
zero failures/errors/skips**. Used JDK 17, the installed Android SDK and writable
temporary uv/Gradle/Android caches. Tests require the complete thirteen-tool
assistant registry, two-tool interpreter, all three turn modes, memory isolation,
client-owned resumption, persisted translation/settings/job cursors, and buffered
translation discard across call pauses. Assertions were adapted to the merged
structures without weakening them. All decision rows and research index entries
from both branches were retained.

No new credentialed Gemini, Google Docs, Codex, or physical phone/glasses check
was run during this merge. The hardware limitations below remain unverified.

## Validation and limits

- Android: `./gradlew assembleDebug testDebugUnitTest` passes, including new
  token contract, out-of-order audio/transcript, routing, interruption, buffering
  bounds and mode-command validation tests.
- Backend: `uv run pytest` passes (70 tests). New coverage includes strict
  request validation, normalization, tool declarations and real SDK serialization
  of locked instructions with client-owned resumption.
- Real Gemini: `uv run python ../scripts/smoke_translation.py` from `backend/`
  passes start/stop tools and Spanish→English / English→Spanish transcript and
  audio checks with backend-issued ephemeral tokens. Details and exact observed
  translations are in [research evidence](../research/live-translator.md).
- Phone speaker routing is implemented using Android communication routing;
  it also switches the input device. Simultaneous glasses input/phone output is
  not promised. Playback verifies the selected route using silence before
  speech, restores the original route afterward, and stops on routing failure.
- Phone mode supports short consecutive utterances (40-second PCM buffer,
  12,000 transcript characters); overflow stops with an error rather than
  allocating indefinitely. This is not continuous simultaneous interpretation.
- Spoken input, glasses hardware, accents, other language pairs and reliable
  labels across arbitrary conversations need device testing. Model-generated
  labels can be wrong. Real checks used text input and verified returned audio
  bytes, not listening quality. Without glasses the “private” route is the
  existing phone fallback, not a privacy guarantee.

## Verified sources

Checked **2026-10-07**:

- [Gemini Live language support and automatic language selection](https://ai.google.dev/gemini-api/docs/live-api/capabilities#supported-languages)
  (currently 99 listed languages; the app exposes the subset above).
- [Live WebSocket output transcription and ordering](https://ai.google.dev/api/live#BidiGenerateContentServerContent).
- [Ephemeral token constraints](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens).
- [Android communication-device selection](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)).
- [AudioTrack preferred versus actual routing](https://developer.android.com/reference/android/media/AudioTrack#setPreferredDevice(android.media.AudioDeviceInfo)).

Follow-up: on Pixel + glasses, alternate Spanish/English in Auto and PTT, test
speaker on/off, Stop during playback, change language mid-session, interrupt,
disconnect Bluetooth, and force a network reconnect. Check that no old-persona
speech plays and that the glasses microphone returns after every public turn.
