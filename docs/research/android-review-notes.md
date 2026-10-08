# Android robustness review evidence

Verified **2026-10-08** (America/Toronto), for targetSdk/compileSdk 36 and DAT 1.0.0.
Runtime inference remains Gemini. Earlier implementation notes are historical;
`docs/architecture.md` and the current source resolve their superseded claims.

## Platform references checked

- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): microphone requires RECORD_AUDIO and the microphone FGS declaration/permission; connected-device use requires an eligible permission such as BLUETOOTH_CONNECT. Microphone startup has while-in-use restrictions. The existing manifest and visible Activity startup match this design.
- [Android 16 target changes](https://developer.android.com/about/versions/16/behavior-changes-16): reviewed for target 36 compatibility; no missing new service type was established for this remote-device conversation path.
- [Audio focus](https://developer.android.com/media/optimize/audio-focus): target 35+ focus requests require the top app or a foreground service. ConversationService promotes itself before requesting focus.
- [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord): client sample rate, blocking read, stop and release. Requested 8 kHz is a client PCM format, not proof of the glasses' negotiated Bluetooth link rate. Existing 8→16 kHz interpolation and 16 kHz passthrough remain intact.
- [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack): streaming/nonblocking write, playback head and pause/flush semantics. Existing output is PCM16 mono at 24 kHz. A failed flush must not escape a WebSocket callback and terminate its dispatcher thread.
- [Coroutine cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html): cleanup that suspends needs a non-cancellable context. Audio cleanup now owns that guarantee and serializes simultaneous callers.
- Existing pinned [DAT session evidence](hands-free-background.md), [camera evidence](m4-vision-notes.md), and [M1 wire/audio evidence](m1-android-notes.md) were read alongside the actual application. No DAT upgrade or new capability API was introduced.

## Real Gemini check

Used backend's actual authenticated `/token` handler without application lifespan,
with a disposable Memory database. Tested Auto, PTT, Over, meeting, and Spanish
translator setup using the constrained v1beta endpoint, `Authorization: Token …`,
and only model plus `sessionResumption` in setup. All five succeeded.

Auto additionally received a harmless text request to say ready, returned audio,
`turnComplete`, and a resumable handle. Reconnecting with the same token and that
handle received `setupComplete`. The check establishes handle acceptance, not a
long conversation's retained semantics. No provider error, token, key or handle
was printed. No jobs, journal scheduler, Docs write or personal archive was used.
The temporary smoke harness was `/private/tmp/androidreview_smoke.py` and its log
was `/private/tmp/androidreview-smoke.log`; these are local validation artifacts.

Protocol sources: [Live WebSocket API](https://ai.google.dev/api/live),
[ephemeral tokens](https://ai.google.dev/gemini-api/docs/ephemeral-tokens),
[session management](https://ai.google.dev/gemini-api/docs/live-api/session-management).
These links also have the existing verified M1/M2 evidence; this review's new
provider evidence is the actual smoke above. Backend locking stays
`lock_additional_fields=[]`; session resumption remains client-owned.

## Unverified

- `adb devices` returned no attached device. No Android instrumentation, hardware
  audio/camera/Motion test, physical DAT mock playback, rotation, screen-off,
  permission revocation or Android 16 foreground-start test ran.
- Bluetooth HFP/SCO codec/rate, echo cancellation, acoustic barge-in delay,
  translation route restoration and underruns require a Pixel/glasses test.
- Real DAT permission/registration revocation, terminal session recovery and
  simultaneous camera/Motion/Speech capability behavior are not proved by JVM tests.
- Native resource release under platform exceptions compiles; the JVM lifecycle
  tests simulate ownership and callbacks, not AudioRecord/AudioTrack internals.
- Long sessions, natural goAway, fresh-token rollover, process kill/restart,
  battery/thermal measurements and timer speech during calls remain unverified.
