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
| 10 | **Over mode end-of-turn:** turn ends ONLY when the user says the keyword ("over") OR after **10 s of silence**. No max-length cap while the user keeps talking | confirmed by user 2026-10-07 | User hates being cut off |
| 11 | Wave 2 = all 10 ideas (Codex from glasses, keys/places memory, meeting mode, translator, journal, read-to-me, cooking coach, research on demand, workout coach, dashboard); Codex agents run on `gpt-6-astra` reasoning high | confirmed by user 2026-10-07 | User wants to go big with OpenAI tokens |
| 12 | No face recognition of strangers | proposed 2026-10-07 | Privacy/legal |
| 13 | Auto defaults to 2000 ms patience (500–5000 ms configurable); add Over manual-turn mode with DAT-first sign-off recognition, shared-PCM Android fallback and end-of-turn only on the selected keyword or 10 seconds of continuous silence (5–30 seconds configurable), with no continuous-speech limit | confirmed by user 2026-10-07 | User correction: no maximum speaking duration; “over and out” ends the session. Supersedes decision 3's proposed 1200 ms. Settings persist in DataStore; see `docs/features/over.md` for device limitations. |
| 20 | **Tiered confirmations with hardware approval** — Tier 0 (append_to_doc to existing doc/Inbox, remember, recall, timers, workout, look, etc.): no confirmation. Tier 1 (forget, deep_research, journal overwrite, meeting export to new destination, visual deletion): confirmed by a nod OR capture-button press detected by the APP and posted directly to the backend with the approval_id — never by a model tool arg. Tier 2 (codex_task): capture-button press on the glasses (not nod) + task text shown in a phone notification. Dashboard approval remains as fallback for all tiers. | confirmed by user 2026-10-08 | Model-forged confirmations were a critical prompt-injection hole; physical input can't be forged by the model, and hands-free use is preserved |
