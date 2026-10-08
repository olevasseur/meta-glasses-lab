# Over mode: speech sources, microphone sharing and manual activity

Verified **2026-10-07**. This supplements the historical M1/M2 notes; their 1200 ms
Auto setting is superseded by this feature's 2000 ms default.

## DAT evidence

Cloned [facebook/meta-wearables-dat-android](https://github.com/facebook/meta-wearables-dat-android)
to `/tmp/glasses-over-dat` and inspected commit
`d3159f738676221181577dd745d83d10b6ab46ba`. Also used a sparse clone at
`/tmp/glasses-over-dat-api`. Inspected the official BirdSpotter sample's
[DatGlassesSessionRepository](https://github.com/facebook/meta-wearables-dat-android/blob/d3159f738676221181577dd745d83d10b6ab46ba/samples/BirdSpotter/app/src/main/java/com/meta/pixelandtexel/birdspotter/data/dat/DatGlassesSessionRepository.kt)
and `javap` signatures from the actual Maven
[`mwdat-speech:1.0.0` AAR](https://repo.maven.apache.org/maven2/com/meta/wearable/mwdat-speech/1.0.0/).

Verified API: `DeviceSession.addSpeech(SpeechConfiguration())`, `Speech.start()` /
`stop()`, `Speech.state`, `errors`, and `transcriptions: StateFlow<TranscriptionResult?>`.
Results have `text`, `isFinal`, and `confidence`. The new capability starts stopped
and must be started explicitly. Prerequisites are a STARTED device session and
`Permission.MICROPHONE` via the Meta AI permission contract.

The [official docs dump](https://wearables.developer.meta.com/llms.txt?full=true)
states that ASR runs on the glasses, partial results replace prior hypotheses,
and Speech can stop due to inactivity/timeout without an error. Speech is limited
to development/beta channels. The sample also notes StateFlow equality conflation:
identical repeated values are not guaranteed separate delivery. We attach a fresh
capability each user turn and fall back to phone STT on stop/failure. **Inference:**
DAT text delivery avoids a second Android microphone consumer; this is not proof
that firmware permits simultaneous on-glasses ASR and HFP.

## Android fallback

Official [SpeechRecognizer reference](https://developer.android.com/reference/android/speech/SpeechRecognizer)
documents on-device creation/availability at API 31, main-thread lifecycle calls,
and `destroy()` cleanup. [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)
documents API 33 PCM injection via `ParcelFileDescriptor`, with channel count,
encoding and sampling-rate extras. Segmented mode can use the audio source as
its lifetime; segment callbacks are separate from session completion.

The fallback requires API 33 and on-device recognition. It requests offline
English, PCM16 mono/16 kHz and segmented results. A nonblocking pipe taps the
app's existing resampled AudioRecord output, so slow/absent STT cannot block the
Gemini uplink. Android warns that implementations may ignore the audio source
and open their own mic; this limitation is exposed in UI/docs. API 29–32 keeps DAT
support but has no PCM-injection fallback. We do not use the older URI injection
or deliberately open a competing microphone.

[DataStore 1.2.1](https://developer.android.com/jetpack/androidx/releases/datastore)
is the documented stable Preferences dependency. It stores mode, patience,
keyword and Over silence timeout (superseding the original speech limit); JVM tests reopen the store from disk.
[AudioTrack](https://developer.android.com/reference/android/media/AudioTrack#getPlaybackHeadPosition())
provides the playback-head frame counter used to wait until queued/device-buffered
model audio is played before opening the next activity.

## Gemini and real checks

[Live WebSocket reference](https://ai.google.dev/api/live) verifies activityStart /
activityEnd require disabled automatic detection, realtime audio/text input,
and model turn completion. [Ephemeral-token guide](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens)
describes constrained setup. Kept `lock_additional_fields=[]` and omitted locked
`sessionResumption` exactly as in the working app/backend contract.

The real backend handler returned HTTP 200 for `{"mode":"over"}`. The constrained
v1beta socket accepted header authorization and setup with model plus empty
sessionResumption. PCM input used macOS speech synthesis saying “What is two plus
two? Answer briefly. Over.” After activityStart and PCM, a 2.5-second input pause
produced no reply. activityEnd produced 77,280 PCM output bytes, the correct answer,
no sign-off acknowledgement and turnComplete. No token/key was printed or saved.
This checks the manual activity boundary and prompt for one real session, not
on-device recognition. An earlier text-only variant closed after activityEnd;
only the PCM path is verified. `scripts/smoke_over.py --wav …` reproduces PCM.

## Unverified

- DAT Speech + HFP coexistence, device permission/entitlement, startup latency,
  recognition accuracy and equality conflation on real glasses.
- Pixel on-device engine honoring PCM injection and segmented mode, installed
  English model availability, gaps when a recognizer restarts, and echo behavior.
- Actual pause tolerance in Auto at 500/2000/5000 ms; clamp/config serialization
  is tested, but real Auto microphone VAD behavior was not measured.
- Silence detector energy/crossing thresholds under real quiet speech, breathing, wind, noise and HFP resampling.
- Long-lived/reconnected hardware conversations and reply playback drain.
- Text-only manual-activity failure reason; audio input succeeds.

## Silence-rule correction (2026-10-07)

Decision 10 now ends an Over turn only on the selected keyword or 10 seconds of
continuous silence (5–30 seconds configurable). No speaking-duration limit or
manual Finish turn remains. The client counts quiet samples in the shared 16 kHz
stream using RMS/crossing-rate gating with 100 ms attack and bounded 200 ms weak
release; timestamps or STT completion alone never count as silence. This is a
local heuristic, not a provider VAD guarantee.

Rechecked [DataStore documentation](https://developer.android.com/topic/libraries/architecture/datastore)
and [Gemini wire reference](https://ai.google.dev/api/live) on 2026-10-07: transactional
preference edits support deleting the legacy setting while writing the new
10-second default; explicit activity control still uses disabled automatic VAD.
A previously saved new silence setting wins over the legacy value. Unit tests
verify persistence after reopening the store and both bounds.

Verification: 39 JVM tests passed, including three minutes of uninterrupted
synthetic voiced PCM, pauses restarting the full silence interval, exact silence
expiry, boundary onsets, weak-energy hysteresis, DC/clicks and broadband phone/HFP
noise. Re-ran `scripts/smoke_over.py`: real token HTTP 200, setupComplete, no reply
during its 2.5-second pause, then activityEnd → 74,882 PCM response bytes and
turnComplete with the correct answer and ignored sign-off. The script validates
the Gemini protocol, not the Android detector or actual 10-second microphone
behavior. Hardware speech/noise accuracy remains unverified as listed above.
