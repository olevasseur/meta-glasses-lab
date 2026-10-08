# M4 vision — “what am I looking at?”

Implemented and verified against the APIs on **2026-10-07**. Runtime AI remains
Gemini Live; API keys stay on the backend.

## Use it

1. Configure and start the backend and debug Android app as described in
   [android/README.md](../../android/README.md). Connect/register the glasses.
2. Before starting a conversation, tap **Grant DAT camera permission** and approve
   the Meta permission dialog. This uses the glasses camera, not the phone camera.
3. Tap **Start**. Say **“What am I looking at?”**, **“Read this”**, or **“What color
   is this?”**. In PTT mode use the screen or touchpad double-tap controls to
   finish the question. Gemini calls `look(question)` and answers using a fresh photo.
4. Say **“Turn on watch mode”** or enable the **Watch mode** switch to send frames
   continuously. Say **“Stop watching”**, disable the switch, or tap **Stop** to end
   it. Watch mode automatically ends after two minutes. Repeating “on” while it is
   active does not extend that limit.

The camera status and last tool result are visible. Denied permissions, no
connected glasses, capture timeout, and a disconnected/busy Gemini connection
produce an error rather than a false `frame_sent` result. A watch startup or
capture failure turns its switch off and displays the error.

## Behavior and configuration

`GlassesCamera` lazily attaches DAT's camera capability to the same device session
as capture-button Inputs, head-gesture Motion and Over Speech. It starts a
medium-quality, 2 fps DAT stream and uses `Stream.capturePhoto()`. The photo is encoded off the UI thread as JPEG quality
80 with a maximum 1024-pixel long side, preserving aspect ratio without upscaling.
HEIC is decoded through Android `ImageDecoder`. No camera audio is requested.

`VisionController` serializes captures, limits **all** realtime frames (including
concurrent look/watch requests) to at most 1 fps, allows 15 seconds for capture,
and closes the camera five seconds after the last capture when idle. Watch mode
targets 1 fps, but slow capture/encoding lowers that rate rather than queuing a
backlog. Conversation Stop, disconnect, call/focus pause, or the two-minute timer
cancels capture and closes the camera. Ending vision preserves the shared
Inputs/Motion/Speech session; otherwise the now-unused DAT session is stopped too. Disabling gestures
detaches Motion while retaining Inputs and the shared session until Stop, so it
cannot interrupt an active look/watch capture. Timers are constants
in `VisionController.kt`; JPEG and stream settings are in `GlassesCamera.kt`.

`POST /token` advertises `"client_tools":["await_gesture_confirmation","look","set_watch_mode"]`.
Vision declarations live in `backend/vision.py` and join the existing registry in
`backend/tools/__init__.py`; all seven tools are part of the locked token config
in Auto, PTT and Over.
Android executes known local tools locally, refuses unsupported advertised client
tools, and sends other tools to `/tools/{name}`. The backend rejects vision tool
execution with HTTP 400. An older backend token without `client_tools` still
parses; its model will not have the new declarations. No new environment variables
are required; the existing `GEMINI_API_KEY`, `APP_SHARED_SECRET`, and optional
`GEMINI_LIVE_MODEL` configuration applies. Vision was verified with `gemini-3.8-live`.

The app sends the JPEG as:

```json
{"realtimeInput":{"video":{"mimeType":"image/jpeg","data":"<base64>"}}}
```

Then it replies to the matching tool call with `{"status":"frame_sent"}`. The
same JPEG also appears in `functionResponses[].parts[].inlineData`. **This is
intentional:** real tests of realtime video plus a bare status repeatedly yielded
only “taking a look” acknowledgements. Binding the image to the function response
produced actual descriptions in both auto and PTT token modes. Watch frames use
only realtime input. No extra AI request or synthetic user turn is required.

Captured photos are not saved to disk, logged, sent to `/tools`, or retained in
the tool-response replay cache. A lost/duplicate visual response returns an error
requesting a fresh look instead of replaying a stale success. Gemini receives the
image in its conversation context. The duplicated image attachment for a look
increases its upload size; watch frames are sent once.

The backend keeps `contextWindowCompression.slidingWindow={}` enabled. Google's
documented limits without compression are 15 minutes for audio-only sessions and
2 minutes for audio/video; compression extends session duration. Connections still
roll over around ten minutes. The existing resumption handling and
`lock_additional_fields=[]` remain unchanged; `sessionResumption` is client-owned.
The two-minute watch cap is an app battery/lifecycle policy, not a replacement for
compression or resumption.

## Mock and real checks

With the conversation stopped, enable **Mock glasses (phone audio)**, then Start
and ask the same visual question. `mwdat-mockdevice` receives a bundled original
synthetic image (blue mug and orange) through `setCapturedImage`, plus a short
H.264 loop through `setCameraFeed`. DAT requires the video source even when only
photo-from-stream capture is used. This exercises the DAT capture path rather
than returning fixture bytes directly from the vision controller. The files are
`android/app/src/main/res/raw/vision_sample.jpg` and `vision_stream.mp4`.

The repeatable real API check needs the worktree's configured `.env`:

```sh
cd backend
uv run python ../scripts/smoke_vision.py
uv run python ../scripts/smoke_vision.py --mode ptt
```

It calls the real `/token` handler in-process, connects with an ephemeral token,
waits for Gemini's `look` call, sends the bundled JPEG and image-bound tool
response, and requires the spoken transcript to identify a blue mug/cup and
orange. It never logs credentials, captures private images, or writes Google Docs.
Both modes passed on 2026-10-07; an auto-mode answer was “You're looking at a blue
mug filled with a dark beverage, and an orange next to it on the table.”

Local checks passed: Android `./gradlew assembleDebug testDebugUnitTest` (32 JVM
tests) and backend `uv run pytest` (54 tests). JVM tests cover wire encoding,
image dimensions, tool routing, argument validation, frame-before-ack ordering,
shared rate limiting, idle shutdown, timeout, concurrent captures, cancellation,
watch failure, and the two-minute cap. Backend tests cover locked declarations,
execution metadata, resumption-field ownership, and rejection of local execution.

Merged verification (2026-10-07): backend `uv run pytest` **103 passed, 2 warnings**;
Android `./gradlew assembleDebug testDebugUnitTest` **BUILD SUCCESSFUL**, **79 tests,
zero failures/errors/skips**. Tests require all seven tools and client ownership
metadata in Auto/PTT/Over, preserve routing with older tokens, and retain the
memory prompt budget and latest summary under the larger combined prompt.
Builds used writable temporary uv/Gradle/Android caches in the sandbox. Combined
camera/Motion/Speech/HFP behavior still needs physical-device verification.

## Limitations and unverified behavior

- No Android device was connected (`adb devices` was empty). Real glasses capture,
  HEIC orientation, permission dialogs, mock resource decoding, HFP plus camera
  coexistence, thermal behavior, and battery consumption need on-device testing.
- Auto/PTT **token configuration** and visual answers were exercised over the real
  API with a text question. Spoken input, continuous watch over two minutes, and
  camera-enabled connection resumption were not exercised against real devices/API.
- The foreground service retains conversation/camera work across backgrounding
  and rotation. A call/focus pause ends watch and clears captures; enable watch
  again after resuming. See [hands-free operation](hands-free.md).
- Watch mode supplies context; it does not promise a spoken description of every
  frame. A connection failure ends watch mode; the user must enable it again.
- Fine text and distant details may be lost at the chosen resolution/compression.
  The 1 fps upload target does not imply the DAT stream itself consumes only 1 fps.

## Verified sources (2026-10-07)

- [Meta CameraAccess camera lifecycle/capture sample, inspected commit](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/camera/CameraViewModel.kt)
- [Meta CameraAccess mock camera setup](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/mockdevicekit/MockDeviceKitViewModel.kt)
- [Gemini Live wire reference](https://ai.google.dev/api/live), [video capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities), [session limits/compression](https://ai.google.dev/gemini-api/docs/live-api/session-management)
- [FunctionResponse parts schema](https://ai.google.dev/api/generate-content#FunctionResponse), [multimodal function responses](https://ai.google.dev/gemini-api/docs/generate-content/function-calling#multimodal)
- [Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder), [Bitmap JPEG encoding](https://developer.android.com/reference/android/graphics/Bitmap)

See [M4 research notes](../research/m4-vision-notes.md) for the integration evidence.
