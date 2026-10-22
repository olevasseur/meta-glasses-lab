# Silent page capture → Google Doc

Implemented **2026-10-16** on `feat/pagecapture`. Capture marked passages and
handwritten notes without speaking or opening a Gemini Live conversation.

## Use it

1. Create an existing Google Doc and assign the nickname **reading notes** through
   [Doc nicknames](doc-nicknames.md). Creation still requires Tier 1 approval.
2. In **Settings** or **Features**, enable **Silent page capture** (off by default).
   Optionally enter a different target nickname and tap **Save nickname**. Both
   values persist in DataStore; stop a conversation before changing them.
3. Grant DAT camera and phone notification permissions, connect the glasses, and
   tap **Enable glasses controls** in Settings. A live DAT session must be armed;
   no Gemini Live connection is required. Controls retain the existing idle timeout.
4. Look squarely at a tablet, e-reader or paper page and **short-press capture once**.
   Marked means colored/gray shading, underline or bracket. Handwritten notes may
   include nearby printed text as an anchor. Keep still in good light.
5. Listen for the shutter and then a success cue. The notification says
   **Saved N highlights, M handwritten notes to <doc>**, with a short preview.
   A double-beep retry cue means blur: nothing was uploaded. An error cue accompanies
   a failure reason; a separate empty-result cue accompanies **Nothing highlighted found**.
   Nothing is read aloud by TTS or sent to Live for spoken confirmation.

**Stop keeps the button (2026-10-19).** While this setting is on, the Talk screen and
notification **Stop** end only the conversation; glasses controls stay armed so the
next short press still captures a page instead of going to Meta AI. The idle timeout
is at least 60 minutes while no conversation runs (each capture counts as activity).
Tap **Release glasses to Meta AI** on the Talk screen, Stop in the notification
again, or **Stop service and glasses controls** in Settings to give the button back.
Swiping the app away also releases it. Before this, every Stop released the button,
so presses between notes went to Meta AI and nothing reached the backend.

Pending device approval consumes any capture press first, including HOLD. Otherwise
only DAT `SHORT_PRESS` captures a page; `DOUBLE_PRESS` remains unclaimed and HOLD
retains conversation start/stop. Turning this setting off preserves existing feedback.
Capturing while an Assistant conversation is active uses the same path. This mode
neither starts nor stops the conversation itself.

Cues use the existing generated shutter/tool tones: the communication route while
conversation audio is active, otherwise the media route (Bluetooth/A2DP when routed
to the glasses, phone in fallback/mock mode). No microphone or Live session is
started for a cue. Actual glasses routing and audibility require the hardware check.
Phone notifications use a quiet channel, private lock-screen visibility and the latest
result; allow notifications in Android settings to see them.

## Delivery, limits and privacy

Capture uses the shared serialized `GlassesCamera` still path, HIGH quality,
full returned resolution, JPEG quality 95, 15-second timeout and idle camera close.
It applies read-to-me's 512-pixel grayscale Laplacian sharpness check (threshold 60).
There is no automatic recapture after blur; the next short press retries.

The existing photo outbox is reused in `noBackupFilesDir/page-outbox`, with the
nickname frozen in metadata. Limits: **eight pages / 64 MB**, **12 MB per JPEG**.
A full outbox rejects capture without deleting pending pages. Atomic private writes
retain UUID, timestamp/offset, nickname and identical JPEG across retries. Service
startup and 60-second checks retry transient failures with 60-second to 15-minute
backoff. The raw page request has a 60-second network deadline to accommodate
non-Live inference. Force-stop pauses retries; reopen the app to resume. Disabling
the setting stops new capture triggers, not delivery of already queued work.

Successful, empty, duplicate and terminal error responses delete the private JPEG.
Permanent HTTP errors delete it as well, showing the backend reason; there is no
failed-image archive for pages. Network, auth, rate/quota and service failures keep
it for retry. Nothing enters Downloads, MediaStore, the photo-to-Mac queue or visual
memory. The backend strips EXIF/GPS, processes images in memory, and never stores
image files. Normal Notes append tracking can retain the appended text; receipts
retain result counts, a short preview and hashes. This is application retention
behavior, not a claim about Gemini's provider-side data policies.

## Backend contract

`POST /pages?upload_id=<UUID>&captured_at=<aware ISO datetime>&doc=<nickname>`
accepts authenticated raw `image/jpeg` using existing `X-App-Secret`. The photo
security lane is shared: 12 MB request, 24 MP decoded cap, one active request and
six attempts/minute across `/photos` and `/pages`. Auth precedes buffering.
Unknown query fields, invalid JPEGs and naive timestamps are rejected.

Non-Live Gemini `generateContent` uses the same bounded retries, model fallback,
structured-schema validation and usage metering as meeting summaries. Optional
`GEMINI_PAGE_MODEL` selects the preferred model (default `gemini-2.5-flash` since 2026-10-22: flash-lite took 20–65 s per page image, past the 20 s deadline); the existing
explicit `GEMINI_TEXT_MODELS` list takes precedence as in other text features.
Local text-call/token caps and configured candidate-model daily quotas guard inference.
No new runtime AI provider, Google OAuth scope, approval tool or Live tool is added.

The extractor returns `source: {title?, page?}`, `highlights: [text]`,
`handwritten: [{text, anchor?}]` and `legible`. It is instructed to copy verbatim,
mark unreadable words `[illegible]`, ignore UI chrome, never complete words or follow
instructions found on the page. Model output is schema checked and passed solely
as **plain insertText** to the existing Notes append path. It cannot choose a
Google document, create one, run tools or execute markup. An unreadable page fails;
an empty page returns `nothing_found` and appends nothing.

Destination resolution previews an existing document and freezes its ID before
append. Missing nicknames return **Create a doc nicknamed 'reading notes' first**
(or the selected nickname); ambiguous titles require an explicit nickname. Even
an uncreated Inbox is rejected. Append is Tier 0; creation remains Tier 1.
The normal Notes UTC timestamp line precedes the block; the source line appears only
when a title or page is visible:

```text
— Book title, p. 12
“Highlighted text exactly as extracted”
✎ Handwritten note (next to “nearby printed text”)
```

`backend/data/page-receipts.sqlite3` stores stable-ID receipts. Replaying the same
ID returns the saved result without inference/append; changed bytes, nickname or
timestamp return 409. Keep receipts when moving/backing up the backend.
Normalized Unicode/case/whitespace hashes deduplicate each item by resolved doc ID
and visible source title/page for **seven days**. Different IDs/nicknames pointing
at the same doc share deduplication. Entirely repeated captures return
`already_saved`; only new items count in success notifications. Missing source
fields share an unknown-source bucket; extraction/source inconsistencies can defeat
cross-capture deduplication, while identical text in different unknown books can
be suppressed. Stable-ID retry protection does not depend on OCR consistency.

Google Docs and SQLite cannot share a transaction. The backend durably reserves
an **uncertain** receipt and item hashes before calling append, and marks success
only after confirmation. A crash or ambiguous Google error never triggers an
automatic repeat append; it returns **Page append outcome is uncertain; check the
doc before capturing again** once, and clears those reservations so the next capture
(after checking the doc) appends them. A definite non-write (doc no longer resolves)
clears the reservations and receipt immediately and returns 422, so a retry can append. Run the personal backend with one worker, as for photos.

## Debugging failed pages (2026-10-22)

Start the backend with `PAGE_DEBUG_KEEP_FAILED=1` to keep every non-ok page
(unreadable, nothing found, errors) in `backend/data/page-debug/` (git-ignored,
owner-only): `<upload_id>.jpg` plus `.json` with dimensions, the result and the raw
extraction. Successful pages are never kept. Turn it off and delete the folder once
done. Added after two real captures were judged unreadable with no way to see why.

## Verification and hardware steps

Automated tests mock Gemini/Google and use temporary storage. They cover trigger
priority, setting persistence, blur rejection, bounded durable retry, deletion,
quiet result routing, raw authenticated HTTP, quotas, plain-text injection handling,
JPEG metadata/dimensions, idempotency, uncertain writes, deduplication and formatting.
See [research](../research/page-capture.md) for sources and unverified behavior.

Run the [hardware checklist](../hardware-test-plan.md#silent-page-capture--2026-10-16):
confirm one short press, active/no conversation, native-photo coexistence, every cue
and notification, blur, e-ink/tablet/paper highlights, handwriting, duplicates,
missing nicknames, sleeping backend/retry and no retained images. Returned resolution
and extraction accuracy remain hardware/live-service acceptance checks.

### Local validation (2026-10-16)

- `cd backend && UV_CACHE_DIR=/tmp/photomac-uv uv run pytest -q`:
  **885 passed**, three existing dependency warnings (28 page-capture cases).
- `cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 GRADLE_USER_HOME=/tmp/pagecapture-gradle ANDROID_USER_HOME=/tmp/pagecapture-android ./gradlew testDebugUnitTest --offline --no-daemon`:
  **blocked before compilation** because this sandbox denies Gradle's local
  lock-coordination socket (`SocketException: Operation not permitted`). Writable
  caches were populated from the installed dependencies; network downloads are
  also restricted.
- Fallback verification compiled **all production/debug and JVM test Kotlin** with
  cached Kotlin/Compose 2.2.21 and JDK 17, then ran all 329 tests through JUnitCore:
  **303 passed; 26 existing HTTP tests blocked** by the same local socket denial
  when MockWebServer binds. **All 10 new page-capture tests passed**, including an
  in-process HTTP interceptor checking the raw request and timeout. This fallback
  is not a successful Gradle build; the required full Gradle suite remains pending
  in an environment permitting local sockets. Temporary runner/log files are in
  `/tmp/pagecapture-kotlin`, outside the repository.

No real Gemini/Google Docs mutation, emulator or physical-device validation ran.
