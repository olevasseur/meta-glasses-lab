# Cooking / DIY coach

Implemented on **2026-10-07** and integrated with main. Gemini Live remains the
voice assistant; a backend Gemini text request extracts or generates the project.
Nod/shake navigation uses the shared [head-gesture detector](head-gestures.md)
and DAT Motion stream. Pending yes/no confirmations always take precedence.

## Setup and use

Configure the backend and start a conversation as in [M4 vision](m4-vision.md).
Before cooking, grant DAT camera permission and Android notification permission.
Tap **Allow precise timers** and enable **Alarms & reminders** in Android settings.
Install an offline voice for the phone's default language in Android's text-to-speech
settings and connect the glasses for Bluetooth audio. These are one-time phone setup
steps; the coach's operational controls are spoken. The app reports a timer setup
error if exact alarms or its notification channel are disabled.

Examples:

- **“Start a cooking project: 200 grams of pasta, one tablespoon of oil. Boil
  water, cook the pasta for eight minutes, then drain and add the oil.”**
- **“Read this recipe and guide me through it.”** Gemini first uses `look()` on
  the visible page, then sends the transcribed instructions to `start_project`.
- **“Start a DIY project from https://….”** Give an exact public HTTPS URL; the
  backend fetches the page and extracts its text before Gemini structures it.
- **“Look in my fridge and make me a 20-minute pasta.”** Gemini first looks, then
  sends the observed ingredients and the explicit generation request to the backend.
- **“Next step.” “Previous step.” “Repeat that step.” “Where am I?”**
- **“How much olive oil?” “Double the recipe.” “Go back to the original amounts.”**
- **“Does this look done?” “Is this the right screw?”** `check_progress` retrieves
  the current project and takes a fresh photo through the existing vision controller.
- **“Watch while I work.”** Reuses existing watch mode and its two-minute cap.
- **“Set a pasta timer for eight minutes.” “Set a glue timer for 30 minutes.”
  “List my timers.” “Cancel the pasta timer.”**
- **“Enable nod navigation.”** Enables the existing head-gesture setting and project
  navigation. With live Motion and an active cooking/DIY project, nod for
  `next_step()` or shake for `repeat_step()`. Wait until Gemini finishes speaking.
  “Disable nod navigation” disables project navigation only; confirmation gestures
  keep their own setting. Watch mode never infers nods or advances steps.

The existing **Start**, **Stop**, **Watch mode**, permission controls, transcript,
and last-tool-result display remain available. The foreground conversation service
continues across backgrounding, screen-off use and Activity recreation. Stop ends
conversation/vision; it does **not** cancel timers. Navigation requires a running conversation
and reachable backend. Existing alarms do not require Gemini, network, or the activity.

## Gesture navigation

The existing **Head gestures** switch enables confirmations and project navigation
(default project preference: on, global head gestures: off). No second Motion
stream is opened. Project opt-out persists beside Auto/PTT/Over, translation,
workout sensitivity and job cursors in Preferences DataStore.

Each accepted movement queries the backend's persisted `current_step`, then calls
`next_step` for a nod or `repeat_step` for a shake only for an active cooking/DIY
project. No project or a completed project causes no navigation. Reconnects and
service recreation use that same persisted state. One request can run at a time;
movements during a request are dropped, never queued. The returned step is sent
to Gemini as an already-executed result to read aloud, not a request to navigate
again. Results also appear in Last tool result.

A pending yes/no question owns the movement even before Gemini finishes asking,
on expiry, or if sending its answer fails. It never falls through into navigation.
Calls/focus pauses, disconnects, Stop, new speech/model output/tool calls, translation,
held PTT, active replies/playback, unavailable/disabled Motion and rep workouts
block or cancel navigation. Over uses the existing activity-end/text-turn path
and resumes listening after the reply drains. Backend side effects already sent
cannot be undone by cancellation; uncertain failures are never automatically
retried. Ask “current step” before retrying. A lost Live announcement does not
repeat the backend action.

## Tools and state

| Tool | Execution and behavior |
|---|---|
| `start_project(kind, source)` | Backend; `kind` is `cooking` or `diy`. Replaces the active project only after successful extraction. |
| `next_step()` | Backend; advances once, then marks complete after the last step. Repeated next at completion stays complete. |
| `previous_step()` | Backend; bounded at the first step; from complete returns the last step. |
| `repeat_step()`, `current_step()` | Backend; reads without advancing. |
| `how_much(ingredient)` | Backend; case-insensitive exact match, then substring candidates; ambiguity never silently picks an ingredient. |
| `scale_project(factor)` | Backend; absolute multiplier of original cooking quantities: 2 doubles, 0.5 halves, 1 resets. DIY scaling is rejected. |
| `check_progress(question)` | App; retrieves backend project and uses `look()`; image is bound to the tool response for Gemini to judge. |
| `set_timer(name, duration)` | App; duration is seconds, 1–604800. Up to 20 timers; names 1–60 characters, case-insensitive. Existing names must be explicitly canceled before replacement. |
| `list_timers()`, `cancel_timer(name)` | App; lists remaining seconds/permission state or cancels an exact named alarm. |
| `set_nod_navigation(on)` | App; persists project navigation in the existing conversation DataStore. Enabling also turns on head gestures. `motion_available` reports current readiness separately from the saved setting. |

One active project is stored in SQLite, including title, kind, ingredients and
quantities, equipment, ordered instructions, duration per step, position and scale.
State survives backend restarts and fresh Live connections. Concurrent navigation
uses SQLite transactions. Failed source retrieval/extraction preserves the old
project. This is a personal, single-user store shared by clients using the app secret;
there is no project history, account separation, or project picker.

Quantity math uses exact fractions; unknown/to-taste quantities remain unspecified.
Scaling does not multiply durations, temperatures, or equipment. Scaled inventory is
authoritative. If extracted instructions contain inline amounts or split portions,
Live is instructed to adjust them from the inventory; that spoken interpretation is
model-dependent. Unit conversion and automatically recalculating cooking times are
not implemented. Timers start only on user request, not automatically from a step.

Timers use `AlarmManager.setAlarmClock()` and synchronous preference persistence,
with unique alarm identities to reject stale broadcasts after cancellation or
replacement. Elapsed time drives remaining duration within a boot. Wall-clock
fallback supports reboot; clock-change and boot broadcasts rebase and re-arm the
alarms. Overdue alarms fire after reboot/unlock. A short media-playback foreground
service posts a named phone notification and speaks **“The pasta timer is done”**
using Android offline TTS. During a conversation it uses communication audio;
otherwise it follows the phone's media/Bluetooth route. Captured images are transient
and go directly to Live, not to the project database.

## Configuration

- Existing `GEMINI_API_KEY` and `APP_SHARED_SECRET`: backend `.env` only for the
  Gemini key; existing app authentication configuration is unchanged.
- `GEMINI_COACH_MODEL`: defaults to `gemini-3.5-flash`, verified by real extraction.
  `GEMINI_LIVE_MODEL` remains `gemini-3.8-live` by default.
- `COACH_DB_PATH`: optional absolute path; defaults to
  `backend/data/coach.sqlite3` (gitignored). Delete that database while the backend
  is stopped to clear the project.
- Backend calls use a small JSON wire schema, followed by strict Pydantic validation.
  Extraction has a 20-second HTTP timeout. No new dependencies were added.
- URL sources: public HTTPS on port 443, TLS hostname verification, pinned validated
  DNS address, no redirects/cookies/auth/JS/subresources. HTML/plain text only,
  1 MB response and 50,000-character extracted-text limits. Body reads have an
  eight-second budget. Give a final URL or dictate the recipe for unsupported pages.

## Combined merge verification

Checked **2026-10-07** after merging with main: backend `uv run pytest`
**309 passed, 2 dependency warnings in 2.15s**; Android
`assembleDebug testDebugUnitTest` **BUILD SUCCESSFUL in 19s**, **45 tasks executed**,
**160 JVM tests, zero failures/errors/skips**. Used JDK 17, the installed Android
SDK and writable temporary uv/Gradle/Android caches.

Main's shared registry advertises **35 assistant tools**, including **14 client
tools**; Translator retains exactly two translation tools. Cooking operations run
in a worker thread, preserving job/event responsiveness during extraction. The
combined prompt preserves all feature policies and leaves room for saved memory
within the existing 8,000-byte budget. `lock_additional_fields=[]` and client-owned
`sessionResumption` remain unchanged. All decision rows and research entries were
retained; no test assertions were weakened.

Regressions cover nod-next/shake-repeat for cooking and DIY, pending confirmation
priority (including unready/expired/failed answers), missing/completed projects,
in-flight duplicate movements, cancellation during lookup, uncertain backend errors,
lost announcements, Auto/PTT/Over text-turn ordering and navigation preference
persistence alongside existing settings/job cursors. Existing timer, camera,
reading, memory, journal, dashboard, workout and translator checks also pass.
No credentialed Gemini/Docs calls or physical phone/glasses checks ran during this
merge; the hardware limitations below still apply.

## Verification and limits

Run:

```sh
cd backend
uv run pytest
uv run python ../scripts/smoke_coach.py
uv run python ../scripts/smoke_vision.py
cd ../android
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew assembleDebug testDebugUnitTest
```

Verified on 2026-10-07: Android build and 41 JVM tests, 83 backend tests, Android lint,
real Gemini extraction of a synthetic pasta recipe (3 ingredients, 3 steps,
200 g pasta and an eight-minute duration), and real auto-mode ephemeral-token Live
setup plus image-bound visual answer with the expanded tool set, and a public
HTTPS fetch of example.com through the pinned TLS/HTML path. The full token
still locks populated fields with `lock_additional_fields=[]`; session resumption
remains client-owned. Test environments use writable temporary Gradle/uv caches
and `ANDROID_USER_HOME` when default cache/signing directories are sandboxed.

Unit checks cover navigation bounds/completion, persistence and concurrent updates,
exact scaling and ingredient ambiguity, URL SSRF/size/redirect validation, extraction
schema serialization, multiple timers, persistence/cancel/stale alarms, clock changes
and reboot arithmetic, and progress-check image routing/camera errors.

Unverified and follow-ups:

- `adb devices` was empty. Real alarm delivery in Doze, reboot recovery, offline TTS,
  notification permission flows, Bluetooth/HFP announcements and simultaneous Live
  speech require Pixel/glasses testing. Local TTS may overlap Live audio; it has a
  55-second service lifetime and falls back to the phone notification if unavailable.
- Muting notifications/audio, revoking alarm permission, force-stopping the app,
  powering off the phone, or missing offline voices can suppress/delay alerts.
  Force-stop needs reopening; denied alarm permission needs regranting. This code
  does not bypass Android's permission, audio-routing, or Do Not Disturb policies.
- Full spoken end-to-end project creation, watched-page transcription, generated
  fridge recipes, DIY judgments and two-minute watch sessions remain device checks.
  The existing visual smoke uses the synthetic blue-mug/orange fixture.
- Physical nod/shake navigation still needs device verification. It shares main's
  Motion detector, sensitivity, cooldown and experimental capability requirement;
  Inputs itself has no nod event. Enable Motion in Developer Center and wait for
  live samples. Rep workouts suppress both navigation and confirmation gestures.
- Photos cannot prove internal food temperature, hidden wiring, structural integrity
  or correct torque. The prompt asks for relevant measurements rather than inferring
  safety from appearance. Model extraction/generation can still make mistakes.
- Network failures can leave uncertain outcomes after server-side project changes;
  the existing tool path avoids automatic POST retries. Ask `current_step` before
  retrying navigation or project creation. Project changes are not rolled back by
  Live tool cancellation.

## Verified sources — 2026-10-07

- [Android alarm scheduling and permissions](https://developer.android.com/develop/background-work/services/alarms)
- [Android mediaPlayback foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#media)
- [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- [Gemini structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output)
- [Gemini 3.5 Flash capabilities](https://ai.google.dev/gemini-api/docs/whats-new-gemini-3.5)
- [DAT Inputs 1.0.0 artifact](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-inputs/1.0.0/), inspected locally with `javap`
- [M4 vision protocol and sources](../research/m4-vision-notes.md)

See [coach research](../research/cooking-diy-coach.md) for the verified wire-schema
finding and Motion integration notes.
