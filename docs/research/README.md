# Research index

All research done 2026-10-06 by parallel subagents. Re-verify anything marked **[unverified]** before relying on it; APIs here move monthly.

| File | Topic | Headline |
|---|---|---|
| [wearables-dat-sdk.md](wearables-dat-sdk.md) | Meta Wearables Device Access Toolkit | v1.0.0 (2026-09-24). Android via Maven Central, no token. "Hey Meta, start {app}" can launch our app, but trailing speech isn't known to be forwarded. |
| [voice-backends.md](voice-backends.md) | OpenAI Realtime vs Gemini Live | OpenAI primary (`gpt-realtime-2.1` + `semantic_vad` low; evaluate `gpt-live-1`), Gemini switchable. Memory = our backend. |
| [m1-android-notes.md](m1-android-notes.md) | Gemini Live wire protocol + DAT Android integration (verified while building M1) | WebSocket endpoint, `Authorization: Token …`, message shapes, audio formats, resumption rules. |
| [m2-backend-notes.md](m2-backend-notes.md) | google-genai ephemeral tokens + Docs API (verified while building M2) | `gemini-3.8-live` works with ephemeral tokens; `lock_additional_fields` semantics. |
| [meeting-mode.md](meeting-mode.md) | Meeting silence, capture and minutes (2026-10-07) | Real manual/scribe silence probes, client audio/tool gate, durable map-reduce, Docs retry design. |
| [m3-memory-notes.md](m3-memory-notes.md) | SQLite memory + Gemini text summaries (2026-10-07) | Flash-Lite 3.5 free-tier model, real JSON schema compatibility, real summary and Live setup checks. |
| [over-mode-notes.md](over-mode-notes.md) | Over turn control, DAT speech and shared PCM (2026-10-07) | DAT partial/final API verified; Android on-device PCM fallback; real manual-activity PCM session passed. Over now ends on keyword or 10-second silence, with no speech limit. Auto defaults to 2000 ms. |
| [m4-vision-notes.md](m4-vision-notes.md) | DAT camera + Gemini Live vision (2026-10-07) | Photo-from-stream, mock media, realtime JPEG and image-bound tool responses verified with real auto/PTT tokens. |
| [hands-free-background.md](hands-free-background.md) | Foreground service, DAT inputs/voice launch, Android tile/shortcut (2026-10-07) | Real 1.0.0 APIs verified; service owns the conversation, gesture handling respects reserved controls, hardware checks remain manual. |
| [background-jobs-notes.md](background-jobs-notes.md) | Codex jobs, research, events | 2026-10-07: durable queue and two-step confirmation; CLI flags and event protocol; local tests pass, real CLI smoke blocked by sandbox permissions. |
| [live-translator.md](live-translator.md) | Live translator (2026-10-07) | Language support, fresh persona sessions, real Spanish↔English checks, and per-utterance phone routing limits. |
| [dashboard-notes.md](dashboard-notes.md) | Local web dashboard (2026-10-07) | Jinja2/htmx, cookie sessions/CSRF, memory UI, real Gemini token-button check. |
| [journal-notes.md](journal-notes.md) | Daily Gemini journal + Docs replacement (2026-10-07) | Per-day markers, UTF-16/revision checks, real fake-day generation and seven-tool Live setup. |
| [read-to-me-notes.md](read-to-me-notes.md) | Read-to-me (2026-10-07) | HIGH-quality DAT photo, blur retry policy, app-owned page/chunk position, real Gemini Live text fixtures. |
| [google-docs-notes.md](google-docs-notes.md) | Voice → Google Docs | Backend function tool, Desktop OAuth client published to production, Inbox doc + alias map + rapidfuzz. Keep API is Workspace-only. |
| [head-gestures.md](head-gestures.md) | Head gestures (2026-10-07) | Real DAT Motion 1.0.0, pure Kotlin detector, explicit Gemini confirmation tool; auto/PTT real API smoke passed. Hardware thresholds/axes still need validation. |
| [workout-coach.md](workout-coach.md) | Workout coach (2026-10-07) | Reuses one DAT motion stream; synthetic rep profiles, monotonic intervals, SQLite workout history. Human accuracy and physical cue latency unverified. |
| [cooking-diy-coach.md](cooking-diy-coach.md) | Cooking/DIY projects and app timers (2026-10-07) | Real Gemini extraction schema, Android exact alarms/TTS; merged DAT Motion nod-next/shake-repeat with confirmation precedence. |

- [voice-eval-notes.md](voice-eval-notes.md) — **2026-10-08**: real spoken Live harness, Gemini TTS models/quotas, waveform-checked fixtures, tool and timing evaluation.

## Reconciled conflicts
- **Token locking vs resumption (2026-10-06):** the backend initially locked the whole setup, which would make Gemini ignore the app's resumption handle. Fixed: `lock_additional_fields=[]` and `sessionResumption` left client-owned. See docs/architecture.md.
- **Custom "Hey Meta" commands:** the Docs-notes research cited an older source (UploadVR, 2025) saying no custom "Hey Meta" commands. The SDK research found that **SDK 1.0.0 added experimental Voice Invocation** ("Hey Meta, start {APP NAME}" → `LaunchApp`). Current truth: launching works (with approval); arbitrary commands are not known to be forwarded.

## Open questions to re-check
- Does Voice Invocation forward the rest of the utterance?
- `gpt-live-1`: model ID, image input, config fields.
- Firebase AI Logic support for `gemini-3.8-live`.
- Google Workspace MCP servers with personal @gmail.com.
- Meta AI Connectors: personal/private connectors, reachable by voice from glasses?

- [visual-memory.md](visual-memory.md) — **2026-10-07**: Flash-Lite image captions,
  pricing/privacy boundaries, local visual storage, real sample search/deletion and
  Auto/PTT/Over constrained Live setup checks. **2026-10-08**: merged dashboard
  browsing/retention/status and shared camera/Motion lifecycle regression checks.

- [android-review-notes.md](android-review-notes.md) — **2026-10-08**: target-36
  foreground/audio review, coroutine cleanup, real five-mode constrained setup
  and same-token resumption acceptance; hardware behavior remains unverified.

- [Diagnostics](diagnostics.md) — **2026-10-08**: actual DAT metadata/audio routing,
  bounded remote logs, dashboard polling, credential-safe Live setup smoke, and
  current Google OAuth Console paths for the consolidated hardware session.

- [emulator-smoke.md](emulator-smoke.md) — **2026-10-08**: API 36 headless emulator,
  DAT camera/Inputs/Motion/Speech simulation, WAV capture and real-Gemini test boundaries.

- [Backend security](backend-security.md) — **2026-10-08**: independent browser
  approvals, SSRF edge cases, bounded requests/jobs/logs, disclosure and retention
  regressions; real Auto/PTT/Over constrained Gemini setup passed.

- [Device approvals](device-approvals.md) — **2026-10-08**: tiered app-owned nod/button/screen approvals, immutable backend payloads, real Gemini confirmation/result turns, and device limitations.

- [App UI](app-ui.md) — **2026-10-08**: four-tab Material 3 phone UI, onboarding,
  accessibility/test tags, authenticated read-only review snapshot, unchanged
  service/approval ownership; instrumentation execution remains a device check.
- [Gemini resilience](resilience.md) — **2026-10-08**: classified retries, model
  fallback, on-device offline capture, durable text work and cached Live health;
  both Live models completed real constrained setup and text defaults were
  verified through the models list endpoint.

- [latency-notes.md](latency-notes.md) — **2026-10-08**: startup overlap, token HTTP keepalive, adaptive playback buffers, and measured Auto/PTT/Over latency boundaries.

- [gemini-quota.md](gemini-quota.md) — **2026-10-08**: current project-specific quota reality, AI Pro $10/month credits, safe key probe (tier inconclusive), personal-use estimates and local metering limitations.

- [HFP audio](hfp-audio.md) — **2026-10-08**: seeded narrowband/codec/loss/noise
  stress channel, exact app resampler port, 8 kHz Vosk keyword proxy and paired
  Gemini evaluation. Hardware codec, beamforming and SCO effects remain unverified.

- [memory-eval-notes.md](memory-eval-notes.md) — **2026-10-08**: synthetic memory evaluation, profile budget starvation, event-time corrections, key aliases and lexical recall.

- [Claude Code headless jobs](claude-code-headless.md) — **2026-10-08**:
  restricted print-mode policy and macOS Bash sandbox settings, offline coding tests,
  private temp/shared Git boundaries, subscription-safe CLI options, both-agent
  comparison, agent-bound approval and external enforcement smoke script.

- [Long-session soak](long-session-soak.md) — **2026-10-08**: opt-in sequential
  meeting/Auto Live runner, transcript persistence checks, backend load and
  visual/log retention measurements; see the linked evaluation for outcomes.

- [meeting-chunked.md](meeting-chunked.md) — **2026-10-08**: durable local meeting WAV chunks, non-Live Gemini transcription, overlap stitching, short on-device wake handoff; replaces continuous Live scribe capture.

- [memory-hypotheticals.md](memory-hypotheticals.md) — **2026-10-08**: modality,
  subject and plan review dates; assertion-aware recall and 40 adversarial cases.

- [DAT session + HFP recovery](dat-session-hfp.md) — **2026-10-09**: first Pixel 10 Pro/RB Meta 01G8 failure, documented vs inferred HFP interaction, independent audio/control lifecycle, bounded DAT retries and SDK error diagnostics.

- [Over latency correction](over-mode-notes.md#hardware-latency-correction--2026-10-09)
  — **2026-10-09**: repeated DAT timeout removed from Over, persistent shared-PCM
  recognition with startup buffering, precise manual-boundary logs, four Live
  sessions/twelve turns; physical-device retest and 16.739 s root cause unresolved.
