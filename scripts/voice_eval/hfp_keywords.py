"""Transcript-only port of OverTurnDetector; no Android STT claims."""
import json
import re


def normalize(text):
    return ' '.join(re.findall(r'[^\W_]+', text.lower()))


def ends(text, suffix):
    return text == suffix or text.endswith(' ' + suffix)


class KeywordDetector:
    def __init__(self, aliases=()):
        self.aliases = aliases
        self.tail = self.hypothesis = ''
        self.pending = None
        self.since = 0.
        self.ended = False

    def transcript(self, text, final, now):
        if self.ended:
            return None
        words = normalize(text)
        if not words:
            return None
        signoff = ends((self.tail + ' ' + words).strip(), 'over and out')
        match = 'sign_off' if signoff else 'keyword' if any(ends(words, k) for k in ('over', *self.aliases)) else None
        if final:
            self.tail = ' '.join((self.tail + ' ' + words).split()[-3:])
        if words != self.hypothesis:
            self.since = now
        self.hypothesis, self.pending = words, match
        if final and match and (signoff or not ends(words, 'over')):
            self.ended = True
            return match
        return None

    def tick(self, now):
        if not self.ended and self.pending and now - self.since >= 1.2 - 1e-9:
            self.ended = True
            return self.pending
        return None


def replay(events, end, aliases=()):
    detector = KeywordDetector(aliases)
    for event in events:
        at = event['time_s']
        reason = detector.tick(at)
        if reason:
            return {'reason': reason, 'time_s': detector.since + 1.2}
        reason = detector.transcript(event['text'], event['final'], at)
        if reason:
            return {'reason': reason, 'time_s': at}
    reason = detector.tick(end)
    return {'reason': reason, 'time_s': detector.since + 1.2 if reason else None}


def transcribe(model, pcm8):
    from vosk import KaldiRecognizer
    recognizer = KaldiRecognizer(model, 8000)
    events, final_parts = [], []
    # 20 ms shared PCM cadence, with 1.5 s quiet to let the last word stabilize.
    data = pcm8 + bytes(24000)
    for start in range(0, len(data), 320):
        final = recognizer.AcceptWaveform(data[start:start+320])
        result = json.loads(recognizer.Result() if final else recognizer.PartialResult())
        text = result.get('text' if final else 'partial', '')
        if final:
            final_parts.append(text)
        if text and (not events or text != events[-1]['text'] or final):
            events.append({'time_s': (start + 320) / 16000, 'text': text, 'final': bool(final)})
    text = json.loads(recognizer.FinalResult()).get('text', '')
    if text:
        final_parts.append(text)
        events.append({'time_s': len(data) / 16000, 'text': text, 'final': True})
    end = len(data) / 16000 + 1.2
    return {'text': ' '.join(final_parts).strip(), 'events': events,
            'exact': replay(events, end), 'aliases': replay(events, end, ('ova', 'older'))}
