# Chunked meeting audio

Verified **2026-10-08**. Supersedes the continuous Live scribe design in
[meeting-mode.md](meeting-mode.md); see the failed ten-minute soak in
[the evaluation](../evals/soak-2026-10-08.md).

- https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite — accepts
  audio, produces text, supports structured outputs; no Live support needed.
- https://generativelanguage.googleapis.com/v1beta/models — authenticated catalog
  returned `gemini-3.5-flash-lite` and `gemini-2.5-flash`, both with
  `generateContent` and a 1,048,576 input-token limit. Only model metadata logged.
- https://ai.google.dev/gemini-api/docs/audio — WAV/FLAC/Opus supported, inline
  requests limited to 20 MB, documented audio limit 9.5 hours. Our 62-second WAV
  limit is 1,984,044 bytes before provider base64 encoding, well below that limit.
  The documentation's current examples use Interactions; the installed SDK's
  `models.generate_content` with `Part.from_bytes(audio/wav)` was independently
  exercised with real audio in this evaluation.
- https://ai.google.dev/gemini-api/docs/pricing — standard Flash-Lite 3.5 input
  including audio $0.30/million tokens, output including thinking $2.50/million;
  listed free tier has no inference charge. Actual account billing tier/credits
  are unknown. Usage metadata is an estimate basis, not an invoice.
- https://developer.android.com/reference/android/speech/RecognizerIntent —
  `EXTRA_AUDIO_SOURCE` allows the existing Android 13+ on-device recognizer to
  consume a pipe from the shared PCM stream. It need not open a second microphone.
- https://developer.android.com/reference/android/speech/SpeechRecognizer —
  on-device availability must be checked; recognition is optional for recording.

Implementation: PCM16 mono 16 kHz WAV, ~60 s cuts, up to 62 s to find 300 ms of
silence, 2 s repeated context. Private on-device disk outbox, authenticated raw
upload, SQLite durable receipts and queued audio, non-Live transcription via the
existing retry/fallback/meter helper. Speaker letters are **chunk-local change
hints**, not a persistent identity system. Overlap removal compares only normalized
word suffix/prefix within the overlapping time interval, with a 40-word ceiling.

Real pilot exposed a schema failure: the model sometimes emitted seconds into
fields named `start_ms`/`end_ms`. The final schema asks explicitly for fractional
`start_seconds`/`end_seconds`, converts to milliseconds locally, and validates
bounds/order. The evaluation reran that pilot; its calls remain in metered totals.

Unverified: physical Pixel/glasses HFP route, recognizer accuracy/background
availability, simultaneous assistant playback and room recording, call/radio
interruptions, two-hour hardware battery/thermal behavior, real Google Docs
export, overlapping natural speakers, consistent diarization and perfect timestamps.
No voice biometric identification is performed. Exact overlap matching can retain
near-matching ASR variants or remove genuine repeated words at the boundary.

Final real evaluation: 21 chunks / 1,200 seconds processed, 34 audio attempts and
one minutes call, zero Live sessions. The exact-silence shortcut handled four
chunks locally. WER 7.31%, zero exact boundary repeats of three or more words.
Nine 503s and three 429s were backed off; provider recovery/fallback retained all
queued audio. Most action owners remain unknown in the generated minutes.
Full evidence: [evaluation](../evals/meeting-chunked-2026-10-08.md).
