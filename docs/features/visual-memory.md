# Visual memory — “where did I leave my keys?”

Implemented **2026-10-07**. Opt-in periodic glasses captures become searchable
object/place/text sightings on the Mac backend. It builds on [M4 vision](m4-vision.md)
and the existing [memory tool routing](long-term-memory.md). It never implements
person/face identification; the captioner is instructed to use only “a person.”

## Use it

1. Configure the backend, connect glasses, and grant DAT camera permission before
   starting the conversation, as in M4. Start the conversation.
2. Say **“Turn on visual memory for ten minutes”** (`set_visual_memory(true, 10)`),
   or enable **Visual memory** on the phone. Default duration is 30 minutes;
   accepted durations are 1–120 minutes. Repeating enable does not extend the timer.
3. The phone displays a fixed capture banner above the scrollable controls and
   plays a short chime. It captures immediately, then waits 20 seconds after each
   completed attempt. Silent mode/notification volume can affect audibility.
4. Say **“Where did I leave my keys?”**, **“When did I see my wallet?”**, or
   **“What was that restaurant I walked past?”**. `find_object(query)` and
   `when_did_i_see(query)` return up to ten historical sightings with caption,
   objects, place type, readable text, label, location label, pin, and capture time
   including the phone's UTC offset. Gemini answers from that evidence. A sighting
   is not a guarantee the object is still there; an absent match means unknown.
5. Say **“Remember where I parked”** (`remember_this(label?)`) or tap
   **Remember this**. It captures a fresh frame and pins it, bypassing motion and
   duplicate filtering. This also works with periodic mode off and displays the
   indicator/chime for the one-shot capture.
6. Say **“Pause visual memory”**, switch it off, or stop/background the conversation.
   Disconnect and timeout stop the mode; reconnect does not re-enable it.
7. Say **“Delete the last hour”** or tap **Delete last hour**. Capture pauses and
   the backend removes recent records and thumbnails, including pins. The result
   reports the count. `delete_visual_memory(minutes?)` defaults to 60 and supports
   1–10080 minutes. Deletion has a stored cutoff so already queued older captures
   cannot recreate erased records. A failed deletion must be retried explicitly.

Optional **Attach a coarse place label** lets you type a name such as “home” or
“downtown” on the phone. No GPS permission, geocoding, or automatic location is
used. This is off by default and resets when its UI leaves composition; the label
is sent only while opted in, and is not sent to Gemini's caption request.

## Configuration and storage

Repo-root `.env` (existing secret values stay backend-only):

```dotenv
GEMINI_VISUAL_MODEL=gemini-3.5-flash-lite
VISUAL_RETENTION_DAYS=7
```

The model override is independent of Gemini Live and transcript summarization.
Retention is clamped to 1–365 days. Run the backend with **one worker**. Pillow is
added for bounded JPEG validation, EXIF stripping, resizing and thumbnail encoding;
the existing google-genai SDK captions images with locally validated JSON.

`backend/data/visual/visual.db` stores captions, object tags, place type, readable
text, capture time, optional labels, and pins. Whole-scene JPEG thumbnails have
at most a 320-pixel long side. Original frames stay in memory only. The directory
is owner-only and gitignored, with 0600 DB/image files. No face crops are produced.
Unpinned records expire after seven days; pins survive automatic retention.
Cleanup runs at startup, every minute while the server runs, and on reads/writes.
It also removes orphan thumbnails. Stop the server and remove this directory to
clear the entire visual archive, including older pins.

All endpoints use existing `X-App-Secret` authentication and no-store responses:

| Endpoint | Contract |
| --- | --- |
| `POST /visual/frames` | `jpeg` base64 JPEG, aware `captured_at`, optional `label`/`location_label`, `pinned` boolean. At most 2 MB encoded and 4 MP decoded; timestamp within five minutes behind / one minute ahead of backend. Returns `status`, `id`, `caption`, `captured_at`, `pinned`. |
| `POST /tools/find_object` / `when_did_i_see` | `{"query":"keys kitchen"}`; ranked keyword matches, newest first for equal scores. |
| `POST /visual/delete` | `{"minutes":60}`; removes matching records/thumbnails including pins and reports count. |
| `/token` | Adds client-owned `set_visual_memory`, `remember_this`, `delete_visual_memory` plus backend-owned search tools. Client tools cannot execute under `/tools`. |

Android reuses M4's serialized camera capture, 1 fps safety limit, 15-second
camera timeout, and five-second idle shutdown. Memory captures do not go into
Gemini Live video or carry image parts in tool responses. Motion shares the
existing head-gesture collector even when gesture recognition is off. Fresh IMU
samples suppress captures if angular rates stayed below 0.08 rad/s since the last
saved capture. Missing/stale IMU falls back to captures with duplicate filtering.
A 64-bit luminance difference hash skips frames within five bit differences of
the last saved image. Explicit pins always bypass filtering. Uploads are serial,
not queued/retried, and frames never persist on the phone.

## Privacy and limitations

- Durable visual history stays on the Mac. **Gemini captioning sends frames to
  Google**; this is not offline inference. Google's free-tier data-use policy
  applies. Caption prompts prohibit identity/face descriptions, including posters,
  and names from badges/identity documents. Model compliance is probabilistic.
  Whole-scene thumbnails can contain a person; there is no face cropping or face
  recognition and no claim of face blurring.
- Deletion removes this visual archive's records/files using SQLite secure-delete.
  It does not erase filesystem backups, Google-held data, current Live context,
  spoken transcript/summary copies of answers, or external notes. Restart the
  conversation to clear its current visual-answer context.
- Foreground only. No camera availability means an honest error, not a saved claim.
  A request already executing on the backend may finish after pause; explicit
  deletion serializes with captioning and removes pre-deletion sightings.
- Motion/hash thresholds are heuristics: stationary-head scene changes and similar
  images may be missed. No semantic embeddings, exact object tracking, or guarantee
  of seeing small keys/reading distant signs. Keyword search scans local history;
  add FTS/embeddings if personal history becomes too large or synonym misses matter.
- Coarse labels are manually supplied names, not verified positions. Phone/backend
  clocks should agree. No dashboard module existed in this worktree or local main;
  a future dashboard can use the stored metadata and authenticated search contract.
- Physical camera/Motion/HFP coexistence, chime audibility and power consumption
  still need Pixel/glasses testing. Real verification below used synthetic images.

## Validation and sources

Run:

```sh
cd backend && uv run pytest
uv run python ../scripts/smoke_visual_memory.py --live-setup
cd ../android && ./gradlew assembleDebug testDebugUnitTest
```

The repeatable smoke captions the bundled mug/orange plus programmatically drawn
keys and parking signage in disposable local storage. Real Gemini identified
keys/keyring and parking level B2; search retrieved all three, deletion removed
all three thumbnails/records including a pin. Real constrained Live setup passed
in Auto, PTT and Over, preserving token locks and client-owned resumption.
No secrets, tokens, or private captures were printed, and no real archive was used.

Unit tests cover capture opt-in/timers, cancellation, chime/indicator callbacks,
concurrent snapshots, still/stale motion, duplicate bypass for pins, local routing,
camera capture without Live upload, image bounds, privacy prompt/schema serialization,
keyword/time ordering, retention/pins, deletion cutoff, file permissions and auth.

Verified **2026-10-07**:
[Flash-Lite model](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite),
[pricing](https://ai.google.dev/gemini-api/docs/pricing) (free-tier input/output;
standard paid $0.30 input / $2.50 output per million tokens),
[image understanding](https://ai.google.dev/gemini-api/docs/image-understanding),
[structured output](https://ai.google.dev/gemini-api/docs/structured-output),
[ephemeral tokens](https://ai.google.dev/gemini-api/docs/ephemeral-tokens),
[Live reference](https://ai.google.dev/api/live).
See [research notes](../research/visual-memory.md) for evidence and unverified items.

Final local results: backend **116 passed, 2 existing dependency warnings**; Android
**BUILD SUCCESSFUL**, **86 tests, zero failures/errors/skips**. JDK 17 and the
installed Android SDK were used, with temporary Gradle/Android caches and
`--no-daemon --project-cache-dir /private/tmp/places-project-cache` to avoid a
previous daemon carrying another worktree’s sandbox restrictions.
