# Hands-free background operation and launch triggers

Verified **2026-10-07** (America/Toronto). Android only; Gemini remains the runtime AI.

## DAT 1.0.0 evidence

Cloned https://github.com/facebook/meta-wearables-dat-android to
`/tmp/glasses-background-dat`, commit `d3159f738676221181577dd745d83d10b6ab46ba`.
Read the published sample/integration references and inspected the actual cached
Maven Central 1.0.0 core and inputs AAR signatures with JDK 17 `javap`.

- [Inputs reference in the repository](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/inputs/SKILL.md): attach `addInputs` only after `DeviceSessionState.STARTED`; sources can be restricted and `consumeBack` disabled. No runtime `Permission.INPUTS`; enable the capability in Developer Center.
- [Inputs guide](https://wearables.developer.meta.com/docs/develop/dat/inputs/): capture `HOLD` is a single semantic event, without separate down/up events. Double-tap maps to `InputEvent.Select` from `InputSource.CAPTOUCH`. Single tap pauses/resumes the device session; tap-and-hold stops it when no interactive display content is shown. Mapping can vary with device/display state. The public documentation URL returned a login page on this date; mapping was cross-checked against the prior M1 full-docs download (`/tmp/m1-meta-docs.txt`, obtained 2026-10-06), and the event/source types compile against the actual SDK. Real gesture delivery still needs hardware testing.
- [Voice invocation repository reference](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/voice-invocations/SKILL.md) and [Meta guide](https://wearables.developer.meta.com/docs/develop/dat/voice-invocations/): validate both Activity entry points with `isVoiceInvocationsIntent`. Retain one `Wearables.startVoiceInvocationsStream(AutoDeviceSelector())`; its factory starts automatically, independently of device sessions. Collect `LaunchApp`; call `responseHandle.sendSuccess(null)` or `sendFailure(...)` once per delivered action. These are suspend methods returning delivery success. Cancel collectors before `close()`.
- [Core artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-core/1.0.0/) / [inputs artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-inputs/1.0.0/): verified the above exact public symbols and compiled the implementation against them. Inputs has no raw double-tap enum; the application must filter the semantic event by source.

Voice Invocation requires the Android package, approved **Voice Invocation**
permission, and spoken app name in [Wearables Developer Center](https://wearables.developer.meta.com/devcenter/).
No custom voice phrase intent filter is needed. Inputs and Voice Invocation are
experimental, supported for development/beta rather than production channels.

## Android constraints and implementation choices

- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): declare microphone and connected-device types plus their normal permissions, obtain RECORD_AUDIO, and obtain BLUETOOTH_CONNECT for the Bluetooth path. Use microphone alone for phone-only operation; Android 10 predates the microphone type and uses the connected-device type.
- [Background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): microphone permission is subject to while-in-use restrictions. The tile, shortcut, and Meta voice launch enter a visible Activity before microphone startup. A retained foreground service permits subsequent glasses controls while the Activity is backgrounded. Capture cannot cold-launch a killed process without a listening DAT session.
- [Audio focus](https://developer.android.com/media/optimize/audio-focus): request focus with speech/voice-communication attributes, pause for loss including duck requests, and resume on gain only after other blockers clear. Android 15+ requires a top app or foreground service when requesting focus. Phone-state observation additionally covers ringing/off-hook and already-active calls when READ_PHONE_STATE is granted.
- [PhoneStateListener](https://developer.android.com/reference/android/telephony/PhoneStateListener): the compatibility listener covers minSdk 29, is deprecated on newer Android, and is registered only with READ_PHONE_STATE. The app does not use incoming numbers. Without this optional grant, interruption protection depends on focus callbacks.
- [Quick Settings tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles): use `startActivityAndCollapse(PendingIntent)` on API 34+, the Intent overload only below 34, and `unlockAndRun` when locked.
- [App shortcuts](https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts): a static launcher shortcut and `requestPinShortcut` provide home-screen entry points.

The service owns a `ViewModelStore` so the existing conversation model and its
coroutines outlive Activity recreation without copying the Gemini/backend code.
Audio pause closes platform capture/playback, gates Gemini audio, and retains the
WebSocket/resumption state. Auto/PTT changes require a new backend-issued token
because automatic VAD is locked. The existing minimal setup, endpoint, header,
and client-owned `sessionResumption` are unchanged; no backend changes were made.

## Unverified

- No physical phone/glasses, Meta entitlement approval, voice cold launch,
  lock-screen launch, Bluetooth disconnect timing, HFP chime routing, cellular
  call interruption, or OEM process/battery behavior was tested.
- No credentialed Gemini API calls were made in this task. The existing verified
  protocol contract is preserved; actual paused-session resumption and mode
  transitions require device/live-service testing.
- Voice invocation trailing speech forwarding is not established. Speak the
  Gemini request after the launch chime.
- Published SDK types and JVM tests do not prove device gesture delivery.

See [feature behavior and manual checklist](../features/hands-free.md) for validation.
