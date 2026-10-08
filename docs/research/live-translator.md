# Live translator verification

Verified **2026-10-07**, America/Toronto. Runtime model: `gemini-3.8-live`.

## Language and protocol evidence

- [Live capabilities: supported languages](https://ai.google.dev/gemini-api/docs/live-api/capabilities#supported-languages)
  currently lists **99 languages**, including English and Spanish. Native-audio
  models choose languages automatically and switch naturally; explicit speech
  `languageCode` is unsupported. Restrict the pair through system instructions.
  This supports automatic multilingual handling; it does not promise perfect
  recognition of accents, overlapping speakers, or mixed-language utterances.
- [Gemini 3.8 Live model](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live)
  supports text/audio input, audio generation and function calls. Keep the
  existing model, PCM16/24 kHz output and transcription configuration.
- [WebSocket reference](https://ai.google.dev/api/live#BidiGenerateContentServerContent)
  defines output transcription as text, without a language-ID field. Its ordering
  relative to audio is not exact; the final transcript precedes generation end
  and turn completion. Buffer a complete turn before choosing an output route.
- [Ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens)
  bind session configuration. Preserve `lock_additional_fields=[]`, leave
  `sessionResumption` client-owned, and acquire a fresh token/session when the
  persona or translation language changes. Do not resume the previous persona.

Implementation: prompt Gemini to speak an English-name label (`English:` or
`Spanish:`) before the translation. These labels are **audible**, not hidden
metadata. They avoid adding a separate language-detection service. Missing or
unrecognized labels use the private route. This is model-directed routing,
not a guaranteed classifier. All routes still carry the original Gemini audio.

## Android feasibility

[AudioManager.setCommunicationDevice](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo))
is available from API 31. It selects an output from available communication
devices; Android selects the matching input automatically. Therefore do not
promise continuous glasses microphone capture while switching to the phone.

[AudioTrack routing](https://developer.android.com/reference/android/media/AudioTrack#setPreferredDevice(android.media.AudioDeviceInfo))
distinguishes requested and actual devices: a preferred device does not guarantee
the physical route. The implementation selects the built-in communication
speaker, starts a separate PCM track with silence, checks `routedDevice`, then
plays one translated utterance. It restores the previous communication route
and waits for it before microphone forwarding resumes. Playback errors stop the
conversation with a visible error. The normal track is flushed before public
playback. Stop, interruption, reconnect and persona changes cancel that playback.

Decision: glasses output by default; opt-in, consecutive phone playback on
Android 12+. Pause microphone forwarding while the phone speaks to avoid
translation feedback and uncertain input routing. Auto mode sends `audioStreamEnd`
when pausing; PTT closes any active turn. Physical routing remains device-specific.

## Credentialed check

Ran `cd backend && uv run python ../scripts/smoke_translation.py` using root
`.env` without printing credentials. It calls the authenticated `/token` handler
in-process, then connects to the real constrained WebSocket with the returned
ephemeral token and `Authorization: Token …`. Setup contains only model and an
empty `sessionResumption`. No local backend HTTP server or phone was involved.

- Assistant: “Translate Spanish” → `start_translation`, backend normalized `es`.
- Fresh translator token/session, Spanish text “¿Dónde está la estación de tren?”
  → **“English: Where is the train station?”**, 76,800 PCM bytes.
- Same translator session, English text “I would like a glass of water, please.”
  → **“Spanish: Quisiera un vaso de agua, por favor.”**, 109,920 PCM bytes.
- “Stop translation” → `stop_translation`, backend returned assistant command.

Both language labels satisfied the same routing pattern used by Android. This
checks generated audio presence and transcript meaning, not acoustic fidelity.
The smoke script is repeatable; wording and PCM byte counts can vary.

## Unverified

- Real Pixel 10 Pro + glasses routing, HFP microphone recovery, output volume,
  echo, Bluetooth disconnect during speaker playback and user-visible latency.
- Spoken/audio input auto-detection: the real smoke uses **text** input.
- Languages other than Spanish/English, dialect accuracy, label adherence beyond
  the tested turns, overlapping speakers and long utterances.
- Real Android voice tool dispatch/reconnect and resumption after network loss;
  fresh constrained sessions and tools were exercised by the Python smoke.
- End-to-end switching back to an assistant connection after the stop tool: the
  smoke validates the stop command, then closes its translator connection.
