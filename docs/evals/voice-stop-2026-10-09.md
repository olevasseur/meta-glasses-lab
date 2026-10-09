# Voice stop evaluation — 2026-10-09

## Result

- **5/5 spoken Gemini Live cases passed** using the production constrained-token
  setup and complete tool registry. Auto/PTT/Over exit requests each selected
  `end_conversation` and produced **“Bye!”**. Two false-positive utterances
  selected no tools.
- **25/26 completed text selection probes passed** on `gemini-3.5-flash-lite`.
  One meeting-context goodbye incorrectly selected `end_conversation`; Android's
  independent meeting guard rejects it, preserving capture. This is a model
  selection limitation, not a passing meeting-intent result.
- Android: **263 JVM tests, zero failures/errors/skips**; debug APK assembled.
  Backend: **625 passed**, three existing dependency warnings.
- Physical glasses/HFP/DAT recovery has **not** been tested in this session.

## Budget and method

**32 text-generation attempts total**: 27 selection attempts (26 completed,
1 provider 429) plus 5 Gemini TTS requests. **5 Live sessions**, no Live retries.
The Live runner also made two countTokens requests; even including those,
there were only 34 non-Live requests, below the requested 80-call ceiling.

All selection probes used the actual assistant system prompt and all 43 registered
function declarations, automatic tool execution disabled. Cases cover clear exit,
bare stop as interruption, negation, reported speech, questions about goodbye,
a French-language explanation task, and meeting goodbye/stop. Text evaluation is
a selection proxy, not a Live/audio/hardware test. No tools were really executed
by the text model.

The first text run completed six passing cases, then the repository's conservative
10% local eval-token allowance refused the next request **before** an API call.
The second run used `USAGE_EVAL_PERCENT=100` for this authorized bounded evaluation,
completed 20 cases (19 passing), then hit a provider **per-minute 429** on its 21st
attempt. It stopped without retrying. The runner now spaces calls by at least
3.2 seconds. Partial attempts and failures are retained rather than counted as
completed tests. No persistent quota configuration was changed.

[First text results](voice-stop-2026-10-09-text.json),
[first budget ledger](voice-stop-2026-10-09-text.budget.json),
[second text results](voice-stop-2026-10-09-text-full.json),
[second budget ledger](voice-stop-2026-10-09-text-full.budget.json).
Prompt/tool SHA-256 snapshots are recorded in the artifacts. The first run had the
same declarations in a different order; the second and Live runs use final order.

## Spoken Live cases

Synthetic Gemini `gemini-3.8-flash-tts` Kore speech, streamed as PCM16 16 kHz to
`gemini-3.8-live`; Auto uses server VAD, PTT/Over send normal activity boundaries.
The Over harness supplies the sign-off boundary; it does **not** exercise the
concurrently edited Android keyword recognizer. Real `/token` config is used,
while Android tools are simulated and backend side effects isolated.

| Mode | Spoken request | Selected tool | Spoken response | Result |
|---|---|---|---|---|
| Auto | Goodbye. | end_conversation | Bye! | Pass |
| PTT | Stop listening. | end_conversation | Bye! | Pass |
| Over | Goodbye. Over. | end_conversation | Bye! | Pass |
| Auto | Don't stop listening. | none | Still listening | Pass |
| Auto | I said goodbye to my mom. | none | Conversational response | Pass |

For the three exits, first audio arrived **0.64 / 0.62 / 0.55 seconds** after the
simulated local tool reply; generated farewell PCM lasted **0.68 / 0.83 / 0.87
seconds**. These are network/PCM measurements, not physical playback timings.
The Live harness keeps receiving to score the response; the four-second phone
shutdown deadline is tested separately on the JVM.

[Full Live results and timing/usage](voice-stop-2026-10-09-live.json),
[generated Live score report](voice-stop-2026-10-09-live.md).
Five WAV fixtures and generation/hash metadata are under `scripts/voice_eval/audio/`.

## App regression checks

`VoiceStopTest` covers normalized exact phrases, partial/reported/negated speech,
final-turn fallback, all-mode armed state, local routing without backend calls,
no approval, delayed generation, queued/device playback, missing/stuck playback,
duplicate calls without extending the deadline, meeting/pause guards, cancellation,
and session reset. `GeminiSessionTest` additionally exercises real socket-message
parsing, local tool response, audio enqueue/drain and socket close in all modes.
Existing service teardown still owns transcript-memory closure and audio release.
Backend tests verify locked declaration/client ownership in Auto/PTT/Over,
backend execution rejection, and unchanged translation/resumption contracts.

Final command tails (JDK 17, SDK 36; writable temporary Android/Gradle/uv caches):

```text
cd android && ./gradlew assembleDebug testDebugUnitTest
BUILD SUCCESSFUL in 11s
45 actionable tasks: 2 executed, 43 up-to-date

cd backend && uv run pytest
625 passed, 3 warnings in 9.45s
```

The first Android attempt could not create a debug keystore in the sandbox's
read-only home; pointing `ANDROID_USER_HOME` at the existing temporary Android
cache resolved it. No signing/configuration source changes were needed.

## Reproduction and remaining checks

```sh
cd backend
USAGE_EVAL_PERCENT=100 uv run python ../scripts/voice_eval/voice_stop.py \
  --repeats 1 --output /tmp/voice-stop-new-text.json
PYTHONPATH=../scripts USAGE_EVAL_PERCENT=100 uv run python -m voice_eval \
  --label voice-stop-new --cases ../scripts/voice_eval/voice-stop-cases.json \
  --case voice-stop-goodbye --case voice-stop-listening --case voice-stop-over \
  --case voice-stop-negation --case voice-stop-reported --timeout 30 \
  --output /tmp/voice-stop-new-live.json
```

Do not rerun these against the exhausted five-session budget for this task.
Follow the [hardware checklist](../hardware-test-plan.md): confirm audible goodbye,
SCO/microphone release, summary upload and capture HOLD recovery with controls
still armed. PTT requires an open and normally finished user turn; muted/closed
microphones and offline recognition are outside this feature. The local fallback
uses complete Gemini turns, so it is independent of tool selection, not of Gemini
transcription delivery. During meetings the phone guard remains essential.
