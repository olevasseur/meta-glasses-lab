# Gemini outage recovery verification

Date: **2026-10-08**. Runtime AI remains Gemini only.

## Verified sources and protocol

- [Gemini troubleshooting](https://ai.google.dev/gemini-api/docs/troubleshooting):
  429/RESOURCE_EXHAUSTED and 503/UNAVAILABLE should use exponential backoff with
  jitter. Invalid requests/access need configuration fixes. The installed SDK
  `HttpRetryOptions.attempts=1` disables its nested retry loop, letting our shared
  helper bound total request latency to approximately 25 seconds. A real probe
  rejected a remaining six-second deadline (HTTP 400: minimum ten seconds), so
  the helper queues remaining work when less than ten seconds remain.
- [Live session management](https://ai.google.dev/gemini-api/docs/live-api/session-management):
  session resumption uses a server handle, with a limited lifetime. We keep valid
  handles for the same model and clear explicitly invalid/nonresumable handles or
  handles older than two hours. A model switch starts a fresh context.
- [Ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens):
  constrained WebSocket endpoint, `Authorization: Token <name>`, minimal setup
  with `model` and client-owned `sessionResumption`. Existing populated-field
  locking (`lock_additional_fields=[]`) is preserved.
- [WebSocket close codes, RFC 6455 §7.4.1](https://www.rfc-editor.org/rfc/rfc6455.html#section-7.4.1):
  1008 is policy violation and 1011 is an unexpected server condition. A 1008
  alone does not establish a bad field mask. Our reason parser distinguishes
  quota/overload, expired access/resumption and explicit invalid configuration;
  unknown policy closes retry fresh access rather than claiming a diagnosis.
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
  and [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent):
  on-device recognition and API 33 shared PCM input reuse the existing recorder.
  Availability/model installation and source support depend on the device.
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech):
  local voices and communication audio attributes allow short status speech
  independent of Gemini, using the existing conversation route.

## Real checks in this worktree

- Models list: authenticated GET
  `https://generativelanguage.googleapis.com/v1beta/models` returned HTTP **200**.
  Both `gemini-3.5-flash-lite` and `gemini-2.5-flash` were present with
  `generateContent` support. These are the shared text defaults; this verifies
  existence, not guaranteed quota or availability.
- A real ephemeral token + minimal constrained setup returned `setupComplete`
  for **gemini-3.8-live** at 16:22 UTC. The second health query hit the cache.
- A separate real constrained setup returned `setupComplete` for
  **gemini-3.1-flash-live-preview**. No microphone/audio/tool execution was sent.
- The shared text helper received nonempty generated text for a harmless
  "reply OK" request. A later probe using the final timeout policy returned
  `GeminiUnavailable` and deferred work, with no spurious configuration error.
  No personal text, images or Google Docs were sent.
- Google's earlier outage (503/high demand and 1011/internal error) was reported
  by the user; it was no longer reproducible during these probes. Recovery under
  those faults is tested with simulated failures, not claimed as live verified.

## Unverified

- Pixel/glasses HFP routing of local TTS, earcons, echo suppression and on-device
  English transcription during an actual provider outage.
- Live fallback with an active spoken conversation, real token expiry and
  two-hour session-handle expiry. Unit tests exercise the state transitions.
- Google Docs writes/uncertain-response recovery: simulated only; no real writes.
- Multi-process coordination: health cache and background worker are designed for
  the existing single-worker personal backend.
