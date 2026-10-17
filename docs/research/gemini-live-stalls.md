# Gemini Live stalls after activityEnd

Date: **2026-10-17**. Model: `gemini-3.8-live` (backend default), manual activity
detection (Over mode).

## Symptom
On the phone, after "over" (`activityEnd`), Gemini sometimes sends nothing: no input
transcription, no reply, no tool call. The 8-second Over no-reply watchdog reopens the
turn and the note is lost. Watchdog reopens per day from app logs: 10-11 0/18,
10-12 0/24, 10-17 6/12. Today's usage was 5 Live sessions / ~10 minutes, far below
quota guards, and no error or close frame was received, so this is not a quota limit.

## A/B reproduction (no phone or glasses)
`scripts/gemini_stall_ab.py` streams the same 13.9 s macOS-TTS note request
(16 kHz PCM, 20 ms chunks), then `activityEnd`, and waits 20 s. 20 runs per arm, run
concurrently:

| Arm | Silent (20 s) | Slow (>10 s) | Normal |
|---|---|---|---|
| **full**: backend `/token` locked config (system prompt, tools), constrained endpoint | 1 | 1 (17.4 s) | 18 (~1 s) |
| **plain**: API key, no system prompt or tools, `automaticActivityDetection.disabled` | 1 | 2 (12.3, 12.6 s) | 17 (~1 s) |

Both arms stall at similar rates, so our system prompt, tools and constrained token
are not the trigger. The stall is on the Gemini Live service side for manual-activity
audio turns. An earlier, separate run of the full arm had 1/6 silent.

## Unverified
- Whether automatic activity detection (Auto mode) stalls at the same rate.
- Whether other Live models (e.g. `gemini-3.8-live-extended-thinking`) behave differently.
- Whether this is a transient incident (no status page checked) or persistent.
- Root cause inside the service.
