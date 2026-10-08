# HFP stress evaluation research

Verified **2026-10-08**. Runtime AI remains Gemini; Vosk is an offline evaluation
proxy only. No Vosk model, API key or eval dependency is added to Android/backend.

## Evidence and design boundaries

- [Bluetooth Core baseband specification](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-61/out/en/br-edr-controller/baseband-specification.html)
  specifies CVSD decoder output as 8 kHz PCM. CVSD and mSBC are distinct codecs;
  mSBC is wideband. The simulator deliberately models our **8 kHz capture path**,
  not every possible HFP negotiation. Its IMA ADPCM and block quantization are
  artifact approximations, not Bluetooth codec implementations.
- [Meta docs](https://wearables.developer.meta.com/llms.txt?full=true) returned a
  login page on this date. The 8 kHz beamformed glasses premise comes from this
  repository's [prior DAT research](wearables-dat-sdk.md) and the actual app's
  `AudioRecord` request. Negotiated hardware codec/rate needs a glasses check.
- [SciPy resample_poly](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.resample_poly.html)
  filters before decimation. We band-limit to 300–3400 Hz and use polyphase
  decimation before converting to 8 kHz; simply keeping every other clean sample
  would alias out-of-band energy. The app only has an 8→16 kHz interpolator, which
  is ported exactly, including signed integer rounding and chunk continuity.
- [SciPy firwin](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.firwin.html)
  supplies the causal 31-tap half-band interpolation candidate for an offline
  comparison. Frequency response alone does not prove better recognition.
- [Vosk models](https://alphacephei.com/vosk/models) lists
  `vosk-model-small-en-us-0.15` as a 40 MB **wideband** English model, Apache 2.0.
  [Official streaming example](https://github.com/alphacep/vosk-api/blob/master/python/example/test_simple.py)
  demonstrates partial/final results. We download that model to a temporary
  directory, add `--allow-upsample=true` to its `conf/mfcc.conf`, and submit actual
  8 kHz PCM to `KaldiRecognizer(model, 8000)`. It internally upsamples to its
  16 kHz feature rate. This is an 8 kHz input proxy, **not a model trained for
  narrowband** and not Android SpeechRecognizer. The resampler ablation instead
  submits each app-uplink candidate directly at 16 kHz to the same model.
- [Android NoiseSuppressor](https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor)
  attaches to an AudioRecord session and exposes availability/enabled state.
  [AcousticEchoCanceler](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler)
  has the same session-scoped lifecycle. System availability is not proof that
  an effect is effective on SCO. `VOICE_COMMUNICATION` can already apply platform
  preprocessing; blindly stacking suppression risks damaging consonants.
- Existing [M1](m1-android-notes.md), [M2](m2-backend-notes.md),
  [Over](over-mode-notes.md), [voice eval](voice-eval-notes.md) and
  [resilience](../features/resilience.md) documents define our verified Gemini
  wire/config contract. The current run uses the production `/token` handler,
  constrained v1beta WebSocket, header authorization, minimal model/resumption
  setup and unchanged field locking. The WebSocket reference fetch timed out;
  current provider acceptance is recorded separately in the evaluation results.

## Interpretation

Procedural noise beds are original generated spectral/temporal approximations,
with a [CC0 dedication](../../scripts/voice_eval/hfp-noise-LICENSE.txt).
They use rumble, hiss, motor tones, moving envelopes and transient clatter.
Cached Gemini TTS timer speech supplies intelligible bystanders at −12 dB
relative to active wearer speech, as a simple beamforming approximation.
Nominal SNR is measured before channel filtering; wind energy below 300 Hz is
substantially removed by the channel. SNR is not the same as post-filter SNR.
There is no measured array response, room impulse response or physical wind
microphone saturation in this simulator.

Input transcription is asynchronous. Gemini text replay measures lexical
recognition, not a deployable local endpoint detector. The Live harness still
uses the oracle fixture endpoint plus 1.2 s grace in Over mode, reported
separately from proxy keyword misses/false ends. Vosk partials replace hypotheses;
final segments feed the same trailing-word/sign-off and 1.2 s grace rules as
`OverTurnDetector`. Gemini incremental text is accumulated before replay.

## Unverified

- Actual glasses CVSD/mSBC negotiation, mic spectrum, packet sizes/loss/concealment,
  beamformer directionality, AGC, acoustics, clipping and wind pressure noise.
- Android/DAT recognition, PCM injection support, confidence calibration and
  simultaneous DAT Speech/HFP availability.
- Pixel NoiseSuppressor/AEC creation and effect on SCO, feedback/echo rejection,
  phone-in-pocket body attenuation and walking head motion.
- Accuracy on real voices, accents/languages, unseen commands and real noise;
  this is a small paired development stress test, not a population estimate.

## Reproduction identifiers

- Python 3.12.13; NumPy 2.5.3; SciPy 1.18.1; Vosk 0.3.44.
- Downloaded Vosk ZIP SHA-256:
  `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`.
- Production `live_model([])` resolved to `gemini-3.8-live`; no avoid-model list
  or fallback was requested. Baseline model is inferred from that unchanged
  selection; the candidate client additionally records the returned model.
- Compiled Kotlin `PcmResampler(8000)` and Python FIR reference were compared on
  all 36 HFP trial waveforms: **5,592,960 output samples, zero differences**.
  This checks integer PCM equivalence on this corpus, not every possible signal.

The additional short-phrase challenge uses the installed macOS Samantha voice
(`say -r 175`) and ffmpeg conversion to PCM16/16 kHz before the same HFP channel.
Speech files are temporary and not redistributed; hashes identify the generated
input. This is a second synthetic speech source, not human ground truth. The
original long-positive results (7/7 local hits) do not generalize to isolated
keywords: this challenge obtained 17/21 correct keyword/sign-off detections.
The ova/older variant also obtained 17/21, while adding false ends. Keeping exact
matching is intentional; the remaining question-ending ambiguity needs a chosen
phrase such as “your turn”, not broader edit-distance matching.

The baseline's maximum measured PCM scheduling lag was 20.1 ms (one frame), with
no extra TTS generation. All 42 baseline connection attempts completed their
bounded harness path; four response timeouts remain failures. Noise/environment,
SNR and loss change together, so this is a scenario comparison rather than a
factorial isolation of codec, AGC, noise and loss effects.
