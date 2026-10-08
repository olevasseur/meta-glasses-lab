# Debug emulator smoke

The real debug APK can run without glasses, using DAT MockDeviceKit camera,
Inputs, Motion and Speech. Real Gemini tokens, WebSocket audio, tool execution,
transcripts and Android AudioTrack are retained. Release builds have no WAV
parser, fixture queue or intent behavior; audio fixtures live only in the test APK.

## Run / use

See [rerun instructions and measured results](../evals/emulator-smoke.md).
`scripts/emulator_smoke.sh` installs the API 36 arm64 Google APIs image if absent,
creates a disposable AVD, starts an isolated backend, builds and installs both
APKs, grants Android permissions, reverses the backend port, runs instrumentation,
pulls small PNG screenshots and shuts down its emulator/backend on success or error.
It requires the normal JDK 17 / Android SDK tooling, `uv`, Python 3, and `.env`
with `GEMINI_API_KEY` and `APP_SHARED_SECRET`. Calls incur normal Gemini usage.

For a debug launch without the suite:

```sh
adb shell am start -n com.olevasseur.glassesapp/.MainActivity \
  --ez debug_mock_glasses true
```

Allow Android permissions first. The existing **Mock glasses (phone audio)**
switch also works. **Start**, **Stop**, **Simulate double-tap**, and **Simulate
capture hold** exercise the usual controls. Say “What is two plus two?” or “What
am I looking at?”; the mock photo shows a blue mug and an orange.

Adding `--ez debug_wav_audio true` selects silent PCM capture until instrumentation
calls `SmokeHooks.inject(inputStream)`. Accepts RIFF WAV, signed little-endian
PCM16, 16 kHz mono, max 4 MiB. It sends 20 ms chunks at real-time pace and silence
between files; no host microphone is opened. Disable mock mode or force-stop to
restore the microphone. This is an in-process debug API, not an exported receiver
that arbitrary apps can use to inject files. The bundled test assets are generated
local TTS utterances; regeneration commands are in their README.

## Coverage and limitations

The suite drives Compose Start/Stop and checks DAT connection, active Inputs,
replayed IMU samples, mute/unmute via DAT double-tap, real Gemini connection,
question transcript and audio reply, `look` returning `frame_sent` plus fixture
objects in the description, Over withholding output before the sign-off, and
stopped audio/session state. The driver also fails on crashes or ANRs in logcat.

Over uses a 30-second silence fallback in the test so the four-second no-response
assertion cannot pass because of a silence timeout. The WAV sign-off is followed
by a DAT `simulateTranscription("over")` event. This verifies the actual local
turn detector and Gemini activity boundary; it does **not** verify ASR accuracy.
Motion replay is stationary and verifies sample delivery, not nod/rep accuracy.
Headless `-no-audio` verifies AudioTrack writes, not audible sound. Physical SCO,
echo cancellation, Meta AI registration and real glasses entitlement remain
hardware checks. Live model wording and network/quota failures can fail the suite.

Sources verified **2026-10-08**:
[DAT sample](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/mockdevicekit/MockDeviceKitViewModel.kt),
[DAT mock AAR](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-mockdevice/1.0.0/),
[emulator CLI](https://developer.android.com/studio/run/emulator-commandline),
[instrumentation CLI](https://developer.android.com/studio/test/command-line).
