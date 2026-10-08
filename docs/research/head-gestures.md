# Head gestures: DAT motion and Gemini confirmations

Verified **2026-10-07 (America/Toronto)**.

## DAT evidence

Cloned https://github.com/facebook/meta-wearables-dat-android to
`/tmp/gestures-meta-wearables-dat-android`; HEAD was
`d3159f738676221181577dd745d83d10b6ab46ba`. Read its motion and session lifecycle
notes, BirdSpotter's motion integration and body-frame measurements. Downloaded
the actual 1.0.0 AAR from Maven Central and inspected public signatures with
JDK 17 `javap`; the app and adapter tests compile against that artifact.

- [Motion documentation](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/motion/SKILL.md):
  experimental capability, enabled in Developer Center, no runtime `Permission`.
  Attach using `session.addMotion(MotionConfiguration(MotionSamplingRate.HZ_30))`
  after the session reaches STARTED. Collect samples/state/errors before explicit
  `motion.start()`. Cancel collection, stop, then `session.removeMotion()` on cleanup.
  Parent pause/resume propagates to motion. Supported rates include 30 and 60 Hz.
- [Published motion artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-motion/1.0.0/):
  `MotionSample` has `timestampNs`, nullable `accelerometer`, `gyroscope`,
  `magnetometer`, `orientation`, and `source`. `Vector3` components are floats;
  `Quaternion` is x/y/z/w. Timestamp is device-monotonic nanoseconds, gyro is rad/s,
  acceleration is m/s². Filter `MotionSource.GLASSES`, not Neural Band/UNKNOWN.
- [Session lifecycle](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/session-lifecycle/SKILL.md):
  capabilities share a `DeviceSession`; STOPPED is terminal. This implementation
  attaches motion to the existing capture-button session rather than opening a
  competing session. Paused/stopped/missing samples clear gesture confirmation.
- [BirdSpotter body frame](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/BirdSpotter/app/src/main/java/com/meta/pixelandtexel/birdspotter/data/location/GlassesAim.kt):
  its authors measured +Y up, X lateral, -Z forward. **Inference:** gyro X is
  pitch, Y yaw, Z roll. Both signs are accepted. This is sample evidence, not a
  documented cross-device coordinate guarantee. Short gyro integration avoids
  relying on an undocumented fused-quaternion frame or heading wrap convention.
- [BirdSpotter session integration](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/BirdSpotter/app/src/main/java/com/meta/pixelandtexel/birdspotter/data/dat/DatGlassesSessionRepository.kt):
  confirms explicit motion start and nullable sensor/source handling. Motion may
  stop under capability contention. This feature surfaces status; toggle motion
  to reattach, or Stop/Start the conversation after a terminal device-session stop.

The Meta `llms.txt?full=true` page returned a login page on this date; the cloned
public documentation and published binaries supplied the verification instead.

## Gemini evidence

[Live WebSocket reference](https://ai.google.dev/api/live) documents a complete
text turn via `clientContent.turns[].parts[].text`, role `user`, and
`clientContent.turnComplete=true`. It starts a response independently of audio
VAD, including when PTT disables automatic activity detection. The reference
also documents tool responses and cancellation. The app's constrained endpoint,
header auth, minimal setup, and client-owned sessionResumption remain unchanged.

The backend advertises `await_gesture_confirmation(question)`; Android handles
it locally. A ready response is capability availability, never consent. The
client waits for the model's completed turn and then accepts one answer for the
specific question, up to 30 seconds from the tool request. Model/user
interruption, new non-gesture tool calls, speech, cancellation, disconnect,
missing motion, disabling gestures, or expiry clear that question.

`scripts/smoke_gestures.py` exercised real `/token` creation and the constrained
WebSocket with the real backend prompt/tools in both modes. Gemini called the
new tool, accepted its ready response, and acknowledged `[gesture: nod = yes]`
as yes (auto) and `[gesture: shake = no]` as no (PTT). Synthetic color preference
only; no Google Docs writes or memory deletion. No credentials were printed.
This smoke does not exercise resumption or Android Bluetooth hardware.

## Validation and unverified

- Android `assembleDebug testDebugUnitTest`: **39 tests, 0 failures/errors/skips**.
  Includes 19 new detector, confirmation, wire-format and real-DAT adapter tests.
- Backend `uv run pytest`: **52 passed**, two existing upstream warnings.
- Synthetic checks cover direction, 30/60 Hz and jitter, noise/bias, walking,
  slow/fast/large/small motion, turning/glancing, roll/cross-axis motion, cooldown,
  invalid samples, discontinuous clocks, sensitivity, stale confirmations and
  failed sends. These establish algorithm behavior, not measured human accuracy.
- **Unverified:** actual glasses gyro axis mapping across models/firmware,
  entitlements, timing under Bluetooth load, pause/resume/cleanup on hardware,
  false-positive/false-negative rates while walking, comfort and battery use.
- **Unverified:** physical user nod → Android → Gemini end to end and real
  append/forget confirmation. The base branch has no forget tool; its future
  confirmation can use the same client tool without changes to the detector.
- No IMU-only detector can distinguish every intentional nod from an identical
  incidental motion. These conservative gates intentionally reject some valid
  gestures; a spoken answer remains available.
