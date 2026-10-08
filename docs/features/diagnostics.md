# Diagnostics and remote logs

Implemented **2026-10-08**. Runtime AI stays Gemini. The first hardware session is
[one ordered checklist](../hardware-test-plan.md), covering all 14 existing feature guides.

## Use

Open **Settings → Diagnostics** in the Android app. See the [screen map](app-ui.md). It shows:

- DAT registration/link/compatibility, battery percentage and worn (`donState`)
  from the selected device's metadata. Unavailable state is not inferred as worn.
- Actual `AudioRecord.routedDevice` and `AudioTrack.routedDevice`, configured PCM
  rates, advertised device rates, SCO flag, selected communication device, and
  the translator's separate phone output when active. Device type/ID distinguish
  multiple headsets. Advertised rates are capabilities, not measured link rates.
- Live microphone RMS meter (four UI updates/second). Capture must be running;
  stopped capture reads zero. RMS is before the app's uplink mute gate.
- Gemini state, age of the latest resumable handle (only its age), and the last
  speech-end → first-received-audio estimate. It reuses local speech-energy
  detection on forwarded PCM; it includes configured silence/VAD/network/model
  delay and excludes Android/Bluetooth playback buffering. It resets on Stop or
  a fresh persona session. It is not a measured acoustic round trip.
- Authenticated backend health and upload status, Android/DAT permissions,
  runtime mode/mock/gesture/watch/visual/meeting/translator/coach/workout flags.

**Test speaker beep** and **Record 5 s and play back** require the conversation
and armed controls stopped. They request temporary audio focus and use the same
communication routing as conversations, with low-amplitude 660 Hz PCM for the
beep. Both open the microphone to exercise that route. Recording stays bounded
in RAM, plays locally, and is discarded without a Gemini/backend upload. Leaving
the screen or losing focus cancels the test and releases audio. Do not launch a
conversation from an external shortcut while an audio probe is running.

**Test camera frame** needs a running, unpaused Assistant conversation and DAT
camera permission. It captures through the existing serialized camera owner,
shows a local preview/dimensions, and sends no image to Gemini or the log server.
The shared five-second idle close still applies. **Test motion (show live gyro)**
requests the existing single Motion collector without enabling gestures or
changing visual/workout demand; it reports x/y/z rad/s and stale samples.
Leaving Diagnostics releases only its own Motion demand. No new voice tools are
added: ordinary conversation/feature commands continue to generate diagnostics.

**Copy logs** and **Share logs** export the bounded JSONL buffer plus the current
hardware/permission snapshot. The Android share chooser opens only on that button.
Review before sharing: hardware names/status may be personal. Transcripts,
raw PCM/IMU traces, photos, tool arguments/results, tokens and resumption handles
are deliberately excluded from operational events.

## Mac workflow

From the repository root:

```sh
./scripts/dev_up.sh
```

Requires JDK 17, Android SDK 36/platform-tools, `uv`, a USB-authorized phone, and
root `.env` with `GEMINI_API_KEY` and `APP_SHARED_SECRET`. Defaults match this Mac:
`JAVA_HOME=/opt/homebrew/opt/openjdk@17`, `ANDROID_HOME=$HOME/Library/Android/sdk`.
Set `ANDROID_SERIAL` for multiple attached devices. The script prepares ignored
`android/local.properties` (preserving unrelated settings), starts/reuses an
authenticated localhost backend, reverses port 8787, builds/installs debug,
launches the app and tails logs. It does not expose secrets as command arguments.
Ctrl-C stops its tail and only the backend it started. Existing backend processes
are reused, so restart them yourself after changing backend code/configuration.

Browser: **http://localhost:8787/dashboard/live-logs**, login with the existing
shared secret. Exact feature and level filters persist through htmx's two-second
polling. The page shows the newest 200 matching events. **Filter / refresh** works
without JavaScript/CDN availability. This uses the existing dashboard cookie,
CSRF, escaping, CSP and no-store boundaries.

A second agent/terminal on the Mac can watch without touching the phone:

```sh
cd backend
uv run python ../scripts/tail_logs.py
uv run python ../scripts/tail_logs.py --feature gemini --level WARN
uv run python ../scripts/tail_logs.py --once
```

Useful feature tags: `diagnostics`, `audio`, `dat`, `permissions`, `gemini`,
`over`, `hands-free`, `vision`, `motion`, `gestures`, `memory`, `notes`, `jobs`,
`meeting`, `translator`, `journal`, `read-to-me`, `coach`, `workout`,
`visual-memory`, `settings`, `app`. Tool lifecycle events include the tool name,
never its arguments. Backend requests record feature, HTTP status and elapsed ms.
While Diagnostics is visible, actual route changes and two-second mic/gyro
summaries reach the log viewer. Inspect the existing phone error/tool panel or
feature dashboard for detailed results; logs intentionally omit private content.

## API, storage and limits

All API calls require `X-App-Secret`; dashboard cookies cannot authorize them.
No new secrets/config variables/dependencies. `GET /diagnostics/health` checks
backend reachability/authentication without a Gemini request. It does not prove
Google credentials, Gemini quota or successful audio playback.

`POST /logs` accepts `{"entries":[{"id":"unique-id","timestamp":"ISO time",
"feature":"audio","level":"INFO","event":"route_ready"}]}`. At most 100 entries,
512 characters/event, 128 KiB/body; unknown fields or invalid tags/levels get 422.
Timestamps are bounded display strings; server `received_at` and `cursor` provide
trusted arrival order. Secrets known to the process and recognizable token forms
are redacted defensively; producers must still never pass sensitive content.

`GET /logs?after=CURSOR&feature=audio&level=INFO` returns `entries`, `cursor` and
`gap`. First read shows the latest 200 matches; subsequent reads advance in arrival
order. A reported gap means the cursor predates the retained live window.

Android has a synchronized **500-entry RAM ring** and sends batches of up to 100
through the service-owned coroutine every five seconds after the previous check.
Only an acknowledged batch advances the cursor; failures retry. IDs survive retries.
Logs disappear on process death, and a long outage can evict unsent entries. Ring
evictions count all overwritten entries, including already-uploaded ones. Health
failures are shown locally rather than recursively generating upload errors.

The single-worker backend keeps **2,000 events** for live reads, restoring that
window from JSONL on restart. Recent IDs deduplicate retries inside this window;
this is not an indefinite exactly-once archive. Files are
`backend/data/logs/events.jsonl` plus `.1`–`.5`, each approximately **1 MiB** max,
owner-only directory/files (0700/0600), covered by existing `backend/data/` ignores.
Rotation occurs before a write would exceed the limit. Oldest files are replaced.
CLI warns about window gaps; inspect rotated files for older retained records.
Stop the backend before deleting these files to clear logs consistently.

No remote control, continuous raw-media upload, logcat scraping, persistent phone
outbox or multi-worker coordination is introduced. The live viewer needs the
phone service to exist and the backend to be reachable. The script tails app
structured logs; OS/native crashes still need `adb logcat` separately.

## Verification and sources

Checked **2026-10-08**: SDK 1.0.0 public `Device` metadata inspected with `javap`;
Android compiles against actual AARs. New unit tests cover ring capacity/retry IDs,
redaction, latency, audio probe PCM and the backend's auth, bounds, rotation,
restart, filters, concurrent writes, HTML escaping and script prerequisites.
Run the complete suites and credential-safe smoke:

```sh
cd android && ./gradlew assembleDebug testDebugUnitTest
cd ../backend && uv run pytest
uv run python ../scripts/smoke_diagnostics.py --live-setup
```

The smoke uses disposable logs/memory with no application lifespan jobs and no
Docs writes. Real `/token` HTTP 200 and constrained Gemini `setupComplete` passed.
Token policy, `lock_additional_fields=[]`, and client-owned resumption are unchanged.
Physical Pixel/glasses audio/sensors, perceived latency, full USB deployment and
browser rendering remain unverified (no adb device attached).

Sources verified **2026-10-08**:
[Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord),
[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[htmx polling](https://htmx.org/docs/#polling),
[published DAT core 1.0.0](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-core/1.0.0/),
[existing verified Gemini protocol](../research/m1-android-notes.md),
[diagnostics research and unverified list](../research/diagnostics.md).

Final local run: Android **BUILD SUCCESSFUL in 20s**, **187 JVM tests, zero
failures/errors/skips**; backend **378 passed, two existing dependency warnings
in 5.16s**. `bash -n scripts/dev_up.sh` and local documentation links passed.
The real dev_up prerequisite run exited clearly before any installation because
`adb devices` was empty. No push, merge, OAuth consent or physical-device run.

## Combined merge verification

Integrated with main on **2026-10-08**. Audio teardown retains main's serialized,
non-cancellable, best-effort resource cleanup and resets the diagnostics mic meter
after capture stops. Diagnostics uses the existing service, camera and Motion
owners; its Motion button follows main's pause/meeting/translator restrictions.
The visual-memory capture banner remains visible while Diagnostics is open.
Main's tool registry and settings remain intact, as do `lock_additional_fields=[]`
and client-owned `sessionResumption`. All decision rows and research entries from
both branches were retained.

Backend `uv run pytest`: **378 passed, 2 existing dependency warnings in 4.43s**.
Android `assembleDebug testDebugUnitTest`: **BUILD SUCCESSFUL in 25s**, **45 tasks
executed**, **201 JVM tests, zero failures/errors/skips**. Used JDK 17, the installed
SDK and writable temporary caches with `--no-daemon`. No test assertions changed.
Script syntax and merge whitespace checks passed. Physical hardware, browser
rendering and credentialed Gemini/Docs calls were not exercised during this merge.
