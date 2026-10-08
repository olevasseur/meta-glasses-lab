# Durable chunked meeting capture

Implemented **2026-10-08**. Meeting recording opens **no Gemini Live connection**.
PCM comes from the existing glasses HFP or phone microphone and goes directly to
private device files. A short ordinary Live session is opened only after local
wake recognition or an explicit Ask/capture action.

## Use

Tap **Start meeting**, or say **“Start a meeting called Harbour pilot”** in a normal
assistant conversation. Choose **Phone mic on table** (default) for a group, or turn
it off to use the existing glasses route. Enter an exact Doc title/alias to append;
leave blank to request a new document with the existing Tier 1 device approval.

Set **Wake phrase** before starting (default **hey glasses**). Say **“Hey glasses,
what is the budget?”**, tap **Ask assistant**, or hold the glasses capture button.
The local recognizer forwards the recognized request after normal Live setup.
Capture HOLD still answers a pending approval before reaching the meeting action.
A single reply closes Live after playback drains; a 60-second timeout bounds
connection/retry/tool waits. Recording continues on the same AudioRecord during
setup, response and closure. Failed assistant access does not stop recording.
A phrase alone or button opens listening; wait for connection before speaking.

Say **“Hey glasses, stop the meeting”**, or tap **Stop meeting & prepare minutes**.
The regular app/notification **Stop** also ends capture. No continuous model hears
room discussion or emits unsolicited `stop_meeting` calls. A short addressed Live
session keeps ordinary tool permissions and device approval semantics. Translation
cannot take over meeting recording. After Stop meeting the app returns to the
ordinary assistant for the eventual two-sentence recap; main Stop leaves it off.
The old trailing “over” meeting option is retired; saved preferences are harmless.

## Storage and protocol

- PCM16 mono 16 kHz **WAV**, 32,000 bytes/second. A 60-second chunk is
  **1,920,044 bytes**. Cut after 60 seconds on 300 ms of silence when available,
  otherwise at 62 seconds; include the preceding 2 seconds in the next chunk.
- PCM is written directly to disk, WAV is sealed with an atomic rename, and the
  next chunk retains only 64 kB of overlap. No full-meeting audio buffer exists.
  The final partial chunk is sealed at Stop. Restart seals recoverable unfinished
  PCM, marks the interrupted meeting ended, and resumes upload/finalization.
- Authenticated `POST /meetings/start` adds `"chunked":true` to existing metadata.
  `POST /meetings/{id}/chunks/{index}?start_ms=N` sends `Content-Type: audio/wav`.
  The server validates PCM format and duration, then commits audio plus its hash.
  An identical index/timestamp/hash is acknowledged again; different bytes conflict.
  Phone files are deleted **only after acknowledgement**, regardless of provider
  health. The backend owns the durable audio copy after that acknowledgement.
- `POST /meetings/{id}/process` advances at most one ordered transcription. A
  shared lock serializes audio inference; a persisted 60-second cooldown prevents
  failed chunks being retried by every phone poll. The existing backend recovery
  loop also tries a queued chunk each minute, including after backend restart.
- `POST /meetings/{id}/end?chunk_count=N` seals an immutable expected count.
  Missing indices reject finalization. Map/reduce waits until every chunk is
  transcribed, even if all uploads were acknowledged during an outage.
- Incremental text has recorded-audio-relative timestamps (capture pauses excluded) and `chunk N Speaker A/B`
  hints. Labels reset per chunk; names appear only when spoken, not inferred
  from voices. Exact normalized overlap suffix/prefix is removed, within the
  acoustic overlap only. Later repeated statements remain.
- Exact all-zero PCM is accepted as silence without a Gemini call; quiet room
  audio still goes through transcription.
- Successful transcription atomically stores text/receipt and releases the queued
  audio BLOB. Existing map/reduce, summary retention, Docs idempotency and new-Doc
  Tier 1 approvals remain. Failed/denied exports keep backend minutes. The raw
  audio is not a permanent archive; SQLite may retain reusable freed pages.

Phone uploads up to eight files per pass, then processes one chunk; it retries
while the conversation service lives and when the app next opens. Network loss
needs no special meeting mode: recording and queued files continue. Stop recording
is distinct from finishing queued uploads. Dashboard **Meetings** shows saved
transcripts and minutes; the phone Live transcript shows only addressed exchanges.
The room microphone can also capture assistant speech acoustically.

## Configuration and limits

No new dependencies, key storage, OAuth scopes or token locking changes.
`GEMINI_MEETING_TRANSCRIPTION_MODEL` defaults to `gemini-3.5-flash-lite`.
The shared helper honors ordered `GEMINI_TEXT_MODELS` first (default fallback
`gemini-2.5-flash`), with its existing bounded retries and usage recording under
`kind=text`, using the actual attempted model. Existing minutes model options and
usage meter configuration apply. Backend keys remain backend-only.

Each operation holds at most a bounded chunk/response, not a full meeting. A
fully offline two-hour recording needs approximately **238 MB** at this WAV rate,
including overlap. Queue disk usage grows with offline duration; keep adequate
phone/backend space. Recording has no two-hour limit. Insufficient storage fails
visibly and retains recoverable files; it cannot guarantee speech after disk full.
In-progress PCM survives ordinary process death, but sudden power loss can lose
unflushed filesystem data. Calls, focus loss, Bluetooth/OS capture pauses and a
killed foreground service still interrupt physical capture; offline networking
does not. Automatic capture restart after process death is intentionally absent.

Wake recognition requires Android 13+ and an installed on-device English model;
without it, use Ask/capture while recording continues. Recognition/model timestamps,
ASR text and speaker changes are approximate. Exact dedupe can miss ASR variations
or remove a genuine immediate repetition. This is not verified speaker identity or
a verbatim record. Natural overlapping voices and distant speakers need hardware
validation. Meeting questions can use normal recall tools, but a newly opened Live
session does not inherit all earlier meeting context automatically.

Tests cover silence/hard-cap chunking, exact overlap bytes, restart recovery,
lost upload replies, offline catch-up, finalization from durable transcripts,
manifest holes/conflicts, queue cooldown, bounded two-hour processing, wake/button
handoff, normal token selection, translation exclusion and closing after playback.
Real evaluation and final test counts: [meeting chunked evaluation](../evals/meeting-chunked-2026-10-08.md).

Verified sources with URLs, **2026-10-08**: [research record](../research/meeting-chunked.md),
[Google audio guide](https://ai.google.dev/gemini-api/docs/audio),
[Flash-Lite model](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite),
[Android shared PCM recognition](https://developer.android.com/reference/android/speech/RecognizerIntent),
[pricing](https://ai.google.dev/gemini-api/docs/pricing). Physical hardware,
real Docs export and two-hour real-time endurance remain unverified.
