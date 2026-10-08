# Workout coach

Implemented and verified **2026-10-07**. Runtime AI remains Gemini. Rep detection
and timer scheduling run on the phone; raw IMU samples never leave it.

## Use

Start a conversation, keep the app foregrounded, and say:

- “Start 12 squats for three sets, with 30 seconds rest.”
- “Count ten push-ups.” Supported profiles: squats, push-ups, sit-ups, crunches,
  lunges, jumping jacks and burpees. Each lunge lowering/rising counts once;
  one jumping jack assumes two jumps (out and back).
- “Count reps of anything.” This uses the generic acceleration profile, one
  open-ended set. It needs a detectable head-motion cycle; it cannot count an
  exercise whose head stays still.
- “Time a 60-second plank.”
- “30 on, 15 off, eight rounds.” This selects intervals, 30 seconds working,
  15 resting and eight sets. No final rest is added.
- “Workout status,” “Stop workout,” or “How many push-ups did I do this week?”

The workout panel shows reps, completed sets, phase, remaining time and IMU
availability. **Stop workout** saves partial progress. **Rep sensitivity** tunes
the motion threshold and persists locally; higher accepts smaller movements.
Changing sensitivity clears the in-progress detection cycle.

Enable the experimental Motion capability in Meta Developer Center and connect
the glasses as described in [head gestures](head-gestures.md). Starting a rep
workout attaches to the existing capture-button session and the **same single
30 Hz DAT Motion stream** used by gestures. It does not require enabling the
gesture switch. Wait for **IMU live** before moving. With no motion, the workout
waits and counts no reps. Timed workouts also work with phone-only audio.

Gesture confirmations are suspended during a rep workout, including rest.
Ordinary gesture behavior returns after stopping/completing it. This prevents
exercise movements from answering a yes/no confirmation. Session stop,
backgrounding, and ViewModel cleanup stop the workout and retain partial logs.
There is no background workout service.

## Counting and cues

The user selects the exercise; this is not automatic exercise or form recognition.
The pure Kotlin detector filters over 60 ms, requires opposite signal lobes and
a return near neutral, and adapts its threshold to accepted peak amplitudes.
Squats/push-ups/lunges/generic use acceleration magnitude minus gravity as a
vertical-motion approximation. Sit-ups/crunches/burpees use pitch rate and an
integrated pitch excursion. Jumping jacks require two large acceleration cycles.
Cadence, cross-axis rotation, invalid data and sample gaps reject candidates.

Every detected rep updates the local count. Gemini receives complete text turns
only every five reps, on the final three reps, and at set/workout completion.
Example: `[rep 5/12 squats]`. A target rep combines its milestone and completion
in one event. Rest blocks rep counting and resets the detector before the next
set. Open-ended counting has one set and ends on Stop.

Timers use monotonic deadlines. Gemini speaks phase changes and one final
`[rest countdown: 3, 2, 1]` / `[work countdown: 3, 2, 1]` cue. Long UI delays skip
expired announcements instead of sending a backlog. **Spoken cues have Gemini
and network latency; they are not a precision metronome.** Disconnected,
backpressured, or active PTT sends are dropped rather than replayed later;
the visible timer/count continues. A reconnect retains the local workout.

## Tools, storage and configuration

`start_workout(exercise, target_reps?, sets?, work_seconds?, rest_seconds?)`,
`stop_workout()` and `workout_status()` execute locally. Exercise identifiers:
`squats`, `push_ups`, `sit_ups`, `crunches`, `lunges`, `jumping_jacks`, `burpees`,
`generic`, `plank`, `intervals`. Defaults: one set, 30 seconds rest, 30 seconds
working in timed modes. Limits: 1–100 sets, 1–1000 target reps, 1–3600 work seconds,
0–3600 rest seconds. Multiple rep sets require a target. Timed modes do not
accept a rep target; rep modes do not accept `work_seconds`.

The existing `.env`, backend URL and `X-App-Secret` are sufficient. No new
dependency, API key, AI provider or token configuration is introduced.
`lock_additional_fields=[]` and client-owned `sessionResumption` are unchanged.

`POST /workouts` writes the `workouts` table in `backend/data/memory.db` using
the existing memory lock and owner-only SQLite storage. Payload:

```json
{
  "workout_id": "b2eb9c2a-d3b3-4dd3-9f77-26a08f729903",
  "exercise": "push_ups",
  "started_at": "2026-10-07T14:00:00Z",
  "ended_at": "2026-10-07T14:02:00Z",
  "reps_per_set": [12, 7],
  "completed_sets": 1,
  "planned_sets": 3,
  "target_reps": 12,
  "work_seconds": null,
  "rest_seconds": 30,
  "active_seconds": 90,
  "status": "stopped"
}
```

Status is `completed`, `stopped` or `interrupted`. Timed sets have zero reps.
`active_seconds` is elapsed working-phase time, excluding rest; it does not
prove continuous physical activity. Identical normalized UUID retries return
`stored:false`; different data for a saved UUID returns 409.

The phone atomically snapshots reps/phase changes and at least every five seconds
to `noBackupFilesDir/workout-outbox`. Finished logs retry uploads every five
seconds while open. Relaunch recovers an unfinished snapshot as interrupted,
ending at its last saved time; the last few seconds can be lost on process death.
Failed uploads are reported and retained. History includes uploaded logs only,
so ask again after a pending upload succeeds. Disk-full failures are reported as
in-memory-only progress. There is no additional retention/erase UI.

`workout_history(period, exercise?)` is the authenticated backend tool at
`POST /tools/workout_history`. Periods: `today`, `this_week` (default, Monday
start), `this_month`, `last_7_days` (rolling), `all`. Calendar boundaries are UTC,
not phone-local time. Workouts are assigned by start time, including a workout
crossing midnight. Totals include partial stopped/interrupted workouts, grouped
by exercise, plus the latest 100 logs and a `truncated` flag. Aggregate totals
include all matching logs. Generic memory `forget` remains fact-only and does
not erase workout logs.

## Verification and limitations

- Android `assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL**, 72 tests,
  zero failures/errors/skips. New coverage: all exercise profiles with noisy
  synthetic signals, walking/cross-axis/impact rejection, both polarities,
  30/60 Hz, adaptation, invalid samples; sets, sparse milestones, rest, timer
  deadlines and delayed ticks; strict tool args; authenticated upload retries,
  interrupted recovery and unreadable-file retention.
- Backend `uv run pytest`: **114 passed**, two existing dependency warnings.
  Tests cover auth, validation, idempotency/conflicts, UTC boundaries, totals,
  filters, partial logs, truncation and unchanged token/resumption locking.
- Real `uv run python ../scripts/smoke_workout.py`: passed in **Auto and PTT**:
  constrained token/setup with all workout tools; Gemini `start_workout`,
  `[rep 5/12 squats]` spoken encouragement, `workout_status`, `stop_workout`,
  and `workout_history` calls/responses. Synthetic partial logs and identical
  upload replay used a disposable SQLite database; real user memory was untouched.
  Motion and local-tool results were simulated. This did not measure physical
  timer/countdown speech latency or rollover.
- Build used JDK 17, installed Android SDK, temporary Gradle/Android user
  caches and `--no-daemon` because inherited daemons have other worktree sandbox
  permissions. No credentials were printed or committed.

Hardware accuracy remains **unverified**. Synthetic fixtures establish expected
algorithm behavior, not accuracy on human workouts. Acceleration magnitude minus
gravity is not a true orientation-derived vertical component. Fast/very slow
reps, head stabilization, loosely fitted glasses, multi-phase burpees, hopping,
running, and incidental movements can miss or double-count. Walking rejection
is tested on specific synthetic gait signals; identical IMU waveforms cannot
be distinguished universally. Meta Motion remains experimental.

Follow up with recorded real-glasses traces for every exercise, walking/stairs,
sensor loss, Bluetooth contention, screen/background transitions and speech
latency; tune thresholds and validate counts against manual labels. No physical
glasses, microphone/audio route, calibrated form assessment or battery test was
performed during implementation.

## Verified sources

Checked **2026-10-07**:

- [Gemini Live WebSocket reference](https://ai.google.dev/api/live): tool responses
  and complete `clientContent` text turns. Reuses the verified
  [M1](../research/m1-android-notes.md)/[M2](../research/m2-backend-notes.md) wire
  contract and existing gesture text encoder.
- [Meta Motion guide, pinned commit](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/motion/SKILL.md)
  and [published DAT Motion 1.0.0 artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-motion/1.0.0/):
  existing adapter compiles against the real SDK; see the prior
  [head-gesture verification](../research/head-gestures.md).
- [Workout research and unverified list](../research/workout-coach.md).
