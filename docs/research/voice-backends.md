# Realtime voice backends — OpenAI Realtime vs Gemini Live

- **Researched:** 2026-10-06 (subagent web research; some pages read via a summarizing fetch, so check items marked **[unverified]**)
- **Problem we're solving:** Meta AI cuts the user off (aggressive end-of-turn detection) and forgets what was said (no memory).

> **Decision (2026-10-06):** user chose **Gemini Live** as the app backend. OpenAI notes below are kept for reference only; OpenAI credits are for dev tooling (codex).

## TL;DR
- **Primary: OpenAI** (lots of credits). Start with `gpt-realtime-2.1` + `semantic_vad` eagerness `low`. Evaluate **`gpt-live-1`** (full-duplex, no separate turn detector) as the real fix for being cut off.
- **Secondary: Gemini Live**, switchable behind a provider interface. Much cheaper per minute, native video, free tier.
- **Always offer push-to-talk** as a fallback (glasses capture-button / touchpad double-tap).
- Memory and tools (Google Docs) live on **our own small backend**, shared by both providers.

## A) Gemini Live API
- **Models:** `gemini-3.8-live` (stable default), `gemini-3.8-live-extended-thinking`, `gemini-3.1-flash-live-preview` (legacy), `gemini-2.5-flash-native-audio-preview-12-2025`. https://ai.google.dev/gemini-api/docs/models
- **Transport:** WebSocket only. Ephemeral tokens via `authTokens.create` (30 min default expiry, must start a session within 1 min; lock to model/config with `liveConnectConstraints`). https://ai.google.dev/gemini-api/docs/ephemeral-tokens
- **Video:** ≤1 JPEG/PNG frame/sec.
- **Session limits:** audio-only 15 min, audio+video **2 min** unless `contextWindowCompression` (sliding window) is on. Connections last ~10 min (`GoAway` warning first). `sessionResumption` handles valid 2 h. Context: 128k native-audio, 32k others. https://ai.google.dev/gemini-api/docs/live-session
- **VAD:** `automaticActivityDetection` with `startOfSpeechSensitivity`, `endOfSpeechSensitivity`, `prefixPaddingMs`, `silenceDurationMs`. Push-to-talk: `disabled: true` + manual `activityStart`/`activityEnd`. https://ai.google.dev/gemini-api/docs/live-guide
- **Function calling:** yes.
- **Mobile SDK:** Firebase AI Logic (Swift/Kotlin/Java/Flutter/Web), Preview. Lists only 3.1-flash-live and 2.5 native-audio **[unverified whether 3.8 is supported]**. App Check required from 2026-11-02. https://firebase.google.com/docs/ai-logic/live-api
- **Pricing (paid, per 1M tokens):** input audio $3 (~$0.005/min), video $1 (~$0.002/min); output audio $12 (~$0.018/min). Free tier exists. https://ai.google.dev/gemini-api/docs/pricing

## B) OpenAI Realtime API
- **Models:**
  - `gpt-realtime-2.1` — reasoning + tools; text/audio/image in; 128k context.
  - `gpt-realtime-2.1-mini` — smaller.
  - **`gpt-live-1`** (2026-09-10) — full-duplex model behind ChatGPT Voice; listens and talks simultaneously, no separate turn detector; delegates reasoning/tools to a paired model. Reportedly +30 pts on Full Duplex Bench vs 2.1. $0.05/min voice layer + paired model. **[unverified: image input, exact model ID, which config fields apply]**
  - https://developers.openai.com/api/docs/models · https://openai.com/index/introducing-gpt-live-1-in-the-api/ · https://aicybr.com/blog/openai-gpt-live-1-api-full-duplex-voice
- **Pricing gpt-realtime-2.1 (per 1M):** audio $32 in / $0.40 cached / $64 out; text $4/$24; images $5. Rough estimate ~$0.20–0.40/min **[estimate]**. https://developers.openai.com/api/docs/models/gpt-realtime-2.1
- **Transport:** WebRTC for clients (incl. mobile), WebSocket for servers. Ephemeral tokens via `POST /v1/realtime/client_secrets`. https://developers.openai.com/api/docs/guides/realtime
- **Images:** `input_image` items. **Session max:** 60 min.
- **Push-to-talk:** `turn_detection: null`, then `input_audio_buffer.commit` + `response.create`. https://developers.openai.com/api/docs/guides/realtime-conversations
- **MCP:** remote MCP on 2.1 (`type:"mcp"`, `server_url`, `require_approval`), executed by OpenAI. https://developers.openai.com/api/docs/guides/realtime-mcp

## C) Other options
- **Pipecat / LiveKit Agents** — open-source frameworks wrapping either API, own end-of-turn models, provider switching **[not re-verified]**.
- **ElevenLabs Agents** — hosted alternative (note: our ElevenLabs credits are currently exhausted).

## Fixing "it cuts me off"
OpenAI — `semantic_vad` waits until the utterance *sounds finished*, not just a pause:
```json
{"type":"session.update","session":{"audio":{"input":{"turn_detection":
 {"type":"semantic_vad","eagerness":"low","create_response":true,"interrupt_response":true}}}}}
```
Fallback: `{"type":"server_vad","threshold":0.6,"prefix_padding_ms":300,"silence_duration_ms":1200}` (default silence is 500 ms). https://developers.openai.com/api/docs/guides/realtime-vad — session JSON nesting changed between API versions; check the current reference.

Gemini (in `setup` / `config`):
```json
{"realtimeInputConfig":{"automaticActivityDetection":{
 "disabled":false,"startOfSpeechSensitivity":"START_SENSITIVITY_LOW",
 "endOfSpeechSensitivity":"END_SENSITIVITY_LOW","prefixPaddingMs":300,"silenceDurationMs":1200}},
 "contextWindowCompression":{"slidingWindow":{}},"sessionResumption":{}}
```
Best overall: try `gpt-live-1`. Fallback: push-to-talk mode.

## Long-term memory (neither API has it)
1. Save every transcript on the backend (both APIs emit user + model transcripts).
2. At session end, a cheap text model summarizes and extracts durable facts → plain records + vector index (pgvector or similar).
3. At session start, inject a ~1–2k token profile + recent summaries into `instructions` / `systemInstruction` (cached input is cheap on OpenAI).
4. Expose `remember(fact)` / `recall(query)` tools for in-session lookups.
5. In-session: Gemini compression + resumption; OpenAI 60-min sessions + reconnect with summary.

## Architecture
- **Voice path:** phone ↔ provider directly (WebRTC for OpenAI, WebSocket for Gemini) using a backend-issued ephemeral token — saves a hop on top of Bluetooth latency.
- **Backend:** issues tokens, stores memory/transcripts, executes tools. Google refresh tokens and API keys **never** live in the app.
- **Tools:** run on backend — either as a remote MCP server OpenAI calls, or as function calls the phone relays.
- **Camera:** frames only on demand ("what am I looking at?"), ≤1 fps. Gemini's 2-min audio+video cap makes always-on video costly without compression.
