# HFP audio evaluation

Implemented **2026-10-08**. The evaluation converts cached clean Gemini TTS into
an approximation of the glasses' requested 8 kHz microphone path, then exercises
real Gemini Live with production token/tool configuration and simulated effects.
No real notes, timers or other external actions are executed.

## Voice and buttons

Start a conversation normally, select **Over**, and finish with “over”. “Over and
out” ends the conversation. The existing capture-button/screen controls are
unchanged. In **Auto**, a three-second thinking pause exceeds the default
2000 ms patience; use Over for long dictation. The evaluation requires no new
button or spoken command. See the [dated results](../evals/hfp-audio-eval-2026-10-08.md)
for what to watch during the glasses test.

## Reproduce

From the repository root, with Python 3.12 and the existing backend dependencies:

```sh
curl -fL -o /tmp/hfp-vosk.zip https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
unzip /tmp/hfp-vosk.zip -d /tmp/hfp-model
printf '\n--allow-upsample=true\n' >> /tmp/hfp-model/vosk-model-small-en-us-0.15/conf/mfcc.conf
PYTHONPATH=scripts uv run --project backend --with numpy==2.5.3 --with scipy==1.18.1 --with vosk==0.3.44 \
  python -m voice_eval.hfp_runner \
  --vosk-model /tmp/hfp-model/vosk-model-small-en-us-0.15 \
  --output /tmp/hfp-local.json
```

Add `--live` and choose a **new** output path to spend up to 42 sequential Live
attempts. Credentials come from the gitignored `.env`; no new TTS is generated.
The output refuses overwrite. It checkpoints the attempt count before connecting,
never retries a case, backs off on any provider/transport error, and stops after
three consecutive errors. A repeated invocation with another file is a new run:
coordinate the total budget with the other agents/user. One process must own the
run; do not launch multiple Live runners. Existing general eval concurrency flags
are intentionally absent here.

Both Live runners now reserve their entire run through main's shared
[evaluation allowance](usage-meter.md) before provider access and record connection
usage through the common client. The default two-session daily allowance refuses
the 42-case baseline and eight-case candidate; deliberately configure budgets
after checking AI Studio before running them. Local-only evaluation needs no
reservation. A refused baseline retains its local acoustic/keyword results.

```sh
PYTHONPATH=scripts uv run --project backend --with numpy==2.5.3 --with scipy==1.18.1 --with vosk==0.3.44 \
  python -m voice_eval.hfp_ablation \
  --vosk-model /tmp/hfp-model/vosk-model-small-en-us-0.15 \
  --output /tmp/hfp-resampler.json
PYTHONPATH=scripts uv run --project backend --with numpy==2.5.3 --with scipy==1.18.1 --with vosk==0.3.44 \
  pytest scripts/voice_eval/test_hfp.py backend/tests/test_voice_eval.py
```

The ablation is entirely local: linear app uplink versus a causal 31-tap FIR,
using the same Vosk model and narrowband inputs. It does not spend Gemini quota.
Vosk is an evaluation dependency only; Gemini remains the sole runtime AI.

## Channel configuration and limitations

`hfp.py` contains seven fixed conditions: clean, quiet HFP, street 20 dB/1% loss,
café 10 dB/3%, wind 0 dB/5%, car 10 dB/3%, kitchen 5 dB/1%. Each uses seed 42.
The path is generated background plus a −12 dB bystander → 300–3400 Hz filter →
anti-aliased 8 kHz → bounded AGC → approximate codec → burst loss with fading
repeat concealment → exact port of app interpolation to 16 kHz.

“CVSD-like” is IMA ADPCM adaptive quantization; “mSBC-like” is 6-bit block-adaptive
quantization. Neither is bit-exact. mSBC itself is normally wideband; this test
still restricts its artifact approximation to our 8 kHz capture premise. Loss is
an exact rounded fraction of 20 ms packets, mostly in 40–100 ms bursts. AGC has
20 ms updates, bounded gain and soft limiting. The wearer is louder than the
bystander, with no learned spatial beamformer.

Noise is original synthesized audio dedicated to CC0, not recorded café/wind
measurements. TTS bystanders have separate fixture provenance. The [license](../../scripts/voice_eval/hfp-noise-LICENSE.txt)
and [research notes](../research/hfp-audio.md) explain sources and assumptions.

Vosk receives 8 kHz PCM but internally upsamples for its wideband acoustic model.
Android SpeechRecognizer cannot run on the Mac. Both Vosk and Gemini keyword
replays are proxies; only real hardware establishes user-perceived endpoint
latency. The Live Over activityEnd remains an oracle so acoustic/tool performance
can be separated from recognition failures. Metrics and transcripts are saved in
JSON; the report keeps completion coverage beside accuracy and false-trigger rates.

Verified source URLs (2026-10-08):

- https://alphacephei.com/vosk/models
- https://github.com/alphacep/vosk-api/blob/master/python/example/test_simple.py
- https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.resample_poly.html
- https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.firwin.html
- https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor
- https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler
- https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-61/out/en/br-edr-controller/baseband-specification.html

The recorded run used NumPy 2.5.3, SciPy 1.18.1 and Vosk 0.3.44. The baseline
Python interpolator freezes the pre-change linear app path for paired comparisons.
The FIR candidate is in `hfp_ablation.py`; its impulse response is checked against
the Kotlin implementation. Post-clip Live silence is digital silence; continuous
noise during the final VAD wait and idle false triggers still need hardware testing.

Render saved measurements without any API call:

```sh
PYTHONPATH=scripts python3 -m voice_eval.hfp_report \
  docs/evals/hfp-audio-eval-2026-10-08.json \
  docs/evals/hfp-resampler-ablation-2026-10-08.json \
  --candidate docs/evals/hfp-fir-live-2026-10-08.json
```

## App audio changes and hardware diagnostics

The 8→16 kHz capture converter uses a causal 31-tap half-band FIR with 0.94 ms
of delay and retained history across 20 ms callbacks. Phone 16 kHz input remains
byte-for-byte passthrough. There is no new setting or voice command. Unit tests
cover passband gain, rejection of interpolation images, saturation, chunk
continuity, empty callbacks and capture counts. Local Vosk improves most in quiet;
noisy recognition gains are inconsistent, so this is not a noise-removal claim.

**Settings → Diagnostics** reports the attached AEC's enabled state and whether
Android exposes NoiseSuppressor. Platform NS state is explicitly unknown: global
availability does not establish activity/effectiveness on SCO. Additional NS is
not forced on top of `VOICE_COMMUNICATION` preprocessing. Check speaker echo and
consonant clarity on the real recording route before changing those effects.

The optional short-keyword challenge uses local macOS `say`/Samantha plus ffmpeg,
not a provider. It adds 70 trials covering three positive sign-offs and seven
negative/ambiguous phrases. Generated speech stays in a temporary directory;
only transcripts and hashes are retained. No Apple voice/model is redistributed.

```sh
PYTHONPATH=scripts uv run --project backend --with numpy==2.5.3 --with scipy==1.18.1 --with vosk==0.3.44 \
  python -m voice_eval.hfp_keyword_challenge \
  --vosk-model /tmp/hfp-model/vosk-model-small-en-us-0.15 \
  --output /tmp/hfp-keywords.json
```

Pass `--challenge docs/evals/hfp-keyword-challenge-2026-10-08.json` to the report
renderer to include that table. The `ova`/`older` variant is an offline ablation;
it is not enabled in the app. The existing “your turn” setting avoids the specific
ambiguity of questions such as “Is it over?”.

For the optional eight-case FIR Live comparison, `python -m voice_eval.hfp_candidate
--baseline <finished-baseline.json> --output <new-candidate.json>` uses the same
Python environment as the other eval commands. It refuses an unfinished/stopped
baseline, refuses an existing output, and requires baseline attempts + 8 ≤ 50.
Three consecutive failures stop it. The recorded run stopped after seven extra
attempts (49 total); no further API checks should be run under this task's budget.

## Combined merge verification — 2026-10-08

Integrated with main's adaptive playback buffers, latency diagnostics and serialized
audio cleanup. Diagnostics retains buffer/underrun details alongside AEC/NS status.
The shared evaluation client retains latency timing and usage metering while adding
HFP transcription events, model identity and close codes. Both HFP runners use the
shared quota reservation gate; local-only analysis remains available without it.
Main's registry, DataStore settings, approvals, `lock_additional_fields=[]` and
client-owned `sessionResumption` are unchanged. All 15 decision rows and 30 research
links are retained; decision numbers were already unique.

```text
cd backend && uv run pytest
506 passed, 3 warnings in 6.42s

cd android && ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL in 24s
45 actionable tasks: 45 executed
245 JVM tests, 0 failures, 0 errors, 0 skipped

Offline HFP suite (NumPy/SciPy/Vosk evaluation dependencies):
20 passed, 1 warning in 1.96s
```

Used JDK 17, the installed Android SDK and writable temporary build caches.
Regression checks cover quota refusal before provider access, whole-run reservation,
local result retention, and combined latency/metering/transcription behavior.
No assertions were weakened. No new credentialed provider, emulator or physical
phone/glasses checks ran; acoustic behavior remains a hardware verification item.
