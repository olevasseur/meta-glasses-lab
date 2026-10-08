# Voice evaluation protocol and TTS

Verified **2026-10-08**. Runtime AI remains Gemini only.

Sources checked:

- [Live WebSocket reference](https://ai.google.dev/api/live): PCM16 mono 16 kHz input,
  manual activity, transcription, tool calls, cancellation, audio output and usage.
- [Ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens):
  constrained v1beta endpoint and authorization. Harness reads the production
  `/token` endpoint; model/config remain backend-owned, minimal setup includes
  `sessionResumption: {}`, `lock_additional_fields=[]` unchanged.
- [Gemini TTS generateContent](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation):
  current `gemini-3.8-flash-tts` and `gemini-3.8-flash-lite-tts`, verbatim text in
  `parts[].text`, delivery in `speech_metadata.style`, named prebuilt voices.
  Actual SDK 2.28.0 requests accepted `prebuilt_voice_config.voice_name` with
  Kore, Puck and Aoede; returned audio converted to 16 kHz PCM16 for Live.
- [Pricing](https://ai.google.dev/gemini-api/docs/pricing): both TTS models and
  Gemini Live list free-tier input/output. Eligibility is not unlimited quota or
  proof of an account's invoice. This project's Flash-Lite TTS returned HTTP 429
  with `GenerateRequestsPerDayPerProjectPerModel-FreeTier`, quota value **10**.
- [Token counting](https://ai.google.dev/gemini-api/docs/tokens): countTokens gives
  a text-token measurement. The harness labels Flash-Lite counts as a proxy;
  Live usageMetadata is retained separately and includes multimodal/session input.

## Experimental findings

Individual TTS generation succeeded, but the daily quota made 68 independent
requests impractical. Full-suite generation used Flash TTS, up to eight utterances
per request, grouped by voice. Silence boundaries are measured in the waveform;
ambiguous counts fail closed. One Puck batch repeated “Turn on watch mode.” An
independent real Gemini Flash-Lite audio transcription confirmed nine utterances
where eight were requested. `scripts/voice_eval/batch-review.json` records the source
PCM hash, transcription and exclusion of the duplicate. Corrections never apply to
newly generated audio with a different hash. Another batch's two long-turn pieces
had no distinct separator; long turns now synthesize as a whole, with a deterministic
3/4.5/6-second pause inserted at a quiet word boundary in the middle half.

TTS output is synthetic speech, not a recording of a real wearer. Noise is seeded
Gaussian noise mixed at 15 dB SNR, including pauses; it is not calibrated HFP noise.
The same cached PCM is used before and after tuning, with hashes in the result.
Input transcripts remain available to inspect recognition and fixture quality.

The real pilot exposed a harness error: a tool-call generation can finish before
its function response arrives. The receiver now ignores that intermediate
turnComplete while a tool response is outstanding and waits for five seconds of
quiet after generation completion, collecting continuations and first audio. An offline socket regression reproduces this ordering and duplicate
call delivery. Head gestures are simulated only after a pending question's turn ends.

An oracle chooses Over's activityEnd from the fixture endpoint plus the Android
1.2-second sign-off grace. This verifies provider behavior while manual activity
is open; it does **not** verify DAT recognition or Android's PCM silence detector.
Auto uses the production 2000 ms setting; 3–6-second pauses deliberately exceed it.

See [evaluation results](../evals/voice-eval-2026-10-08.md) and the JSON beside them
for actual outcomes, prompt size, transcripts, tool arguments and timing.

## Unverified

- Physical microphones, Bluetooth/HFP, acoustic playback/echo, DAT speech/motion,
  on-device sign-off recognition, alarms, reading chunk playback and camera capture.
- Multi-hour endurance, goAway recovery, session resumption and production reconnect
  behavior; this is a bounded single-turn/confirmation evaluation client.
- Real Google writes, Codex subprocesses, journal/coach generation and visual
  archives; external services are mocked and local storage is disposable.
- Meeting silence gates and persona transitions after the successful switch command.
- Generalization to unseen prompts, voices, languages, adversarial images or noise;
  this small development set is used for tuning, not a held-out benchmark.
