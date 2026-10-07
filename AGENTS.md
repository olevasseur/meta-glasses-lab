# AGENTS.md — instructions for coding agents (Codex, Claude, etc.)

## Project
Personal Android app for Meta smart glasses (Meta Wearables Device Access Toolkit) whose voice assistant runs on **Gemini Live**, plus a small personal backend for memory and Google Docs notes.

## Hard rules
- **Runtime AI = Gemini** (Gemini Live API). Do not add OpenAI calls to the app or backend.
- Never commit secrets: API keys, `token.json`, `client_secret*.json`, keystores. Use `.env` (see `.env.example`).
- Google refresh tokens and API keys live on the backend only, never in the Android app. The app gets short-lived Gemini ephemeral tokens from the backend.
- Read `docs/research/` and `docs/decisions.md` before designing anything. If you research something new, **write it to `docs/research/<topic>.md`** with date, source URLs, and an "unverified" list, and add it to `docs/research/README.md`.

## Layout (planned)
- `android/` — Kotlin app (DAT SDK `com.meta.wearable:mwdat-*`, Gemini Live)
- `backend/` — Python (uv) service: ephemeral tokens, memory, `append_to_doc` tool
- `scripts/` — one-off setup (Google OAuth consent)
- `docs/` — research, decisions, architecture
