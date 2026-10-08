# meta-glasses-lab — "the glasses app"

A personal voice assistant for Meta smart glasses that **doesn't cut you off**, **remembers you**, and can **see, take notes, coach, translate and run Codex jobs**. Android app (Pixel) + a small Python backend on the Mac. Runtime AI: **Gemini Live**. Built by Codex agents orchestrated by Claude Code.

## Features
| Feature | Say / do | Docs |
|---|---|---|
| Over mode | Talk as long as you want; turn ends when you say **"over"** or after 10 s of silence | [over.md](docs/features/over.md) |
| Long-term memory | "Remember that…", "What did I say about…", "Forget that" | [long-term-memory.md](docs/features/long-term-memory.md) |
| Vision | "What am I looking at?", watch mode | [m4-vision.md](docs/features/m4-vision.md) |
| Hands-free | Capture-button hold, "Hey Meta, start …", screen off, QS tile | [hands-free.md](docs/features/hands-free.md) |
| Head gestures | Nod = yes, shake = no | [head-gestures.md](docs/features/head-gestures.md) |
| Notes → Google Docs | "Add this note to my grocery doc" | [google-docs-notes.md](docs/research/google-docs-notes.md) |
| Codex from your glasses | "Tell Codex to fix … in valiant" (allowlisted repos, confirm first, never pushes) | [background-jobs.md](docs/features/background-jobs.md) |
| Research on demand | "Research … and put it in a doc" | [background-jobs.md](docs/features/background-jobs.md) |
| Live translator | "Translate Spanish" | [live-translator.md](docs/features/live-translator.md) |
| Meeting mode | "Start meeting" → silent scribe → minutes in Google Docs | [meeting-mode.md](docs/features/meeting-mode.md) |
| Journal & morning brief | Nightly diary in Google Docs, "morning brief" | [end-of-day-journal.md](docs/features/end-of-day-journal.md) |
| Read-to-me | "Read this", "what's the wifi password on this card?" | [read-to-me.md](docs/features/read-to-me.md) |
| Cooking / DIY coach | Steps, timers, "does this look done?", nod = next step | [cooking-diy-coach.md](docs/features/cooking-diy-coach.md) |
| Workout coach | "Start squats, 3 sets of 12" — reps counted from head motion | [workout-coach.md](docs/features/workout-coach.md) |
| Visual memory (opt-in) | "Where did I leave my keys?" — objects/places only, never faces | [visual-memory.md](docs/features/visual-memory.md) |
| Dashboard | http://localhost:8787/dashboard | [dashboard.md](docs/features/dashboard.md) |
| Approvals | Nod / capture button / tap — Gemini can never approve | [device-approvals.md](docs/features/device-approvals.md) |
| Resilience | Retries, backup model, offline notes when Gemini is down | [resilience.md](docs/features/resilience.md) |
| App UI | Talk · Features · Memory & Notes · Settings | [app-ui.md](docs/features/app-ui.md) |

## Status (2026-10-08)
- Backend 488 tests, Android 226 tests passing. Real Gemini / Codex checks passed per feature (see each doc).
- **Emulator smoke PASS** on main: real APK on Android 16 emulator + DAT mock glasses + real Gemini (`scripts/emulator_smoke.sh`, screenshots in `docs/evals/emulator/`).
- Voice eval: 68/68 tool selection, 0/10 false triggers (`docs/evals/`). Security review + tiered hardware approvals (`docs/security-review.md`).
- **Not yet verified on physical Pixel + glasses.** Start with `scripts/dev_up.sh` and follow `docs/hardware-test-plan.md`. Google OAuth not configured yet.
