# Speech fixtures

Generated locally on macOS, 2026-10-08, using Samantha at 155 words/minute.
No user recordings or provider audio. PCM16 mono, 16 kHz.

```sh
say -v Samantha -r 155 -o /tmp/question.aiff 'What is two plus two? Answer in one short sentence.'
say -v Samantha -r 155 -o /tmp/look.aiff 'Use your look tool now. What objects and colors am I looking at? Describe the camera image briefly.'
say -v Samantha -r 155 -o /tmp/over.aiff 'Over.'
for name in question look over; do
  ffmpeg -y -i "/tmp/$name.aiff" -ar 16000 -ac 1 -c:a pcm_s16le "$name.wav"
done
```

The test injects known DAT speech text at the end of WAV playback in Over mode;
these files still go to real Gemini through the app's PCM path.
