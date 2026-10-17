# Silent page capture evidence

Date: **2026-10-16**. Runtime inference remains Gemini; no OpenAI runtime calls.

## Sources and implementation evidence

- [DAT Inputs 1.0.0 artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-inputs/1.0.0/):
  inspected the installed AAR `classes.jar` with JDK 17 `javap` on this date.
  `CapturePressType` exposes `SHORT_PRESS`, `HOLD`, `DOUBLE_PRESS`. The single
  short press is used explicitly. SDK enum inspection establishes symbols only.
- [Meta Inputs guide](https://wearables.developer.meta.com/docs/develop/dat/inputs/):
  opened on this date; public reader returned no usable guide content. Native
  photo behavior is therefore not inferred from event delivery. Existing
  [hands-free evidence](hands-free-background.md) documents session ownership.
- [Photo-to-Mac evidence](photo-to-mac.md) and [read-to-me evidence](read-to-me-notes.md):
  reused installed DAT 1.0.0 HIGH stream still capture, full returned pixels,
  serialized camera access and the existing sharpness heuristic. No new SDK.
- [Gemini image understanding](https://ai.google.dev/gemini-api/docs/image-understanding)
  and [structured output](https://ai.google.dev/gemini-api/docs/structured-output),
  opened on this date: image input and schema-constrained extraction are supported.
  Implementation follows the repository's installed Python SDK/meeting-summary
  `generateContent` contract, with local validation and no tools. Schema conformance
  does not establish OCR accuracy or instruction resistance.
- [Google Docs batchUpdate](https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/batchUpdate),
  opened on this date, and the existing `Notes.append` implementation: insert plain
  text into the resolved existing document. The request offers no application
  upload-ID receipt shared with SQLite. Inference: exactly-once successful delivery
  cannot be guaranteed across a crash between independent services. Persist an
  uncertain reservation before append and never retry that side effect automatically.
- Local `PhotoOutbox`, `PhotoToMacController`, `photos.py`, `meeting_summary.py`,
  `gemini_resilience.py`, `usage.py`, `notes.py` and tests were inspected. The page
  path reuses their storage, camera, auth, decode, retry, usage and append patterns.

Deduplication is scoped to resolved document + normalized visible source title/page
+ item kind/text. It retains successful hashes for seven days and uncertain hashes
until manual reconciliation. Source identity is OCR-dependent; same-ID receipts
remain authoritative across network retries. Page JPEGs are retained only in the
bounded private phone queue pending delivery, never in backend persistence.

Cue generation reuses the existing shutter/tool tone helper. Without conversation
audio, media routing avoids opening HFP or a microphone merely to make a sound;
actual A2DP routing to glasses remains unverified. Active conversation cues use its
communication route. Phone notifications contain plain text and request no sound.

## Unverified

- Whether a short capture press **also triggers Meta's native photo** while a DAT
  session is active. The app itself saves no image to a public destination.
- Real e-ink highlight detection (including gray shading), tablet glare/moire,
  underlines/brackets on paper and unusual layouts.
- Handwriting accuracy, anchors, verbatim fidelity and resistance to instructions
  visually embedded in a real page. Tests establish the output-as-data boundary,
  not model accuracy; no credentialed extraction/Google write ran here.
- Returned resolution, native sensor resolution, HEIC orientation and sharpness
  threshold calibration on the actual glasses.
- Short-press delivery while controls are armed with and without a conversation;
  DAT/camera coexistence, locked phone operation, HFP/A2DP cue audibility, volumes,
  phone notification display and service/battery lifecycle on the device.
- Real backend outage recovery and an interrupted Google append. Fault-injection
  tests establish conservative receipt behavior, not external-service availability.

## Validation boundary

Backend: **885 pytest tests passed**, including 28 new page cases, with mocked
Gemini and Google. Android: all production/debug and JVM test Kotlin compiled
using the cached Kotlin/Compose 2.2.21 compiler. Direct JUnitCore: **303 passed**,
including all 10 page-capture tests; **26 existing HTTP tests** could not bind
MockWebServer sockets in this sandbox. Gradle itself is blocked by the same socket
restriction. The full required Gradle run must be repeated outside this constraint;
no hardware or real-service claim follows from the compiler/JVM checks.
