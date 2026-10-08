# the glasses app — Android

Kotlin / Compose, `com.olevasseur.glassesapp`, Android 10+ (minSdk 29), compile/target SDK 36, JDK 17. Gradle 8.13 + AGP 8.13.2; DAT core, camera, inputs, motion, speech and mockdevice 1.0.0 resolve from Maven Central without a GitHub token. Gemini is the only runtime AI provider.

Background conversations, launch triggers, call/focus handling, and the current hardware test checklist are documented in [Hands-free operation](../docs/features/hands-free.md).

## Build

From the repository root:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
cd android
```

Create or edit **android/local.properties** (gitignored; no quotes around values):

```properties
sdk.dir=/Users/aiagent/Library/Android/sdk
BACKEND_URL=http://127.0.0.1:8787
APP_SHARED_SECRET=replace-with-the-backend-shared-secret
```

Use your own SDK path on another machine. `BACKEND_URL` defaults to the value above. `APP_SHARED_SECRET` must match the backend `.env`; it is compiled into **debug only**, with an empty value in release. Never put a Gemini API key or Google credentials in this file/app. Start shows a configuration error if the debug shared secret is missing. Changing either build value requires rebuilding/reinstalling.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Unit-test report: `app/build/reports/tests/testDebugUnitTest/index.html`. JVM tests cover streaming resampling, protocol encoding, all three token modes, memory, gestures, vision, settings persistence, hands-free state transitions, and DAT trigger filtering. The wrapper distribution is SHA-256 pinned.

In a restricted build sandbox, writable caches can be selected without changing the project:

```sh
export GRADLE_USER_HOME=/tmp/m1-gradle-home
export ANDROID_USER_HOME=/tmp/m1-android-home
```

## Install on the Pixel

Enable Android Developer options and USB debugging; connect USB and accept the computer authorization prompt. Start the personal backend on the Mac on port 8787, then run from `android/`:

```sh
adb devices
adb reverse tcp:8787 tcp:8787
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.olevasseur.glassesapp/.MainActivity
```

If multiple devices are attached, add `-s <serial>` to each adb command. Reapply `adb reverse` after reconnecting/rebooting. The debug network policy permits HTTP only to loopback/Android emulator hosts (`127.0.0.1`, `localhost`, `10.0.2.2`); use HTTPS for a remote backend. Release builds disallow cleartext traffic and cannot run with the debug shared-secret authentication scheme.

## Real glasses and Developer Mode

1. Pair the glasses with the phone in Meta AI and enable their Bluetooth calls/audio route. Update Meta AI and glasses firmware to versions supported by DAT 1.0.0.
2. In Meta AI: **Settings → App Info → tap App version five times → Developer Mode → Enable**. Follow any DAT installation/update prompt while wearing the glasses. Only one Developer Mode app can be registered at once.
3. Open this app and allow Android microphone and Nearby devices permissions. Tap **Connect glasses** and complete registration in Meta AI. Developer Mode uses application ID/client token `0`, following CameraAccess. The status shows registration, link and SDK/firmware compatibility.
4. **Grant DAT microphone permission** exercises CameraAccess's permission-check/Meta AI consent contract. HFP microphone capture uses Android `RECORD_AUDIO`; it does not depend on DAT camera/microphone streaming permission. Grant DAT camera permission separately to use look/watch mode.
5. Select **Auto**, **Push-to-talk** or **Over**, then **Start**. Auto receives the backend's patient VAD configuration. PTT requests `{"mode":"ptt"}` and requires automatic activity detection disabled in that token.
The foreground conversation service continues with the screen off or another app in front. Its persistent notification provides Stop, mode, and talk/mute controls. Stop the service before changing permissions or the mock-device provider. See the [hands-free guide](../docs/features/hands-free.md) for launch triggers and idle timeout.

The app selects an HFP/SCO communication device on API 31+ with `setCommunicationDevice`, waiting up to three seconds for selection. Android 29–30 uses SCO broadcasts. If Bluetooth permission, a route or the connection is unavailable, audio uses the phone. Disconnecting the selected Bluetooth device pauses audio; reconnect Bluetooth and tap Resume to select it again. Disconnect unrelated Bluetooth headsets when testing glasses routing.

Capture is signed little-endian PCM16 mono at 8 kHz for HFP or 16 kHz for the phone. Streaming interpolation converts 8→16 kHz, preserving chunk continuity. Playback uses a 24 kHz mono PCM AudioTrack with voice-communication usage; Android performs physical HFP output conversion. Transcripts and the selected route appear on screen.

### Push-to-talk and interruption

- Hold the on-screen button to speak; release/cancel ends the activity. Accessibility activation toggles speaking, with **Finish speaking** available.
- **Capture HOLD is a gesture, not a key-down event. DAT 1.0.0 has no capture-release event.** Capture HOLD now starts/stops the conversation while controls are armed. Touchpad double-tap toggles speaking in PTT (or mutes/unmutes Auto). It requires an active DAT Inputs capability; capability/permission errors leave on-screen PTT available. Inputs is experimental and may require app enablement in Wearables Developer Center.
- A 60-second safeguard ends a latched PTT activity. Stop, lost connection and lost Inputs also release the relevant talk source.
- A Gemini interruption pauses/flushes AudioTrack immediately on the WebSocket callback, clears its queue and invalidates older pending audio events.

## Mock-device test

No physical glasses are needed for the SDK control path:

1. Allow Android microphone permission. If Nearby devices permission was declined, grant it and reopen the app so DAT can initialize.
2. With the conversation stopped, enable **Mock glasses (phone audio)**. The app enables `MockDeviceKit`, pairs `RAYBAN_META`, powers it on, unfolds and dons it. Registration and DAT permissions begin granted.
3. Choose PTT and Start. When glasses controls are STARTED / ACTIVE, tap **Simulate double-tap** once to start and once to end an utterance. **Simulate capture hold** stops/starts the whole conversation. Both inject events through the SDK. The on-screen hold button works too.
4. Repeat in Auto, then Stop and disable Mock glasses before using real glasses.

MockDeviceKit does not emulate an OS Bluetooth HFP microphone/speaker. Mock mode deliberately routes microphone and speaker to the phone; Gemini still needs a running backend and valid ephemeral token. This separates testing the DAT control path from the physical radio/audio path.

## Backend/session integration

`POST /token` sends the selected `mode` (`auto`, `ptt` or `over`) and Auto `silence_ms` with `X-App-Secret`. The response must contain `token`, `model`, `expires_at`. OkHttp connects to the v1beta constrained Live endpoint using `Authorization: Token <token>`, sends model plus `sessionResumption` (empty initially, then containing the handle), and gates audio until `setupComplete`.

**Resumption integration requirement:** the backend must lock model, tools, VAD, voice, transcriptions and other policy fields while leaving `sessionResumption.handle` overridable. Google documents that an empty field mask with a supplied token setup replaces/ignores the client's entire setup. Locking all of `sessionResumption` to `{}` prevents the client handle from taking effect. Review the backend's token field mask before live testing; this Android milestone does not modify the backend.

The newest resumable handle and token stay in memory. `goAway` or disconnection reconnects using the handle, reusing an unexpired token for resumption; an expired token is refreshed. Stop/process death clears the handle. Retry delays grow from 1 to 15 seconds, with eight attempts. A reconnect without a handle starts a new session; the UI reports that. Audio during a reconnect is dropped, not replayed. A handle is a server snapshot: the most recent in-flight words can be lost, especially during generation/tool work.

Gesture confirmation and vision tools run locally. For each backend tool call, the app sends its args object to `/tools/{name}` with `X-App-Secret` and returns the response object under the matching function ID/name. IDs are deduplicated in memory; completed responses can be resent without repeating the backend POST. Cancellations cancel pending HTTP work but cannot undo already executed backend side effects. An uncertain HTTP failure is returned as an error asking the model to consult the user before retrying. The UI displays the last result.

## Verification still needed

Build/JVM tests do not verify Pixel audio routing, real DAT registration or Inputs entitlement, real Gemini credentials, token field-mask compatibility, microphone echo/latency, or interruption/reconnection under live traffic. Test these on the Pixel with actual glasses; exercise a long pause in Auto, screen PTT, capture start/stop, double-tap talk/mute, interruption mid-answer, Bluetooth loss, and network loss. Confirm that a tool appears once on the backend across a reconnect.

Verified APIs and source links are recorded in [M1 research notes](../docs/research/m1-android-notes.md).

## First hardware session

From the repo root, run `./scripts/dev_up.sh` to check prerequisites, configure
ignored debug properties, start/reuse the backend, reverse port 8787, build/install,
launch, and tail structured logs. Open **Diagnostics** on the phone and follow
[the consolidated hardware test plan](../docs/hardware-test-plan.md).
See [Diagnostics](../docs/features/diagnostics.md) for probes, measurements and limits.

## Automated API 36 smoke (real Gemini)

Run `scripts/emulator_smoke.sh` from the repository root. It builds and installs
the real debug APK and instrumentation APK, uses DAT mock camera/Inputs/Motion,
injects WAV speech, verifies live replies/vision/Over/Stop, and shuts down its
headless emulator. See [results and rerun configuration](../docs/evals/emulator-smoke.md)
and [debug fixture controls](../docs/features/emulator-smoke.md).
