# Photo to Mac

Implemented **2026-10-10**. During an Assistant conversation, say **“Take a picture
and send it to my Mac”**, **“Save a photo to my computer”**, or **“Snap a photo.”**
An optional short label becomes part of the filename. Grant DAT camera permission
and connect the glasses first, as for [vision](m4-vision.md). No extra phone button
or approval is required.

`save_photo(label?)` captures a DAT still at HIGH quality, preserving every pixel
returned by the SDK. It uses `Stream.capturePhoto()`, not a preview/video frame.
HEIC orientation is applied by Android ImageDecoder; JPEG encoding uses quality
95. This shares the serialized camera, 15-second capture timeout and idle shutdown
with look, reading and visual memory, without reading's text-sharpness rejection.
A short shutter cue uses the active communication/voice-call route to the glasses
(phone route in mock/fallback mode).

The image goes only to the configured Mac backend. It is never attached to a
Gemini tool response, sent as Live video, captioned, or added to visual memory.
There are **no image inference tokens** for this action; the ordinary spoken
request, tool declaration/result and spoken confirmation still use Live tokens.
An explicit additional scene question can separately use look. Existing watch or
visual-memory modes keep their own independent captures; this command does not
enable either mode.

On success Gemini says **“Saved to your Mac's Downloads as glasses-…”** using the
existing [tool-reply flow](tool-reply.md). The user turn stays closed through the
result and follow-up; no second user turn releases the confirmation. A custom
save directory is described honestly as the Mac's configured photo folder. Only
the filename, status and human-readable confirmation reach the model, never the
full filesystem path or image.

## Configuration and endpoint

Run the backend as the Mac user whose Downloads folder should receive photos.
`PHOTO_SAVE_DIR` optionally overrides the default `~/Downloads`. The destination
**must already exist** and be writable; the backend never creates it. macOS may
require Downloads access for the process hosting Python. This implementation does
not change any local configuration or macOS permissions.

Uploads use the app's existing `BACKEND_URL` and `X-App-Secret`; for the intended
setup this is the Tailscale HTTPS backend address. Keep that host in the existing
`BACKEND_ALLOWED_HOSTS` configuration. No new key, OAuth scope, or AI service.

`POST /photos?upload_id=<UUID>&captured_at=<aware ISO datetime>&label=<optional>`
accepts raw `Content-Type: image/jpeg`. IDs/timestamps originate on the phone,
not in the tool arguments. Authentication runs before body buffering. Unknown
query arguments, paths/traversal in labels, invalid images and naive timestamps
are rejected. Labels are limited to 80 input characters and sanitized to at most
60 ASCII letters, digits, underscores or hyphens. Missing/unsafe configuration
returns an error and leaves the private phone copy intact.

The limits are **12,000,000 JPEG bytes and 50,000,000 decoded pixels**. These give
headroom beyond the visual-memory thumbnail limits for high-quality stills,
without promising a particular sensor size. Pillow checks dimensions before pixel
loading, applies EXIF orientation, then re-encodes JPEG quality 95 with no EXIF,
GPS, comments, XMP or ICC metadata. Dimensions are retained; JPEG re-encoding is
lossy. There is at most one active photo request and six attempts per minute,
including retries. The existing global request limit also applies. Run the
personal backend with one worker; this bounds simultaneous large decodes.

Names are `glasses-YYYY-MM-DD_HH-MM-SS[-label].jpg`, using the phone capture's local
clock/offset; collisions receive `-2`, `-3`, etc. Temporary files are written and
fsynced in the destination, mode **0644**, then atomically published with a hard
link that cannot overwrite an existing file or symlink. Existing files remain
untouched. Private receipts in `backend/data/photo-receipts.sqlite3` reserve the
filename before publication, so a lost HTTP response or restart reuses the same
ID/name. Reusing an ID with different bytes or metadata returns 409. Keep receipts
when relocating/backing up the backend if outstanding phone retries exist.

## Unreachable Mac and private outbox

Before upload the JPEG and metadata are atomically stored in the phone's
`noBackupFilesDir/photo-outbox`. No MediaStore/gallery entry, public storage, or
Android backup. Limits are **eight photos / 64 MB**, with a 12 MB per-photo limit.
A full outbox refuses another capture rather than deleting unsent photos; capacity
is conservatively checked before opening the camera. A capture/encoding/storage
failure reports an error and never claims a save.

On a network failure Gemini says **“Saved on the phone; I'll send it when your Mac
is reachable.”** Authentication/validation rejections instead explain that the
Mac rejected delivery and that the phone retains the photo. Uploads have a
20-second total timeout, keeping capture plus initial delivery below the existing
45-second tool-reply budget under normal scheduling. Photos are removed only
after an `ok` response with a valid filename.

The service retries one queued photo on startup and every 60 seconds, preserving
its ID, timestamp, label and bytes. Retries never recapture or replay a shutter
cue. A successful deferred delivery updates the phone's last-tool status. Retry
runs while the app's conversation service exists; after force-stop/process death,
reopen the app to resume. No OS job scheduler was added. Camera actions are
unavailable while paused, translating or in meeting capture. The queue survives
those transitions and normal service restarts; uninstall/clear app data deletes it.

## Verification and device checklist

Backend tests cover full dimensions, EXIF/GPS removal with rotation, JPEG byte and
pixel bounds, names/collisions, retry after publication failure, concurrent saves,
symlinks/traversal, existing-directory requirement, default destination, auth,
content type, request/rate limits and client-tool ownership. Android JVM tests
cover route isolation, raw authenticated upload, durable retry/cancellation,
bounded storage, failure messages and Auto/PTT/Over follow-up behavior. The voice
eval simulator has three dedicated cases in
`scripts/voice_eval/photo-to-mac-cases.json`; no new recorded speech or real Gemini
calls were made for this feature.

Validation on 2026-10-10: `uv run --frozen pytest` — **743 passed**, three existing
dependency warnings. `./gradlew --no-daemon testDebugUnitTest assembleDebug` —
**BUILD SUCCESSFUL**, **313 JVM tests**, zero failures/errors/skips. Temporary
writable dependency/build caches were used; local settings and secrets were not
changed.

Hardware checks still needed: actual returned still dimensions/quality, HEIC and
bitmap orientation, glasses shutter audibility over HFP, capture coexistence with
Inputs/Motion, spoken tool choice and confirmation, and upload/retry over the real
Tailscale HTTPS connection with the Mac asleep and then awake. Verify the actual
Downloads file and macOS permission behavior. HIGH is the highest exposed DAT
quality; **native sensor resolution is not established** by JVM tests.

Optional dashboard thumbnails were deferred. See [decision 22](../decisions.md)
and [dated research and source URLs](../research/photo-to-mac.md).
