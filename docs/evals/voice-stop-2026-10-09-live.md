# Voice evaluation — 2026-10-09

Real Gemini Live, synthetic Gemini TTS input. External effects and Android tools simulated.

| Run | Complete | Tool selection | Arguments | False triggers (negative cases) | Over premature | Auto premature | Median first audio |
|---|---:|---:|---:|---:|---:|---:|---:|
| voice-stop-live | 5/5 | 5/5 | 5/5 | 0/2 | 0/1 | 0/3 | 2.44 s |

## voice-stop-live

System prompt: **1771 tokens**, 7951 UTF-8 bytes. System plus serialized tools: **6327 tokens** (gemini-3.5-flash-lite tokenizer proxy; hashes and Live usage in JSON).

| Case | Tools | Selection | Args | Premature | Error |
|---|---|---|---|---|---|
| voice-stop-goodbye | end_conversation | True | True | False |  |
| voice-stop-listening | end_conversation | True | True | False |  |
| voice-stop-over | end_conversation | True | True | False |  |
| voice-stop-negation | none | True | True | False |  |
| voice-stop-reported | none | True | True | False |  |

## Scope and interpretation

Raw results retain earlier infrastructure-error attempts when explicitly retried. Selection misses are not retried by --retry-errors. Audio hashes support paired comparisons.

Every requested case stays in the selection/argument denominator, including infrastructure errors. Premature rates include only sessions with an observed input boundary; completion coverage is reported separately. False triggers use no-tool and image-injection cases; unexpected-tool rate across all cases is in JSON. Arguments use declared exact/subset/substring checks, not full semantic equivalence. Latency is first received audio minus final input-audio time; it can be negative on interruption and is not acoustic playback latency.

Over activityEnd uses the known fixture endpoint plus 1.2 seconds grace; this tests Gemini manual activity, not DAT/Android keyword recognition or the silence detector. Auto uses 2000 ms patience, so 3–6 second mid-sentence pauses deliberately exceed its setting. Fresh sessions, deterministic initial state, and cached audio are shared across prompt variants; model sampling remains nondeterministic. Mode-switch cases end after the accepted tool response, as Android opens a fresh session. Meeting silence gates, continuous watch, rollover/resumption, Bluetooth routing, physical gestures/timers and real Google/Codex work are not verified. Dashboard/hands-free cases test absence of fabricated tools, not UI correctness.

Sources checked 2026-10-08: [Live protocol](https://ai.google.dev/api/live), [ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens), [Gemini TTS](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation), [free-tier availability](https://ai.google.dev/gemini-api/docs/pricing).
