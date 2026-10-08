# Meeting / lecture mode

**Re-architected 2026-10-08:** meetings record durable local audio chunks and use
non-Live Gemini transcription. The continuous Live scribe has been replaced after
the [ten-minute soak failure](../evals/soak-2026-10-08.md).

See [durable chunked meeting capture](meeting-chunked.md) for the full current
protocol, configuration, voice/buttons, persistence, limits and verified sources.
See [the real evaluation](../evals/meeting-chunked-2026-10-08.md) for accuracy,
usage, retry evidence and the two-hour extrapolation.

- **Start meeting** or “Start a meeting called …” starts local capture. Phone mic
  on table defaults on; the existing glasses HFP route is optional.
- **Hey glasses** (configurable), **Ask assistant**, or capture **HOLD** opens one
  normal Live exchange, then closes it. Recording continues throughout. Local
  recognition uses the existing shared-PCM SpeechRecognizer infrastructure.
- **Hey glasses, stop the meeting**, **Stop meeting & prepare minutes**, or the
  app/notification **Stop** ends recording. Pending audio catches up after network
  or provider outages; finalized minutes wait for every chunk's transcript.
- Existing map/reduce creates summary, decisions, action items with known
  owners/deadlines, open questions and two recap sentences. It covers all stored
  transcript chunks, including meetings longer than two hours.
- Exact existing Doc title/alias exports immediately. A new Doc requires the
  existing **Tier 1** device/dashboard approval, in “Glasses Meetings”, titled
  “Meeting – <title> – <UTC date>”. Denial keeps backend minutes/transcripts.
- Authenticated dashboard **Meetings** shows incremental transcripts, minutes and
  Doc links. Third-party meeting statements are not promoted to personal facts.

The old trailing “over” meeting option and Live scribe gates are superseded for
new capture. Older transcript outboxes remain supported so pending exports can
finish. Historical evidence remains in [the original research](../research/meeting-mode.md)
and [the original minutes smoke artifact](meeting-mode-smoke.json); it does not
establish endurance of the new pipeline.

Unverified: physical Pixel/glasses capture, room pickup, background wake accuracy,
HFP echo, battery/thermal endurance and real Docs export. WAV uses approximately
238 MB for two offline hours; available disk space and foreground-service lifetime
still bound capture. Source URLs and verification date **2026-10-08** are in the
[research record](../research/meeting-chunked.md).
