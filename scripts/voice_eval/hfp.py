"""Seeded narrowband stress channel, NOT a bit-exact Bluetooth codec or beamformer.

Eval-only dependencies: numpy/scipy. Procedural noise is dedicated to CC0; see
hfp-noise-LICENSE.txt. TTS bystanders retain their existing fixture provenance.
"""
import audioop
from dataclasses import asdict, dataclass

import numpy as np
from scipy.signal import butter, resample_poly, sosfilt


@dataclass(frozen=True)
class Condition:
    name: str
    noise: str = 'quiet'
    snr_db: float = 20
    loss: float = 0
    codec: str = 'cvsd-like'
    bystander_db: float = -12


CONDITIONS = [
    Condition('clean', codec='none'),
    Condition('hfp-quiet'),
    Condition('street-20dB-1pct', 'street', 20, .01),
    Condition('cafe-10dB-3pct', 'cafe', 10, .03, 'msbc-like'),
    Condition('wind-0dB-5pct', 'wind', 0, .05),
    Condition('car-10dB-3pct', 'car', 10, .03),
    Condition('kitchen-5dB-1pct', 'kitchen', 5, .01, 'msbc-like'),
]


def decode(pcm):
    if len(pcm) % 2:
        raise ValueError('PCM16 requires whole samples')
    return np.frombuffer(pcm, dtype='<i2').astype(float) / 32768


def encode(samples):
    return np.rint(np.clip(samples, -1, 32767 / 32768) * 32768).astype('<i2').tobytes()


def rms(x):
    return float(np.sqrt(np.mean(x * x))) if len(x) else 0.


def active_rms(x):
    """Reference speech energy excludes quiet 20 ms frames, so pauses don't lower SNR."""
    frames = [x[i:i + 320] for i in range(0, len(x), 320)]
    active = [f for f in frames if rms(f) > max(.003, rms(x) * .2)]
    return rms(np.concatenate(active)) if active else 0.


def band(x, low, high, rate=16000):
    return sosfilt(butter(4, [low, high], fs=rate, btype='bandpass', output='sos'), x)


def noise_bed(kind, count, rng):
    """Acoustic caricatures: spectral beds, slow motion, tonal motors and clatter."""
    if kind not in ('street', 'cafe', 'wind', 'car', 'kitchen'):
        raise ValueError('Unknown noise bed')
    t = np.arange(count) / 16000
    white = rng.normal(size=count)
    rumble = sosfilt(butter(2, 240, fs=16000, output='sos'), white)
    hiss = band(white, 400, 6000)
    if kind == 'wind':
        bed = 5 * rumble * (1 + np.sin(2 * np.pi * .7 * t)) ** 2 + .07 * hiss
    elif kind == 'car':
        bed = 3 * rumble + .15 * hiss + .05 * np.sin(2 * np.pi * 110 * t)
    elif kind == 'street':
        bed = (3 * rumble + .4 * hiss) * (1 + .6 * np.sin(2 * np.pi * .23 * t))
        bed += .07 * np.sin(2 * np.pi * 440 * t) * np.maximum(0, np.sin(2 * np.pi * .31 * t)) ** 12
    elif kind == 'cafe':
        bed = band(white, 180, 3500) * (.5 + .5 * np.sin(2 * np.pi * 3.1 * t) ** 2)
    else:
        bed = .5 * rumble + .15 * hiss + .03 * np.sin(2 * np.pi * 240 * t)
        for start in rng.integers(0, max(1, count), max(1, count // 8000)):
            n = min(1600, count - start)
            bed[start:start+n] += white[start:start+n] * np.exp(-np.arange(n) / 180)
    return bed / max(rms(bed), 1e-12)


def app_upsample(pcm, previous=None):
    """Frozen pre-FIR PcmResampler.kt baseline; Kotlin division truncates to zero."""
    x = np.frombuffer(pcm, dtype='<i2').astype(np.int32)
    if not len(x):
        return b'', previous
    prior = np.r_[x[0] if previous is None else previous, x[:-1]]
    out = np.empty(len(x) * 2, dtype='<i2')
    out[::2] = np.trunc((prior + x) / 2).astype(np.int16)
    out[1::2] = x
    return out.tobytes(), int(x[-1])


def agc(x, rate=8000):
    gain = 1.
    out = x.copy()
    for start in range(0, len(x), rate // 50):
        frame = x[start:start + rate // 50]
        target = np.clip(.12 / max(rms(frame), .01), .25, 4.)
        gain += (.5 if target < gain else .03) * (target - gain)
        out[start:start+len(frame)] = np.tanh(frame * gain)
    return out


def packet_loss(x, fraction, rng, rate=8000):
    """20 ms packets, 2–5 packet bursts, exact rounded loss budget; fading repeat PLC."""
    if not 0 <= fraction <= .05:
        raise ValueError('Loss must be between 0 and 5%')
    size = rate // 50
    count = (len(x) + size - 1) // size
    target = round(count * fraction)
    lost = set()
    while len(lost) < target:
        start = int(rng.integers(count))
        for index in range(start, min(count, start + int(rng.integers(2, 6)))):
            lost.add(index)
            if len(lost) == target:
                break
    out = x.copy()
    previous = np.zeros(size)
    decay = 1.
    for index in range(count):
        start = index * size
        n = len(out[start:start+size])
        if index in lost:
            decay *= .65
            out[start:start+n] = previous[:n] * decay
        else:
            previous = np.pad(x[start:start+n], (0, size-n))
            decay = 1.
    return out, sorted(lost), count


def simulate(pcm, condition, seed=42, bystander=b''):
    c = condition
    if c.codec not in ('none', 'cvsd-like', 'msbc-like') or not np.isfinite(c.snr_db) or c.bystander_db > 0:
        raise ValueError('Invalid channel configuration')
    x = decode(pcm)
    if not len(x):
        raise ValueError('Empty audio')
    if c.name == 'clean':
        narrow = encode(resample_poly(band(x, 300, 3400), 1, 2))
        return pcm, narrow, {'condition': asdict(c), 'seed': seed, 'loss_fraction': 0}
    rng = np.random.default_rng(seed)
    ref = active_rms(x)
    mixed = x.copy()
    if c.noise != 'quiet':
        bed = noise_bed(c.noise, len(x), rng)
        mixed += bed * ref / (10 ** (c.snr_db / 20))
        if bystander:
            voice = np.resize(decode(bystander), len(x))
            voice = band(voice, 300, 3000)
            mixed += voice * ref * 10 ** (c.bystander_db / 20) / max(active_rms(voice), 1e-12)
    # Anti-alias BEFORE decimation. The app only upsamples: it has no 16→8 converter.
    narrow = agc(resample_poly(band(mixed, 300, 3400), 1, 2))
    if c.codec == 'cvsd-like':
        # shortcut: IMA ADPCM approximates adaptive-delta quantization, not CVSD bits.
        codec_input = np.pad(narrow, (0, len(narrow) % 2))
        packed, _ = audioop.lin2adpcm(encode(codec_input), 2, None)
        narrow = decode(audioop.adpcm2lin(packed, 2, None)[0])[:len(narrow)]
    elif c.codec == 'msbc-like':
        # shortcut: block-adaptive 6-bit quantization approximates coding noise only.
        for start in range(0, len(narrow), 120):
            frame = narrow[start:start+120]
            scale = max(float(np.max(np.abs(frame))), .001)
            narrow[start:start+120] = np.rint(frame / scale * 31) / 31 * scale
    narrow, lost, count = packet_loss(narrow, c.loss, rng)
    pcm8 = encode(narrow)
    pcm16, _ = app_upsample(pcm8)
    return pcm16, pcm8, {'condition': asdict(c), 'seed': seed, 'lost_packets': lost,
                        'packets': count, 'loss_fraction': len(lost) / count,
                        'speech_active_rms': ref, 'output_rms': rms(narrow)}
