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
| 7 | App name "the glasses app", package `com.olevasseur.glassesapp`; target Pixel 10 Pro | confirmed 2026-10-06 | User choice |
| 8 | Build order: M1 conversation + M2 notes backend in parallel, then M3 memory, M4 vision/triggers | proposed 2026-10-06 | Notes is a tool inside the conversation; backend part doesn't need glasses |
| 9 | Phone reaches backend via `adb reverse` during dev; app holds no long-lived keys (shared secret + ephemeral Gemini tokens) | proposed | Keeps keys off the phone |
| 10 | Auto defaults to 2000 ms patience (500–5000 ms configurable); add Over manual-turn mode with DAT-first sign-off recognition, shared-PCM Android fallback and a 45-second continuous-speech safety limit | confirmed by user 2026-10-07 | Avoid cutting off pauses; supersedes decision 3's proposed 1200 ms. Settings persist in DataStore; see `docs/features/over.md` for device limitations. |
