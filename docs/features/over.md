# Over mode and configurable patience

Updated **2026-10-17** with one retry and Inbox fallback for silent Gemini stalls. Runtime conversation AI remains Gemini Live.

## Use

Stop the conversation before changing settings. Choose **Auto**, **Push-to-talk**,
or **Over**, then tap **Start**. Mode, Auto patience, Over keyword and silence
timeout persist locally in Preferences DataStore.

- **Auto:** set **Patience** from 500–5000 ms (default 2000 ms). Gemini waits for
  this much silence using the existing low-sensitivity automatic detection.
- **Push-to-talk:** screen hold/release and glasses touchpad double-tap toggle
  remain available, including the existing 60-second limit.
- **Over:** speak when connected; startup audio is buffered for keyword recognition. Speak for as
  long as you like; finish with the selected phrase: **over** (default), **go ahead**,
  or **your turn**. For example: “What is two plus two? Over.” Only the selected
  turn keyword is active. **Over and out** always stops the whole session.
  Alternatively, stop speaking for **10 continuous seconds** (configurable).
  Silence before the first detected speech never ends a turn; keep listening
  until speech arrives or the conversation idle timeout applies.
  **Stop** closes the session immediately. There is no manual Finish turn button
  in Over mode; if keyword recognition is unavailable, use the silence timeout.

Over opens a manual activity when Gemini connects. The microphone streams
continuously during the user's turn, including pauses. Only a recognized suffix or
the configured continuous-silence timeout sends `activityEnd`. **There is no
maximum turn length while the user keeps speaking.** Microphone capture stays
open during the reply, but its audio is not sent to Gemini or the keyword
recognizer as speech. The recognizer receives synthetic silence during replies
and stays alive. After `turnComplete` and playback drain, a fresh activity and
turn detector begin, reusing the warm recognition session. Over therefore does not support barge-in during replies.
Reconnect clears local hypotheses and starts a fresh user activity.

With head gestures enabled, a valid nod/shake to Gemini's pending yes/no question
also sends an answer: it closes the open activity, pauses keyword matching,
and sends the complete gesture text turn. Recognition resumes after the reply
and playback drain. Transcript uploads, memory tools and Google Docs notes use
the same lifecycle and backend registry in all three conversation modes.

## Recognition and timing

Over starts Android on-device `SpeechRecognizer` immediately, with offline English
(`en-US`), instead of first waiting for DAT Speech. The hardware report's repeated
8.2-second delay matched the DAT session's 8-second timeout, repeated by the old
per-turn teardown. Over no longer depends on DAT Speech permission/readiness.

Recognition receives the **same resampled 16 kHz mono PCM16** as Gemini. It uses
API 33 audio-source segmented mode and stays open across turns; only the local
hypotheses/silence counter reset. Model playback feeds zeros to this source, so
model echo cannot supply a sign-off. Stop, reconnect and call/focus suspension
release recognition. A service-driven end/timeout restarts immediately after a
250 ms backoff, including during playback; this is not a per-turn restart.

A 16-second (512 KB) PCM queue retains startup/restart audio and partial pipe
writes. A background nonblocking writer retries pipe backpressure without
blocking capture or Gemini upload. Overflow or a broken service produces an
explicit “audio could not be evaluated” error; use the silence timeout or restart.
Android 13/API 33+ and the installed offline English model are required. No
second recorder is intentionally opened.

While local recognition is preparing, restarting or unavailable, received Gemini
`inputTranscription` chunks also feed an independent suffix detector. Late chunks
while waiting for a reply are ignored, and a healthy local recognizer owns the
turn. This is opportunistic: all 12 measured Live turns delivered transcription
**after** `activityEnd`. It cannot replace the PCM buffer, and provider chunks have
no turn identifier or guaranteed ordering. Cold-start/restart fallback still
needs hardware validation; the silence timeout remains available.

Recognition callbacks ending on silence only restart recognition; they do not
end the Gemini turn. Changing nonblank on-device partial/final hypotheses are
the preferred speech evidence and reset the silence counter even for quiet HFP
speech. Duplicate hypotheses do not keep a turn alive. The PCM energy detector
is the fallback between recognition updates, including during service restart.
Both speech sources refresh conversation activity; merely listening does not.
Partial hypotheses must keep the same normalized text for
1.2 seconds before a suffix commits. Final “go ahead”, “your turn” and “over and
out” commit immediately; “over” gets the same 1.2-second grace period so “and
out” can follow. Matching ignores case, punctuation and whitespace and requires
whole trailing words. It is not substring matching (“clover” does not match).

**End turn after silence** defaults to **10 seconds**, adjustable from **5–30
seconds** in one-second steps. A fresh full interval is required after speech
resumes: short pauses never accumulate across speech. **No detected speech means
no silence expiry**, even after minutes of quiet. Each turn resets this latch.
The detector counts captured PCM samples since the latest speech evidence, so missing microphone
callbacks and recognizer restarts do not manufacture silence.

The local energy gate removes DC, uses Over-specific PCM16 RMS thresholds of 350 to start and
225 to hold speech, and checks zero-crossing rate (0.008–0.20) to reject rumble
and broadband hiss. Speech onset must last 100 ms, ignoring short clicks. The
lower threshold bridges weak syllables for up to 200 ms; weak breathing or
background energy cannot hold that state forever. Completely quiet frames count
as silence immediately. An onset at the timeout boundary gets at most 100 ms to
qualify; otherwise the accumulated silence still ends the turn. This is an energy
heuristic, not semantic or speaker-specific VAD. On the six existing quiet-HFP
fixture cases attenuated by 20 dB, the old gate detected 0/6 and the new gate
detected 6/6 (five distinct utterances). This is synthetic calibration, not a
measurement of the reported hardware session; background false speech and quiet
wearer speech still need hardware checks. Other features retain 700/450.

## Reply recovery

After `activityEnd`, Over waits at most **8 seconds without a relevant event**
(model content, tool call or completion). When only input transcription has arrived
it allows **10 seconds from End**. Active model output refreshes a 45-second
deadline; this imposes no user speaking limit.
A **stall** (no model content or tool call for this turn, whether or not Gemini
sent an input transcription) retries once instead of discarding the request.
Updated **2026-10-19**: hardware logs showed Gemini transcribing a note and then
never replying, three times in a row; a replay of the same speech reproduced it in
1 of 8 attempts. Input transcription therefore no longer counts as progress. The phone retains the
exact PCM accepted by the socket between activityStart/activityEnd, up to **3
minutes / 5.76 MB**. Exceeding this cap disables replay for that turn; it does not
end speech or replay truncated audio. Local final recognition segments plus the
last partial are retained separately, with the trailing sign-off removed.

At the 8-second watchdog, play one recovery cue (no TTS), retire the socket, and
open a fresh Live session using the existing bounded context recap. Do not resume
the stalled checkpoint. After setup, send activityStart, **200 ms / 6,400-byte
PCM chunks every 10 ms**, then activityEnd and a fresh 8-second watchdog. Keep
microphone uplink and recognition paused throughout replay. All old-socket events,
including already queued callbacks, are ignored before tool execution.

If the retry also stalls, the audio cannot be replayed, or reconnect/
replay fails, atomically queue Gemini's own input transcription (else the
on-device text), sign-off removed, for **Inbox**, prefixed
`[Gemini didn't answer] `. The stable receipt uses the existing offline delivery
path; it never grammar-parses this item into a different command. Local TTS says
**“Gemini didn't answer. I saved what I heard to your inbox.”** If there is no
text (or the phone cannot persist it), say **“Gemini didn't answer. Please say
that again.”** Reopen listening after the announcement and connection recovery.
The note is durable on the phone before this confirmation; actual Docs delivery
can remain pending while the backend is unavailable. Inbox must already exist.
The failed retry socket is retired too, preventing a late tool from executing
after fallback. Connection setup has a 25-second overall recovery budget.

Any received model content or tool call excludes automatic replay and fallback;
existing 45-second and tool watchdog handling remains. An empty non-tool
`turnComplete` after Gemini transcribed speech is treated as a stall (retry, then
Inbox); without any transcribed speech it still reopens silently. Auto, PTT, meeting and
translator behavior is unchanged. Stop cancels replay; audio suspension pauses
replay and defers fallback speech until resume.

Diagnostics contain no PCM or speech text:
`turn=N stall_retry started bytes=…`,
`turn=N stall_retry outcome=ok|stalled|failed latency_ms=…`, and
`turn=N stall_fallback saved=inbox|empty`. `ok` means reply progress was received,
not that a tool succeeded; latency is measured from retry start. A non-replayable
turn goes directly to fallback without a retry-start log.

A tool call starts a **45-second execution deadline** for its pending batch.
After the last toolResponse is sent, a fresh **8-second no-reply deadline** starts;
tool execution time does not consume it. Input-only progress allows 45 seconds
from that send, and active model output refreshes the 45-second stall deadline.
A tool-generation `turnComplete` is not enough to reopen
while tools or their spoken continuation are outstanding. Exceeding the deadline
logs `tool_reply_watchdog`, plays the short existing earcon, and reopens listening.
Timed-out tool jobs are cancelled locally; late results and duplicate IDs are
suppressed. A backend side effect may already have happened: recovery never
retries it automatically. Calls/focus suspension defer recovery until audio is
allowed again; Stop/reconnect cancel the old watchdog. Normal completed replies
still wait for playback drain.

Diagnostics record `speech_detected first_ms/last_ms` for each local user turn
(monotonic time, null when no speech was detected), plus the Gemini turn number
and re-open reason. No transcript content is included in these diagnostics.
Provider events have no user-turn ID, so unusually late model/transcription events
around a recovered turn remain a hardware stress-test item.

**Migration:** the old `over_safety_seconds` preference is removed atomically
when settings load (or save). Existing installs receive the new 10-second
`over_silence_seconds` default, regardless of the former speech limit. Mode,
keyword and Auto patience are preserved. An already saved new silence value is
preserved and clamped to 5–30 seconds. The different old semantics are deliberately
not converted to a silence duration.

## Backend contract

`POST /token`, authenticated with the existing `X-App-Secret`:

```json
{"mode":"over"}
```

Over and PTT lock `automaticActivityDetection.disabled=true`. Auto accepts:

```json
{"mode":"auto","silence_ms":3250}
```

Omitting `silence_ms` gives 2000. Integers clamp to 500–5000; other types receive
422. Manual modes ignore this option. Tokens continue using
`lock_additional_fields=[]`; `sessionResumption` remains client-owned. The system
prompt treats trailing sign-offs as turn control and never acknowledges them.
No new backend secrets or environment variables are required.

## Verification

- **2026-10-17 stall retry:** JVM regressions cover bounded exact PCM, pure versus
  input-only stalls, one retry, fresh context, old-socket suppression, retry failure,
  durable fallback receipts, sign-off stripping and mode exclusions. Hardware:
  use a debug socket/proxy to withhold all relevant responses on the first session
  only; speak a disposable Inbox note ending in “over”. Expect one cue, one fresh
  setup/replay and one note append. Then withhold both replies: expect the local
  fallback phrase, one prefixed Inbox note and resumed listening. Kill/restart the
  app while delivery is pending and check no duplicate append. Repeat with empty
  recognition, >3 minutes of continuous speech, call/focus suspension and Stop.
  Deliver a late old-socket tool call and confirm it cannot execute. A transcript
  arriving before timeout must retain the existing 45-second handling without
  replay. See [hardware plan](../hardware-test-plan.md#over-stall-retry--2026-10-17).
  Physical replay pacing, recognizer accuracy, cue/TTS routing and real Docs writes
  remain unverified in this change; the service-side evidence is in
  [Gemini Live stalls](../research/gemini-live-stalls.md).
  Validation: backend `UV_CACHE_DIR=/tmp/meta-stallretry-uv uv run pytest -q`:
  **888 passed, 3 warnings**. All production/test Kotlin compiled using the cached
  compiler; **65 focused JVM tests passed** via JUnitCore. An earlier direct full
  run passed 314 tests; 26 MockWebServer tests could not bind sockets in the
  sandbox. `./gradlew testDebugUnitTest` could not start (cache permissions, then
  `FileLockContentionHandler` socket restriction with writable caches); the
  orchestrator must run the Gradle task outside this sandbox.

- **2026-10-09 stall fix:** JVM socket tests cover no-event recovery, empty
  completion, delayed tools/results, the overall tool deadline, suspension and
  Stop. Detector tests cover silent startup, quiet partials, 8→16 kHz speech,
  fresh silence intervals and continuous speech without a maximum. Two real
  Live sessions both replied to digital silence, and both accepted subsequent
  speech on the same socket; the reported provider stall was **not reproduced**.
  See [stall evaluation](../evals/over-stall-2026-10-09.md).

- **2026-10-09 latency correction:** JVM coverage checks warm reuse across three
  turns, first-word sign-offs, startup buffer/backpressure, transcription fallback,
  and immediate `activityEnd` with no trailing audio or duplicate end. New
  `live-turn` diagnostics include UTC + monotonic timestamps, turn number, queued
  bytes and input byte count; no transcript contents. “Sent” means accepted into
  OkHttp's queue, not acknowledged by Gemini. Receive timing is logged on the
  socket callback before Main/UI delivery.
- Real Live: **4 sessions / 12 turns**, median activityEnd→audio **1.095 s**;
  **10/12 < 2 s**, range **0.840–6.427 s**. Includes synthetic HFP 8→16 kHz and a
  missed-first-over/repeated-question case. The existing 1.2-second sign-off grace
  is additional. The 16.739-second hardware delay was not reproduced or proven
  fixed. See [full evaluation](../evals/over-latency-2026-10-09.md) and
  [hardware retest](../hardware-test-plan.md#over-latency-retest--2026-10-09).

- Merged with memory and head gestures: backend **97 passed, 2 warnings**;
  Android **BUILD SUCCESSFUL**, **67 JVM tests, zero failures/errors/skips**.
  Added coverage for memory and all five tools in every token mode, plus Over
  gesture activity/text ordering, failed sends and preserved PTT ownership.
  Used writable temporary uv/Gradle/Android caches and Gradle `--no-daemon`
  with `--project-cache-dir /tmp/meta-merge-over-project-cache` in the sandbox.
  Combined physical Motion/Speech/HFP operation remains unverified.
- `cd android && ./gradlew assembleDebug testDebugUnitTest`: passed, 39 JVM tests,
  including keyword/sign-off precedence, exact 10-second silence, uninterrupted
  three-minute speech with the shortest timeout, repeated nine-second pauses,
  noisy/breath-like PCM, onset/release hysteresis, timeout bounds, token requests,
  migration and DataStore recreation.
- Prior implementation: `cd backend && uv run pytest`: 66 passed. Includes Over
  constraints, real SDK
  HTTP serialization, patience clamping/types and resumption field-mask checks.
- Real `/token` in-process HTTP request using the gitignored `.env`: Over HTTP
  200. Connected to the constrained v1beta WebSocket with the token header and
  minimal model + `sessionResumption:{}` setup; received `setupComplete`.
- Sent `activityStart`, locally synthesized PCM speech asking two plus two and
  ending in “Over”, waited 2.5 seconds with **no reply**, then sent `activityEnd`.
  Received 74,882 PCM bytes, the correct answer without sign-off acknowledgement,
  and `turnComplete`. Re-run **2026-10-07** with `scripts/smoke_over.py`.
  Credentials and returned token were never printed. This verifies manual Gemini
  activity boundaries; the Android silence/keyword detector is covered by JVM
  tests, not by this Python protocol smoke test.

Reproduce on macOS (the synthesis is a local test fixture, not app runtime AI):

```sh
say -v Samantha -r 155 -o /tmp/over-question.aiff 'What is two plus two? Answer briefly. Over.'
afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/over-question.aiff /tmp/over-question.wav
cd backend
uv run python ../scripts/smoke_over.py --wav /tmp/over-question.wav
```

In the restricted development sandbox the build used JDK 17 and the existing
Android SDK, with writable Gradle/Android-user/uv caches under `/tmp`.

## Limitations and device follow-up

- **Hardware retest pending:** Pixel PCM injection, persistent recognizer latency,
  HFP playback/echo behavior, and detection of the first word after each reply.
  The supplied hardware log establishes that on-device startup succeeded, not
  that every word reached its recognizer.
- Persistent segmented recognition, early-word replay, service restart behavior and
  callback timing still need the hardware retest below. A service may ignore the
  segmented extras or deliver delayed results; the buffer covers unsent PCM,
  not audio already consumed and then lost inside a failing speech service.
- Android recognizers may ignore PCM-source extras and open their own mic. A pipe
  that remains blocked beyond the bounded buffer disables recognition with an
  error; it cannot stall Gemini upload. There is no fallback that intentionally races a second mic.
  On unsupported phones, pause for the configured silence interval or switch
  to PTT/Auto after Stop.
- Recognition mistakes or a stable partial ending in “over” can commit too soon;
  choose “your turn” if “over” occurs often in ordinary speech. The 1.2-second
  grace is finite: pause longer after “over” and it may commit before “and out”.
- Quiet speech below the energy threshold may be classified as silence. Loud
  tonal noise, wind or nearby voices may count as speech and delay the timeout.
  Synthetic tests cover low noise, breath-like energy, DC, clicks and broadband
  phone/HFP hiss. Over mode has since been used with real speech on a Pixel and
Meta glasses (2026-10), but thresholds were not measured on recordings. There is no
  fallback speech-duration cap. Validate thresholds on those recordings.
- A text-only manual-activity probe closed after `activityEnd`; PCM is the
  verified path and the committed smoke script uses PCM. The text-only failure
  was not diagnosed; the Android app does not use that path.
- Conversations continue in the foreground service with the screen off. Settings
  take effect on the next Start; the notification can cycle all three modes by
  restarting Gemini. Capture HOLD starts/stops the conversation. Call/focus
  pauses stop recognition and retain an open Over activity; resuming restarts
  the silence interval. Double-tap has no Over turn-control action. See
  [hands-free operation](hands-free.md).

## Verified sources (2026-10-07)

- [DAT Speech API](https://wearables.developer.meta.com/docs/reference/android/dat/latest/com_meta_wearable_dat_speech_speech)
  and [full official docs](https://wearables.developer.meta.com/llms.txt?full=true).
- [DAT Android repository at inspected commit](https://github.com/facebook/meta-wearables-dat-android/tree/d3159f738676221181577dd745d83d10b6ab46ba),
  [published speech binary](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-speech/1.0.0/).
- [Android RecognizerIntent PCM/segmented extras](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE),
  [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer),
  [DataStore](https://developer.android.com/jetpack/androidx/releases/datastore),
  [AudioTrack playback position](https://developer.android.com/reference/android/media/AudioTrack#getPlaybackHeadPosition()).
- [Gemini Live wire API](https://ai.google.dev/api/live),
  [ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens).

See [research evidence and unverified items](../research/over-mode-notes.md).
