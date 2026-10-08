# Over mode and configurable patience

Implemented and checked **2026-10-07**. Runtime conversation AI remains Gemini Live.

## Use

Stop the conversation before changing settings. Choose **Auto**, **Push-to-talk**,
or **Over**, then tap **Start**. Mode, Auto patience, Over keyword and silence
timeout persist locally in Preferences DataStore.

- **Auto:** set **Patience** from 500–5000 ms (default 2000 ms). Gemini waits for
  this much silence using the existing low-sensitivity automatic detection.
- **Push-to-talk:** screen hold/release and glasses touchpad double-tap toggle
  remain available, including the existing 60-second limit.
- **Over:** wait for the status to say **Listening for sign-off**. Speak for as
  long as you like; finish with the selected phrase: **over** (default), **go ahead**,
  or **your turn**. For example: “What is two plus two? Over.” Only the selected
  turn keyword is active. **Over and out** always stops the whole session.
  Alternatively, stop speaking for **10 continuous seconds** (configurable).
  **Stop** closes the session immediately. There is no manual Finish turn button
  in Over mode; if keyword recognition is unavailable, use the silence timeout.

Over opens a manual activity when Gemini connects. The microphone streams
continuously during the user's turn, including pauses. Only a recognized suffix or
the configured continuous-silence timeout sends `activityEnd`. **There is no
maximum turn length while the user keeps speaking.** Microphone capture stays
open during the reply, but its audio is not sent to Gemini or the keyword
recognizer. After `turnComplete` and playback drain, a fresh activity and
recognizer begin. Over therefore does not support barge-in during replies.
Reconnect clears local hypotheses and starts a fresh user activity.

With head gestures enabled, a valid nod/shake to Gemini's pending yes/no question
also sends an answer: it closes the open activity, stops keyword recognition,
and sends the complete gesture text turn. Recognition resumes after the reply
and playback drain. Transcript uploads, memory tools and Google Docs notes use
the same lifecycle and backend registry in all three conversation modes.

## Recognition and timing

The first choice is DAT `mwdat-speech:1.0.0` on real connected glasses, with the
DAT **microphone permission** granted using the existing button. It supplies
partial/final replacement hypotheses; Gemini input transcription is not needed
to decide when to send `activityEnd`.
Speech attaches to the existing capture-button/Motion DAT session. Each turn
removes only its Speech capability; ending a turn or disabling head gestures
does not stop the other capabilities. Stop releases the shared session.

If DAT is missing, denied, fails, times out or stops, the fallback is Android
on-device `SpeechRecognizer`, with offline preference and English (`en-US`).
It receives the **same resampled 16 kHz mono PCM16** as Gemini through a pipe.
No second Android recorder is deliberately opened. Phone and mock-glasses audio
use this fallback directly. Android 13/API 33 or later is required for this PCM
injection fallback, even though the on-device recognizer factory exists at API 31.
Install the offline English recognition model on the phone first.

Recognition callbacks ending on silence only restart recognition; they do not
end the Gemini turn. The independent PCM detector owns the silence timeout.
Partial hypotheses must keep the same normalized text for
1.2 seconds before a suffix commits. Final “go ahead”, “your turn” and “over and
out” commit immediately; “over” gets the same 1.2-second grace period so “and
out” can follow. Matching ignores case, punctuation and whitespace and requires
whole trailing words. It is not substring matching (“clover” does not match).

**End turn after silence** defaults to **10 seconds**, adjustable from **5–30
seconds** in one-second steps. A fresh full interval is required after speech
resumes: short pauses never accumulate across speech. Silence before the first
word also counts. The detector counts captured PCM samples, so missing microphone
callbacks and recognizer restarts do not manufacture silence.

The local energy gate removes DC, uses PCM16 RMS thresholds of 700 to start and
450 to hold speech, and checks zero-crossing rate (0.008–0.20) to reject rumble
and broadband hiss. Speech onset must last 100 ms, ignoring short clicks. The
lower threshold bridges weak syllables for up to 200 ms; weak breathing or
background energy cannot hold that state forever. Completely quiet frames count
as silence immediately. An onset at the timeout boundary gets at most 100 ms to
qualify; otherwise the accumulated silence still ends the turn. This is an energy
heuristic, not semantic or speaker-specific VAD; thresholds need hardware checks.

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

- **Unverified on hardware:** DAT STT coexisting with HFP capture, permissions,
  recognizer latency, Pixel PCM injection and playback/echo behavior. DAT's API
  and published binary support transcripts; that does not prove concurrent HFP
  behavior. Test on the Pixel and real glasses before relying on voice sign-off.
- DAT Speech is a development/beta capability. Its StateFlow can coalesce equal
  hypotheses; a new capability is attached for each user turn to avoid reusing a
  previous sign-off. Startup can miss early speech: wait for **Listening**.
- Android recognizers may ignore PCM-source extras and open their own mic. A pipe
  that stops accepting data disables recognition with an error; it cannot stall
  Gemini upload. There is no fallback that intentionally races a second mic.
  On unsupported phones, pause for the configured silence interval or switch
  to PTT/Auto after Stop.
- Recognition mistakes or a stable partial ending in “over” can commit too soon;
  choose “your turn” if “over” occurs often in ordinary speech. The 1.2-second
  grace is finite: pause longer after “over” and it may commit before “and out”.
- Quiet speech below the energy threshold may be classified as silence. Loud
  tonal noise, wind or nearby voices may count as speech and delay the timeout.
  Synthetic tests cover low noise, breath-like energy, DC, clicks and broadband
  phone/HFP hiss; real Pixel/glasses recordings remain unverified. There is no
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
