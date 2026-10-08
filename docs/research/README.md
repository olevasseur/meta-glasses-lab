# Research index

All research done 2026-10-06 by parallel subagents. Re-verify anything marked **[unverified]** before relying on it; APIs here move monthly.

| File | Topic | Headline |
|---|---|---|
| [wearables-dat-sdk.md](wearables-dat-sdk.md) | Meta Wearables Device Access Toolkit | v1.0.0 (2026-09-24). Android via Maven Central, no token. "Hey Meta, start {app}" can launch our app, but trailing speech isn't known to be forwarded. |
| [voice-backends.md](voice-backends.md) | OpenAI Realtime vs Gemini Live | OpenAI primary (`gpt-realtime-2.1` + `semantic_vad` low; evaluate `gpt-live-1`), Gemini switchable. Memory = our backend. |
| [m1-android-notes.md](m1-android-notes.md) | Gemini Live wire protocol + DAT Android integration (verified while building M1) | WebSocket endpoint, `Authorization: Token …`, message shapes, audio formats, resumption rules. |
| [m2-backend-notes.md](m2-backend-notes.md) | google-genai ephemeral tokens + Docs API (verified while building M2) | `gemini-3.8-live` works with ephemeral tokens; `lock_additional_fields` semantics. |
| [over-mode-notes.md](over-mode-notes.md) | Over turn control, DAT speech and shared PCM (2026-10-07) | DAT partial/final API verified; Android on-device PCM fallback; real manual-activity PCM session passed. Auto defaults to 2000 ms. |
| [google-docs-notes.md](google-docs-notes.md) | Voice → Google Docs | Backend function tool, Desktop OAuth client published to production, Inbox doc + alias map + rapidfuzz. Keep API is Workspace-only. |

## Reconciled conflicts
- **Token locking vs resumption (2026-10-06):** the backend initially locked the whole setup, which would make Gemini ignore the app's resumption handle. Fixed: `lock_additional_fields=[]` and `sessionResumption` left client-owned. See docs/architecture.md.
- **Custom "Hey Meta" commands:** the Docs-notes research cited an older source (UploadVR, 2025) saying no custom "Hey Meta" commands. The SDK research found that **SDK 1.0.0 added experimental Voice Invocation** ("Hey Meta, start {APP NAME}" → `LaunchApp`). Current truth: launching works (with approval); arbitrary commands are not known to be forwarded.

## Open questions to re-check
- Does Voice Invocation forward the rest of the utterance?
- `gpt-live-1`: model ID, image input, config fields.
- Firebase AI Logic support for `gemini-3.8-live`.
- Google Workspace MCP servers with personal @gmail.com.
- Meta AI Connectors: personal/private connectors, reachable by voice from glasses?
