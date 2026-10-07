# M1 Android verification

Date: **2026-10-06** (America/Toronto). Scope: Android implementation only. The research index was intentionally not edited because this task explicitly permits only this research file and `android/`.

## Meta DAT 1.0.0

Cloned `https://github.com/facebook/meta-wearables-dat-android` to `/tmp/m1-meta-wearables-dat-android`; inspected commit `d3159f738676221181577dd745d83d10b6ab46ba` and the actual Maven Central 1.0.0 AAR public signatures with `javap`.

Sources:
- [CameraAccess registration/state sample](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/wearables/WearablesViewModel.kt)
- [CameraAccess activity/permission sample](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/MainActivity.kt)
- [BirdSpotter device session/Inputs usage](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/BirdSpotter/app/src/main/java/com/meta/pixelandtexel/birdspotter/data/dat/DatGlassesSessionRepository.kt)
- [Published Inputs artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-inputs/1.0.0/)
- [Meta developer documentation](https://wearables.developer.meta.com/llms.txt?full=true), particularly getting-started, Inputs and Android mock-device testing.

Verified: initialize after Android permissions; `Wearables.startRegistration(activity)`; observe registration and selector/device metadata flows; `Wearables.checkPermissionStatus(Permission.MICROPHONE)` and `Wearables.RequestPermissionContract()`. Developer Mode allows ID/client token `0`; Meta AI Settings → App Info → tap version five times enables it. Only one developer app stays registered.

Inputs uses `Wearables.createSession(selector)`, `session.start()`, wait for `DeviceSessionState.STARTED`, then `session.addInputs(InputsConfiguration(...))`. Collect `events`, `state`, `errors`; stop the session for cleanup. There is no runtime `Permission.INPUTS`. `CapturePressType` contains only `SHORT_PRESS`, `HOLD`, `DOUBLE_PRESS`; `InputEvent.Capture` has pressType/source/timestamp, no release. Therefore true capture-button hold/release PTT is unsupported by this API. M1 implements a documented HOLD-to-toggle alternative plus true screen hold/release.

Mock: `MockDeviceKit.getInstance(context).enable(MockDeviceKitConfig(...))`, `pairGlasses(GlassesModel.RAYBAN_META)`, power/unfold/don; inject `glasses.services.input.capture(CapturePressType.HOLD)` once Inputs is active. This does not emulate an Android Bluetooth audio device.

## Gemini wire protocol

Verified against [WebSocket API reference](https://ai.google.dev/api/live) and [ephemeral-token guide](https://ai.google.dev/gemini-api/docs/ephemeral-tokens), which redirects to `/docs/live-api/ephemeral-tokens`.

Ephemeral endpoint:

```text
wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained
Authorization: Token <ephemeral token name>
```

`access_token` is also supported as a query parameter; M1 uses the header. The first message is setup; wait for setupComplete. Token constraints own configuration. **Field-mask requirement:** with supplied token setup and empty mask, Google's reference says the client setup is ignored. The backend must permit the changing resumption handle rather than lock the whole sessionResumption object. M1 sends an empty sessionResumption object initially to enable handle updates even when that field is unlocked. Backend changes are outside this task.

Wire shapes used (camelCase):

```json
{"setup":{"model":"models/<backend model>","sessionResumption":{}}}
{"setup":{"model":"models/<backend model>","sessionResumption":{"handle":"<latest>"}}}
{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=16000","data":"<base64>"}}}
{"realtimeInput":{"activityStart":{}}}
{"realtimeInput":{"activityEnd":{}}}
{"toolCall":{"functionCalls":[{"id":"1","name":"append_to_doc","args":{"text":"note"}}]}}
{"toolResponse":{"functionResponses":[{"id":"1","name":"append_to_doc","response":{"status":"ok"}}]}}
```

`serverContent.modelTurn.parts[].inlineData` supplies audio. `serverContent` also carries `interrupted`, `turnComplete`, `inputTranscription.text`, `outputTranscription.text`. Top-level `goAway.timeLeft` warns of disconnect; `sessionResumptionUpdate` has `resumable` and `newHandle`. Cancellation carries `toolCallCancellation.ids`. Unknown fields are tolerated.

## Audio, VAD and sessions

The [Live capabilities guide](https://ai.google.dev/gemini-api/docs/live-guide) redirects to `/docs/live-api/capabilities`: input is little-endian PCM16 mono, natively 16 kHz; output is 24 kHz. Process every audio part, including events also containing transcripts. Interruption requires clearing playback. Manual activity messages require disabled server VAD. Patient VAD and input/output transcription remain backend-owned per architecture; M1 does not override those fields.

The [session guide](https://ai.google.dev/gemini-api/docs/live-session) redirects to `/docs/live-api/session-management`: persist the latest resumable handle for the next setup; handles last up to two hours. Connections roll over around ten minutes. The ephemeral guide permits resumption with the same unexpired token even with one use; fetch a new token after expiry. M1 retains token/handle in memory for the running conversation, flushes playback and reconnects on goAway/disconnect. It does not promise lossless replay of audio between snapshots.

[Android AudioManager](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)) supplies API 31+ communication-device selection; older supported Android releases use SCO. Both AudioRecord and AudioTrack use communication routing. M1 requests 8 kHz HFP or 16 kHz phone PCM, with streaming 8→16 kHz interpolation; the OS converts the 24 kHz output to the physical link rate. Bluetooth absence/denial/route timeout/disconnection falls back to the phone. Mock mode explicitly uses phone audio.

[AGP 8.13 compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes) verifies SDK 36 support, Gradle 8.13 and JDK 17. M1 pins AGP 8.13.2, Gradle 8.13 and Kotlin 2.2.21, with Compose dependency versions taken from the current CameraAccess sample.

## Unverified / bounded behavior

- No real Pixel/glasses or credentialed Gemini session was exercised in this implementation session. HFP quality, echo cancellation, barge-in latency, runtime DAT/Inputs entitlement and legacy API 29–30 SCO need device testing.
- Backend token field-mask compatibility with a changing resumption handle needs integration verification. A fully locked `{sessionResumption:{}}` can silently prevent resumption.
- Mock pairing/input injection compiles against the actual SDK; on-device behavior still needs the README smoke test. Mock audio is phone audio.
- Capture HOLD is a toggle because the published API has no release. M1 caps one manual activity at 60 seconds.
- Foreground only; backgrounding stops the conversation. Handles/transcripts are memory-only and Stop clears resumption. No camera streaming, memory storage or background service is included in M1.
- Tool cancellations cannot undo a backend side effect; POST failures have uncertain outcomes. Deduplication is per running process/session, not a durable exactly-once guarantee.

## Local validation outcome

JDK 17 / SDK 36, 2026-10-06: `./gradlew assembleDebug` succeeded (6 seconds); `./gradlew testDebugUnitTest` succeeded (3 seconds), **20 tests, zero failures/errors/skips**; `./gradlew lintDebug` succeeded (4 seconds), with zero errors and eight non-blocking warnings (seven dependency-update notices and one legacy backup-declaration notice). The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. This validates compilation against real DAT artifacts and JVM behavior, not credentialed/live hardware behavior. No commits were made.
