# Faster conversation startup and playback

Implemented **2026-10-08**. Runtime AI remains Gemini only.

Press **Start**, use the Start shortcut, or HOLD the glasses capture button after
arming controls. Token fetch and Live setup now begin while Android selects the
microphone/speaker route. Audio remains gated until the route is ready. Wait for
Connected and an active audio route before speaking. Opening Settings or merely
viewing the conversation does not spend a Live session. Stop closes it; calls,
pauses and routing failures retain the existing microphone gates and cleanup.

Keep the conversation running for subsequent turns: its socket, audio route and
resumption token stay warm. Auto still waits **2000 ms** by default. The patience
slider is unchanged. In Over, say the configured sign-off; its existing 1.2-second
recognition grace and silence fallback are unchanged. PTT ends on release.

While stopped, **Settings → Conversation → Thinking sound for slow tools** enables
one quiet 80 ms tone after a tool has run for 1.5 seconds. It defaults off. Fast,
failed or cancelled calls leave no delayed tone. Tones are rate-limited, skipped
during speech/playback, pause, meeting, translation and pending confirmations.
The microphone uplink is blanked for 200 ms around a cue to reduce acoustic echo;
a short coincident user utterance can be lost, so leave this off if that matters.
The sound acknowledges waiting, not successful completion.

Playback requests a 40 ms effective buffer (or the larger Android minimum), with
at least 200 ms capacity. On Android 12+, playback can start with the first PCM
write. The existing writer polls at 5 ms and writes at most 20 ms per iteration;
there is no extra jitter-fill delay. Active underruns grow the effective buffer
by 20 ms up to allocated capacity. Silence between turns does not cause growth.
Interruptions still clear queued/device audio. Older Android versions use their
platform start threshold. Translator phone-speaker mode still buffers complete
utterances for route selection.

The backend reuses its token HTTP transport with a 120-second idle keepalive.
It **never caches token responses**: each fresh session gets one freshly locked
token, updated memory and correct mode/persona. `uses=1`, the 60-second new-session
window, 30-minute lifetime and `lock_additional_fields=[]` are unchanged. Only a
valid resumption handle can reuse the running session's token. Key/timeout changes
rotate the transport and backend shutdown closes it. Token requests serialize
within the personal backend worker; no retries of tool writes were introduced.

Independent tool calls already run concurrently. The existing five-minute Drive
metadata cache and persistent Docs/Drive clients remain in use; writes using their
non-thread-safe transport remain serialized.

## Inspecting latency

Settings → Diagnostics → log export includes `latency` events for token fetch,
WebSocket connect, setup, route readiness, first received audio, tool round trips,
first chunk to AudioTrack write, effective buffer size and active underrun growth.
No token, arguments or transcript is included in these timing events. Route details
also show current buffer frames and total underruns (which can include idle gaps).

`speech_end_to_first_audio_ms` uses local speech energy. `end_wait_ms` is either
measured to manual activityEnd or estimated from configured Auto silence;
`after_end_ms` includes model, transport and tools. It is not pure inference time.
An early response can produce a negative estimated residual. The last-chunk to
write measurement excludes OS/Bluetooth/hardware buffering and is not an acoustic
measurement. Tool-generating intermediate turnComplete events can reset the
existing Android speech estimator; use the headless waterfall for complete
multi-generation tool turns.

Run the bounded headless probe from the repository root:

```sh
PYTHONPATH=scripts uv run --project backend python -m voice_eval.latency --label local-check --samples 1 --output /tmp/latency-check.json
```

This opens three sequential Live sessions (Auto/PTT/Over), reusing cached speech
and simulating Docs. It stops on the first failed or audio-less response and does
not retry automatically. `--samples` defaults to two and is capped at four; count
all invocations against the shared key budget. `--cold-transport` reproduces the
old per-request Google connection setup. Do not run alongside another local probe.

Measurements, methodology and limits are in
[latency-2026-10-08](../evals/latency-2026-10-08.md). The phone-to-backend network,
SCO, actual speaker latency and real Google Docs timing still need hardware/account
validation. No Google OAuth token was present in this worktree, and no real Docs
writes were made.

Verified sources (2026-10-08): [Gemini ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens),
[Live protocol](https://ai.google.dev/api/live),
[Gen AI SDK](https://googleapis.github.io/python-genai/),
[HTTPX keepalive](https://www.python-httpx.org/advanced/resource-limits/),
[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack),
[AudioManager](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)).
