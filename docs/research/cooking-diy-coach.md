# Cooking / DIY coach research

Verified **2026-10-07**. Implementation: [feature guide](../features/cooking-diy-coach.md).

## Gemini extraction and Live compatibility

Sources: [structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output),
[3.5 Flash capabilities](https://ai.google.dev/gemini-api/docs/whats-new-gemini-3.5),
[Python SDK](https://googleapis.github.io/python-genai/).

Real `google-genai` 2.28.0 calls to `gemini-3.5-flash` accepted a small
`response_json_schema` with nested ingredients, equipment, and steps. The synthetic
sample returned three steps/ingredients, preserved 200 g pasta, and represented
eight minutes as 480 seconds. `scripts/smoke_coach.py` repeats this check without
printing credentials or creating a persisted project. Provider output is validated
against bounded Pydantic models before persistence.

Initial `response_schema=Project` with `extra="forbid"` failed: this SDK emitted
`additional_properties` in its typed schema, rejected by the service. Both the full
raw JSON schema and the constrained typed schema without extra-field restrictions
also received HTTP 400. Minimal schemas succeeded. The final code uses a deliberately
small wire schema and strict local validation. The precise cause of the generic 400
for the larger schema is unverified; do not assume every Pydantic constraint can be
sent unchanged to this model/API combination. A real SDK serialization test protects
the final request shape; automatic function calling is disabled for extraction.

The existing real vision smoke passed with all new declarations present in the
locked token config: `/token`, constrained WebSocket minimal setup, `look`, realtime
JPEG plus image-bound tool response, and a spoken blue-mug/orange identification.
Auto mode was exercised. The coach does not modify `lock_additional_fields=[]`,
`sessionResumption`, WebSocket endpoint, or audio encoding.

## Android alarm and speech API

Sources: [alarm guide](https://developer.android.com/develop/background-work/services/alarms),
[AlarmManager](https://developer.android.com/reference/android/app/AlarmManager),
[foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech).

Verified platform API contract: `setAlarmClock` is an exact alarm outside the
activity lifetime, requires exact-alarm access on current Android, and can start a
foreground service when firing. The implementation checks permission, persists named
timers, registers boot/time-change/permission-grant receivers, and uses a short
`mediaPlayback` service for local speech. Notification permission is requested by
the activity. TTS engine discovery requires a manifest query on modern Android;
the service selects an offline voice and queues simultaneous announcements.

Compilation, JVM policy tests and lint passed against SDK 36/JDK 17. These checks
are not evidence of real alarm timing or glasses audio routing. Permission flows,
Doze, reboot, TTS and audio coexistence require device verification.

## DAT Inputs finding and Motion integration (2026-10-07)

Source: published [mwdat-inputs 1.0.0](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-inputs/1.0.0/)
AAR, inspected in Gradle's cache using JDK 17 `jar tf` and `javap`.

`InputEvent` subclasses are `Back`, `Button`, `Capture`, `Drag`, `Nav`, and `Select`.
`InputSource` contains `CAPTOUCH`, `NEURAL_BAND`, `CAPTURE_BUTTON`, `ACTION_BUTTON`,
`NEURAL_BAND_DRAG`, and `UNKNOWN`. No nod/head-motion event or source is published
there. The original branch therefore returned unsupported. During the merge,
main already provided a detector through the separate DAT Motion capability; see
[verified Motion APIs and source URLs](head-gestures.md). The merged app reuses
that single stream for nod-next/shake-repeat, with confirmation precedence and
workout suppression. Vision watch is never treated as a head-motion sensor.
This is integration of existing verified APIs, not a new hardware validation.

## Unverified

- Physical nod/shake-to-project navigation, sensor calibration and combined
  Motion/camera/Speech/audio behavior on the user's glasses remain unverified.
- No connected Android hardware (`adb devices` empty). Real foreground-service
  launch, alarm notification/voice delivery during Live and without it, Doze,
  permissions, Bluetooth routes, reboot and force-stop recovery.
- End-to-end voice→look→page transcription→project, generated fridge recipes,
  URL extraction on arbitrary recipe sites, and DIY visual accuracy.
- Fraction math is exact, but Live's adjustment of split quantities embedded in
  natural-language steps is model-dependent. Neither food safety nor correct
  assembly can be established from an image alone.
