# Workout coach protocol and motion reuse

Date: **2026-10-07**.

Rechecked [Gemini Live WebSocket reference](https://ai.google.dev/api/live):
`clientContent.turns` with `role=user`, text parts and `turnComplete=true` starts
a model response independently of audio VAD. It can interrupt current model
generation. Workouts therefore reuse the existing gesture text encoder and
send sparse milestones, not every sensor sample. Timer scheduling is local;
Gemini speaks the phase/countdown cues with network/model latency. The
constrained endpoint, `Authorization: Token` header and resumption ownership
remain as verified in [M1](m1-android-notes.md) and [M2](m2-backend-notes.md).

Rechecked the pinned [Meta Motion guide](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/motion/SKILL.md)
alongside the existing [head-gesture SDK inspection](head-gestures.md). Motion
is an experimental capability attached to the existing DAT DeviceSession.
Workout and gestures share one adapter/stream; no second addMotion call.
The Android build continues to use the real
[mwdat-motion 1.0.0 artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-motion/1.0.0/).

Detector choices are **engineering heuristics**, not externally validated
exercise classifiers. Acceleration magnitude minus 9.80665 approximates vertical
acceleration only when vertical movement dominates. Pitch uses the existing
adapter's X-axis mapping, based on the Meta sample evidence recorded in
head-gestures.md. The user selects a profile; a bipolar cycle, bounded duration,
cross-axis limits, and adaptive amplitude gates detect repetitions.

## Real API check

`scripts/smoke_workout.py` passed with real ephemeral tokens in Auto and PTT.
Gemini called start/status/stop/history, accepted simulated local tool responses,
and spoke encouragement after `[rep 5/12 squats]`. History used actual backend
dispatch and synthetic idempotent uploads in a disposable memory database.
The first smoke assertion incorrectly required speech at the tool turn's
completion; waiting for the subsequent spoken response fixed the harness.
No runtime protocol change was needed. Credentials/provider payloads were not
printed. Local detector-to-phone-to-glasses behavior remains outside this test.

## Unverified

- Human rep accuracy for all profiles, real-glasses axis calibration and
  sensitivity. In particular burpees have several pitch phases and may count
  differently from the simple synthetic model; jacks assume two jumps per rep.
- Universal walking/running/incidental-motion rejection. Identical waveforms
  are indistinguishable with these sensors alone; synthetic gait tests are
  bounded evidence.
- Physical DAT capability sharing, sensor loss/recovery, Bluetooth audio,
  battery, lifecycle behavior and actual spoken cue latency.
- Real session rollover/resumption during a workout. Configuration ownership is
  unchanged and covered by backend tests; real smoke uses new sessions.
