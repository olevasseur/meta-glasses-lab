# Hands-free operation

**Approval update — 2026-10-08:** While an approval card is pending, capture events approve that action before normal HOLD start/stop handling. Tier 2 Codex requires capture or screen; nod is unavailable. See [device approvals](device-approvals.md) for configuration, limits and verified evidence.

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
| Notification **mode** | Cycles Auto → Push-to-talk → Over → Auto, persists the selected mode, obtains the appropriate backend token, and starts a new Gemini session. |
| Touchpad **double-tap**, Over mode | No turn-control action; use the selected sign-off or silence timeout. A pending nod/shake confirmation still works. |
| Notification **Talk / Finish** or **Mute / Unmute** | Controls the current PTT turn or Auto microphone mute. |
| App **Stop**, **Stop service and glasses controls**, or notification **Stop** | Stops Gemini, audio, DAT input listening, notification, and wake lock. Capture HOLD no longer works in the background until armed again. |
| Quick Settings **Glasses conversation** tile | Opens the Activity and immediately starts a conversation after permissions/unlock. Add the tile using Android's Quick Settings editor. |
| Launcher **Start conversation** shortcut | Long-press the app icon, then drag the shortcut to the home screen, or tap **Add Start shortcut to home screen** inside the app and accept the launcher's pin prompt. |
| **Hey Meta, start the glasses app** | With approved/configured Voice Invocation, opens/foregrounds the app, starts the service/conversation, and acknowledges each delivered `LaunchApp`. |

Single touchpad tap and tap-and-hold remain reserved by the glasses. DAT pause or
loss marks glasses controls unavailable; **HFP conversation audio keeps running**.
A paused DAT session waits for resume. A stopped/failed session is automatically
replaced while controls are armed, even during a conversation. Use screen or
notification controls until Inputs is ACTIVE again; capture HOLD may otherwise
be handled by the glasses as a system photo. To stop retries and microphone use,
use app/notification **Stop**. Input failure never substitutes a single-tap gesture.

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
   alongside mode, patience and Over settings in Preferences DataStore. An existing
   `hands_free` / `idle_minutes` preference migrates automatically. Expiry stops the
   entire service, including armed controls and paused sessions. Transcript
   updates, control actions and locally detected speech in an active user turn reset idle time; raw PCM silence, notification
   refreshes, and reconnect status updates do not. Changing the timeout takes
   effect immediately. A 65-minute wake-lock timeout is renewed on activity,
   while explicit stop/destroy releases it.

## Calls, focus, and disconnections

Ringing/off-hook pauses audio when Phone permission is granted. All focus losses,
including requests to duck, also pause. Capture/playback are released, PTT ends,
queued output is flushed, and Gemini uplink/downlink audio is gated. Over keyword
recognition stops during the pause while its manual activity remains open;
resuming starts recognition with a fresh silence interval. A pending reply
finishes before Over opens another turn. Camera/watch work stops, and pending
gesture confirmations clear; those local tools cannot start while paused. The Gemini
session stays alive and retains its normal reconnect/resumption behavior. Call
end and focus gain resume only after **all** blockers clear; Stop or idle expiry
prevents later callbacks from restarting the conversation.

Bluetooth audio-device removal stops capture and pauses the conversation instead
of switching to the phone microphone. Reconnect the glasses and tap **Resume**
to retry routing. If no Bluetooth device is available when explicitly starting,
the existing phone-audio fallback still applies. DAT inputs loss alone never
pauses audio, ends a PTT turn, or changes the HFP route. The Talk screen and
notification say **Glasses controls unavailable** while screen/notification
Stop, PTT Talk/Finish, mode and Auto mute controls remain available.

Recovery waits 1, 2, 5, 10, then 30 seconds between failed/ended attempts, including
creation failures, device errors and a 15-second startup/Inputs activation timeout.
The delay stays capped across short sessions and resets on a new arm/device.
PAUSED retains the session and waits for STARTED or STOPPED, including during startup. Stop,
idle expiry and device selection changes cancel pending retries. Each attempt,
outcome, session transition and SDK error code/description enters diagnostics.
Permanent device refusal can still require a glasses restart; audio does not wait
for recovery. See [DAT/HFP findings](../research/dat-session-hfp.md) (2026-10-09).

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
- Changing conversation mode starts a fresh Gemini context because the backend locks VAD
  mode. Patience, keyword and silence settings survive mode changes. The visible transcript remains, but is not replayed into that new context.
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

Merged validation on **2026-10-07**: backend `uv run pytest` **103 passed,
2 dependency warnings in 0.98s**. Android `assembleDebug testDebugUnitTest`
**BUILD SUCCESSFUL in 18s**, **45 actionable tasks: 45 executed**; **94 JVM tests,
zero failures/errors/skips**. Added merge regressions for the three-mode cycle,
Over mute rejection, all-mode pause/stop/restart, and persisted idle/Over settings.
Used JDK 17, installed SDK 36 and writable temporary uv/Gradle/Android caches.
Backend tool registry, `lock_additional_fields=[]` and client-owned resumption
are unchanged. Combined physical camera/Motion/Speech/HFP, calls and background
launch behavior remain unverified; see the checklist below.


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
blockers, explicit Bluetooth recovery, DAT input loss without audio pause, stop preventing
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
   resumes. Single tap pauses DAT controls while conversation audio continues;
   a second tap resumes controls. Tap-and-hold stops DAT; automatic recovery must
   restore controls without interrupting HFP. Verify mapping on the actual model.
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
   retained Gemini context. End the DAT session separately: audio and an open PTT
   turn continue, unavailable controls are visible, and a new DAT session attaches
   Inputs automatically. No automatic switch to phone capture during either loss.
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

12. Select Over with a nondefault keyword/patience/silence timeout. Cold-launch
    through the tile and confirm those settings load before recording. Speak for
    longer than the one-minute idle timeout without sign-off: the service must
    remain active. During speech, receive a call or lose focus: recognition/audio
    stop without ending the Over turn. Resume, then finish with the keyword.
    Repeat an interruption during Gemini's reply; it must not open a new user
    turn until that reply completes. Double-tap must not end or mute an Over turn.
13. With Over, head gestures and watch enabled, background/rotate/lock the app.
    Confirm the service retains all capabilities, notes/memory still work, and
    Stop ends capture and closes the memory session. Interrupt with a call:
    watch stops and gesture questions clear. Resume and explicitly enable watch
    again. Cycle all notification modes and restart the app; verify the chosen
    mode and all other settings persist.

## Verified sources

Verified **2026-10-07** against the real SDK artifacts, cloned repository, and
official Android references; device/runtime behavior remains unverified:

- [Pinned DAT voice integration reference](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/voice-invocations/SKILL.md)
- [Pinned DAT inputs reference](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/inputs/SKILL.md)
- [Meta Inputs guide](https://wearables.developer.meta.com/docs/develop/dat/inputs/) and [Voice Invocations guide](https://wearables.developer.meta.com/docs/develop/dat/voice-invocations/) (gesture details also checked against the 2026-10-06 M1 docs snapshot; public pages required login on 2026-10-07).
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [audio focus](https://developer.android.com/media/optimize/audio-focus), [tiles](https://developer.android.com/develop/ui/views/quicksettings-tiles), and [shortcuts](https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts).
- [Protocol/token evidence](../research/m1-android-notes.md), [token verification](../research/m2-backend-notes.md), and [hands-free research](../research/hands-free-background.md). The current architecture's `lock_additional_fields=[]` and client-owned `sessionResumption` remain unchanged.

## DAT/HFP regression validation (2026-10-09)

JVM tests cover STARTED→STOPPED replacement with collector cleanup before reacquisition,
early STOPPED, STOPPING, device errors, startup timeout, exact capped backoff,
PAUSED/resume without reattachment, and disarm during retry. State tests check
input loss/recovery in every mode and preserve call/focus/Bluetooth pause guards.
The ViewModel no longer calls `finishTalking()` on DAT loss. Existing serial-owner
tests continue to cover rapid Stop/re-arm and stale callback suppression.

Run the [Pixel/RB Meta 01G8 retest](../hardware-test-plan.md#dat-hfp-regression-retest--2026-10-09)
before treating concurrent DAT/HFP recovery as hardware-verified.
