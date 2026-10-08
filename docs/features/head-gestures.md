# Head gestures

Implemented and verified **2026-10-07**. Nod for yes and shake for no during a
Gemini yes/no confirmation. Raw IMU readings stay on the phone; Gemini receives
only `[gesture: nod = yes]` or `[gesture: shake = no]` as a complete text turn.

## Use

1. Enable the experimental Motion capability for the app in Meta Wearables
   Developer Center. Register/connect the glasses as usual; there is no extra
   DAT runtime permission dialog for motion.
2. Turn on **Head gestures · confirmations**, then **Start** a conversation.
   Works in Auto, Push-to-talk and Over; motion shares the capture-button DAT
   session with Over speech recognition.
3. Wait for **● Live · nod yes / shake no**. Hold still for at least a quarter
   second, then nod down and back, or shake left–right–center. Either direction
   works. Pause briefly at the end. **Last gesture** shows detection and whether
   it was sent. Movements outside a pending question are detected for calibration
   but are not sent.
4. Try by voice: “Ask me whether I want the color blue, and let me answer with a
   head gesture.” After Gemini asks the question, nod or shake. For notes, ask
   “Add this note to Work Log.” If the document needs disambiguation, Gemini may
   ask about one specific title and accept a gesture. A nod cannot choose among
   several unnamed documents; answer that kind of question by voice.
5. Use **Sensitivity** to calibrate: higher means a smaller angular excursion
   is accepted. Test comfortable movements while watching Last gesture. Turn
   gestures off to detach Motion immediately. Backgrounding retains the service-owned DAT session. Capture HOLD stops
   the conversation but retains armed controls; app/notification Stop disarms
   and releases the shared session. Call/focus pauses clear pending confirmations. Enablement and sensitivity persist locally.

There is no voice command to change the setting. The model uses the local
`await_gesture_confirmation(question)` tool before speaking a yes/no question.
Its response says ready/unavailable; it never writes or deletes anything. The
backend's `forget` tool also accepts a valid nod to its specific pending
confirmation before deleting the selected saved facts.

## Configuration and behavior

| Setting/check | Value |
|---|---|
| Initial setting | Off; sensitivity 50% |
| IMU | `mwdat-motion:1.0.0`, 30 Hz, glasses-source gyro + accelerometer |
| Axes | X pitch, Y yaw, Z roll, based on Meta sample measurements |
| Angular excursion threshold | 22° at 0%, 16° at 50%, 10° at 100% |
| Nod | Out-and-back pitch excursion, return near neutral, opposite rate lobes |
| Shake | Both sides of neutral, at least two rate reversals, return near neutral |
| Quiet/start | ≥250 ms quiet; gyro <8°/s at rest, ≥20°/s to start |
| Duration | 280–1,400 ms movement; 160 ms settled ending; 1,600 ms total cap |
| Cooldown | 1,200 ms after detection; 250 ms after rejection |
| Rejection | Acceleration peak >2 m/s² from gravity or RMS ≥0.85; strong cross-axis motion; >65° span; >300°/s; bad/gapped timestamps |
| Confirmation | One answer after model turn completion, ≤30 s from tool request |

The pure Kotlin detector low-pass filters angular velocity over 40 ms and
integrates only a short candidate movement. It requires gyro and accelerometer;
missing/invalid data and non-glasses sources never produce a gesture. Orientation
is optional and unused. The live indicator requires recent usable samples; after
one second without them, it drops to an unavailable status and clears the question.

Confirmation clears on speech, interruption, tool cancellation, a different tool
call, disconnect, motion loss, disabling, or timeout. A gesture is consumed even
if WebSocket enqueue fails, and is never queued for reconnect. PTT gestures do
not require holding Talk; an active Talk press prevents a gesture send. In
Over, a valid gesture closes the open audio activity and sends the complete text
answer, then waits for the reply and playback drain before listening again.
Sent gesture answers are included in the transcript memory outbox. No token
locking or resumption config was changed.

## Validation

```sh
cd android
./gradlew assembleDebug testDebugUnitTest
# BUILD SUCCESSFUL in 17s; 39 tests, zero failures/errors/skips

cd ../backend
uv run pytest
# 52 passed, 2 warnings in 2.78s
uv run python ../scripts/smoke_gestures.py
# PASS auto: /token and constrained WebSocket setup
# PASS auto: confirmation tool call and response
# PASS auto: [gesture: nod = yes] acknowledged as yes
# PASS ptt: /token and constrained WebSocket setup
# PASS ptt: confirmation tool call and response
# PASS ptt: [gesture: shake = no] acknowledged as no
```

Build environment: JDK 17 and the installed Android SDK. This sandbox required
`GRADLE_USER_HOME=/tmp/gestures-gradle-home`,
`ANDROID_USER_HOME=/tmp/gestures-android-home`, and
`UV_CACHE_DIR=/tmp/gestures-uv-cache` because shared home caches are read-only.
These are build-only overrides. No keys or signing files were added to Git.

## Limitations and hardware follow-up

Motion is experimental and unavailable for production release channels per
Meta's current docs. Hardware axis mapping and thresholds are not calibrated on
this user's glasses yet. Motion can reject intentional gestures while walking;
an incidental movement with the same waveform as a nod cannot always be
distinguished. Use the spoken fallback when the indicator is unavailable.

Validate nods/shakes, ordinary turns, walking/stairs, glasses removal, Bluetooth
loss, and pause/resume on real glasses before relying on it for confirmations.
Test at both ends of the sensitivity slider. Measure false positives and tune
with recorded traces if needed. An unexpected Motion stop can be retried by
toggling the setting; a terminal device-session stop needs Stop/Start. Real
append/forget actions and physical sensor-to-Gemini behavior remain unverified;
the real API smoke used a harmless synthetic question only.

## Verified sources

Checked 2026-10-07:

- [Meta motion API guide](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/motion/SKILL.md)
- [Meta session lifecycle](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/plugins/mwdat-android/skills/session-lifecycle/SKILL.md)
- [Motion 1.0.0 published AAR](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-motion/1.0.0/)
- [BirdSpotter measured body frame](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/BirdSpotter/app/src/main/java/com/meta/pixelandtexel/birdspotter/data/location/GlassesAim.kt)
- [Gemini Live WebSocket reference](https://ai.google.dev/api/live)

See [research and unresolved verification](../research/head-gestures.md).
