# DAT session termination when opening HFP

Researched **2026-10-09** for Pixel 10 Pro + Ray-Ban Meta **RB Meta 01G8**, DAT
Android **1.0.0**, device compatibility **COMPATIBLE**. Runtime AI remains Gemini.

## Evidence from this hardware session (provided logs, UTC)

- 13:48:46: DAT controls STARTED / ACTIVE, glasses worn.
- 13:49:41.279: capture HOLD starts an Over conversation.
- 13:49:41.867: route ready after 557 ms; glasses communication device selected
  around 41.82. 13:49:42.643: HFP capture 8000→16000 Hz.
- 13:49:42.982: “Session ended by device”; the app immediately marks the
  conversation paused. DAT then stops; app releases SCO around 43.07.
- 13:49:43.57: Gemini connects, but the app has already disabled audio.
- Next capture HOLD takes a system photo because DAT inputs were never restored.
- Earlier at 13:22:46, DAT startup timed out; an on-screen conversation with HFP
  and no DAT session subsequently worked.

**Verified app defect:** `onInputLost` called `finishTalking()` and
`ConversationService.inputsAvailable(false)` added `PauseReason.GLASSES` once
inputs had been available. Separately, the inputs owner waited forever after
STOPPED; only changing the selected device restarted it. Both defects are
independent of why firmware ended the session.

**Inference:** HFP activation is a plausible trigger (~1.16 s after route
selection), but temporal correlation does not establish firmware causality.
Capture HOLD, DAT Speech startup in Over, another system interaction, or an
on-glasses runtime failure remain possible. No SDK logcat was captured for this
run. COMPATIBLE does not prove a healthy running DAT app.

## Verified documentation and SDK surface

Sources retrieved 2026-10-09. Official Android repository HEAD was
`d3159f738676221181577dd745d83d10b6ab46ba`; inspected published samples/skills and
cached Maven 1.0.0 AAR public signatures with JDK 17 `javap`.

- [Meta full documentation](https://wearables.developer.meta.com/llms.txt?full=true),
  sections “Session lifecycle”, “Use device microphones and speakers”, “Inputs”:
  device transitions do not expose their cause. System experiences can interrupt
  sessions. PAUSED retains the connection; wait for resume. HFP is the documented
  platform microphone path (8 kHz mono), sharing access with DAT. Android uses
  communication mode and `setCommunicationDevice`. The explicit HFP-before-camera-
  stream ordering appears in the **iOS** section: attach camera, establish HFP,
  then start streaming. It does not specify SCO-before-`createSession` for Android
  inputs. Camera stream PCM is a beta alternative requiring a video stream.
  Touchpad single tap pauses/resumes; temple tap-and-hold stops DAT. Physical
  capture HOLD is a separate app input. No blanket rule saying every HFP/call or
  system-microphone activation must end DAT was found. The dedicated
  [lifecycle page](https://wearables.developer.meta.com/docs/develop/dat/lifecycle-events/)
  and audio page can require login; the full-docs endpoint supplied the content.
- [Official lifecycle skill](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/session-lifecycle/SKILL.md):
  STOPPED is terminal; create another session. Subscribe to state/errors before
  fire-and-forget `start()`. PAUSED must not cause session replacement. Meta's
  default UX recommends waiting for user restart after STOPPED. This app instead
  retries while explicitly armed, as requested; Stop/disarm cancels that policy.
- [Official Inputs skill](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/inputs/SKILL.md):
  attach only after STARTED; adding Inputs activates it automatically. Removing
  Inputs requires cancelling its collectors. Recovery here discards the entire
  failed session before attaching Inputs to its replacement.
- [Official Speech skill](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/speech/SKILL.md):
  Speech supplies on-device transcripts, requires MICROPHONE permission and is
  experimental. It is not a raw PCM microphone substitute for Gemini Live.
- [CameraAccess session observer](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/camera/CameraViewModel.kt):
  collects session errors and releases session references on STOPPED.
- [Core 1.0.0 artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-core/1.0.0/)
  and [Inputs artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-inputs/1.0.0/):
  public session states include IDLE, STARTING, STARTED, PAUSED, STOPPING, STOPPED.
  `DeviceSessionError` and `InputsError` expose symbolic enum codes and
  `description`, **not a public numeric code**. Examples include
  SESSION_ENDED_BY_DEVICE, NO_ELIGIBLE_DEVICE, DWA_UNAVAILABLE,
  DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED and ACTIVATION_TIMEOUT.

## Related reports (verified reports, not reproduction on our device)

- [Discussion #130](https://github.com/facebook/meta-wearables-dat-android/discussions/130)
  and [issue #131](https://github.com/facebook/meta-wearables-dat-android/issues/131):
  camera/HFP coexistence can yield SESSION_ENDED_BY_DEVICE. Some developers
  fixed older SDK setups by pinning communication mode/device instead of repeatedly
  calling deprecated `startBluetoothSco`; another required tearing down SCO
  before DAT creation. These contradictory device-specific reports do not
  establish a universal ordering. Our API 31+ path already pins the modern route.
- [Issue #192](https://github.com/facebook/meta-wearables-dat-android/issues/192):
  includes DAT 1.0.0 reports of session termination 0.1–0.6 seconds after SCO,
  including CameraAccess and a modern `setCommunicationDevice` path. A Meta
  responder attributes submitted capture-drop logs to glasses-side DAT restarts.
  October 5 guidance allows a **one-time** SDK reset for one unavailable-device
  failure, warns against repeated resets, and says SDK logging is not controlled
  by `log.tag.*` properties. This does not diagnose RB Meta 01G8 or establish that
  a reset prevents HFP termination. No global reset was added: it would invalidate
  shared camera/motion/speech and the independent voice invocation stream.
- [Issue #196](https://github.com/facebook/meta-wearables-dat-android/issues/196):
  reports PCM silence during camera streaming with normal audio outside a DAT
  session. Despite its title, its environment uses **client SDK 0.7.0 with DWA
  1.0.0**, Oakley glasses and a Samsung phone. The October 4 follow-up rules out
  the earlier proposed Meta AI version correlation. Useful retest symptom,
  not evidence that our inputs-only retry silences HFP.

## Implemented response

DAT availability is independent of audio eligibility. Loss no longer pauses
Gemini/HFP or ends an open PTT turn. Talk UI and notification expose unavailable
controls; their controls remain usable. Calls, focus loss and actual Bluetooth
route loss keep their existing audio protections.

While armed, failed creation, session errors, STOPPING/STOPPED, startup timeout,
Inputs errors and stalled Inputs activation clean up the attempt and retry after
1, 2, 5, 10, then 30 seconds repeatedly. Backoff remains capped even across short
successful sessions, resetting with a new arm/device selection. Startup/active-
session Inputs activation timeouts are 15 seconds. PAUSED keeps its session/collectors and waits for resume, including when it
arrives before the first STARTED. Retry does not touch SCO or depend
on whether Gemini is running. Device changes/disarm cancel waits and collectors;
existing serial ownership prevents overlap with a new arm.

Session diagnostics subscribe before start and record state transitions and SDK
symbolic errors/descriptions. Attempts/outcomes, input transitions and cleanup
are written directly to the existing diagnostic ring/upload path; rapid state
changes cannot disappear solely through UI StateFlow conflation. Arbitrary
exception messages are omitted (class name only).

No new audio API/ordering is justified for this failure by the sources above.
Camera PCM would require keeping the camera streaming for a voice-only feature;
Speech transcripts do not replace Gemini audio. Preserve the working HFP path
and retry DAT over the already-established route.

## Unverified / next hardware evidence

- Exact firmware cause, capture HOLD vs HFP vs Speech, and any call-priority rule
  on RB Meta 01G8. Compare Auto/PTT/Over and physical HOLD vs on-screen Start.
- Recovery success while HFP remains active, frequency of device tones, and
  nonzero mic audio after every successful reattachment. A retry cannot guarantee
  firmware accepts the session; permanent refusal may require glasses restart.
- Inputs delivery after recovery, background behavior, and shared camera/motion/
  speech capabilities on replacement sessions. JVM tests exercise ownership,
  state transitions, backoff and cancellation, not Bluetooth firmware.
- Capture full unfiltered logcat around the event and search `DAT:` afterwards;
  app diagnostics cannot expose private SDK numeric protocol errors.

See [exact hardware retest](../hardware-test-plan.md#dat-hfp-regression-retest--2026-10-09)
for acceptance criteria. No live Gemini call or physical hardware retest was run
while implementing this fix.

## Local verification

`assembleDebug testDebugUnitTest`: BUILD SUCCESSFUL, **257 tests**, zero failures,
errors or skips (40 suites). `uv run pytest`: **619 passed**, three dependency/SDK
warnings, 11.98 seconds. Backend code unchanged; `git diff --check` passed.
Sandbox verification used writable Gradle/Android homes under `/tmp` and a copy
of the existing debug signing key there; no build configuration change was needed.
