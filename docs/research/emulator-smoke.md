# Emulator smoke research

Verified source inspection: 2026-10-08. Runtime results are in
[the evaluation](../evals/emulator-smoke.md).

- DAT 1.0.0 CameraAccess uses MockDeviceKit pairing, power/unfold/don and
  `services.camera.setCameraFeed(Uri)` / `setCapturedImage(Uri)`.
  [Official sample](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/mockdevicekit/MockDeviceKitViewModel.kt).
- Inspected the installed Maven 1.0.0 AAR signatures with `javap`:
  MockSpeechKit has `setTranscriptionSource(INJECTED)`, `isListening`, and
  `simulateTranscription(text, isFinal, confidence)`; MockMotionKit has
  `setMotionFeed(List<MotionSample>, loop)`.
  [Published artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-mockdevice/1.0.0/).
  These are explicit SDK simulations, not measured ASR or inertial accuracy.
- Android documents headless launch, `-no-audio`, snapshot controls and selecting
  a system image / data directory.
  [Emulator command line](https://developer.android.com/studio/run/emulator-commandline).
- Install `system-images;android-36;google_apis;arm64-v8a` with sdkmanager and
  create the AVD with avdmanager. These commands remain available despite
  the current docs recommending the newer Android CLI.
  [sdkmanager](https://developer.android.com/tools/sdkmanager),
  [avdmanager](https://developer.android.com/tools/avdmanager).
- Android's instrumentation runner can be invoked with `adb shell am instrument`.
  Check the JUnit result, since adb's exit status alone is insufficient.
  [Command-line testing](https://developer.android.com/studio/test/command-line).

## Design and unverified items

DAT's mock does not supply an OS Bluetooth SCO endpoint. The debug source feeds
16 kHz PCM through the existing capture callback, and keeps real 24 kHz AudioTrack
playback. A byte counter proves writes to AudioTrack, not acoustic output.
The emulator has no guaranteed offline speech model; the Over test injects the
known transcript through DAT after its corresponding WAV finishes. Gemini hears
only PCM, through the ordinary backend-issued ephemeral token and constrained
WebSocket. Keyword recognition accuracy and physical Bluetooth remain unverified.
No token, setup, model, or lock configuration changes are needed.

## Observed host limitation (2026-10-08)

Installed emulator 37.2.12 and API 36 image revision 7. The headless engine crashed
before Android boot: macOS reported `EXC_BAD_INSTRUCTION`, `SIGILL`, faulting frame
`init_cache_info + 52`, instruction `0xd53b0029`. A hidden-window probe aborted in
Qt's CPU-feature check, reporting required NEON unavailable. The host is arm64.
Direct reads of `hw.cachelinesize`, `hw.optional.neon` and `kern.hv_support` with
sysctl returned `Operation not permitted` in this sandbox. This supports a host
capability-query restriction as a likely cause; it does not prove a binary fix.
[Upstream QEMU cache initialization](https://github.com/qemu/qemu/blob/master/util/cacheflush.c)
uses `sysctlbyname("hw.cachelinesize")` on Darwin. The Android emulator is a fork;
we did not rebuild or patch it. [Official emulator release notes](https://developer.android.com/studio/releases/emulator)
and [archive](https://developer.android.com/studio/emulator_archive) were consulted.

Unverified: **all APK runtime scenarios**, screenshots and app crash behavior,
because no Android instance booted. Compiling instrumentation is not running it.
The standalone isolated backend `/token` returned HTTP 200 with the real API.
