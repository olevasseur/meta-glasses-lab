# Long-session soak protocol

Date: 2026-10-08. Read the architecture, decisions, M1/M2 protocol notes, meeting
notes and resilience guidance before implementation.

Rechecked official sources:

- https://ai.google.dev/gemini-api/docs/live-api/session-management
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens

Also checked https://rapidfuzz.github.io/RapidFuzz/Usage/fuzz.html#rapidfuzz.fuzz.WRatio
and installed RapidFuzz 3.14.6: `score_cutoff` zeroes only values below the supplied
threshold. Every `memory.score` caller requires at least 65, so passing that cutoff
preserves qualifying results while avoiding large fuzzy-comparison allocations.
A 72,000-row synthetic recall fell from 65.99 seconds to 0.43 seconds after joining
chunks once and using the cutoff. Join-only peak RSS was 1,023 MiB; the final
implementation measured 354 MiB in a separate process. Search still scales with
stored history; no constant-memory or leak-free guarantee follows from peak RSS.

The session guide documents approximately ten-minute connection lifetimes,
`goAway.timeLeft`, sliding-window compression, and resumption using the latest
resumable handle. Handles are valid for two hours after the last session
termination; this is not a requirement to discard a healthy continuously updated
session at the two-hour mark. The constrained-token configuration stays owned by
the existing backend; client-owned resumption remains unlocked.

The existing single-turn `voice_eval.client.evaluate` intentionally reports
`GoAwayNotResumed`. A separate opt-in `voice_eval.soak` runner shares its wire
helpers and adds multi-connection lifetime, backend upload/load, and measurements.
It does not substitute its reconnect behavior for an Android endurance test.

The 35-second pilot received one complete input transcription, then a provider
1011 at about 21 seconds. Same-token/handle attempts received 1008. The captured
chunk survived backend persistence and real Gemini generated local minutes.
That is a failed pilot, not rollover evidence. A sandbox restriction prevented
`ps` execution; resource sampling uses standard-library peak RSS instead.

Auto completed its 30-minute audio timeline, retained the minute-two context at
minute 25, and accepted fresh-token resumption. The meeting attempt failed at its
first ten-minute connection boundary. The two-hour backend load stored all 72,000
rows with zero failures and 120 passing retention probes.

Research outcome and the full run measurements are recorded in
[the evaluation](../evals/soak-2026-10-08.md).

Integration note, 2026-10-08: main's newer recall uses `memory_quality.search_score`
and current-fact ranking; the join optimization now feeds that implementation.
The 65-point cutoff remains on the legacy forget/suppression scorer. Historical
timings above describe the evaluated branch, not a new benchmark of the merge.
The runner now uses main's whole-run Live reservations, per-connection metering
and memory-evaluator text-attempt budget. Offline regressions cover these paths;
the historical credentialed soak has not been repeated after integration.

Unverified: physical glasses endurance, audio routing and battery; words spoken
while offline; production Android natural-rollover behavior; exact speech
recognition under room noise/overlapping speakers; Google Docs export. Raw
conversation memory has no retention purge. Only visual expiry and bounded
operational-log rotation are exercised by retention probes.
