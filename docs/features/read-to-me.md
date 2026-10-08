# Read-to-me

Implemented **2026-10-07** on `feat/readtome`, based on `feat/vision`. Gemini Live
reads a fresh high-resolution glasses photo and speaks it through the current audio
route. No OpenAI calls or long-lived Gemini credentials are added to Android.

## Use it

Configure the existing backend and app, connect/register the glasses, tap **Grant
DAT camera permission**, then **Start**, as in [M4 vision](m4-vision.md).

| Say | Behavior |
| --- | --- |
| “Read this” / “Read everything visible” | Verbatim, in reading order |
| “Summarize this page” | Gist of the document or screen |
| “What's the Wi-Fi password on this card?” | Find a specific answer |
| “How much is the pad thai?” | Find the price in the visible menu |
| “Read this French text in English” | Full English translation |
| “Keep reading” | Next chunk of the current page |
| “Next page” | Fresh photo, next page number, same reading mode |
| “Repeat that” | Repeat the current chunk without recapturing |
| “Stop” | Pause reading and flush queued audio; keep current position |

Turn the page before saying “next page.” At page end the assistant asks for the
next page; it does not capture or advance automatically. After a spoken stop,
“keep reading” repeats the interrupted chunk. Tap the existing **Stop** button to
end the entire conversation and clear reading state. In PTT mode, use the existing
screen hold/release or glasses capture-button HOLD toggle for these same commands.
No new buttons or permissions are required.

## Implementation and configuration

- `read_text(mode, question?, retry?)` supports verbatim, summary, find, translate.
  Find requires a question. `reading_control(action)` handles continuation, and
  `submit_reading(page_id, text)` gives Android the model-extracted page text.
  These are client tools advertised by `/token`; `/tools` rejects their execution.
- Reuses the look capture pipeline, its serialization, 15-second capture timeout,
  shared 1 fps upload limit, idle close, cancellation, and image-bound tool responses.
- Text captures request `VideoQuality.HIGH`, upgrade an existing medium stream, and
  preserve the full returned photo at JPEG quality 95. HEIC is decoded at full size.
  DAT's API does not expose a photo-resolution selector; native sensor resolution is
  unverified. Ordinary look/watch upload dimensions remain unchanged.
- Before upload, grayscale pixels downscaled to a 512-pixel long side are checked
  with variance-of-Laplacian. Below 60, no image is sent. The assistant asks the user
  to hold still and retries up to twice (three attempts total), then asks for better
  framing/light and waits. Threshold lives in `GlassesCamera.checkSharpness` and
  needs hardware calibration. Errors and retries appear in the existing tool status.
- Page text is limited to 60,000 characters, split at whitespace into at most 600
  characters per chunk. App memory owns page number, text and chunk start/end.
  Explicit app Stop clears it; reconnect can retain it during the same conversation.
  Reading Stop also stops any active watch capture.
- Photos are transient and are not saved, logged, or retained for response replay.
  Page text and ordinary conversation/tool history remain in session memory. Gemini
  receives the image and text. There is no permanent memory write on this branch.
- No new environment settings or runtime dependencies. Existing `GEMINI_API_KEY`,
  `APP_SHARED_SECRET`, and optional `GEMINI_LIVE_MODEL` apply. Token locking and
  client-owned session resumption are preserved.

## Verification

Android `./gradlew assembleDebug testDebugUnitTest` passes with **39 JVM tests**;
backend `uv run pytest` passes with **59 tests** (two existing dependency warnings). New JVM checks cover blur detection, bounded retries, capture cancellation,
image-bound responses, shared rate limiting, argument validation, stale page IDs,
chunk reconstruction, repeat, pause/resume, next page, and clear. Backend checks
cover locked tools, mode enums, prompt policy, and rejection of local execution.

Run real synthetic-image checks from `backend/`:

```sh
uv run --with pillow python ../scripts/smoke_read_to_me.py
uv run --with pillow python ../scripts/smoke_read_to_me.py --mode ptt
uv run --with pillow python ../scripts/smoke_read_to_me.py --case menu-verbatim --controls
```

Pillow is a smoke-only dependency. The script renders a menu, Wi-Fi card and French
paragraph; it validates actual tool calls, submitted text, and completed output
transcripts. Auto and PTT token checks passed for all four modes. Menu result: **$14.50**;
Wi-Fi result: **River7429** (synthetic); French result: **“Welcome to our small
village. The library opens at nine o'clock. The market is closed on Sundays.”**
These exercise real Live sessions with text input and AUDIO output; the script
emulates Android's tool responses, not the camera or microphone.

## Limitations and follow-ups

Exact word position after an interruption is unavailable; resume repeats the current
chunk to avoid skipping unheard words. Gemini supplies OCR and speech, so omissions,
wrong ordering, or chunk-instruction violations remain possible. Unreadable text
should be marked `[illegible]`, and the assistant must admit uncertainty rather than
guess. Sharpness is scene-dependent and can produce false positives/negatives.

The real continuation probe exercised repeat, stop, resume, and a distinct second
page. An extra strict probe requiring a control tool call after an already exhausted
page did not complete; end-of-page state transitions are JVM-tested, but that Live
routing is unverified. Re-presenting the same photo can make Gemini say there is no
more text instead of submitting a new page.

No phone/glasses hardware was tested. Verify real capture dimensions, permissions,
HEIC orientation, HIGH stream performance, retry speech timing, background lifecycle,
and dense/column text on device. A full-resolution JPEG costs more bandwidth than
look. Long pages have a 60,000-character ceiling and no durable reading bookmark.

Optional backend OCR escalation and “remember this” integration are deferred:
this base branch has no memory service. Existing explicit “save a note” can still
use Google Docs; it is not advertised as permanent reading memory.

## Verified sources (2026-10-07)

- [DAT 1.0.0 camera artifact (inspected public API)](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-camera/1.0.0/)
- [Meta camera sample](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/camera/CameraViewModel.kt)
- [Gemini Live wire reference](https://ai.google.dev/api/live)
- [Image-bearing FunctionResponse schema](https://ai.google.dev/api/generate-content#FunctionResponse)
- [Android Bitmap](https://developer.android.com/reference/android/graphics/Bitmap), [ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)

See [research notes](../research/read-to-me-notes.md) for evidence and unverified items.
