# Architecture & milestones

App name: **the glasses app** (Android package `com.olevasseur.glassesapp`). Target phone: Pixel 10 Pro.

```
Meta glasses (mic/speaker over BT HFP, camera + buttons via DAT SDK)
   ⇅ Bluetooth (via Meta AI app)
Android app  ──WebSocket──►  Gemini Live API   (audio in/out, tool calls)
   │  ▲
   │  └─ ephemeral token
   ▼
backend (Python/FastAPI on the Mac; phone reaches it via `adb reverse tcp:8787 tcp:8787`)
   ├─ POST /token            → Gemini ephemeral token (session config locked in)
   ├─ POST /tools/{name}     → append_to_doc, remember, recall, forget
   ├─ POST /transcripts      → store transcripts for memory
   └─ POST /sessions/{id}/end → summarize after final transcript upload
```

## Milestones
- **M1 — Conversation that doesn't cut you off.** Glasses mic → Gemini Live → glasses speaker. Patient VAD + push-to-talk (capture-button hold / on-screen button). Works with the DAT mock device and real glasses.
- **M2 — Notes.** `append_to_doc` tool: "add this note to <doc>" → Google Doc (Inbox doc + alias map v0, fuzzy match later).
- **M3 — Memory.** Transcripts → summaries/facts → injected at session start; `remember`/`recall` tools.
- **M4 — Vision + triggers.** "What am I looking at?" (camera frame on demand), "Hey Meta, start …" voice invocation.

## Contract between app and backend (v0)

### `POST /token`
Request: `{}` or `{"mode":"auto","silence_ms":2000}` (header `X-App-Secret: <shared secret from .env APP_SHARED_SECRET>`). Auto patience clamps to 500–5000 ms; default 2000.
Response:
```json
{"token": "<gemini ephemeral token name>", "model": "gemini-3.8-live", "expires_at": "<ISO8601>", "client_tools": ["await_gesture_confirmation", "look", "set_watch_mode"]}
```
The backend locks the session config into the token (`liveConnectConstraints`): model, system instruction, voice, VAD, tool declarations. The app connects to the Live API WebSocket with this token and sends a minimal setup message.

Session config (backend-owned):
- `realtimeInputConfig.automaticActivityDetection`: `startOfSpeechSensitivity=START_SENSITIVITY_LOW`, `endOfSpeechSensitivity=END_SENSITIVITY_LOW`, `prefixPaddingMs=300`, `silenceDurationMs=2000` by default (client patience setting applies in Auto).
- `contextWindowCompression.slidingWindow: {}`
- **Locking:** the token uses `lock_additional_fields=[]`, so only the fields above are locked (sent as `fieldMask`). `sessionResumption` is intentionally **not** locked: the app sends `sessionResumption: {}` on first connect and `{"handle": "<latest>"}` on reconnect. A global lock makes Gemini ignore the client's handle, so every ~10-minute connection rollover would silently start a fresh conversation.
- `responseModalities: ["AUDIO"]`, input + output audio transcription enabled
- Tools: function declarations (see below)

`client_tools` identifies declarations executed on Android, not `/tools`. M4 adds
`look(question)` and `set_watch_mode(on)`. Camera frames go directly to Gemini;
see [M4 vision](features/m4-vision.md) for lifecycle and image-bound tool responses.

Push-to-talk mode: app may request `{"mode":"ptt"}` → backend returns a token whose config has `automaticActivityDetection.disabled=true`; app sends `activityStart`/`activityEnd`.

Over mode: `{"mode":"over"}` also disables automatic activity detection. Local DAT speech (or Android on-device STT on shared PCM) ends a user activity on the selected trailing phrase. A local PCM speech-energy detector also ends the activity after 10 seconds of continuous silence (configurable from 5–30 seconds). There is no maximum turn length while speech continues. See [Over feature](features/over.md).

### `POST /tools/{name}`
Header `X-App-Secret`. Body = the Gemini function call `args` object. Response = JSON object the app sends back as the `functionResponse.response`.

The shared backend registry advertises notes, memory and the client-owned
`await_gesture_confirmation`, `look` and `set_watch_mode` tools in every conversation
mode. Android handles these locally; the backend rejects their execution with HTTP 400.
Memory context is included in the locked system instruction alongside the Over
sign-off, gesture and vision rules. See [memory](features/long-term-memory.md) and
[head gestures](features/head-gestures.md) for their full contracts.

`append_to_doc`:
- args: `{"doc_name": string (optional, default "Inbox"), "text": string, "confirmed_doc_id": string (optional)}`
- response: `{"status":"ok","doc_title":...}` | `{"status":"needs_confirmation","candidates":[{"id":...,"title":...}]}` | `{"status":"not_found","suggestion":"create_or_inbox"}`

### Audio formats (Gemini Live)
- Input: 16-bit PCM, 16 kHz, mono (`audio/pcm;rate=16000`). The glasses' HFP mic is 8 kHz, so resample to 16 kHz.
- Output: 16-bit PCM, 24 kHz, mono.
