# M4 vision protocol and DAT camera verification

Date: **2026-10-07 (America/Toronto)**. Feature details and test instructions:
[M4 vision](../features/m4-vision.md).

## DAT 1.0.0

Cloned `https://github.com/facebook/meta-wearables-dat-android` into `/tmp` (the
sparse inspection checkout is `/tmp/mgl-vision-dat-sparse`), commit
`d3159f738676221181577dd745d83d10b6ab46ba`. Read CameraAccess and inspected the
installed `mwdat-camera`, `mwdat-core`, and `mwdat-mockdevice` 1.0.0 AAR signatures
and relevant implementation bytecode with `javap`.

- `Wearables.createSession(selector)` / `session.start()` precede
  `session.addCamera(StreamConfiguration(...))`. Use `camera.stream.start()`, wait
  for `StreamState.STREAMING`, then `stream.capturePhoto()`. Its `DatResult`
  contains `PhotoData.Bitmap` or `PhotoData.HEIC` (ByteBuffer).
- Close the camera capability on shutdown. Stopping only its stream leaves the
  capability attached and may prevent a subsequent `addCamera`.
- Core rejects another active session for the same device with
  `SESSION_ALREADY_EXISTS`; M4 shares the existing Inputs session. An idle camera
  must not stop capture-button input.
- DAT `Permission.CAMERA` is separate from Android's phone-camera permission.
  M4 uses the existing `Wearables.RequestPermissionContract` from the activity.
- `MockGlasses.services.camera.setCapturedImage(Uri)` supplies photo captures.
  A stream also needs `setCameraFeed(Uri)` with a supported video file (or a
  phone-camera source). SDK bytecode explicitly rejects a stream without a source.
  M4 uses an original synthetic JPEG and matching short H.264 loop.

Sources:
[CameraViewModel.kt](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/camera/CameraViewModel.kt),
[MockDeviceKitViewModel.kt](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/mockdevicekit/MockDeviceKitViewModel.kt),
[Maven 1.0.0 camera artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-camera/1.0.0/),
[core artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-core/1.0.0/),
[mock artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-mockdevice/1.0.0/).

## Gemini wire and observed tool behavior

The current [Live wire reference](https://ai.google.dev/api/live) defines
`realtimeInput.video` as a Blob. The [capabilities guide](https://ai.google.dev/gemini-api/docs/live-api/capabilities)
shows JPEG bytes with MIME `image/jpeg` and a maximum of 1 frame/second. M4 uses
base64 JSON and the previously verified constrained WebSocket endpoint,
`Authorization: Token <name>`, and minimal model/client-owned resumption setup.

**Real behavior matters:** sending a JPEG through realtime video while handling
`look`, followed only by `{"status":"frame_sent"}`, repeatedly produced an
acknowledgement without a visual description. A direct image question worked.
The reference warns that realtime modalities and interleaved client content are
not guaranteed to have the same ordering/context behavior. That warning is
consistent with the observation, but the service's internal cause is unverified.

M4 therefore also binds the same JPEG to the matching tool response:

```json
{"toolResponse":{"functionResponses":[{
  "id":"<call id>","name":"look","response":{"status":"frame_sent"},
  "parts":[{"inlineData":{"mimeType":"image/jpeg","data":"<same base64>"}}]
}]}}
```

`FunctionResponse.parts[].inlineData` is present in the
[API schema](https://ai.google.dev/api/generate-content#FunctionResponse) and
[multimodal function-response documentation](https://ai.google.dev/gemini-api/docs/generate-content/function-calling#multimodal).
**Live acceptance and useful output were verified** with `gemini-3.8-live` and
both auto and PTT ephemeral-token configurations. The final implementation uses
ordinary blocking tool declarations, without a new user turn or artificial delay.
The response body remains exactly `{"status":"frame_sent"}`. The screenshot
bytes are transient; only a retry-needs-fresh-look error is retained for replay.

`scripts/smoke_vision.py` uses the real in-process `/token` handler and requires a
completed output transcript identifying the fixture's blue mug/cup and orange.
One PTT result: “You're looking at a blue mug filled with a dark drink, and next
to it sits an orange with a small green leaf on top.” No credential values or
private captures were logged. No Google Docs calls ran.

The [session guide](https://ai.google.dev/gemini-api/docs/live-api/session-management)
documents 15-minute audio-only and 2-minute audio/video limits without compression,
and roughly ten-minute connection lifetimes. Existing sliding-window compression
and resumption are preserved. The app's 120-second watch timer independently
limits camera use. `lock_additional_fields=[]` still locks populated config fields
while leaving `sessionResumption` client-owned.

## Unverified

- No phone/glasses were attached. Device camera permission, SDK mock playback and
  capture at runtime, shared Inputs/camera sessions, HEIC orientation, real HFP
  coexistence, power use, and physical camera latency remain hardware checks.
- JPEG dimensions/routing/timers/cancellation are unit-tested; Android's actual
  encoder and HEIC decoder were compiled but not executed on-device. Sources for
  the platform APIs: [ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)
  and [Bitmap](https://developer.android.com/reference/android/graphics/Bitmap).
- Real checks use text to request a visual answer under auto and PTT tokens;
  actual microphone turn detection/PTT activity, two minutes of continuous video,
  video-enabled resumption, and provider usage/cost measurement remain unverified.
- Multimodal tool parts were verified only with the configured default model.
  Overrides through `GEMINI_LIVE_MODEL` need their own smoke test.
