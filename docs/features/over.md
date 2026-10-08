# Over mode and configurable patience

Implemented and checked **2026-10-07**. Runtime conversation AI remains Gemini Live.

## Use

Stop the conversation before changing settings. Choose **Auto**, **Push-to-talk**,
or **Over**, then tap **Start**. Mode, Auto patience, Over keyword and safety
limit persist locally in Preferences DataStore.

- **Auto:** set **Patience** from 500–5000 ms (default 2000 ms). Gemini waits for
  this much silence using the existing low-sensitivity automatic detection.
- **Push-to-talk:** existing screen hold/release and glasses capture HOLD toggle
  remain available, including the existing 60-second limit.
- **Over:** wait for the status to say **Listening for sign-off**. Speak and
  pause freely; finish with the selected phrase: **over** (default), **go ahead**,
  or **your turn**. For example: “What is two plus two? Over.” Only the selected
  turn keyword is active. **Over and out** always stops the whole session.
  **Finish turn** sends the current turn manually if recognition misses a phrase;
  **Stop** closes the session immediately.

Over opens a manual activity when Gemini connects. The microphone streams
continuously during the user's turn, including pauses. A recognized suffix or
the continuous-speech safety limit sends `activityEnd`. Microphone capture stays
open during the reply, but its audio is not sent to Gemini or the keyword
recognizer. After `turnComplete` and playback drain, a fresh activity and
recognizer begin. Over therefore does not support barge-in during replies.
Reconnect clears local hypotheses and starts a fresh user activity.

## Recognition and timing

The first choice is DAT `mwdat-speech:1.0.0` on real connected glasses, with the
DAT **microphone permission** granted using the existing button. It supplies
partial/final replacement hypotheses; Gemini input transcription is not needed
to decide when to send `activityEnd`.

If DAT is missing, denied, fails, times out or stops, the fallback is Android
on-device `SpeechRecognizer`, with offline preference and English (`en-US`).
It receives the **same resampled 16 kHz mono PCM16** as Gemini through a pipe.
No second Android recorder is deliberately opened. Phone and mock-glasses audio
use this fallback directly. Android 13/API 33 or later is required for this PCM
injection fallback, even though the on-device recognizer factory exists at API 31.
Install the offline English recognition model on the phone first.

Recognition callbacks ending on silence only restart recognition; they never
end the Gemini turn. Partial hypotheses must keep the same normalized text for
1.2 seconds before a suffix commits. Final “go ahead”, “your turn” and “over and
out” commit immediately; “over” gets the same 1.2-second grace period so “and
out” can follow. Matching ignores case, punctuation and whitespace and requires
whole trailing words. It is not substring matching (“clover” does not match).

The safety slider defaults to **45 seconds**, adjustable from 10–120 seconds.
The clock measures sustained PCM energy; a gap over 800 ms resets it. It can end
a turn only on an energetic audio frame, never during silence. This lightweight
energy approximation uses RMS 600 on PCM16; it is not a semantic speech detector.

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

- `cd android && ./gradlew assembleDebug testDebugUnitTest`: passed, 30 JVM tests,
  including keyword revisions/sign-off precedence, silence and safety timing,
  token requests and DataStore recreation.
- `cd android && ./gradlew lintDebug`: passed, zero errors (eight existing
  dependency/update and backup-declaration warnings).
- `cd backend && uv run pytest`: 66 passed. Includes Over constraints, real SDK
  HTTP serialization, patience clamping/types and resumption field-mask checks.
- Real `/token` in-process HTTP request using the gitignored `.env`: Over HTTP
  200. Connected to the constrained v1beta WebSocket with the token header and
  minimal model + `sessionResumption:{}` setup; received `setupComplete`.
- Sent `activityStart`, locally synthesized PCM speech asking two plus two and
  ending in “Over”, waited 2.5 seconds with **no reply**, then sent `activityEnd`.
  Received 77,280 PCM bytes, the correct answer without sign-off acknowledgement,
  and `turnComplete`. Credentials and returned token were never printed.

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
  On unsupported phones, use **Finish turn** or switch to PTT/Auto after Stop.
- Recognition mistakes or a stable partial ending in “over” can commit too soon;
  choose “your turn” if “over” occurs often in ordinary speech. The 1.2-second
  grace is finite: pause longer after “over” and it may commit before “and out”.
- Sustained noise may trigger the energy-based safety limit; quiet speech may
  miss it. Validate the threshold with HFP recordings before tuning it.
- A text-only manual-activity probe closed after `activityEnd`; PCM is the
  verified path and the committed smoke script uses PCM. The text-only failure
  was not diagnosed; the Android app does not use that path.
- Foreground conversations only. Settings take effect on the next Start.

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
