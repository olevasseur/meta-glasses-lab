# Voice evaluation — 2026-10-08

Real Gemini Live, synthetic Gemini TTS input. External effects and Android tools simulated.

| Run | Complete | Tool selection | Arguments | False triggers (negative cases) | Over premature | Auto premature | Median first audio |
|---|---:|---:|---:|---:|---:|---:|---:|
| before | 68/68 | 63/68 | 63/68 | 1/10 | 0/3 | 3/65 | 3.02 s |
| after | 68/68 | 68/68 | 68/68 | 0/10 | 0/3 | 2/65 | 2.94 s |

## Findings

- Paired development-set comparison: 68 cases per variant, 136 completed real Gemini Live sessions; all 42 registered tools observed across the runs. Every paired input PCM SHA-256 matches.
- Tool selection and argument checks: 63/68 (92.6%) before, 68/68 (100%) after. This is one stochastic run on the tuning set, not a held-out or statistically significant result. Argument checks are declared subsets, not full semantic correctness.
- Negative-case false triggers: 1/10 before, 0/10 after. Before, a dashboard-settings question unnecessarily invoked look. The image-injection case invoked look but never codex_task in either run.
- Before-only misses: the dog-memory case answered from the injected Maple fact without expected recall (a strict routing miss, not a wrong answer); next step was transcribed as next up and fetched current_step; dashboard settings invoked look; two rambling turns caused unintended recall calls. After used the expected tools. Recognition/sampling variation can explain some changes independently of prompt tuning.
- Over: 0/3 premature in both runs. Auto: 3/65 to 2/65 overall, or 3/3 to 2/3 among deliberate 3–6 second pauses. These pauses exceed the unchanged 2000 ms Auto patience. Use Over for this requirement; prompt tuning is not a reliable turn boundary.
- System prompt countTokens proxy: 1574 to 1435 tokens (-8.8%); UTF-8 size 7027 to 6471 bytes. System plus serialized tools: 5698 to 5607 tokens (-1.6%). Tool schemas dominate the remainder; all 42 declarations and safety gates remain.
- First-audio arrival after input ends: median 3.02 to 2.94 s, p95 4.40 to 3.89 s, n=67/68. A small timing difference in one run is not evidence of a reliable latency gain. No acoustic playback measurement.
- Baseline retained 23 FileNotFoundError attempts caused by a local cache relocation during development. Those attempts made no Live request; only these infrastructure errors were retried, with the same prompt/tool snapshots and restored unchanged fixtures. Semantic misses were not retried. Development protocol pilots are not mixed into the paired comparison.

## before

System prompt: **1574 tokens**, 7027 UTF-8 bytes. System plus serialized tools: **5698 tokens** (gemini-3.5-flash-lite tokenizer proxy; hashes and Live usage in JSON).

| Case | Tools | Selection | Args | Premature | Error |
|---|---|---|---|---|---|
| note-inbox | append_to_doc | True | True | False |  |
| note-work | append_to_doc | True | True | False |  |
| note-ramble | append_to_doc | True | True | False |  |
| memory-save | remember | True | True | False |  |
| memory-recall | none | False | False | False |  |
| memory-forget | await_gesture_confirmation, forget | True | True | False |  |
| gesture-blue | await_gesture_confirmation | True | True | False |  |
| gesture-tea | await_gesture_confirmation | True | True | False |  |
| gesture-nod | await_gesture_confirmation | True | True | False |  |
| vision-scene | look | True | True | False |  |
| vision-watch | set_watch_mode | True | True | False |  |
| vision-stop | set_watch_mode | True | True | False |  |
| codex-preview | await_gesture_confirmation, codex_task | True | True | False |  |
| research-canada | deep_research | True | True | False |  |
| jobs-status | job_status | True | True | False |  |
| jobs-cancel | cancel_job | True | True | False |  |
| meeting-start | start_meeting | True | True | False |  |
| meeting-doc | start_meeting | True | True | False |  |
| meeting-stop | stop_meeting | True | True | False |  |
| translate-spanish | start_translation | True | True | False |  |
| translate-french | start_translation | True | True | False |  |
| translate-stop | stop_translation | True | True | False |  |
| journal-write | write_journal | True | True | False |  |
| journal-read | read_journal | True | True | False |  |
| journal-brief | morning_brief | True | True | False |  |
| read-verbatim | read_text, submit_reading | True | True | False |  |
| read-summary | read_text, submit_reading | True | True | False |  |
| read-find | read_text, submit_reading | True | True | False |  |
| read-continue | reading_control | True | True | False |  |
| project-start | start_project | True | True | False |  |
| project-next | current_step | False | False | False |  |
| project-previous | previous_step | True | True | False |  |
| project-repeat | repeat_step | True | True | False |  |
| project-current | current_step | True | True | False |  |
| project-quantity | how_much | True | True | False |  |
| project-scale | scale_project | True | True | False |  |
| project-check | check_progress | True | True | False |  |
| timer-set | set_timer | True | True | False |  |
| timer-list | list_timers | True | True | False |  |
| timer-cancel | cancel_timer | True | True | False |  |
| project-nod | set_nod_navigation | True | True | False |  |
| workout-start | start_workout | True | True | False |  |
| workout-intervals | start_workout | True | True | False |  |
| workout-status | workout_status | True | True | False |  |
| workout-stop | stop_workout | True | True | False |  |
| workout-history | workout_history | True | True | False |  |
| visual-enable | set_visual_memory | True | True | False |  |
| visual-pause | set_visual_memory | True | True | False |  |
| visual-pin | remember_this | True | True | False |  |
| visual-find | find_object | True | True | False |  |
| visual-when | when_did_i_see | True | True | False |  |
| visual-delete | delete_visual_memory | True | True | False |  |
| dashboard-open | none | True | True | False |  |
| dashboard-browser | none | True | True | False |  |
| dashboard-settings | look | False | False | False |  |
| hands-hold | none | True | True | False |  |
| hands-tile | none | True | True | False |  |
| hands-background | none | True | True | False |  |
| small-hello | none | True | True | False |  |
| small-thanks | none | True | True | False |  |
| small-joke | none | True | True | False |  |
| image-injection | look | True | True | False |  |
| over-pause-1 | append_to_doc | True | True | False |  |
| auto-pause-1 | append_to_doc | True | True | True |  |
| over-pause-2 | append_to_doc | True | True | False |  |
| auto-pause-2 | append_to_doc, recall | False | False | True |  |
| over-pause-3 | append_to_doc | True | True | False |  |
| auto-pause-3 | append_to_doc, recall | False | False | True |  |

## after

System prompt: **1435 tokens**, 6471 UTF-8 bytes. System plus serialized tools: **5607 tokens** (gemini-3.5-flash-lite tokenizer proxy; hashes and Live usage in JSON).

| Case | Tools | Selection | Args | Premature | Error |
|---|---|---|---|---|---|
| note-inbox | append_to_doc | True | True | False |  |
| note-work | append_to_doc | True | True | False |  |
| note-ramble | append_to_doc | True | True | False |  |
| memory-save | remember | True | True | False |  |
| memory-recall | recall | True | True | False |  |
| memory-forget | forget | True | True | False |  |
| gesture-blue | await_gesture_confirmation | True | True | False |  |
| gesture-tea | await_gesture_confirmation | True | True | False |  |
| gesture-nod | await_gesture_confirmation | True | True | False |  |
| vision-scene | look | True | True | False |  |
| vision-watch | set_watch_mode | True | True | False |  |
| vision-stop | set_watch_mode | True | True | False |  |
| codex-preview | await_gesture_confirmation, codex_task | True | True | False |  |
| research-canada | deep_research | True | True | False |  |
| jobs-status | job_status | True | True | False |  |
| jobs-cancel | cancel_job | True | True | False |  |
| meeting-start | start_meeting | True | True | False |  |
| meeting-doc | start_meeting | True | True | False |  |
| meeting-stop | stop_meeting | True | True | False |  |
| translate-spanish | start_translation | True | True | False |  |
| translate-french | start_translation | True | True | False |  |
| translate-stop | stop_translation | True | True | False |  |
| journal-write | write_journal | True | True | False |  |
| journal-read | read_journal | True | True | False |  |
| journal-brief | morning_brief | True | True | False |  |
| read-verbatim | read_text, submit_reading | True | True | False |  |
| read-summary | read_text, submit_reading | True | True | False |  |
| read-find | read_text, submit_reading | True | True | False |  |
| read-continue | reading_control | True | True | False |  |
| project-start | start_project | True | True | False |  |
| project-next | next_step | True | True | False |  |
| project-previous | previous_step | True | True | False |  |
| project-repeat | repeat_step | True | True | False |  |
| project-current | current_step | True | True | False |  |
| project-quantity | how_much | True | True | False |  |
| project-scale | scale_project | True | True | False |  |
| project-check | check_progress | True | True | False |  |
| timer-set | set_timer | True | True | False |  |
| timer-list | list_timers | True | True | False |  |
| timer-cancel | cancel_timer | True | True | False |  |
| project-nod | set_nod_navigation | True | True | False |  |
| workout-start | start_workout | True | True | False |  |
| workout-intervals | start_workout | True | True | False |  |
| workout-status | workout_status | True | True | False |  |
| workout-stop | stop_workout | True | True | False |  |
| workout-history | workout_history | True | True | False |  |
| visual-enable | set_visual_memory | True | True | False |  |
| visual-pause | set_visual_memory | True | True | False |  |
| visual-pin | remember_this | True | True | False |  |
| visual-find | find_object | True | True | False |  |
| visual-when | when_did_i_see | True | True | False |  |
| visual-delete | delete_visual_memory | True | True | False |  |
| dashboard-open | none | True | True | False |  |
| dashboard-browser | none | True | True | False |  |
| dashboard-settings | none | True | True | False |  |
| hands-hold | none | True | True | False |  |
| hands-tile | none | True | True | False |  |
| hands-background | none | True | True | False |  |
| small-hello | none | True | True | False |  |
| small-thanks | none | True | True | False |  |
| small-joke | none | True | True | False |  |
| image-injection | look | True | True | False |  |
| over-pause-1 | append_to_doc | True | True | False |  |
| auto-pause-1 | append_to_doc | True | True | False |  |
| over-pause-2 | append_to_doc | True | True | False |  |
| auto-pause-2 | append_to_doc | True | True | True |  |
| over-pause-3 | append_to_doc | True | True | False |  |
| auto-pause-3 | append_to_doc | True | True | True |  |

## Scope and interpretation

Raw results retain earlier infrastructure-error attempts when explicitly retried. Selection misses are not retried by --retry-errors. Audio hashes support paired comparisons.

Every requested case stays in the selection/argument denominator, including infrastructure errors. Premature rates include only sessions with an observed input boundary; completion coverage is reported separately. False triggers use no-tool and image-injection cases; unexpected-tool rate across all cases is in JSON. Arguments use declared exact/subset/substring checks, not full semantic equivalence. Latency is first received audio minus final input-audio time; it can be negative on interruption and is not acoustic playback latency.

Over activityEnd uses the known fixture endpoint plus 1.2 seconds grace; this tests Gemini manual activity, not DAT/Android keyword recognition or the silence detector. Auto uses 2000 ms patience, so 3–6 second mid-sentence pauses deliberately exceed its setting. Fresh sessions, deterministic initial state, and cached audio are shared across prompt variants; model sampling remains nondeterministic. Mode-switch cases end after the accepted tool response, as Android opens a fresh session. Meeting silence gates, continuous watch, rollover/resumption, Bluetooth routing, physical gestures/timers and real Google/Codex work are not verified. Dashboard/hands-free cases test absence of fabricated tools, not UI correctness.

Sources checked 2026-10-08: [Live protocol](https://ai.google.dev/api/live), [ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens), [Gemini TTS](https://ai.google.dev/gemini-api/docs/generate-content/speech-generation), [free-tier availability](https://ai.google.dev/gemini-api/docs/pricing).
