# Live resumption and context recovery

Date: 2026-10-09. Runtime remains Gemini only.

## Verified protocol and SDK

- [Ephemeral tokens](https://ai.google.dev/gemini-api/docs/ephemeral-tokens):
  session resumption can reuse a uses=1 token within its overall expiry; a
  resumed connection does not consume a new-session use.
- [Live session management](https://ai.google.dev/gemini-api/docs/live-api/session-management):
  configure sessionResumption on setup and retain the latest usable handle;
  handles have a two-hour lifetime after session termination.
- [WebSocket reference](https://ai.google.dev/api/live): resumable=false can occur
  while generating or executing tools. An older checkpoint can still resume,
  potentially losing recent data. clientContent with turnComplete=false appends
  context without requesting generation, but the reference warns that mixing
  clientContent and realtimeInput has no ordering guarantee.
- [Python SDK token implementation](https://github.com/googleapis/python-genai/blob/main/google/genai/tokens.py):
  locally inspected google-genai 2.28.0. lock_additional_fields=[] builds a field
  mask from populated fields; None globally locks the setup. Existing serializer
  tests verify sessionResumption is absent from both locked config and field mask.
  Android sends only model and sessionResumption. No token lock change was needed.

## Incident analysis

The supplied 00:56 UTC sequence contains a transient loss, two generic 1008
closes on attempts with a handle, and a fresh connection. The raw provider close
reason and setup-completion state were not supplied. **The exact provider reason
for those 1008 closes is unconfirmed.** A generic policy-close label does not
prove incompatible token constraints or an invalid handle.

Confirmed local causes of context loss: every failure incremented modelFailures;
three failures discarded the token and handle and requested model fallback.
No conversation history was sent to the resulting fresh session. Separately,
resumable=false discarded the last checkpoint even during a temporary generation
boundary. The patch retains that checkpoint through ordinary transport failures,
tries fresh credentials once on an opaque resume policy close, then explicitly
falls back with historical context after another rejection. Explicit invalid or
expired handle errors already fall back directly. Known configuration errors
remain fatal. Close diagnostics record code, setup state, resume state, handle
age and a safe category, never raw reasons or handles.

## Live probes

Read-only probes used backend token_config, the constrained v1beta WebSocket,
Authorization: Token, model gemini-3.8-live and Over mode. No Google Docs writes,
backend memory changes, or approvals were executed.

- Ordinary transport abort: original-token resume and fresh-token resume both
  reached setupComplete with the retained handle.
- Ten-second new_session_expire_time, followed by a twelve-second wait after
  disconnect: both original-token resume and fresh-token resume succeeded.
  Fresh credentials used changed system text, so an identical system string was
  not required in this probe.
- Abort while model audio was arriving: the prior checkpoint resumed with both
  original and fresh tokens. The reported opaque 1008 pair was not reproduced.

- Silent recap probe: clientContent.turnComplete=false stayed silent and a text
  follow-up recalled the seeded word, but the spoken PCM Over follow-up failed
  to use it (even after a mocked recall result). The historyConfig initial-history
  variant also failed this spoken check. Do not rely on that route for this app.
- A labelled recap in the token's locked system context stayed silent at setup
  and the spoken Over follow-up recovered the word. This is the implemented path:
  optional bounded /token reconnect_context, JSON-quoted as untrusted data, with
  no extra client turn. Normal minimal setup and resumption locks are unchanged.
  The probe handles model recall calls with a synthetic not_found response; no
  real memory reads or writes occur. The final check with the exact backend
  JSON-quoted context passed without a recall tool call. An early local macOS TTS fixture was empty;
  it was replaced with verified Gemini TTS PCM before these speech checks.

## Unverified

- Exact cause of the incident's two policy closes and behavior with that specific
  provider-side session state; the mocked JVM sequence tests fallback, not a
  reproduction of Google's rejection cause.
- Physical Pixel 10 Pro/Ray-Ban Meta audio, capture, nod thresholds and Tailscale
  reconnect behavior after installing this build.
- Model adherence to untrusted-context and confirmation instructions is not a
  security guarantee. DeviceApprovals remains the authorization boundary.
- A retained older handle may omit recently generated content. The bounded fresh
  session recap cannot recover unheard/untranscribed audio or unknown write outcomes.
