# Read-to-me verification

Date: **2026-10-07**. Based on the verified [M4 vision pipeline](m4-vision-notes.md).

## Capture and protocol

Inspected the installed DAT 1.0.0 camera AAR with `javap`: `VideoQuality` exposes
HIGH, MEDIUM, LOW; `StreamConfiguration` accepts that quality, and
`Stream.capturePhoto()` returns `PhotoData`. No separate sensor-resolution argument
is exposed by that method. Read-to-me requests HIGH and retains all returned photo
pixels, including full-size HEIC decoding, at JPEG quality 95. This is the largest
returned photo, **not a verified promise of the glasses sensor's native resolution**.
Ordinary look still scales to 1024 pixels and JPEG quality 80.

Sources inspected/rechecked:
- [DAT 1.0.0 published camera artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-camera/1.0.0/)
- [Meta camera sample](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/camera/CameraViewModel.kt)
- [Gemini Live wire reference](https://ai.google.dev/api/live)
- [FunctionResponse image parts](https://ai.google.dev/api/generate-content#FunctionResponse)
- [Android Bitmap](https://developer.android.com/reference/android/graphics/Bitmap)
- [Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)

The photo uses the existing realtime video plus image-bound function response.
New tools are included in the backend-owned locked configuration. The constrained
v1beta endpoint, `Authorization: Token <name>`, `lock_additional_fields=[]`, and
client-owned `sessionResumption` remain unchanged. No text-model endpoint or new
runtime dependency is needed; optional backend OCR and permanent memory are deferred.

## App-owned reading position

Gemini calls `submit_reading(page_id, text)` after seeing a captured page; Android
stores text and returns a <=600-character chunk. Continue/repeat/stop/next-page
operate on this local position. Generation IDs reject late page submissions.
Stopping speech preserves the current chunk; continuing repeats it because playback
has no reliable per-word acknowledgement. The app does not infer OCR position from
streaming speech transcripts. Text is untrusted data, never executable instructions.

A downscaled (512-pixel long side) grayscale Laplacian variance below 60 rejects the
photo. This is a heuristic, not a calibrated OCR-confidence score. The tool reports
retry_required; the model asks the user to hold still and calls read_text(retry=true),
with identical mode/question. Android permits two retries, then requires a new user
request. Invalid or blurry images are not sent to Gemini.

## Real API evidence

`scripts/smoke_read_to_me.py` renders 1800x1200 JPEGs using Pillow, in memory, and
uses real `/token` plus a real `gemini-3.8-live` session. The harness emulates the
Android capture/submit response protocol; it does not exercise phone hardware.
Five checks passed with both auto and PTT tokens: menu find, Wi-Fi find, French translate, menu verbatim,
and menu summary. Examples of completed output:

- Menu: “The pad thai is fourteen dollars and fifty cents.”
- Wi-Fi: “The wifi password is River7429.” (Synthetic test credential.)
- French: “Welcome to our small village. The library opens at nine o'clock.
  The market is closed on Sundays.”
- Verbatim: all three dishes and their prices in displayed order.
- Summary: Thai dishes, with prices ranging from seven to sixteen dollars.

A continuation probe returned the correct repeat, stopped, resumed the same chunk,
and transcribed a distinct second page (Jasmine Tea $3.00, Spring Rolls $6.00).
An additional strict probe expecting a tool call after an exhausted page stalled;
that end-of-page Live routing remains unverified (app state is unit-tested).
Reusing the identical image for next_page elicited “There is no more text to read”
instead of a new submission, so the smoke uses a distinct second-page fixture and
requires a new submit_reading before counting it as successful.

Initial find tests spoke the correct answer but submitted the entire card to app
state. The frame response now carries a mode-specific instruction: find stores only
the answer and summary stores only the gist. This avoids ambiguous “complete text”
wording. Provider output remains probabilistic and must not be treated as guaranteed
OCR accuracy.

## Unverified

- Physical glasses/phone capture, full returned photo dimensions, orientation,
  HIGH-quality stream latency, power, and Bluetooth audio coexistence.
- Sharpness threshold calibration on real menus/receipts, sparse or low-contrast
  scenes, and low light. Sharp background edges can mask blurry foreground text.
- Spoken command recognition and exact audio playback position; checks use text
  prompts with AUDIO output/transcription. Word-level resume is not supported.
- Very dense pages, columns, handwriting, long multi-page real documents, and
  model compliance with full transcription/chunk-only speech under all conditions.
- API acceptance with overridden models; only the configured default is exercised.
