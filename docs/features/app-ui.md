# App UI: setup, talk and review

Implemented **2026-10-08** on `feat/ux`. Material 3 Compose screens follow the
system light/dark theme, with wallpaper-derived dynamic color on Android 12+
(including Pixel). Gemini remains the runtime AI. The conversation still belongs
to the foreground service, so switching tabs or putting the phone away does not
stop it.

## Screen map

| Destination | What is here | Voice / buttons |
| --- | --- | --- |
| **Talk** (home) | Start/Stop/Resume, Auto/PTT/Over chips, Glasses/Gemini/Backend status, transcript bubbles and last tool result. Active meeting, translator, watch, workout, saved cooking step and timer banners. | Tap Start and say “Hello”. Tap a mode chip to switch using the same service path as notification mode controls; changing mode starts a fresh conversation. In PTT, hold the screen button or use its TalkBack start/finish action. Auto has Mute/Unmute. |
| **Features** | Cards for Vision, Visual memory, Translator, Meeting, Cooking/DIY, Workout, Read-to-me and Jobs. Each includes controls already supported by that feature and a “Try saying…” example. | “What am I looking at?”, “Remember where I put my keys”, “Translate Spanish”, “Start a meeting called Design review”, “Next step”, “Start 12 squats for 3 sets”, “Read this menu”, “How are my jobs doing?” |
| **Memory & Notes** | Up to five recent facts, notes, jobs and meetings, a refresh button and Open dashboard. | Read-only snapshots. Use voice for routine saves or the authenticated web dashboard for full management. The browser requires its normal login; no credential is placed in the link. |
| **Settings** | Auto patience; Over keyword and silence; idle timeout; head gestures and sensitivity; registration and permissions; glasses controls; home shortcut; backend URL; debug mock controls; Diagnostics; Run setup again. | Stop conversation before changing turn timing. Capture HOLD and double-tap retain existing behavior. Diagnostics retains its audio, camera, Motion, log-copy and sharing controls. |

Talk's Start/Stop stays above the scrolling transcript. New transcript chunks
follow the latest message until the reader scrolls back. Active feature banners
can be swiped horizontally; the full saved cooking step and timers also appear in
Features. Saved backend state refreshes every 15 seconds while the Activity is
visible; it may briefly lag voice actions. Timers read the existing local alarm
book every second and continue independently of conversation Stop.

The **Visual memory ON** privacy strip stays above every tab, Diagnostics and
setup, including while waiting for a frame. The optional coarse place label stays
attached when moving between tabs; uncheck it in Features to stop attaching it.
No GPS collection or additional capture is introduced.

## Approvals

A new approval returns to Talk and exits Diagnostics/setup. Its card appears at
the top of the review area with **Tier 1 / Tier 2**, the complete authoritative
summary/action details, **Approve**, **Deny**, and the applicable hardware hint.
Long details scroll without ellipsis. Start/Stop remains reachable below it.
While submitting, both decision buttons are disabled. The device's existing
60-second timeout is unchanged.

- Tier 1: nod / capture / screen, or shake to deny, when hardware approval is available.
- Tier 2 (Codex): capture / screen only. The card explicitly says nod cannot approve.
- Hardware unavailable: review and tap on screen; the dashboard remains a fallback.

The card forwards the displayed request ID to the existing approval controller.
No model argument, tab change, setup action or overview read can grant approval.
See [device approvals](device-approvals.md) for notification limits and backend
payload binding.

## First run

Setup is shown once, with explicit **Continue**, **Back** and **Set up later**:

1. Explain microphone, Nearby devices, notifications and optional phone access
   before launching Android permission dialogs. App settings is available for
   permanent denial. Start stays disabled without microphone access.
2. Meta AI → Settings → App Info → tap app version five times → Developer Mode.
3. Connect glasses through the existing DAT registration flow; request DAT
   microphone/camera permissions separately. Phone-only users can continue.
4. Check the configured backend using authenticated `/diagnostics/health`.
5. Start a short “Hello” conversation, inspect connection/audio state, and confirm
   an audible response. Stop test ends it; Finish setup leaves an ongoing session
   running. Full PTT controls are in Talk after finishing setup.

Setup completion/dismissal is local to this installation and can be revisited in
Settings. It is not proof that all permissions or registrations succeeded.

## Configuration and implementation

Existing configuration is unchanged: `android/local.properties` supplies
`BACKEND_URL` and the debug `APP_SHARED_SECRET`. Settings displays the backend URL
read-only; changing it requires rebuilding. This preserves one server identity
for active conversations, approvals and outboxes. Gemini keys and Google OAuth
credentials remain backend-only. Existing release builds still have no embedded
app secret and retain their previous startup restriction.

Presentation lives in `android/app/src/main/java/com/olevasseur/glassesapp/ui/`.
MainActivity retains binding, permission launchers and intent handling. Existing
feature controllers and the Gemini protocol are retained. The ViewModel adds only
a mode-switch forwarding method and read access to the current place label.
Diagnostics notification/PCM helpers stay in the original package.

`GET /ui/overview` is protected by the existing `X-App-Secret`, host/rate limits
and `Cache-Control: no-store`. It returns bounded facts/notes/meetings/jobs and the
active saved project; note excerpts stop at 500 characters. Job execution args,
worktree paths and document credentials are omitted. Project reads open SQLite
in read-only mode and never invoke a tool or advance its step. No Google or Gemini
call is made by this endpoint. Review failures show a stale/unavailable message;
the last in-memory snapshot is kept, without a new disk cache. Jobs preserve the
backend's active-first ordering. Notes are backend-recorded appends, not a fresh
scan of Google Docs.

## Accessibility and checks

Material controls retain their accessibility roles and minimum 48 dp touch
targets. The primary Talk button is at least 64 dp. Switches/sliders have spoken
labels, screen/section titles are headings, decorative navigation symbols are
excluded from speech, and content traversal precedes bottom navigation. PTT
supports accessible click-to-start/click-to-finish as well as hold/release.
Privacy and approval headings announce changes politely. No status relies on
color alone. Wrapping controls accommodate longer labels.

Validation on **2026-10-08**, JDK 17 / SDK 36:

```text
./gradlew assembleDebug testDebugUnitTest assembleDebugAndroidTest lintDebug
BUILD SUCCESSFUL in 28s
84 actionable tasks: 14 executed, 70 up-to-date
219 JVM tests, 0 failures, 0 errors, 0 skipped
Lint: 0 errors, 26 warnings

uv run pytest
476 passed, 3 warnings in 6.80s
```

New JVM tests exercise the overview mapping and authenticated read contract.
Backend tests cover auth, empty state, bounds/order, field filtering and unchanged
project position/scale. `AppUiTest` adds offline Compose instrumentation checks
for four-tab navigation, approval-driven return to Talk, privacy visibility,
complete approval text, request-ID binding, tier hints, disabled submissions,
long-text scrolling and one-tap mode selection. Robolectric is not installed;
these UI tests are in androidTest. They **compiled but were not executed** here.

`EmulatorSmokeTest` uses stable `UiTags` for Start/Stop, Gemini, transcript,
last-tool result and Over state. It dismisses first-run setup explicitly and
retains the same DAT mute/unmute, real WAV reply, look, Over gating and Stop
scenario. Run `scripts/emulator_smoke.sh` outside this sandbox. For offline UI
checks on a device, run:

```sh
cd android
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.olevasseur.glassesapp.AppUiTest
```

Real API check: the existing isolated `smoke_diagnostics.py --live-setup` workflow
(with TestClient base URL set to localhost for the host allowlist) passed backend
health/logs/dashboard checks, real `/token` HTTP 200 and constrained Gemini
`setupComplete`. It used disposable memory/logs, no app lifespan, no queued jobs,
no Google writes, and printed no tokens. `lock_additional_fields=[]` and
client-owned `sessionResumption` are unchanged. This is protocol evidence, not
an Android UI or acoustic test.

**Unverified / follow-up:** execute both instrumentation suites outside the
sandbox; inspect light/dark/dynamic color and 200% font layouts, TalkBack order,
long approvals, rotation/tab restoration, and physical DAT onboarding on Pixel.
No fresh emulator screenshots or hardware checks were possible here. The existing
`docs/evals/emulator/*.png` were reviewed as before-state evidence. Confirm spoken
hello, live mode restarts, Bluetooth routing, gestures and background operation on
real glasses before relying on pocket-only use.

## Verified sources

Checked **2026-10-08**:

- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) — theme schemes and Android 12+ dynamic color.
- [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) — 48 dp targets, semantics and labeled controls.
- [Compose testing patterns](https://developer.android.com/develop/ui/compose/testing/common-patterns) — semantic selectors and test tags.
- [DAT registration sample at the verified SDK commit](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/MainActivity.kt) — existing registration/permission integration, inherited from [M1 research](../research/m1-android-notes.md).
- [Gemini Live reference](https://ai.google.dev/api/live) — existing minimal setup, verified again by the real smoke above.

See [UI research and unverified list](../research/app-ui.md).

## Delivery status

The requested local commit was attempted after verification, but `git add` was
blocked by the sandbox when creating the worktree's Git `index.lock` (Operation
not permitted). Changes remain uncommitted on `feat/ux`; nothing was pushed or
merged. `/private/tmp/mgl-ux-commit.sh` stages this session's explicit paths and
commits with the required Codex co-author trailer when run outside the sandbox.
The optional workflow checkpoint was not saved because the helper has no
configured host ID.
