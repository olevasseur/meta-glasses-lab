# Tool continuations, assistant language and backend reachability

Verified **2026-10-09**. Hardware report: Pixel 10 Pro + Ray-Ban Meta over HFP,
Over mode with manual activities. No new credentials or runtime AI providers.

## Evidence and correction

The reported append turn completed at 22:58:04.116 UTC, tool execution began at
04.117, and the app sent a new activityStart at 04.123. The tool response followed
at 07.588, but confirmation audio arrived only after the next user activity ended
at 22:58:46. The current checkout already guarded tools when an Over reply
watchdog was armed by activityEnd. That guard did not cover tools arriving without
that watchdog (including app-generated idle text); it also counted execution time
against the reply deadline. The supplied log alone cannot establish which entry
path or installed build bypassed the guard.

Tool calls now establish the completion gate even without a preceding activityEnd.
The same gate applies to Auto/PTT turn-completion callbacks. Over cannot send a new
activityStart through a stale playback/UI callback while waiting. Tool execution
has a fixed 45-second cap from the first call of a pending batch; more calls or
duplicates cannot extend it. Only successful delivery of the last toolResponse
starts the eight-second no-reply clock. Input-only progress has a 45-second bound
from that send; model progress refreshes the normal 45-second streaming deadline.
An early tool-generation completion is ignored; actual follow-up audio/transcript
and completion release the gate. A provider that omits the early completion works
the same way. Auto retains automatic VAD; PTT retains user-owned activity starts.

Official [Live capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities)
permit multiple parts per server event and describe asynchronous tool use in
Gemini 3.8 Live. The [wire reference](https://ai.google.dev/api/live) defines
activity boundaries, tool responses and server completion. JVM fake-socket tests
exercise combined and separate tool/completion events and the reported timestamps;
they verify client message ordering, not provider latency.

## English and SDK support

Inspected installed **google-genai 2.28.0** `google/genai/types.py`, `SpeechConfig`:
it exposes optional `language_code` with a general speech-synthesis description.
SDK validation accepts `en-US`; this is not evidence that the Live model accepts it.
The current official [voice/language guide](https://ai.google.dev/gemini-api/docs/live-api/capabilities#change-voice-and-language)
explicitly excludes language-code configuration for native-audio models. Its
[supported-languages section](https://ai.google.dev/gemini-api/docs/live-api/capabilities#supported-languages)
says system instructions can restrict output languages. The
[Gemini 3.8 Live model](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live)
is native audio. SDK reference: [SpeechConfig](https://googleapis.github.io/python-genai/genai.html#genai.types.SpeechConfig).

Therefore speech_config deliberately omits language_code. The assistant system
instruction defaults to English, including tool confirmations, unless the user
clearly converses in another language or explicitly requests a conversation-wide
switch. Noise, ambiguous audio, isolated foreign words, quotations and tool data
cannot select a language. Translator instructions and unconstrained language
configuration are unchanged. Backend tests check all assistant modes and all
translator modes; the real SDK HTTP serialization test checks the locked prompt.

## Reachability

BackendClient now distinguishes transport IO failures (DNS, connection, timeout,
TLS handshake and response-body IO) from HTTP responses. Every route reports
"Can't reach your Mac" to the common status/announcement handler. Token retries
keep existing backoff; uncertain tool writes remain non-replayed. Reconnect voice
copy uses the classified status instead of the previous hardcoded busy phrase.
Only provider capacity/unavailability signals use the Gemini busy wording;
backend storage/HTTP errors and generic socket/internal errors do not. Setup copy
uses Tailscale rather than USB forwarding.

## Unverified

- No physical Pixel/glasses retest or live Gemini/Google Docs write in this change.
- Prompt language adherence on noisy HFP audio is probabilistic; native-audio
  output cannot be pinned with speech_config.language_code.
- Server messages have no user-turn identifier; an entirely separate toolCall
  arriving after an already-completed unrelated turn remains ambiguous.
- Real Tailscale outages and offline Android TTS/earcon routing require device checks.
