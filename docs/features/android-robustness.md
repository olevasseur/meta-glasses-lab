# Android robustness before first hardware test

Reviewed and implemented **2026-10-08**. See [the findings and interaction matrix](../android-review.md)
for exact scenarios, code locations, tests and residual risks.

## What changes

Paused conversations refuse newly arriving Gemini tool requests, including backend
writes, and show the rejection in Last tool result. Already dispatched work may
finish; it is never automatically retried. Stale microphone reconnect callbacks
cannot stop a replacement session. Immediate local-tool completion cannot leave
an old job blocking a new session's call ID. Audio teardown attempts every resource
release even when another release fails, and remains safe under coroutine cancellation.

DAT controls serialize session teardown/startup. Motion is detached while merely
armed, paused, stopped, translating, or capturing a meeting. A network reconnect
alone retains local workout motion. Unexpected camera failures reach the persistent
visible error field. Optional voice-launch startup failure leaves the ordinary
Start path available.

## Voice and buttons

Use the existing **Start**, **Stop**, **Resume**, mode selector, translation,
meeting, visual-memory, watch and gesture controls. Capture HOLD and double-tap
retain their documented mappings; no new commands or setup switches were added.

- Calls/focus/device pauses take precedence over conversation audio and new tools.
  After resolving the blocker, use **Resume** when shown. Repeat a request that
  was rejected while paused.
- **Start meeting** overrides saved Auto/PTT/Over turn control and exits Translator.
  Stop the meeting before asking to start a workout, read-to-me, watch, visual
  memory or gesture navigation. An addressed one-shot **“Hey assistant, look”**
  or progress check is still allowed. Say **“Hey assistant, stop the meeting”**
  or tap **Stop meeting & prepare minutes** to return to normal conversation.
- Translator remains exclusive of assistant tools. Select **Assistant** or say
  **“Stop translation”** before starting those features.
- Stop/disconnect/pause closes camera capture through the existing vision/reading
  lifecycle. Watch and visual memory require explicit re-enabling after a pause.
- Rep workouts own Motion instead of gesture confirmation/navigation. Spoken
  confirmation is the fallback. Timed workouts do not claim gesture ownership.
- Explicitly scheduled cooking timers remain independent alarms; stopping the
  conversation does not cancel them. Their TTS speech is not governed by Gemini's
  meeting/translation silence gate. Use **“Cancel the pasta timer”** if needed.

## Configuration and limits

No new dependencies, environment variables, Android permissions, or AI providers.
Use the existing gitignored `android/local.properties` backend URL/shared secret,
backend `.env`, and DAT developer registration. Grant microphone access; grant
Nearby devices for glasses, notifications for visible background controls, and
optional Phone permission for explicit call detection. DAT camera/microphone
consent remains separate. No Android CAMERA or location permission is added:
images come from DAT and visual location is a manually supplied label.

The service still survives Activity recreation and backgrounding, uses
START_NOT_STICKY, and requires explicit restart after process death. Existing
durable transcript/workout outboxes recover uploads; live context and reading
state do not survive process death. Gemini keys and Google credentials remain
backend-only. Token locking and minimal resumption setup are unchanged.

JVM tests and real provider setup do not establish hardware readiness. First test
HFP echo, call interruptions, route restoration, DAT permissions, background
capture and Stop on the Pixel/glasses. Platform cleanup, battery draw and native
routing are still unverified. See the report's ordered acceptance checklist.

## Verified sources and validation

**2026-10-08:** [Android foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types),
[Android 16 target changes](https://developer.android.com/about/versions/16/behavior-changes-16),
[audio focus](https://developer.android.com/media/optimize/audio-focus),
[AudioRecord](https://developer.android.com/reference/android/media/AudioRecord),
[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[coroutine cancellation](https://kotlinlang.org/docs/cancellation-and-timeouts.html).
The [research record](../research/android-review-notes.md) distinguishes current
source checks, preserved DAT/Gemini evidence, real provider results and unverified
hardware behavior. Build/test totals and output tail are in the review report.
