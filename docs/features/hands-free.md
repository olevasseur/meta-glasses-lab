# Hands-free operation

Implemented and checked **2026-10-07**. Runtime AI is Gemini Live. No backend
changes or new dependencies.

## What it does

`ConversationService` owns the conversation model, audio, Gemini session, and DAT
subscriptions. The Activity binds to that service for controls/transcripts;
leaving the Activity, rotating, or switching off the screen does not stop an
active conversation. The service uses foreground types `microphone|connectedDevice`
when Bluetooth permission is available (microphone alone on a phone-only API 30+
session), with a persistent low-importance notification and a bounded wake lock.
The screen is no longer forced to stay awake.

| Action | Result |
| --- | --- |
| **Start** in the app | Starts service, microphone, and Gemini. A short generated start chime sounds after audio routing is ready. |
| **Enable glasses controls** | Keeps DAT inputs listening in the foreground service without recording audio or opening Gemini. |
| Glasses capture-button **HOLD** | Starts a conversation if controls are armed; another HOLD stops that conversation and leaves controls armed for another start. The stop chime sounds when audio focus is available. |
| Touchpad **double-tap**, Auto mode | Toggles microphone mute. Muted capture is replaced with synthetic silence before Gemini, so VAD can finish the previous turn. |
| Touchpad **double-tap**, PTT mode | Toggles speaking on/off. A turn is capped at 60 seconds by the existing Gemini client. |
| On-screen **Hold to talk** | Actual press/release PTT; works together with the glasses toggle. **Finish speaking** clears both. |
| Notification **Auto mode / PTT mode** | Changes mode, obtains the appropriate backend token, and starts a new Gemini session. |
| Notification **Talk / Finish** or **Mute / Unmute** | Controls the current PTT turn or Auto microphone mute. |
| App **Stop**, **Stop service and glasses controls**, or notification **Stop** | Stops Gemini, audio, DAT input listening, notification, and wake lock. Capture HOLD no longer works in the background until armed again. |
| Quick Settings **Glasses conversation** tile | Opens the Activity and immediately starts a conversation after permissions/unlock. Add the tile using Android's Quick Settings editor. |
| Launcher **Start conversation** shortcut | Long-press the app icon, then drag the shortcut to the home screen, or tap **Add Start shortcut to home screen** inside the app and accept the launcher's pin prompt. |
| **Hey Meta, start the glasses app** | With approved/configured Voice Invocation, opens/foregrounds the app, starts the service/conversation, and acknowledges each delivered `LaunchApp`. |

Single touchpad tap and tap-and-hold remain reserved by the glasses. A DAT session
pause suspends conversation audio; resuming the DAT session restores it. A DAT
session stop leaves audio paused; stop/re-enable controls to create a new device
session. Input failure never substitutes a single-tap gesture.

## Configuration and setup

1. Follow [Android setup](../../android/README.md) for JDK/SDK, backend address,
   debug shared secret, DAT registration, and `adb reverse tcp:8787 tcp:8787`.
   Keep Gemini API keys and Google refresh tokens exclusively on the backend.
2. Grant **Microphone**, **Nearby devices**, **Notifications**, and **Phone** when
   prompted. Microphone is required; Nearby devices enables glasses routing and
   connected-device FGS type. Phone enables explicit ringing/off-hook detection;
   without it, pausing relies on Android audio focus. Notifications must be allowed
   to see the notification buttons (denial does not remove Android's active-app
   foreground-service indicator). Phone-only sessions work without Nearby devices.
3. Enable **Inputs** for this integration in Developer Center. DAT has no runtime
   Inputs permission dialog. Arm controls while the Activity is visible before
   trying capture HOLD with the screen off.
4. For voice launch, open [Wearables Developer Center](https://wearables.developer.meta.com/devcenter/),
   select the app/project, add Android package **`com.olevasseur.glassesapp`** under
   mobile app configuration, request the **Voice Invocation** permission, wait
   for approval, and configure the spoken name **the glasses app**. Use the
   Developer Center identity for the integration (the existing manifest's `0`
   values are Developer Mode placeholders). Register/connect in Meta AI. There is
   no manifest phrase declaration. Approval and device delivery have not been
   tested here. Inputs and Voice Invocation are experimental in DAT 1.0.0.
5. Set **Stop after … minutes** in the app: default **5**, range **1–60**, persisted
   in private `hands_free` preferences under `idle_minutes`. Expiry stops the
   entire service, including armed controls and paused sessions. Transcript
   updates and control actions reset idle time; raw PCM silence, notification
   refreshes, and reconnect status updates do not. Changing the timeout takes
   effect immediately. A 65-minute wake-lock timeout is renewed on activity,
   while explicit stop/destroy releases it.

## Calls, focus, and disconnections

Ringing/off-hook pauses audio when Phone permission is granted. All focus losses,
including requests to duck, also pause. Capture/playback are released, PTT ends,
queued output is flushed, and Gemini uplink/downlink audio is gated. The Gemini
session stays alive and retains its normal reconnect/resumption behavior. Call
end and focus gain resume only after **all** blockers clear; Stop or idle expiry
prevents later callbacks from restarting the conversation.

Bluetooth audio-device removal stops capture and pauses the conversation instead
of switching to the phone microphone. Reconnect the glasses and tap **Resume**
to retry routing. If no Bluetooth device is available when explicitly starting,
the existing phone-audio fallback still applies. A stopped/unavailable DAT input
session also pauses an established glasses session. It does not block a
phone-only conversation that never had active glasses inputs.

## Limits

- Capture HOLD needs an armed, running DAT session; it cannot cold-launch a dead
  app. Voice, tile, and shortcut are the cold-entry paths. The service is
  `START_NOT_STICKY`: Android killing or force-stopping it will not silently
  reopen the microphone. Start again explicitly.
- Modern Android requires visible/eligible startup for microphone access.
  Tile/shortcut bring up the app, and a locked tile requests unlock. Voice launch
  may also require unlocking; Android/OEM restrictions are reported in the UI.
- Notification **Stop** disarms inputs even when the Activity remains bound.
  The bound model/voice stream may stay alive until the UI is destroyed, but it
  does not record. No boot receiver, hotword recognizer, or battery exemption is
  installed.
- Auto mute leaves Android's recording indicator on, but sends silence instead
  of captured microphone samples. It does not mute Gemini's speaker output.
- Changing Auto/PTT starts a fresh Gemini context because the backend locks VAD
  mode. The visible transcript remains, but is not replayed into that new context.
- A paused session may still finish previously initiated tool work and display
  transcript updates; audio received while paused is discarded, not replayed.
  No capture occurs during calls/other pauses after platform cleanup completes.
- Chimes use Android `ToneGenerator`; system volume and routing determine
  audibility. Bluetooth audio, stop-tone tail/routing, background battery behavior,
  simultaneous screen/glasses PTT, and voice approval/delivery require hardware
  verification. Mock glasses do not simulate an Android Bluetooth microphone.
- Voice success acknowledges an accepted foreground conversation start, not a
  completed Gemini connection. Backend/network failures remain visible in the
  app/notification. No trailing voice-launch words are assumed to be forwarded.

## Automated validation

Result on **2026-10-07**: `assembleDebug testDebugUnitTest lintDebug` succeeded;
**32 tests, 0 failures/errors/skips** (12 new tests), and lint **0 errors, 8 existing
warnings**. The final Gradle tail was `BUILD SUCCESSFUL in 9s` and
`55 actionable tasks: 16 executed, 39 up-to-date`.

Run with `JAVA_HOME=/opt/homebrew/opt/openjdk@17` and
`ANDROID_HOME=$HOME/Library/Android/sdk`:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest
./gradlew lintDebug
```

`HandsFreeStateTest` covers arming, HOLD start/stop, overlapping call/focus
blockers, explicit Bluetooth recovery, reserved-session pauses, stop preventing
resume, mode/mute behavior, idle boundaries/reset, bounded settings, and repeated
starts. `GlassesTriggersTest` uses the actual SDK types to reject non-HOLD
capture events, other Select sources, and Back. Existing protocol/backend-client
and resampler tests remain in the suite. No backend files were changed; no real
Gemini API or Google Docs calls were made for this feature.

## Manual acceptance checklist (not executed)

Use a physical Android phone and DAT 1.0.0-compatible, registered glasses with
Inputs enabled, a reachable backend, notification access, and microphone/phone/
Nearby devices permissions. Repeat phone-only steps with Nearby devices denied.

1. Install `android/app/build/outputs/apk/debug/app-debug.apk`. Start Auto; confirm
   one start chime, Gemini connected, correct HFP route, and working two-way audio.
   Lock the phone for two minutes while conversing, open another app, rotate the
   Activity, and reopen it. Confirm one conversation/notification, continuing
   transcripts, and no second microphone/session caused by rebinding.
2. Press notification **Stop** with the screen locked. Confirm microphone usage
   ends, audio stops, notification disappears, and capture HOLD does nothing.
   Inspect `adb shell dumpsys activity services com.olevasseur.glassesapp` and
   `adb shell dumpsys power` for absence of an active foreground conversation and
   held `glassesapp:conversation` wake lock. A visible Activity may keep a bound
   service instance; it must not keep microphone capture running.
3. Open the app, press **Enable glasses controls**, wait for Inputs ACTIVE, then
   lock it. HOLD capture once: conversation and chime start. HOLD again: audio/
   Gemini stop while the notification says controls ready. HOLD again: a new
   conversation starts. SHORT_PRESS and DOUBLE_PRESS must not toggle conversation.
4. In Auto, double-tap: notification says muted; speak a unique phrase and verify
   it is absent from Gemini input transcripts. Double-tap again: microphone
   resumes. Single tap must pause the DAT session/audio, a second single tap must
   resume; tap-and-hold must stop the DAT session and leave audio paused. Stop and
   re-enable controls before the next test. Verify mapping on the actual model.
5. Switch to PTT with the notification. Confirm one fresh token/session, no input
   until a double-tap or screen press, and activity end on the next double-tap/
   release. Exercise screen hold and glasses toggle together; **Finish speaking**
   clears both. Leave a glasses turn open: it must close after 60 seconds. Toggle
   modes quickly during connect/stop; there must be no overlapping recorders.
6. Start a conversation and receive a call. Test ringing, rejection, answering,
   and hanging up. Gemini must not hear the call or speak during it. Start while
   already in a call: audio stays paused. Let focus return before call-IDLE and
   then reverse the order; resume only when both permit it. With another app
   taking audio focus, audio must pause (including duck requests). Stop while
   paused; ending the call must not restart it. Deny Phone permission separately
   and verify the documented focus-only behavior on this phone.
7. Disconnect/power off Bluetooth during speech and during a Gemini reply.
   Confirm captured speech is not redirected to the phone, playback is cleared,
   and UI shows BLUETOOTH pause. Reconnect, tap **Resume**, and verify route and
   retained Gemini context. Disconnect the DAT session separately and check the
   GLASSES pause. No automatic switch to phone capture during either loss.
8. Set idle timeout to one minute. Check silent Auto, PTT waiting, muted, armed
   controls without Gemini, and paused audio. At approximately 60–61 seconds
   without transcript/control activity, verify complete stop. Speak or press a
   control near 45 seconds and confirm the deadline moves. Restart the app and
   confirm the configured minute value persisted.
9. Add the Quick Settings tile. Test cold launch, warm launch, and locked click
   (unlock required). Test the launcher shortcut and pinned home-screen shortcut.
   With permissions already granted, each starts automatically and produces one
   service/session. With microphone denied, each shows the app/permission flow
   without crashing; grant permission and retry. Declining notifications must
   not crash, though notification actions will be unavailable.
10. After Voice Invocation approval, say **Hey Meta, start the glasses app**
    with the app absent, backgrounded, already open, and already conversing.
    Verify cold `onCreate` and warm `onNewIntent` paths, one retained stream, no
    duplicate conversation, success acknowledgement on glasses, and immediate
    microphone startup after Activity eligibility. Say the actual Gemini request
    after the chime. Test missing permission, backend offline, locked phone, and
    rapid repeated launch commands; verify clear failures without duplicate ACKs.
11. Swipe away the Activity while conversing: the started foreground service
    continues. Force-stop the package or use Android's active-app Stop: audio
    ceases and does not spontaneously restart. Relaunch explicitly. Repeat a long
    screen-off session with normal battery settings and a Gemini connection
    rollover; verify context resumption and idle behavior on the target phone.

## Verified sources

Verified **2026-10-07** against the real SDK artifacts, cloned repository, and
official Android references; device/runtime behavior remains unverified:

- [Pinned DAT voice integration reference](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/voice-invocations/SKILL.md)
- [Pinned DAT inputs reference](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/inputs/SKILL.md)
- [Meta Inputs guide](https://wearables.developer.meta.com/docs/develop/dat/inputs/) and [Voice Invocations guide](https://wearables.developer.meta.com/docs/develop/dat/voice-invocations/) (gesture details also checked against the 2026-10-06 M1 docs snapshot; public pages required login on 2026-10-07).
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [audio focus](https://developer.android.com/media/optimize/audio-focus), [tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles), and [shortcuts](https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts).
- [Protocol/token evidence](../research/m1-android-notes.md), [token verification](../research/m2-backend-notes.md), and [hands-free research](../research/hands-free-background.md). The current architecture's `lock_additional_fields=[]` and client-owned `sessionResumption` remain unchanged.
