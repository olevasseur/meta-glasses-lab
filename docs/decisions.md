# Decisions log

Proposed 2026-10-06 from the research in `docs/research/`. Status is "proposed" until the user confirms.

| # | Decision | Status | Why |
|---|---|---|---|
| 1 | Android first (Kotlin) | proposed | No full Xcode; DAT Android is token-free on Maven Central; adb sideload works with Developer Mode |
| 2 | OpenAI Realtime primary, Gemini Live switchable | proposed | OpenAI credits to burn; Gemini cheaper for vision/long sessions |
| 3 | `semantic_vad` eagerness low + push-to-talk fallback; evaluate `gpt-live-1` | proposed | Fixes being cut off |
| 4 | Small personal backend for tokens, memory, tools | proposed | Keeps keys/refresh tokens off the phone; memory shared across providers |
| 5 | Notes v0 = Inbox doc + alias map; fuzzy match later | proposed | Simplest working path |
| 6 | Triggers: capture-button hold / touchpad double-tap; later Voice Invocation "Hey Meta, start {app}" | proposed | Single tap reserved by glasses; Voice Invocation is experimental + needs approval |
