# First glasses hardware test session

Prepared **2026-10-08** for the Pixel + Meta glasses, Gemini Live and one local
backend worker. This is the consolidated session for all 14 merged feature guides,
plus base conversation/Docs and the new diagnostics. **Boxes are unexecuted.**
Budget **100–130 minutes**, plus optional OAuth **15–25 minutes** and any Voice
Invocation approval wait. Sections 1–4 are the first useful 25-minute smoke pass;
continue in order for full acceptance. Skip optional integrations explicitly,
recording “not configured”, rather than marking them passed.

Keep this page open on the Mac, the app on the Pixel, and
**http://localhost:8787/dashboard/live-logs** in a second browser tab. Sign in with
the backend shared secret. Start with all log levels/features, then narrow when
something fails. `cd backend && uv run python ../scripts/tail_logs.py` lets the
other agent watch the same structured events. Filters are exact, not substring.

For each failure record the checkbox ID, local time, mode, visible error, actual
input/output device, and **Diagnostics → Copy logs**. Do not paste keys/tokens.
Detailed tool results/transcripts remain on the phone and relevant dashboard;
operational logs omit their contents. A passing health check establishes backend
authentication, not Gemini or Google Docs access. Do not repeat an uncertain Docs
append until you inspect its destination.

## 1. Setup and observers — 10–15 minutes

- [ ] **1.1 Mac prerequisites.** Install JDK 17, Android SDK Platform/Build Tools 36
  and platform-tools, plus `uv`. Configure root `.env` with existing Gemini key
  and shared secret. Run `./scripts/dev_up.sh` from the repo root. **Expect:** clear
  prerequisite messages, then backend, reverse, APK installation, launch and log
  tail. **If failing:** terminal message; `JAVA_HOME`, `ANDROID_HOME`, `.env` presence
  (never print values), port 8787 ownership. Script defaults to localhost, prepares
  ignored `android/local.properties`, and reuses an already authenticated backend.
- [ ] **1.2 USB authorization.** Pixel **Settings → About phone → Build number**:
  tap seven times, unlock; **Settings → System → Developer options → USB debugging**
  on. Connect a data-capable USB cable, unlock, accept **Allow USB debugging** for
  this Mac. **Expect:** `adb devices` shows one `device`, not `unauthorized`;
  `adb reverse --list` includes tcp:8787. **If failing:** cable/phone prompt;
  set `ANDROID_SERIAL` if several devices are connected, rerun dev_up after reconnect.
- [ ] **1.3 Meta Developer Mode.** Pair glasses in Meta AI, enable their Bluetooth
  calls/audio, update firmware/Meta AI. In Meta AI **Settings → App Info → App
  version** tap five times → **Developer Mode → Enable**; accept DAT install/update
  prompts. Wear/unfold the glasses. **Expect:** Developer Mode enabled. **If failing:**
  [Android setup](../android/README.md), SDK/firmware compatibility; only one
  Developer Mode integration can remain registered.
- [ ] **1.4 Permissions and registration.** Open app → **Permissions**: allow
  microphone, Nearby devices, notifications and Phone. **Connect glasses** and
  finish Meta AI registration. Grant DAT microphone and DAT camera using their
  separate buttons. Enable experimental **Inputs** and **Motion** in the Meta
  Developer Center integration if required. **Expect:** Diagnostics registration,
  connected link, battery and worn state; all requested permissions granted.
  **If failing:** `dat`, `permissions`, on-phone Meta consent/error; Inputs/Motion
  have capability entitlement rather than additional runtime permission dialogs.
- [ ] **1.5 Observer sanity.** Open **Diagnostics** and browser **Live logs**.
  **Expect:** authenticated backend reachable within one health cycle; screen-open,
  state and audio events arrive within roughly 5–10 seconds. Filter `diagnostics`,
  then `audio`, then level `ERROR`; clear filters. **Copy logs**, open **Share logs**
  and cancel chooser. **If failing:** health line, shared secret/rebuild, reverse,
  backend terminal. Browser login and app-header auth are separate. With JS/CDN
  disabled use **Filter / refresh** manually; terminal tail remains available.

## 2. Isolate audio, DAT and sensors — 5–8 minutes

- [ ] **2.1 Baseline sound.** Tap **Stop service and glasses controls**, then
  Diagnostics → **Test speaker beep**. Set a comfortable call volume. **Expect:**
  brief beep on intended glasses speaker; actual output shows its Bluetooth SCO
  device, not merely a selected/preferred name. **If failing:** `audio` events,
  actual routed output, SCO flag and communication device; disconnect other headsets.
- [ ] **2.2 Microphone.** **Record 5 s and play back**, say “Glasses microphone test,
  one two three”, then stay quiet. **Expect:** moving RMS meter, about five seconds
  of intelligible local playback; input/output identities and configured rates
  visible. HFP capture is configured at 8 kHz → 16 kHz uplink, output at 24 kHz;
  advertised hardware rates do not prove physical link rate. **If failing:** actual
  input, RECORD_AUDIO, call volume, `audio` and `diagnostics`. Repeat speaking softly
  and normally; note clipping, wind/echo and rough RMS. Recording is not uploaded.
- [ ] **2.3 Phone/mock control.** Stop, enable **Mock glasses (phone audio)**, repeat
  beep/record and Start → simulate double-tap/capture hold. **Expect:** phone routing
  explicitly, SDK input events obey modes; mock camera later shows blue mug/orange.
  **If failing:** `dat`, `audio`, existing mock resource setup. Disable mock before
  all real-glasses checks. Also repeat phone-only audio with Nearby devices denied;
  restore permission and registration afterward.
- [ ] **2.4 Sensor readiness.** Start normal Assistant Auto → Diagnostics → **Test
  camera frame**, then **Test motion (show live gyro)**. Turn head gently and wear/
  remove/re-wear glasses. **Expect:** upright local preview with dimensions, changing
  x/y/z rates, battery/worn metadata responds; no recent samples label after sensor
  loss. Exit/reopen diagnostics: its motion demand releases without disabling
  another feature. **If failing:** DAT camera permission, `vision`, `motion`, link
  state and IMU status. Do not compensate for a missing stream with sensitivity.

## 3. Conversation modes and latency — 10–12 minutes

- [ ] **3.1 Auto.** At default 2000 ms patience say “Tell me two facts about Toronto.”
  Pause about one second midway in the question. **Expect:** no premature response,
  answer on glasses, input/output transcripts and one speech-end→first-audio
  estimate in Diagnostics. Repeat three times; write observed values and perceived
  lag. **If failing:** RMS/input route → `gemini` state/token HTTP → output route;
  estimate includes patience and excludes speaker buffering.
- [ ] **3.2 Patience and barge-in.** Stop, choose 500 ms then 5000 ms patience, Start
  and compare quiet pauses. In Auto interrupt a long answer with “Stop, just give
  me one sentence.” **Expect:** configured delay changes and queued reply clears
  promptly. **If failing:** `settings`, `gemini`, audible echo versus mic RMS and
  exact playback route. Restore 2000 ms.
- [ ] **3.3 PTT.** Stop → Push-to-talk → Start. Hold screen talk, speak, release;
  repeat with double-tap to open/close. Combine screen hold and glasses toggle,
  then **Finish speaking**. Leave a toggled turn open for 60 seconds. **Expect:**
  no input outside a turn; one answer after release, both sources cleared by
  Finish, latched turn ends at safeguard. **If failing:** `dat`, `gemini`, talking
  status. DAT capture HOLD is a conversation toggle, not key-down/key-up PTT.
- [ ] **3.4 Over sign-off.** Stop → Over, “over”, silence 10 seconds → Start; wait
  for **Listening for sign-off**. Say “What is two plus two? Over.” Then speak
  continuously for >60 seconds, including several pauses shorter than 10 seconds.
  **Expect:** only sign-off or continuous silence ends turn; no maximum speech
  length, no double-tap end/mute. Finish a later turn with “over and out”.
  **If failing:** `over`, DAT mic permission, offline English recognizer on Pixel,
  RMS/energy threshold; session should stop on the full phrase.
- [ ] **3.5 Over configuration/noise.** Select “your turn”, silence 5 seconds;
  test “clover” and “this goes over there” without trailing active sign-off, then
  “Your turn.” Repeat default ten-second silence in quiet, with breathing and mild
  background noise. **Expect:** only whole trailing active phrase commits; brief
  pauses don't accumulate. Quiet/no recognition can finish by silence. **If failing:**
  `over`, RMS, local recognizer availability, note environment for calibration.
  Restart app/cold tile later to verify settings persist. Restore defaults.

## 4. Core lifecycle, interruptions and launches — 15–20 minutes

- [ ] **4.1 Service continuity.** Auto Start; converse with screen locked for two
  minutes, switch apps, rotate, reopen, then swipe away Activity. **Expect:** one
  conversation/notification, ongoing transcripts, no duplicate mic. **If failing:**
  `hands-free`, `gemini`, `audio`; `adb shell dumpsys activity services com.olevasseur.glassesapp`.
- [ ] **4.2 Physical controls.** Arm **Enable glasses controls**, lock screen; capture
  HOLD start/stop/start. Try capture short/double press and reserved touchpad single
  tap/tap-and-hold. **Expect:** only HOLD toggles conversation; touchpad reserved
  controls pause/resume or stop DAT per glasses behavior; HFP audio continues.
  HOLD-stop leaves controls armed; DAT stop automatically retries. **If failing:** Inputs ACTIVE and
  `dat`/`hands-free`; record actual model-specific reserved gesture mapping.
- [ ] **4.3 Mute and mode controls.** Auto double-tap, say a unique test phrase,
  double-tap again. Cycle notification Auto → PTT → Over; rapidly switch during
  connection/stop. **Expect:** muted phrase absent from transcription though local
  RMS still moves; each mode gets fresh Gemini setup, no recorder overlap, saved
  settings retained. **If failing:** `settings`, `hands-free`, `gemini`, transcript.
- [ ] **4.4 Calls/focus.** Ring, reject, answer and hang up a test call; start while
  already in a call; have another app take focus (including duck requests). Repeat
  with Phone permission denied. Stop app while paused, then end call. **Expect:**
  no call audio sent/played by Gemini, resume only after both focus and call clear;
  denied Phone relies on focus; Stop prevents auto-restart. **If failing:** pause
  reasons, `audio`/`hands-free`, permissions. Repeat during an Over user turn and
  reply: recognition pauses; reply drain precedes reopening the next user turn.
- [ ] **4.5 Bluetooth and DAT loss.** During user speech and again during a reply,
  disconnect/power off glasses; reconnect and tap **Resume**. Disconnect DAT alone.
  **Expect:** actual Bluetooth loss pauses/flushed reply, no silent fallback to phone
  capture; explicit Resume reselects route. DAT-only loss keeps audio running and
  retries controls automatically. **If failing:** actual device IDs,
  `dat`, `hands-free`, `audio`; separately distinguish OS Bluetooth from DAT link.
- [ ] **4.6 Network and resumption.** Tell Gemini “The test code is blue lighthouse.”
  Briefly disable phone network then restore; later keep conversing through a
  >10-minute connection rollover while doing sections 5–9. Ask the test code again.
  **Expect:** Connecting/Resuming and a handle age, preserved recent context where
  a handle exists; dropped in-flight audio is not replayed. **If failing:** `gemini`,
  backend health, handle age; no handle means a fresh session. Resumption snapshots
  do not guarantee the very latest unfinished words.
- [ ] **4.7 Idle and complete stop.** Temporarily set idle to one minute. Check silent
  Auto, PTT waiting, muted, armed-without-Gemini, and paused cases (~60–61 seconds
  each). Speak/act near 45 seconds in another run. **Expect:** inactivity fully
  stops; activity extends deadline; continuous Over speech remains active. Press
  notification Stop while locked. **If failing:** `hands-free`, mic indicator,
  `adb shell dumpsys power` for `glassesapp:conversation` wake lock. A bound idle
  service can exist but must not record. Restore five minutes.
- [ ] **4.8 Cold launch/denials.** Add Quick Settings **Glasses conversation**, launcher
  **Start conversation** shortcut and pinned shortcut. Test cold/warm/locked entry;
  repeat microphone denied and notifications denied. Force-stop package or use
  Android active-app Stop, then relaunch explicitly. **Expect:** permissions/unlock
  flow without crash, one stream; no spontaneous restart after force-stop.
  **If failing:** `permissions`, `hands-free`, Android permission/service state.
- [ ] **4.9 Optional Voice Invocation.** After Developer Center approval/configured
  spoken name, say “Hey Meta, start the glasses app” cold, warm, locked and already
  conversing; repeat rapidly, with denied mic and backend offline. **Expect:** one
  accepted launch/acknowledgement and one stream, clear failures. Speak the actual
  request after chime; trailing launch words are not assumed forwarded. **If failing:**
  Voice Invocation entitlement/package configuration, `dat`, `hands-free`, `gemini`.
  Mark unconfigured if approval absent; do not block the rest of the session.

## 5. Vision, watch and reading — 8–10 minutes

- [ ] **5.1 Look.** In Assistant show a colored mug and fruit: “What am I looking at?”
  then “What color is the mug?” Repeat in PTT/Over, ending their turns. **Expect:**
  fresh useful description, not only “taking a look”; image orientation correct.
  **If failing:** Diagnostics camera preview, `vision`, tool start/finish, DAT
  permission and Gemini connected. Denied camera must give an honest error.
- [ ] **5.2 Watch/shared lifecycle.** “Turn on watch mode”; change scene, ask about it,
  toggle gestures off/on, rotate/background/lock, say “Stop watching”. Re-enable
  and let its two-minute cap expire; repeat enable must not extend it. **Expect:**
  shared camera continues despite gesture toggles, no capture backlog, idle camera
  closes after five seconds; pause/disconnect/Stop ends watch. **If failing:**
  `vision`, `motion`, camera state, watch flag; re-enable explicitly after a pause.
- [ ] **5.3 Reading modes.** Present a clear printed menu: “Read everything visible”,
  “Summarize this page”, “How much is the pad thai?” Then French text: “Read this
  French text in English.” Use a synthetic Wi-Fi card if testing specific answers.
  **Expect:** reading order/price/translation matches visible fixture. **If failing:**
  `read-to-me`, camera preview, framing/light; verify actual HIGH-quality capture,
  dense/column text and upright HEIC handling.
- [ ] **5.4 Reading controls/retries.** On a long page say “Stop”, “Keep reading”,
  “Repeat that”; turn to a distinct page and say “Next page”. Deliberately blur/
  cover text once. **Expect:** interrupted chunk repeats without skipped text,
  next page captures fresh; up to two blur retries then honest failure. Call/pause/
  reconnect retains reading position; full Stop clears it. **If failing:**
  `read-to-me`, last tool result, page/chunk behavior; exhausted-page Live routing
  remains a known area to verify carefully.

## 6. Gestures, memory and visual history — 8–12 minutes

- [ ] **6.1 Calibrate gestures.** Enable head gestures; with IMU live ask “Ask me
  whether I want blue and let me answer with a head gesture.” Wait until question
  ends, still briefly, nod down/back. Repeat with shake. Walk/turn head casually,
  adjust sensitivity and repeat in PTT/Over. **Expect:** one correct yes/no,
  incidental movement does not answer; stale/expired questions and call pauses
  cannot confirm. Outside a question, no action unless project navigation active.
  **If failing:** `motion`, `gestures`, Last gesture, pending question, sensitivity.
- [ ] **6.2 Durable memory.** “Remember that my test dog is named Maple.” Recall,
  correct its test name, Stop/Start, recall again. “Forget my test dog's name” and
  approve the specific candidate, once by voice and once with a valid nod on a
  separate harmless fact. **Expect:** saved/corrected memory survives sessions,
  deletion asks first and suppresses later recall. **If failing:** `memory`, backend
  HTTP, dashboard Memory/Conversations; raw archived transcripts still remain.
- [ ] **6.3 Offline memory recovery.** Stop backend briefly while speaking a test
  phrase; stop/reopen app, restore backend/reverse. **Expect:** pending outbox
  retries, ordered transcript eventually visible once, session closes after uploads.
  **If failing:** `memory`, backend health and on-phone pending-upload error; don't
  clear app data before inspecting recovery.
- [ ] **6.4 Keys and places.** Put keys in view: “Turn on visual memory for ten
  minutes”; look elsewhere then “Where did I leave my keys?” Point at a parking
  sign: “Remember where I parked.” Optionally attach coarse “home” label on phone.
  **Expect:** capture banner/chime, sighting times and evidence, pinned one-shot
  works even when periodic capture off; no invented sighting of an unseen object.
  **If failing:** `visual-memory`, `vision`, `motion`, dashboard Visual memory.
- [ ] **6.5 Visual retention/privacy lifecycle.** Remain still then move: check
  duplicate/still filtering; “Pause visual memory”, resume explicitly, switch
  translator/meeting or take a call, then stop. **Expect:** mode turns off on those
  transitions and does not silently restart; foreground banner/notification agrees.
  Browse/search/pin/unpin a test thumbnail; pause capture and **Delete last hour**
  or delete a test range in dashboard. **Expect:** test records/thumbnails removed
  including pins, queued old captures do not resurrect them. **If failing:**
  `visual-memory`, heartbeat ON/OFF/UNKNOWN (stale after 60 s), date offset/filter
  and backend time. Deletion does not erase external notes or spoken transcript.

## 7. Translation — 4–6 minutes

- [ ] **7.1 Two-way interpreter.** “Translate Spanish”; say “Where is the station?”
  then “La estación está cerca.” **Expect:** Spanish then English translations,
  audible language labels, no answer to translated questions, no notes/memory
  tools in translator. Change language using chooser, then **Assistant** or “Stop
  translation”. **If failing:** `translator`, `gemini`, persona flag; wait Connected
  after each switch. Repeat one pair in PTT and Over.
- [ ] **7.2 Public output/interruptions.** Enable **Other-language translation on
  phone speaker**, alternate English/Spanish. Stop mid-playback, change persona,
  disconnect Bluetooth and force one network reconnect. **Expect:** other language
  on phone, English on normal route, mic forwarding paused during public playback,
  original glasses mic restored; no old-persona or interrupted audio replay.
  **If failing:** Diagnostics phone translation output plus communication/input
  device, `translator`/`audio`; missing label uses normal route. Restore switch off.

## 8. Cooking/DIY and workout — 8–12 minutes

- [ ] **8.1 Project navigation.** “Start a cooking project: 200 grams of pasta and
  one tablespoon of oil. Boil water, cook pasta eight minutes, drain and add oil.”
  “Next step”, “Previous step”, “Repeat that”, “Where am I?”, “Double the recipe”,
  “Go back to original amounts”. Try a printed recipe and an explicit public HTTPS
  DIY page; ask for a simple recipe from visible ingredients. **Expect:** coherent
  persisted steps/quantities, no duplicate advance. **If failing:** `coach`, backend
  HTTP, last result; query current step before retrying an uncertain action.
- [ ] **8.2 Project vision/gestures.** “Does this look done?” on a harmless test
  fixture; “Enable nod navigation”, then nod next/shake repeat when Gemini idle.
  Ask a yes/no question before another nod, start a rep workout later and try
  navigation again. **Expect:** fresh frame for progress, confirmation takes
  precedence, exercise/held PTT/reply blocks navigation. “Disable nod navigation”
  leaves confirmations available. **If failing:** `coach`, `vision`, `gestures`,
  motion freshness/pending question. Verify Over gesture ends its open turn once.
- [ ] **8.3 Timers.** Grant **Allow precise timers → Alarms & reminders**, notification
  permission and installed offline TTS voice. “Set a test timer for 20 seconds”;
  “List my timers”; create/cancel another. Lock screen and Stop conversation before
  first expiry; briefly disconnect network. **Expect:** timer still fires once
  with alert/TTS, cancelled timer does not. **If failing:** `coach`, alarm permission,
  notification channel/volume, TTS configuration; note any overlap with Gemini.
- [ ] **8.4 Reps.** “Start five squats for two sets with ten seconds rest.” Count
  manually, including intentional stillness/walking between sets. Repeat a few
  comfortable reps of push-ups, sit-ups/crunches, lunges, jumping jacks, burpees and
  generic counting as appropriate. **Expect:** wait for IMU live, no counts during
  rest/loss, displayed counts close to manual labels; gesture confirmations paused
  during rep workout. **If failing:** `workout`, live gyro, sensitivity; record
  missed/double counts and exercise, do not treat synthetic accuracy as hardware proof.
- [ ] **8.5 Timed workout/history.** “Time a 20-second plank”, then “Ten on, five off,
  two rounds.” Background/rotate; ask workout status, Stop workout partway through
  another, then “How many squats did I do this week?” **Expect:** local timing/count
  survives UI changes, no final rest, saved partial history; spoken cues can lag.
  Call/Stop/persona change ends with interrupted log; Gemini-only reconnect retains
  workout. Turn gestures off during workout/watch. **If failing:** `workout`,
  `memory`, current timer/IMU, network; one shared stream must keep surviving owners.

## 9. Dashboard and remote-log recovery — 4–6 minutes

- [ ] **9.1 Browse and edit.** Dashboard Conversations search → transcript; Memory
  add/edit/delete a harmless test fact (confirm deletion); verify two-tab stale edit
  conflict. Status → **Test Gemini token**. **Expect:** real data and sanitized token
  result; no credentials rendered. **If failing:** browser login/CSRF, backend
  Status/error list and `memory`/`gemini`; reload after stale edit/session expiry.
- [ ] **9.2 Settings and narrow screen.** View dashboard on Pixel via reverse; test
  light/dark and no-JS forms. Save a temporary default mode then restore; confirm
  Android explicit DataStore mode still wins. **Expect:** usable layout, persistent
  preferences, exact alias/allowlist validation. **If failing:** dashboard Status,
  settings files (do not paste private repo paths/IDs), browser console/CSP.
- [ ] **9.3 Logs offline/rotation.** Stop backend for 15 seconds while producing
  harmless state changes; restore. **Expect:** phone says unreachable; same pending
  IDs retry and viewer resumes with retained events. Restart backend and log back
  into browser; retained JSONL window returns. **If failing:** Diagnostics upload
  line, `backend/data/logs`, file permissions, `tail_logs.py --once`. Long outages
  can lose evicted RAM events; viewer window is 2,000, disk ~6 MiB. Do not flood a
  live hardware session just to force rotation (unit tests exercise it).

## 10. Optional Google OAuth preparation — 15–25 minutes

Do this now if Docs, journal, meeting export or research export are desired and
not configured. Otherwise label those exports **not configured**; audio, local
memory, camera, motion, translations and local meeting capture remain testable.
The merged backend needs **three scopes**, including `drive.file` for meeting
folder creation. Menu labels checked against official documentation **2026-10-08**;
account policy/Console UI variations and actual consent remain hardware-session work.

- [ ] **10.1 Project/APIs.** Open [Cloud Console](https://console.cloud.google.com/),
  top project selector → **New Project** → name “Glasses personal” → **Create** →
  select it. **☰ → APIs & Services → Library** → search **Google Docs API** →
  **Enable**; repeat for **Google Drive API**. **Expect:** both enabled for the same
  project. **If failing:** project selector and APIs & Services enabled list.
  [Official API setup](https://developers.google.com/workspace/guides/enable-apis).
- [ ] **10.2 Consent branding/audience.** **☰ → Google Auth platform → Branding →
  Get Started**. Enter app name, your support email → **Next**; choose **External**
  → **Next**; contact email → **Next**; review policy checkbox → **Continue → Create**.
  If already configured, edit Branding/Audience directly. **Expect:** saved External
  app. **If failing:** required email fields/project ownership.
  [Official consent setup](https://developers.google.com/workspace/guides/configure-oauth-consent).
- [ ] **10.3 Scopes/production.** **Google Auth platform → Data Access → Add or Remove
  Scopes**. Add `https://www.googleapis.com/auth/documents`,
  `https://www.googleapis.com/auth/drive.metadata.readonly`, and
  `https://www.googleapis.com/auth/drive.file` (manual entry if needed) →
  **Update → Save**. **Audience → Publishing status → Publish app → Confirm**;
  ensure **In production** before offline consent. **Expect:** all three scopes
  and Production. **If failing:** account policy/verification status; personal use
  may show an unverified-app warning. External Testing tokens for these scopes
  expire after seven days. [Scope setup](https://developers.google.com/workspace/guides/configure-oauth-consent),
  [token lifetime](https://developers.google.com/identity/protocols/oauth2#expiration).
- [ ] **10.4 Desktop credential.** **Google Auth platform → Clients → Create Client
  → Application type: Desktop app**; name “Glasses Mac” → **Create**. Download JSON
  from the created client and save locally as `backend/secrets/client_secret.json`.
  **Expect:** Desktop client file only on Mac; no Android OAuth client needed for
  this backend flow. **If failing:** selected client type/download path. Never
  commit it. [Official client creation](https://developers.google.com/workspace/guides/create-credentials).
- [ ] **10.5 Offline consent.** From `backend/`, run `uv run python
  ../scripts/google_auth.py`. In browser select your account; for your own known
  personal unverified app, use **Advanced → Go to …** if shown, grant all requested
  access, Continue. **Expect:** owner-only `backend/secrets/token.json`, script
  confirms completion. Restart backend (and dev_up/reverse if needed). **If failing:**
  script's scope/offline-access message, dashboard Google status. If previously
  consented in Testing or with only two scopes, run again after publishing; no
  assumption that the old refresh token gains new lifetime/scopes automatically.

## 11. Notes, meeting, journal and background work — 15–20 minutes

Use harmless explicitly named test content. Google-dependent rows require section
10; Codex jobs require installed/logged-in CLI and an explicitly allowlisted test
repository. Do not use a production repository for the hardware smoke task.

- [ ] **11.1 Notes.** “Add this note to Inbox: hardware test passed at this step.”
  Verify one timestamped append to **Glasses Inbox** and dashboard Notes. Add an
  exact alias in dashboard Settings, then test that alias; use two similarly named
  test Docs to exercise disambiguation and a missing title to exercise not-found.
  **Expect:** no write before selecting/confirming destination; inspect Doc after
  any failure before retrying. **If failing:** `notes`, on-phone last tool result,
  Google status, alias JSON and Notes history.
- [ ] **11.2 Meeting silence/addressing.** “Start a meeting called Hardware pilot”,
  phone mic on table. Speak ordinary discussion with a decision, owner/due date
  and open question; then “Hey assistant, what is two plus two?” Try optional
  meeting “over” address setting in a second run. **Expect:** normal discussion
  yields transcript without assistant audio/tools; only direct address opens one
  reply. **If failing:** `meeting`, actual phone input/RMS, live transcript and
  last tool; glasses mic naturally favors wearer over others.
- [ ] **11.3 Meeting minutes/recovery.** Lock/rotate during capture, briefly lose
  network, resume then **Stop meeting & prepare minutes** (also test main Stop).
  **Expect:** final chunks drain, readable minutes/action owners/dates in dashboard
  Meetings, one **Meeting – …** Doc in **Glasses Meetings**, spoken recap when
  ready/next Start. Reopen after interrupted finalization: no duplicate export.
  **If failing:** `meeting`, `memory`, pending outbox, three-scope OAuth and dashboard
  processing state. Missing Docs credentials must remain a visible pending/error.
- [ ] **11.4 Journal and morning brief.** After uploads, “Write my journal”, read
  today's entry, “Give me my morning brief”. Repeat write for this test day and
  verify one date block replaced in **Glasses Journal**, not a duplicate; local
  tasks correspond to spoken evidence. Test missing past day and invalid future
  date. **Expect:** honest missing entry, synced versus pending Docs status,
  morning tasks from journal (not Calendar). **If failing:** `journal`, notes/memory
  uploads, configured timezone/date and Google status. Rewriting replaces manual
  edits inside that day's generated block.
- [ ] **11.5 Scheduled journal.** Set dashboard Journal time a few minutes ahead;
  leave backend running/awake with `JOURNAL_ENABLED=true`, verify save, restore
  normal schedule. **Expect:** one day's block refreshed and scheduler timezone
  respected. **If failing:** backend terminal/scheduler configuration, dashboard
  time override and pending journal; voice tool success alone does not prove scheduler.
- [ ] **11.6 Research job.** “Research the capital of Canada using an official source.”
  Continue talking while it runs, ask job status; repeat while holding a manual
  turn or translator active. **Expect:** durable queued/running/completed state,
  idle spoken summary or notification, sourced report/Docs save status; without
  Google credentials report is local only. **If failing:** `jobs`, backend job
  files, logged-in CLI, notifications permission; no announcement into translator.
- [ ] **11.7 Explicit coding task/cancel.** Configure a disposable allowlisted repo
  via Settings. “Ask Codex to add a hello-world test in [alias].” Inspect spoken
  repo/task preview, decline once; repeat and approve exact request by yes/nod.
  Query/cancel an active job. **Expect:** no execution before approval, retained
  separate worktree, cancellation keeps partial files, no push/merge/commit.
  **If failing:** `jobs`, allowlist, CLI process/sandbox errors, job log/status.
  Unknown/ambiguous alias must fail or ask, not select an arbitrary repository.

## 12. Combined stress and closeout — 5–8 minutes

- [ ] **12.1 Shared capabilities.** In Over, enable gestures and watch, then
  background/rotate/lock. Perform one memory or note command, a gesture answer,
  and a camera question; turn gestures off while watch continues. Repeat with
  visual memory/workout demand. **Expect:** single shared DAT capabilities,
  surviving owners keep samples; reply/turn ordering remains correct. Receive a
  call: watch/visual capture stop, pending confirmation clears; enable explicitly
  after resume. **If failing:** `over`, `vision`, `motion`, `workout`, `dat`, `hands-free`.
- [ ] **12.2 Final release.** Stop conversation/service, stop any remaining test
  timers/workout, disable visual memory/watch, ensure backend uploads complete.
  **Expect:** microphone indicator off, camera off, no held conversation wake lock,
  no unexpected session restart. Copy logs, record battery delta, highest observed
  latency, false gestures/counts, failed IDs, skipped configurations, firmware and
  phone version. **If failing:** Diagnostics final route/state, `adb dumpsys` from
  4.1/4.7; capture OS `adb logcat` separately for native crashes.

## Coverage sources

Behavior/checklists reconciled from all existing feature guides on **2026-10-08**:
[Over](features/over.md), [hands-free](features/hands-free.md),
[head gestures](features/head-gestures.md), [vision](features/m4-vision.md),
[memory](features/long-term-memory.md), [visual memory](features/visual-memory.md),
[meeting](features/meeting-mode.md), [translator](features/live-translator.md),
[reading](features/read-to-me.md), [cooking/DIY](features/cooking-diy-coach.md),
[workout](features/workout-coach.md), [journal](features/end-of-day-journal.md),
[background jobs](features/background-jobs.md), [dashboard](features/dashboard.md),
plus [Android setup](../android/README.md), [backend setup](../backend/README.md),
[current diagnostics](features/diagnostics.md) and
[verified protocol](research/m1-android-notes.md).
Older milestone test counts/tool counts and 1200 ms VAD descriptions in historical
README sections are not the merged behavior: default Auto patience is 2000 ms,
and token resumption remains client-owned with `lock_additional_fields=[]`.

## DAT/HFP regression retest — 2026-10-09

Target: **Pixel 10 Pro + RB Meta 01G8**, DAT 1.0.0, COMPATIBLE firmware.
These checks are **not yet executed**. Record exact firmware, Meta AI version,
Android build, mode, Speech permission and start method for each run. Original
failure: HOLD 13:49:41.279 UTC → HFP ready 41.867 → device-ended DAT 42.982 → app
paused 43.047 → Gemini connected 43.57 with no audio; next HOLD took a photo.

- [ ] **D1 Reproduce the original sequence.** Start with camera/watch/visual memory
  off. Arm controls; wait for STARTED / ACTIVE. In Over, HOLD capture once, speak
  continuously through connection, then say “What is two plus two? Over.”
  **Expect:** HFP input/output stay on glasses and Gemini replies even if DAT ends.
  No input-loss-driven `paused=true`, “Audio paused”, SCO release or NORMAL mode.
  The Talk screen and notification explicitly show unavailable controls.
- [ ] **D2 Recovery with HFP still active.** Keep the conversation running for at
  least two minutes. **Expect:** `dat` attempt/acquiring, SDK code + description,
  session transitions, release and outcome/retry events; delays after each failed
  attempt are 1/2/5/10/30 seconds (plus time spent acquiring), capped at 30 seconds.
  After STARTED / ACTIVE, HOLD stops the conversation; another HOLD restarts it.
  No Stop/re-arm or phone Start should be necessary to restore inputs. Record any
  system photos before recovery, DAT restart tones, repeated refusal codes or
  microphone silence after reattachment. A session recovering is not a pass if
  HFP PCM becomes silent or speech stops reaching Gemini.
- [ ] **D3 Working fallback controls.** While DAT is unavailable, test notification
  Stop and screen Stop; restart on screen. In Auto test notification/screen
  Mute/Unmute; in PTT leave a turn open across DAT loss, keep speaking, then Finish
  from notification/screen. **Expect:** DAT loss does not end PTT, all these controls
  work, no duplicate Gemini connection or audio recorder. Stop cancels all retries.
- [ ] **D4 Separate the trigger.** Repeat Auto, PTT and Over with (a) already-armed
  controls + HOLD, (b) already-armed controls + screen Start, (c) screen Start
  without pre-arming. Compare HFP-ready, Speech state and session-error timestamps.
  Repeat Over with DAT microphone permission denied to exercise shared-PCM keyword
  fallback. Record whether HFP-first reacquisition works; do not infer capture HOLD
  is the cause merely because it preceded HFP.
- [ ] **D5 Pause, stop, timeout and cancellation.** Single temple tap pauses DAT;
  audio continues, no repeated acquisition while PAUSED; tap again restores inputs.
  Temple tap-and-hold stops DAT; it retries while armed. Force a startup timeout
  with glasses unavailable, then restore availability: Inputs returns automatically.
  Disarm during backoff, wait >30 seconds and verify no new acquisition. Rapid
  Stop/re-arm must leave one owner; idle expiry also cancels retries.
- [ ] **D6 Independent audio protections.** Remove the actual Bluetooth audio route,
  receive a call and lose audio focus separately. **Expect:** existing audio pauses
  remain; DAT recovery never resumes through those guards or switches to phone mic.
  Reconnect and explicitly Resume after real Bluetooth loss.
- [ ] **D7 Capture evidence.** Start unfiltered `adb logcat -v threadtime` before
  arming (save locally, sanitize before sharing), then search the saved file for
  `DAT:`. Do not rely on colon-containing logcat tag filters or `log.tag.*` to enable
  SDK output. Keep app Diagnostics → Copy logs and backend `dat`, `audio`,
  `hands-free`, `gemini` events with UTC timestamps. Capture 30 seconds before
  failure through a successful recovery or two capped retries. If DAT never
  recovers, keep fallback audio working, record exact errors, then Stop and restart
  the glasses; do not loop global SDK reset/unregistration.

Sources and limits: [DAT session/HFP research](research/dat-session-hfp.md).

## Over latency retest — 2026-10-09

- [ ] **OVER-LATENCY-1 — Pixel 10 Pro + Ray-Ban Meta, HFP 8→16 kHz.** Install the
  fixed APK, confirm offline English STT, start Over and immediately say “Can you
  hear me? Over.” Repeat on at least ten turns, including “Over” in the first
  second after playback drains. No recurring ~8-second Preparing interval;
  no repeated request needed. Also test “over and out”, the 10-second silence
  fallback, a long model reply (no echo-triggered turn), phone call pause/resume,
  reconnect, and an on-device service timeout/restart. On restart, audio spoken
  before Ready must still produce its sign-off or an explicit buffer/service
  error. Capture `over`, `live-turn`, and `latency` logs: detected sign-off →
  activityEnd queued should occur in the same callback; one Start/End per turn;
  no input bytes sent after End; first inputTranscription, first model content,
  first audio and turnComplete must all be timestamped without transcript text.
  Record median/p95 End→first audio and perceived speaker delay separately;
  target typical End→audio <2 s. Note queue_bytes on any slow End, network state,
  and whether input transcription was also delayed. The 1.2-second “and out”
  grace precedes End and must not be counted as provider time. Simulated WAV
  checks do not mark this hardware checkbox complete.

## Voice stop after DAT/HFP loss — 2026-10-09

- [ ] On Pixel 10 Pro + Ray-Ban Meta, arm controls, start Auto, and confirm the
  known DAT loss after HFP opens. With the phone locked, say “goodbye” / “stop
  listening” / “end conversation” / “that's all, thanks”. Hear a short goodbye;
  verify mic/SCO release within four seconds of tool acceptance, conversation
  closure and memory summary upload, while controls remain ARMED. Wait for DAT
  recovery and start a new conversation with capture HOLD. Repeat in PTT with
  an open/finished Talk turn and in Over with “goodbye, over”. Check “stop” as
  interruption, “don't stop listening”, and “I said goodbye to my mom” do not
  close it. During a meeting, a goodbye must leave capture running; explicitly
  “stop the meeting” must still use the existing meeting stop/minutes path.
