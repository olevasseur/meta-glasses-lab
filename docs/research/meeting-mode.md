# Meeting capture and minutes verification

Date: **2026-10-07**. Runtime: Gemini only. Read alongside M1/M2/M3 protocol notes.

## Silence experiments against the real constrained API

Used backend-only credentials from ignored `.env`, synthetic macOS speech,
16 kHz mono PCM16 and the existing constrained WebSocket endpoint with
`Authorization: Token …`. No personal recordings or Google Docs were sent.

- Manual activity detection disabled, `activityStart` sent, no `activityEnd`:
  **zero transcription and zero audio** during a 25-second sample window. This
  cannot be used as a continuously open two-hour transcription turn.
- Automatic detection with a scribe instruction: the complete ordinary test
  utterance was transcribed, but results varied from zero audio to nonzero PCM.
  One exact-config probe returned **68,642 PCM bytes**, **60,716 nonzero bytes**,
  with no output transcript. A prompt alone is not an enforceable silence rule.
- `proactivity={proactive_audio: true}` inside the token's Live constraints:
  token creation returned HTTP 400, unknown `proactivity` in
  `auth_token.bidi_generate_content_setup`. The capabilities guide documents
  this feature, but this constrained-token surface did not accept it. It is
  **not enabled** by the implementation.
- Chosen approach: automatic input transcription, locked meeting-context instruction,
  and a **client-side audio AND tool gate** opened only by recognized direct
  address, or a trailing whole-word `over` when the meeting Over option is enabled.
  The Android gate is JVM-tested; API probes measure server output, not Android
  speaker output. Unaddressed model audio is discarded, not enqueued or archived.
- A later `Hey assistant` PCM probe transcribed the complete address and returned
  “You agreed to launch the pilot on Friday,” with 118,560 response PCM bytes.
  Other recognized-address probes remained silent. The app therefore replays a
  recognized request once as explicit client text after two seconds without a
  response. A real constrained-session text probe returned “I'm ready to help”
  with 57,602 PCM bytes. A later audio-plus-text replay still returned no response
  under the original strict-silence prompt. The final prompt delegates silence
  to Android and tells Gemini to answer direct requests normally, removing the
  strict-silence directive. With that final configuration, ordinary speech produced
  311,042 PCM bytes (gate closed), direct `Hey assistant` produced the correct
  Friday answer and 117,120 PCM bytes (gate open), and handle resumption passed.
  Missing wake words still fail closed; repeated provider success is not guaranteed.
- A real short Live session produced a resumable handle; reconnect using the
  same token and that handle returned `setupComplete`. This verifies acceptance,
  not a natural ten-minute rollover or two-hour endurance.
- Some synthetic utterances lost their initial `Assistant` in input transcription.
  The gate correctly fails closed when the wake word is absent. The longer
  `Hey assistant` phrase is supported; use the UI if recognition fails. Do not
  use model-generated output as evidence that the user addressed the assistant.

The implementation preserves minimal client setup, `sessionResumption: {}` or
its handle, `lock_additional_fields=[]`, 30-minute token renewal and sliding
context compression. Unsent PCM has a 30-second bounded rollover buffer. Old
resumption rejected with 1007/1008 starts a fresh scribe session; backend meeting
identity and saved chunks survive. Audio already sent but not transcribed is
not replayable with certainty.

## Structured minutes and long transcripts

Real fixture: `scripts/fixtures/meeting-20min.txt`, **2,291 words** across twenty
minutes including a dashboard demo and silent budget review. Two map calls with
8,000-character smoke chunks, then one reduce call, succeeded with
`gemini-3.5-flash-lite`. The production map size is 24,000 characters, reduce
fan-in four, repeated hierarchically with durable per-step checkpoints.

`docs/features/meeting-mode-smoke.json` contains the actual generated result:
summary, seven decisions, attributed tasks/deadlines, open questions, two recap
sentences. It correctly retained the $200 cap and corrected the proposed Monday
launch to conditional Friday with staged invitations. These are model outputs,
not proof of exhaustive or always-correct summarization.

The real provider rejected the initial nested Pydantic schema with HTTP 400
`INVALID_ARGUMENT`. A structural `response_json_schema` without length/count,
default and additional-property constraints succeeded. Full Pydantic validation
still runs locally; invalid responses leave the current step retryable.

A mocked multi-hour fixture exceeds the original memory summarizer's 120,000
character cutoff, checks every mapped character in order, reconstructs the
service between steps, runs multiple reduction levels, and verifies recall and
idempotent export. Meeting summaries are historical memory, not personal facts
inferred from other speakers.

## Google Docs

Create a Drive folder with MIME type `application/vnd.google-apps.folder` and a
Doc with `parents` pointing to it. Add `drive.file` to existing OAuth scopes;
previous consent must be refreshed with `scripts/google_auth.py` for new-folder
creation. Existing destination uses an alias or a unique exact document title.

Persist the document ID before writing. A session-ID Drive `appProperties`
marker recovers a lost creation reply. Append minutes and a unique marker in one
Docs batch, guarded by `requiredRevisionId`. Retry checks for that marker first.
One backend worker serializes meeting finalization and shares the existing Notes
transport lock. No broad/fuzzy destination guessing and no automatic deletion.

## Sources verified on 2026-10-07

- https://ai.google.dev/gemini-api/docs/live-api/capabilities — audio formats,
  automatic/manual activity, input transcription, proactive audio.
- https://ai.google.dev/api/live — Live message fields and activity semantics.
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens — constrained
  tokens, setup and authentication.
- https://ai.google.dev/gemini-api/docs/live-api/session-management — compression,
  session resumption, token/connection lifecycle.
- https://ai.google.dev/gemini-api/docs/structured-output — structured JSON.
- https://developers.google.com/workspace/drive/api/guides/folder — folder create
  and file parent selection.
- https://developers.google.com/workspace/drive/api/guides/properties — appProperties.
- https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/batchUpdate
  — atomic updates and revision write control.
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth — drive.file scope.
- https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#MIC
  — general microphone input for phone table capture.

## Unverified

- Physical Pixel/glasses routing, room pickup, speaker echo and two-hour battery,
  thermal, radio and rollover endurance. Phone table capture uses MIC rather than
  the ordinary VOICE_COMMUNICATION source; this does not guarantee room coverage.
- Actual Google OAuth refresh, folder creation and Doc append under this account;
  exporter behavior is tested with mocked Google clients only.
- Reliable recognition of every wake phrase, every speaker, overlapping speech,
  and exact owner attribution. No diarization or speaker enrollment is claimed.
- Natural goAway rollover, fresh-token resumption past thirty minutes, and
  rejection/fresh-session fallback on real hardware.
- Lossless capture across OS termination/network outages. Longer than thirty
  seconds offline drops old unsent PCM with a gap marker; in-flight transcription
  and the final bounded three-second drain can still lose words.
