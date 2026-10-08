# Real APK emulator smoke

Date: **2026-10-08**. Branch: `feat/emulator`.

## Rerun

On Apple Silicon with JDK 17, Android command-line tools, `uv`, Python 3 and
working `.env` credentials:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$HOME/Library/Android/sdk"
scripts/emulator_smoke.sh
```

Accept Android SDK licenses using `sdkmanager --licenses` if your SDK is new.
The runner installs `system-images;android-36;google_apis;arm64-v8a` and the emulator
if needed. It uses a dedicated AVD, wipes **that AVD only**, grants microphone,
phone-state, Bluetooth and notification permissions, then runs the real APK with
DAT mocks and the real backend/Gemini API. Nothing is pushed or deployed.

Configuration:

| Environment | Default / purpose |
| --- | --- |
| `ANDROID_HOME` | Existing SDK; needs API 36 platform and build-tools 36.0.0 for Gradle |
| `GRADLE_USER_HOME` | `/tmp/mgl-smoke-gradle` |
| `ANDROID_USER_HOME` | `/tmp/mgl-smoke-android`; AVDs go in its `avd/` directory |
| `SMOKE_EMULATOR_PORT` | `5580`; refuses a device or occupied ports |
| `SMOKE_BACKEND_PORT` | `18787`; refuses an occupied port |
| `SMOKE_OUTPUT` | `docs/evals/emulator`; PNGs and ignored logs |

The script temporarily writes matching debug configuration to
`android/local.properties` and restores its previous bytes on exit. The shared
secret is compiled into debug as already supported; no Gemini key enters the APK.
Backend code is copied into a private temporary directory with fresh databases,
no OAuth credentials and no Codex repository allowlist. Journal scheduling is
disabled. Test transcripts remain in that temporary directory for diagnosis.
The final output prints the path; remove it after inspection. Logs can contain
test transcripts and are gitignored; review before sharing them.

Boot timeout: 10 minutes. Instrumentation timeout: 15 minutes. Individual API
waits: 2 minutes. The runner shuts down its emulator and backend even on failure
or interrupt. It never attaches to or stops another connected device.

## Environment constraint for this run

The sandbox denies writes under `$HOME/Library/Android/sdk`. The initial
sdkmanager install there failed with `Operation not permitted` for its download
cache. Installation was retried in writable `/tmp/emulator-sdk`; platform,
build-tools and platform-tools directories link to the existing read-only SDK.
Use the following rerun command for that installation:

```sh
ANDROID_HOME=/tmp/emulator-sdk \
GRADLE_USER_HOME=/tmp/m1-gradle-home \
ANDROID_USER_HOME=/tmp/emulator-android-home \
scripts/emulator_smoke.sh
```

## Results

**Blocked before Android boot; this is not a passing emulator evaluation.**

| Check | Observed result |
| --- | --- |
| sdkmanager API 36 Google APIs arm64 image | Installed, revision 7, in `/tmp/emulator-sdk` |
| Emulator package | Installed 37.2.12 / build 16428233 |
| avdmanager | Created `mgl-smoke-api36-5580` |
| `assembleDebug testDebugUnitTest` | PASS: 183 JVM tests, 0 failures/errors |
| `assembleDebugAndroidTest` | PASS: instrumentation APK compiled |
| `compileReleaseKotlin` | PASS: release hooks compile without WAV implementation |
| Isolated backend startup | PASS, started and stopped normally |
| Real `POST /token` | PASS: HTTP 200, model `gemini-3.8-live`; token never printed |
| Headless emulator boot | FAIL: SIGILL in native `init_cache_info`, before boot |
| Hidden-window startup probe | FAIL: Qt reports required NEON unavailable |
| APK install / instrumentation / screenshots | NOT RUN: no booted Android device |
| App launch, DAT connection/Inputs/Motion, audio reply, UI transcripts, look, Over, Stop, crash-free app | UNVERIFIED on emulator |

Build output tail:

```text
> Task :app:assembleDebugAndroidTest UP-TO-DATE
BUILD SUCCESSFUL in 596ms
75 actionable tasks: 75 up-to-date
```

The first driver build executed 16 tasks and passed in 15 seconds; the final
rerun above reused those successful outputs. The separate build including
instrumentation and the release Kotlin variant also passed (`BUILD SUCCESSFUL in 11s`, 92 tasks). No backend source changed; backend
pytest was not run. The credentialed check exercised the real `/token` route in
an isolated copy of the merged backend, not an HTTP mock. It did not connect an
APK WebSocket or send audio to Gemini.

### Host startup failure

The acceleration probe reported `Hypervisor.Framework OS X Version 15.7`, but
that probe did **not** establish that a VM could boot. The actual headless process
exited with SIGILL. Its macOS crash report identified:

```text
Exception: EXC_BAD_INSTRUCTION / SIGILL
Faulting frame: init_cache_info + 52
Instruction: 0xd53b0029
```

A second probe using `-qt-hide-window` instead of `-no-window` aborted with:

```text
Incompatible processor. This Qt build requires the following features:
    neon
```

Direct `sysctl -n hw.cachelinesize`, `sysctl -n hw.optional.neon` and
`sysctl -n kern.hv_support` each failed with `Operation not permitted` inside
this sandbox. Those denied host capability queries are consistent with both
startup failures; this is an inference from diagnostics, not a demonstrated
emulator fix. No binaries were patched and no sandbox restrictions were bypassed.
[QEMU's cache initialization source](https://github.com/qemu/qemu/blob/master/util/cacheflush.c)
shows the Darwin cache-size query; the shipped Android binary has its own fork.

A cleanup defect discovered during the failed run was fixed: adb logcat can wait
for a nonexistent device, so each cleanup adb command now has an eight-second
limit. The script also ignores a second interrupt while restoring configuration.
The final failed-start rerun exited 1 and completed cleanup: ports 18787, 18788,
5580 and 5581 were closed, `adb devices` was empty, and the original
`local.properties` bytes were restored. No emulator remains running. Raw
logs are retained locally under `emulator/` and ignored by git. No screenshot PNGs
were produced because Android never booted; none were synthesized.

**Follow-up:** run the command above in an ordinary terminal with CPU capability
queries and Hypervisor access available. The script includes a Darwin capability
preflight to report this restriction early. Record the resulting JUnit outcome
and seven PNGs before considering the APK-level smoke verified.

## What is simulated

- DAT camera: existing sample video and still image (blue mug and orange).
- DAT Inputs: SDK double-tap events drive real mute/unmute behavior.
- DAT Motion: looping stationary accelerometer/gyro samples; checks delivery.
- Microphone: local synthetic speech WAVs at normal pace, silence between files.
- Over ASR: DAT transcript injection at the end of the corresponding WAV;
  verifies keyword turn control, not automatic recognition accuracy.

The suite is designed to retain real Gemini token issuance, constrained WebSocket
setup, transcription, spoken responses, look processing, image interpretation and
Android playback writes. Only backend token issuance was measured in this run. Bluetooth SCO has no mock OS endpoint; physical mic/speaker,
echo cancellation and audible quality remain unverified. `-no-audio` preserves
AudioTrack operations without host audio. Over's test timeout is 30 seconds and
its no-response observation is four seconds, followed by the spoken sign-off;
it rejects a silence-timeout completion.

Sources and API verification (2026-10-08):
[research note](../research/emulator-smoke.md),
[DAT sample](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/mockdevicekit/MockDeviceKitViewModel.kt),
[Android emulator](https://developer.android.com/studio/run/emulator-commandline),
[instrumentation](https://developer.android.com/studio/test/command-line).
