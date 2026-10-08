# Visual memory — “where did I leave my keys?”

**Approval update — 2026-10-08:** Delete requests pause capture and propose Tier 1 deletion; nod, capture or Approve executes it. Capture/search remain unchanged. See [device approvals](device-approvals.md) for configuration, limits and verified evidence.

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
6. Say **“Pause visual memory”**, switch it off, or stop the conversation.
   Calls/focus pauses, disconnect, Translator/meeting transitions and timeout stop
   the mode; reconnect/resume does not re-enable it. Backgrounding or rotating the
   Activity retains the bounded capture in the existing foreground service; its
   notification displays **Visual memory is ON**. Active visual capture keeps that
   service from expiring at the ordinary conversation idle timeout.
7. Say **“Delete the last hour”** or tap **Delete last hour**. Capture pauses and
   a Tier 1 card asks for nod/capture/Approve before removing records and thumbnails, including pins. The result
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
Retention is clamped to 1–365 days. A saved dashboard retention value overrides
the environment and applies immediately, without restarting. Run the backend with **one worker**. Pillow is
added for bounded JPEG validation, EXIF stripping, resizing and thumbnail encoding;
the existing google-genai SDK captions images with locally validated JSON.

`backend/data/visual/visual.db` stores captions, object tags, place type, readable
text, capture time, optional labels, and pins. Whole-scene JPEG thumbnails have
at most a 320-pixel long side. Original frames stay in memory only. The directory
is owner-only and gitignored, with 0600 DB/image files. No face crops are produced.
Unpinned records expire after seven days; pins survive automatic retention.
Cleanup runs at startup, every minute while the server runs, and on reads/writes.
It also removes orphan thumbnails. The dashboard can delete any date range,
including older pins. Stop the server and remove this directory to clear the
entire visual archive and its settings.

All endpoints use existing `X-App-Secret` authentication and no-store responses:

| Endpoint | Contract |
| --- | --- |
| `POST /visual/frames` | `jpeg` base64 JPEG, aware `captured_at`, optional `label`/`location_label`, `pinned` boolean. At most 2 MB encoded and 4 MP decoded; timestamp within five minutes behind / one minute ahead of backend. Returns `status`, `id`, `caption`, `captured_at`, `pinned`. |
| `POST /tools/find_object` / `when_did_i_see` | `{"query":"keys kitchen"}`; ranked keyword matches, newest first for equal scores. |
| `POST /visual/delete` | `{"minutes":60}`; proposes a fixed deletion interval; app/dashboard approval removes records/thumbnails including pins. |
| `POST /visual/status` | `{"on":true}` or `false`; phone capture-status heartbeat, refreshed every 20 seconds. Does not enable capture. |
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

## Visual memory dashboard

Open **http://localhost:8787/dashboard/visual-memory** and sign in with the same
backend shared secret as the other dashboard pages. The **Visual memory** navigation
entry uses main's page registry. No new account, frontend build or credentials.

- Browse newest first, grouped by capture day, with a thumbnail, caption, object
  tags, place type, visible text and optional labels. Dates and inclusive day
  filters use the phone's stored UTC offset at capture, even across midnight.
  Lists have 50 captures per page.
- Search captions/tags/text/labels by case-insensitive literal text. Leave the
  day blank to search across all days. Search and pagination work without JavaScript.
- **Pin** retains a capture beyond automatic retention; **Unpin** makes it eligible
  immediately, so an already expired capture is removed on unpinning.
- **Delete this capture… → Confirm delete** removes that record and thumbnail.
  **Retention and deletion** also offers an inclusive date-range delete, including
  pins, with an explicit confirmation checkbox. Deletion does not pause the phone.
  Range deletion uses the same queue barrier as voice deletion: uploads timestamped
  before deletion are discarded, including unrelated in-flight captures; fresh
  captures after deletion can still arrive. Pause capture first for a stable archive.
- **Save retention** accepts 1–365 days, persists in the visual SQLite settings,
  and immediately prunes expired unpinned records/files. Pins remain until explicit
  deletion/unpinning. The saved setting overrides `VISUAL_RETENTION_DAYS`.
- **Visual memory is ON/OFF** reflects the phone's latest capture report, including
  one-shot capture. A report older than 60 seconds (or a backend restart) displays
  **UNKNOWN**, never a misleading OFF. Keep/open the app for fresh reports. Status
  refreshes every 20 seconds with htmx; a normal Refresh link works without it.
  This is a single-phone status display, not a remote capture switch.

Page, thumbnails and mutations share the dashboard cookie authentication, CSRF
form validation, same-origin checks, escaping, CSP and no-store headers. The app
secret header cannot bypass browser login; browser cookies cannot call `/visual/*`.
Viewing never calls Gemini or saves a capture. Retention may prune on reads as
it does for existing voice search. No originals or filesystem paths are exposed.

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
- Capture requires the active Assistant conversation's foreground service. It is
  disabled in Translator and meeting capture. No camera availability means an honest error, not a saved claim.
  A request already executing on the backend may finish after pause; explicit
  deletion serializes with captioning and removes pre-deletion sightings.
- Motion/hash thresholds are heuristics: stationary-head scene changes and similar
  images may be missed. No semantic embeddings, exact object tracking, or guarantee
  of seeing small keys/reading distant signs. Keyword search scans local history;
  add FTS/embeddings if personal history becomes too large or synonym misses matter.
- Coarse labels are manually supplied names, not verified positions. Phone/backend
  clocks should agree. Dashboard capture status can lag by network/refresh latency;
  it cannot establish live phone state while disconnected.
- Physical camera/Motion/HFP coexistence, chime audibility and power consumption
  still need Pixel/glasses testing. Real verification below used synthetic images.

## Combined merge verification

Integrated with main on **2026-10-08**. Backend `uv run pytest`: **368 passed,
2 existing dependency warnings**. Android `assembleDebug testDebugUnitTest`:
**BUILD SUCCESSFUL in 20s**, **45 tasks executed**, **181 JVM tests, zero
failures/errors/skips**. Used JDK 17, the installed SDK and writable temporary
uv/Gradle/Android caches. No assertions were weakened.

All **42 assistant tools** join main's registry, including **17 client tools**;
Translator retains exactly two tools and no injected personal memory. The combined
policy and latest memory fit the existing 8,000-byte budget, including meeting
instructions. `lock_additional_fields=[]` and client-owned `sessionResumption`
remain unchanged. Blocking visual searches run in worker threads so job events
stay responsive. Jobs, journal scheduling and visual retention share the lifespan.

Visual capture, read-to-me, look, watch and cooking progress checks reuse one
serialized camera and rate limit. Motion remains shared with workouts and gestures;
turning gestures off does not starve workout or visual samples. Main's settings
and DataStore are retained; capture opt-in remains session-only and is never
silently restored. The dashboard adds 23 regression cases for browsing, date
boundaries, pagination/search, escaping, private thumbnails, cookies/CSRF, pins,
range/single deletion, retention persistence and status expiry. Android regressions
cover lifecycle gates, authenticated uploads/status and concurrent reading/watch/
memory capture. Tests and the smoke isolate personal archives and background jobs.

All decision rows and research entries from both branches are retained. No new
credentialed Gemini/Docs calls, browser rendering or physical phone/glasses tests
ran during this merge; hardware behavior remains unverified.

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
