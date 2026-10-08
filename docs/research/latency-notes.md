# Conversation latency research

Verified **2026-10-08** using repository code, installed google-genai 2.28.0,
official references and the real constrained Gemini Live endpoint.

- [Ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens):
  `uses=1` permits one new session; expiration for starting it differs from the
  lifetime of an established/resumed session. Our tokens retain the 60-second
  new-session window, 30-minute lifetime and client-owned sessionResumption.
  Warming starts a session promptly after Start/capture, rather than storing
  unconsumed tokens with stale memory, persona or mode constraints.
- [Gen AI SDK client lifecycle](https://googleapis.github.io/python-genai/): a
  client supports repeated requests; leaving its context closes its sync
  transport. Installed `HttpOptions.client_args` accepts HTTPX transport options.
- [HTTPX resource limits](https://www.python-httpx.org/advanced/resource-limits/):
  default idle keepalive is only five seconds. This is shorter than our voice
  turns. The token transport explicitly retains idle connections for 120 seconds
  with at most two connections, closes at backend shutdown and rotates when its
  key or timeout changes. A lock serializes requests with rotation/close.
- [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack):
  effective streaming buffer size can be reduced separately from capacity;
  underrun counts support increasing it again. API 31+ allows a separate start
  threshold. Request one frame for first-write playback, keep a 40 ms target
  bounded below by the reported device minimum, and reserve at least 200 ms
  capacity. On consecutive queued observations, a new underrun grows the effective
  buffer by 20 ms. Idle gaps do not grow it. No application jitter-fill wait is
  added. Device routing can change the actual values.
- [AudioManager](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)):
  route negotiation is a separate OS operation. App code previously waited for
  route setup before requesting a token; it now starts Live before the route wait.
- [Live protocol](https://ai.google.dev/api/live): client `activityEnd` gives a
  timestampable manual turn boundary. The events used by this app do not provide
  a precise server VAD boundary. Auto's 2000 ms is reported as a configured
  estimate; the remaining interval includes model, transport and any tools, not
  pure server compute. The headless probe uses the fixture endpoint, whereas
  Android uses local speech energy to estimate the last speech time.

Repository findings: GeminiSession already launches independent tools as separate
coroutines and keeps the socket between turns. BackendClient already shares an
OkHttp pool. Notes retains Docs/Drive clients and a five-minute metadata cache;
its lock protects the non-thread-safe httplib2 transport and Inbox creation.
Removing that lock would trade correctness for a questionable latency gain.

See [the measured waterfall](../evals/latency-2026-10-08.md). Docs writes in Live
probes are simulated, while /token, its SDK call, the WebSocket, PCM input and
Gemini tool calls/audio are real. No new TTS requests are needed.

## Unverified

Pixel/glasses SCO negotiation, physical first sound, minimum buffers, underruns,
Bluetooth echo from the optional tone, actual DAT keyword detection, WAN phone to
backend time, and real Docs/OAuth round trips. Auto residuals are estimates;
short sequential samples do not establish a model inference speed improvement.
