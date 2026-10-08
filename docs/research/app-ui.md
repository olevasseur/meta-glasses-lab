# App UI research

Date: **2026-10-08**. Scope: restructure the existing phone presentation without
changing runtime AI, feature execution, service ownership or approval authority.

Official Android pages read during implementation:

- https://developer.android.com/develop/ui/compose/designsystems/material3
  Material 3 provides schemes for light/dark appearance and dynamic schemes on
  Android 12+. Use the installed Compose BOM; no UI runtime dependency upgrade.
- https://developer.android.com/develop/ui/compose/accessibility/api-defaults
  Interactive controls need 48 dp targets. Material includes semantic defaults;
  label switches/sliders and omit descriptions for decorative graphics.
- https://developer.android.com/develop/ui/compose/testing/common-patterns
  Stable semantic tags let UI tests target controls independent of visible copy.

Local evidence: all feature guides, architecture/decisions, Android review,
approval/diagnostics contracts, and the seven before-state emulator PNGs. Existing
service `setMode` already reconciles live mode changes from notifications; the
Talk chips forward to that same method. `Projects.run("current_step")` performs
an UPDATE even for a read, so the new overview endpoint reads the saved row using
a read-only SQLite connection. Jobs owns an event-loop-thread SQLite connection;
its status read stays on that thread while other storage reads run in a worker.

DAT setup evidence is inherited from the verified SDK commit, without assuming
new API behavior:
https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/CameraAccess/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/MainActivity.kt

Real Auto `/token` plus constrained Gemini `setupComplete` passed again using the
existing credential-safe diagnostics smoke and disposable memory/logs. Source:
https://ai.google.dev/api/live . Minimal setup and resumption remain unchanged;
see [M1](m1-android-notes.md), [M2](m2-backend-notes.md), and the current architecture
for the corrected token locking policy.

## Unverified

- Compose instrumentation execution and fresh screen rendering: emulator execution
  is unavailable in this sandbox; the instrumentation APK compiles.
- Pixel dynamic colors, large fonts, TalkBack traversal, scroll/focus behavior and
  activity recreation need visual/device checks.
- DAT registration/permission screens, acoustic hello, live mode restart timing,
  physical nod/capture approvals and Bluetooth routing need real glasses.
- Read-only overview tests use isolated SQLite fixtures; personal archive content
  and live browser rendering were not inspected.
