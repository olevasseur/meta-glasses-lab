# Android pre-hardware robustness review

**2026-10-08 · branch `feat/androidreview` · single personal phone/glasses pair**

Reviewed the fourteen feature documents, architecture/decisions and relevant
Gemini/DAT research, then traced Activity → foreground service → ViewModel →
feature controllers → WebSocket/audio/DAT lifetimes. Fixed the concrete issues
below. This is readiness for a supervised hardware test, not hardware acceptance.
Runtime AI remains Gemini; no backend code, token constraints or wire setup changed.

## Findings and fixes

Paths below are relative to `android/app/src/main/java/com/olevasseur/glassesapp/`;
line numbers refer to the reviewed final source. P1 means incorrect execution or
session failure; P2 means resource, recovery or diagnostic failure.

| Severity | File:line | Reproduction / consequence | Fix and evidence |
|---|---|---|---|
| P1 | `GeminiSession.kt:236` | A call/focus pause gates audio, but a late ordinary-assistant `remember`/Docs/tool call still executes because the old condition only checked suspension for meetings. | Reject all newly received calls while suspended and display/send an ignored result. JVM regression checks both backend and local work remain uncalled, plus discarded audio. Work dispatched before the pause can still finish. |
| P1 | `GeminiSession.kt:353` | Capture observes backpressure and queues reconnect; the user starts a new persona/session before Main handles it. The stale callback cancels the new session. | Bind queued reconnect to the failed socket's identity. Regression immediately replaces a stalled socket and verifies the replacement still sends PCM; stale socket audio is ignored. |
| P1 | `GeminiSession.kt:255` | Main.immediate executes a local tool synchronously. Its client action starts a new session and clears jobs, then the outer assignment re-adds the old job. A repeated call ID in the new session is ignored forever. | Register a lazy job before starting it. Regression forces synchronous completion/session switch and reuses the call ID successfully. |
| P1 | `GlassesController.kt:96`, `SerialSessionJob.kt:17` | Stop cancels the DAT collector without awaiting its finally block. Rapid Start can reuse the still-STARTED session; old cleanup stops it and reports input loss into the replacement conversation. | A serialized session owner holds its mutex through cleanup, including cancellation, and invalidates old loss callbacks. Three tests cover restart ordering, repeated Start, and Stop/Start/Stop/Start during delayed cleanup. Actual DAT stop timing remains a hardware check. |
| P2 | `ConversationAudio.kt:236` | A native release/unregister/routing exception aborts teardown, leaving later resources and mode restoration unattempted. Startup cancellation also relied on the caller to re-enter cleanup in a non-cancellable context. | Cleanup owns a non-cancellable mutex, stops/unblocks capture before joining, joins playback before release, and attempts each remaining release independently. Reports a cleanup error visibly. Compiled/linted; native exception/release behavior requires instrumentation. |
| P2 | `GeminiSession.kt:118` | `AudioTrack` flush failure during interruption escapes directly from the OkHttp callback, outside the existing receive error boundary. | Catch at the callback boundary and report the fatal error on the owner scope. Regressions inject flush failure into interruption and pause, including the real fatal→Stop callback shape; shutdown still closes the socket without recursive failure. Another test proves immediate flush invalidates queued old audio. |
| P2 | `ConversationViewModel.kt:63`, `ConversationViewModel.kt:289` | With gestures enabled, Motion remains attached to the armed Inputs session after capture HOLD stops conversation, during a call pause, and in Translator/meeting mode. It consumes sensor/battery resources with no eligible feature. | Feed Motion only eligible sessions. Tests cover every pause, armed-only, stopping and exclusive personas. Network reconnection alone retains local workout motion. Inputs remain armed as intended. |
| P2 | `ConversationToolController.kt:8`, `ConversationViewModel.kt:150` | An addressed meeting request can start a workout, reading, watch or nod navigation even though their cues/gestures are suppressed and the feature docs describe exclusion. | Extract local routing/policy. Reject continuous assistant features in meeting mode, retain explicit cleanup/deletion and addressed one-shot look/progress/timers. Translator and pause gates apply before dispatch. Tests verify rejection has no feature side effect and Assistant routing preserves results. |
| P2 | `GlassesCamera.kt:62` | Stream errors briefly set camera status, then `stop()` immediately overwrites it with “Camera off”; asynchronous failures disappear. | Send unexpected errors/terminal stream states to the persistent visible error field after cleanup. Intentional stop cancels the monitor first. SDK compilation passed; actual revocation/device events remain unverified. |
| P2 | `ConversationService.kt:128`, `ConversationService.kt:169` | A thrown optional Voice Invocation factory error aborts permission/start flow. A late failure during FGS promotion can leave the started service or acquired wake lock alive. | Show voice-launch failure while retaining manual Start; release a held wake lock and stop the started service on promotion failure. Platform startup failure paths are source-reviewed/compiled, not executed by JVM tests. |

## Feature-interaction matrix

Precedence is: **explicit Stop / call-focus-device pause → meeting or translator
persona → turn mode → feature-specific ownership**. Meeting overrides saved
Auto/PTT/Over and exits translation; translation cannot start during a meeting.
This is enforced both at incoming tool admission and local feature dispatch.

“Paused” below means the conversation pipeline. Independent, explicitly scheduled
local alarms are an exception, documented rather than silently folded into that
pipeline. Already-dispatched remote side effects cannot be undone.

| Feature | Assistant: Auto / PTT / Over | Meeting | Translator | Pause / Stop / reconnect |
|---|---|---|---|---|
| Hands-free / service | One service owns mic, speaker, DAT and model across UI recreation. | Same owner; room/phone capture option. | Same owner; fresh Live persona. | Pauses release audio; Stop disarms; network reconnect retains service. Process death requires explicit Start. |
| Over / patient turns | Auto uses VAD, PTT uses held activity, Over uses sign-off/silence. Over has no reply barge-in. | Meeting uses automatic transcription and its own optional “over” address. | Saved turn mode still applies. | PTT ends on pause; Over's activity remains open and its local recognizer stops until resume. |
| Head gestures | A pending confirmation owns a movement before project navigation. Rep workouts suppress both. | Unavailable; use spoken confirmation. | Unavailable. | Questions clear; Motion detaches on pause/Stop, reattaches when eligible; no question replay. |
| M4 look / watch | Shared serialized camera, ≤1 fps upload, 5 s idle close; watch capped at 2 min. | Addressed one-shot look permitted; watch enable rejected. | Unavailable. | Capture/watch stop on pause, disconnect or Stop; explicitly re-enable watch. |
| Visual memory | Explicit opt-in or pin; same camera queue; Motion optional; no GPS. | Enable/pin rejected; deletion and disabling permitted when addressed. | Unavailable. | Capture stops on pause/persona/disconnect/Stop; no automatic restart. Already uploaded frames may finish. |
| Meeting | Explicit start finalizes ordinary session and clears its active features. | Unaddressed speech cannot execute tools or play Gemini audio. | Starting meeting exits Translator. | Pause gates capture/tools; Stop drains final transcripts for 3 s and retains durable outbox. |
| Live translator | Start creates fresh token/context and ends active assistant capture/workout. | Must stop meeting first. | Only start/stop translation tools; optional phone output gates mic. | Phone output cancels on pause/Stop; interrupted translation discarded; reconnect keeps persona. |
| Read-to-me | Page/chunk state; camera shared with look/watch/visual memory; spoken reading Stop also stops watch. | Starting/continuing reading rejected; cleanup allowed. | Unavailable; persona change clears page. | Pause/reconnect retains interrupted chunk; full Stop clears page. |
| Cooking / DIY | Spoken tools coexist; gesture navigation waits for idle and loses to confirmation/rep workout. | Addressed tools/timers and one-shot progress check allowed; enabling nod navigation rejected. | Assistant tools unavailable; existing timers remain scheduled. | Backend project persists; automatic navigation cancelled. Explicit alarms remain independent and can overlap conversation audio. |
| Workout | Shared Motion for reps; timed workouts do not suppress gestures. Automatic cues wait for idle, never close an Over turn. | Start rejected; status/stop remain harmless cleanup operations. | Entry stops workout and saves partial log. | Call/focus/device pause and Stop end/save partial workout; network reconnect alone retains counting. |
| Long-term memory / notes | Existing tools and transcript outbox. | Only addressed tools; meeting summaries are separate from personal fact extraction. | No assistant memory tools or injected personal memory; transcript storage still applies. | New calls rejected while paused; durable uploads retry, already sent writes can finish. |
| Background jobs / research | Idle speech or notification; no interruption of manual turns or confirmations. | Notification delivery; no automatic Gemini announcement. | Notifications, no interpreter injection. | Backend jobs continue; cursor/outbox replay when service returns; no guaranteed delivery after process kill. |
| Journal | Existing voice tools through backend. | Only addressed requests. | Assistant tools unavailable. | Backend scheduler independent of Android; no new paused tool execution. |
| Dashboard | Separate authenticated browser UI. | Independent. | Independent. | Browser changes do not grant phone capture permission or silently enable capture. |

## Lifecycle, permissions, audio and battery checks

- **Activity/service ownership:** MainActivity binds instead of constructing a
  second model. Its `onPause` reports visibility without stopping a conversation;
  service `ViewModelStore` survives rebind. Pending cold-launch intent is saved.
  START_NOT_STICKY avoids reopening a killed microphone. Outboxes persist while
  in-memory Live handle/reading/feature opt-ins do not.
- **Android 16 / target 36:** manifest declares microphone/connected-device FGS
  types and permissions; promotion precedes focus. RECORD_AUDIO is obtained before
  startup. BLUETOOTH_CONNECT is optional for phone capture and selects the modern
  connected-device type. The independent timer has mediaPlayback type. Visible
  Activity entry is still required for fresh microphone startup. These are source
  checks against [Android FGS rules](https://developer.android.com/develop/background-work/services/fgs/service-types)
  and [focus rules](https://developer.android.com/media/optimize/audio-focus), not an OEM runtime result.
- **First run:** mic denial is visible and disables Start; Nearby devices denial
  retains phone fallback. POST_NOTIFICATIONS denial removes background notification
  controls/job delivery but does not grant or deny recording. Phone permission is
  optional; denial leaves focus-only call handling. DAT microphone and camera
  permissions are separate buttons. No Android CAMERA/location permission is needed
  by the current code: remote DAT camera and manually entered visual place labels.
  Registration is explicit. Permanent denial still requires Android app settings.
- **DAT:** Inputs, camera, Speech and Motion share one session. Camera permission
  is checked for every capture; failures close capture. Lost established Inputs
  pause conversation. A terminal session stop still needs Stop/re-enable controls;
  it does not silently create replacement recording. Camera closes five seconds
  after its last capture and watch expires after two minutes. `reading.pause()`
  already closes vision, so no redundant watch-stop fix was necessary.
- **Audio:** route selection precedes AudioRecord/AudioTrack creation. API 31+
  uses communication-device selection; API 29–30 waits for SCO. Bluetooth absence
  or timeout at explicit Start falls back visibly to phone; loss during an active
  HFP session pauses instead. 8 kHz and 16 kHz are requested client capture formats;
  the resampler handles exactly those rates, preserving chunk continuity. Output
  is 24 kHz PCM16 mono. Do not infer the physical link codec/rate from the UI label.
  See [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord).
- **Playback/echo:** queue capped at about 40 s; nonblocking writes preserve partial
  offsets and avoid holding flush behind a blocking write. Playback drain includes
  both queue and native head position. Auto interruption flushes immediately and
  invalidates queued old events; PTT start flushes output. AEC is requested, not
  guaranteed effective. Over purposely avoids sending reply echo by keeping its
  user activity closed through playback. Physical underrun/echo measurement is
  still required; no blind latency/buffer/rate tuning was introduced.
- **Battery/memory:** one bounded wake lock renews on relevant activity and is
  released on Stop/destroy/startup failure. Local workout/meeting/visual capture
  keep useful sessions from idle expiry. Camera/watch and now Motion have explicit
  inactivity gates. PCM, displayed transcripts and reading text have bounds. No
  new retained Activity reference or coroutine scope was added.

## Validation

Baseline: **181 JVM tests** passed before changes. Final: **195 tests, 0 failures,
0 errors, 0 skipped**, including 14 new session/tool-policy/DAT-owner tests.
Existing resampler, reading/watch serialization, turn-mode, memory outbox,
translation, gesture and workout tests remain green.

JDK 17, SDK 36, temporary writable Gradle/Android caches, isolated Gradle project
cache and `--no-daemon`:

```text
./gradlew assembleDebug testDebugUnitTest lintDebug
> Task :app:testDebugUnitTest
> Task :app:lintDebug
BUILD SUCCESSFUL in 27s
55 actionable tasks: 15 executed, 40 up-to-date
```

Lint: **0 errors, 19 warnings** (dependency updates, optional KTX suggestions and
legacy backup declaration). Existing SDK XML/metrics-cache and deprecated callback
warnings also appear in the build. No dependencies or test assertions were relaxed.
Backend was not modified, so backend pytest was not required or run in this review.

Real API: five `/token` → constrained WebSocket setups passed (Auto, PTT, Over,
meeting, Spanish translator). Auto returned audio and turnComplete; same-token
reconnect with the returned handle received setupComplete. No secrets/handles
printed, jobs started or Docs written. This was protocol validation from Python,
not execution of the Android app. See [the evidence record](research/android-review-notes.md).

## Residual risks and first hardware checks

1. **Hardware blocker:** no attached device (`adb devices` empty). Install the
   built debug APK and first validate phone-only, then HFP. Record actual routes,
   recognized speech and audible replies; test Auto barge-in and PTT, then Over.
   Verify AEC, quiet speech, wideband/narrowband routing, wind and dropped audio.
   Do not treat interpolated 8 kHz as restored microphone detail.
2. **Calls and routing:** interrupt during speech and phone-translator playback;
   verify no uplink/downlink during pause and the correct mic after Resume.
   Translation Stop restores routing synchronously by request, while Android's
   physical route change is asynchronous. Test that transition before relying on
   microphone routing. Mid-session route changes without device removal are not
   separately monitored. Independent timer TTS can overlap Live or a call and is
   not covered by the conversation's focus/meeting gate (`CoachAlarmService.kt:45`).
3. **DAT/permissions:** revoke DAT camera/microphone consent, remove/don glasses,
   disconnect/reconnect Bluetooth, and rapidly Stop/Start with watch+Motion+Over.
   Confirm all capabilities stop and no stale loss callback pauses a new session.
   Terminal DAT stops require explicit restart. Recheck phone permission revocation
   and notification denial on Android 16; the OS may kill the process on revocation.
4. **Lifecycle:** rotate/rebind, lock screen, background for several minutes,
   notification Stop and process kill/relaunch. Use dumpsys audio/services/power to
   check native resources, FGS state and wake locks. Verify queued transcript and
   interrupted workout recovery; meeting's three-second final drain can lose late
   transcripts. No acoustic recording or Live context survives process death.
5. **Long sessions:** perform a real connection rollover and an hour-plus meeting
   with weak network. Token refresh, handle semantic continuity, native underruns,
   thermal/battery behavior and suspended native reads are not proved here. Replay
   maps in `GeminiSession` grow until session Stop; arbitrary eviction would risk
   duplicate backend side effects. `TranscriptMemory` disk writes still run on Main
   and can stall UI under a large offline outbox; profile before changing persistence.
6. **Failure UX:** SDK/native failure paths are compiled/source-reviewed; the JVM
   fakes test protocol and ownership, not platform internals. The visible error is
   sufficient to report failure while audio is unavailable, but does not guarantee
   a spoken warning with the screen locked. Doze/reboot/exact-alarm denial and
   missing offline TTS need the existing cooking-timer acceptance checklist.

The ViewModel remains the service composition root; feature engines already live
in separate controllers. This review extracts its local dispatch/precedence and
DAT job ownership where tests need an independent boundary. MainActivity remains
bindings and Compose UI, so a broader rename/rewrite was not justified before the
first device test. No push or merge is part of this work.

## Delivery status

Commit was attempted but staging was blocked by the execution sandbox denying
creation of the shared worktree `index.lock`. All 17 reviewed files remain local
and uncommitted; nothing was pushed or merged. `/private/tmp/androidreview-commit.sh`
contains the exact staging/commit command and the requested co-author trailer.
The optional workflow checkpoint was not saved because its helper has no configured
host ID. Neither limitation changes the build, test or real-API results above.
