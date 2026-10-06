# Decisions log

Proposed 2026-10-06 from the research in `docs/research/`. Status is "proposed" until the user confirms.

| # | Decision | Status | Why |
|---|---|---|---|
| 1 | Android first (Kotlin) | proposed | No full Xcode; DAT Android is token-free on Maven Central; adb sideload works with Developer Mode |
| 2 | **Gemini Live is the app's AI backend** (`gemini-3.8-live`) | confirmed by user 2026-10-06 | User's choice; has Gemini dev credits; cheaper; native video. OpenAI credits go to dev tooling (codex), not the app |
| 3 | Gemini `automaticActivityDetection` with END_SENSITIVITY_LOW + silenceDurationMs ~1200, push-to-talk fallback | proposed | Fixes being cut off |
| 4 | Small personal backend for tokens, memory, tools | proposed | Keeps keys/refresh tokens off the phone; memory shared across providers |
| 5 | Notes v0 = Inbox doc + alias map; fuzzy match later | proposed | Simplest working path |
| 6 | Triggers: capture-button hold / touchpad double-tap; later Voice Invocation "Hey Meta, start {app}" | proposed | Single tap reserved by glasses; Voice Invocation is experimental + needs approval |
