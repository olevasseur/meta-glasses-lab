# Conversation voice stop

Verified **2026-10-09** against this checkout, JVM/backend tests and the bounded
Gemini evaluation linked below.

- Tools are declared in `backend/tools/__init__.py`, locked into ephemeral token
  config, and named in `client_tools`. Android's `routeTool` explicitly routes
  `end_conversation` to the local handler; no HTTP tool request or approval occurs.
- `ConversationService.endConversation()` uses the existing conversation-only
  transition. `stopSession()` closes Gemini, stops transcript memory, flushes its
  durable outbox, releases audio/SCO, and keeps controls armed for DAT recovery.
- The [Live WebSocket reference](https://ai.google.dev/api/live) describes separate
  tool requests/responses and input transcription text. It does not document a
  final flag on that transcription object. The local fallback accumulates input
  until `turnComplete`, and never matches an arbitrary incremental fragment.
- Our playback queue checks both queued PCM and the AudioTrack playback head.
  Voice stop waits for generation completion plus playback idle, bounded by four
  seconds from acceptance. An empty queue before goodbye arrives is insufficient.
- See [DAT/HFP research](dat-session-hfp.md) and
  [voice-stop evaluation](../evals/voice-stop-2026-10-09.md).

## Unverified

- Physical glasses farewell audibility, SCO release timing and subsequent DAT
  capture-button reacquisition. The four-second bound can truncate unusually slow
  generation or playback.
- Recognition of real HFP speech in noise; synthetic audio is not hardware proof.
- PTT still requires an open microphone turn; submitted turns use the same handler
  in all three modes. This adds no always-on recognizer or offline voice stop.
