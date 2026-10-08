"""Evaluation budget gate shared with the backend's SQLite meter."""
import sys
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'backend'))
from usage import meter


def reserve_live(cases, timeout):
    if not 0 < timeout <= 600:
        raise ValueError('Evaluation timeout must be 0–600 seconds')
    # Includes setup/settle; reserve even if a worker fails before connecting.
    from gemini_resilience import live_model
    minutes = len(cases) * (timeout + 30) / 60
    meter.reserve_eval({'live_sessions': len(cases), 'live_minutes': minutes, 'total_tokens': int(minutes * 6000)},
                       model=live_model())


def reserve_tts(text, model):
    # Deliberately conservative; clips can be much longer than their input text.
    meter.reserve_eval({'text_calls': 1, 'total_tokens': max(2000, len(text) * 20)}, model=model)


@asynccontextmanager
async def metered_connection(connect, token, result, *args, **kwargs):
    try:
        async with connect(*args, **kwargs) as ws:
            yield ws
    finally:
        started = result.pop('_usage_started', None)
        if started is not None:
            reports = result.get('usage', [])
            def total(key):
                values = [r[key] for r in reports if isinstance(r.get(key), int)]
                return sum(values) if values else None
            meter.live(uuid.uuid4().hex, token['model'], time.monotonic() - started,
                       total('promptTokenCount'), total('responseTokenCount'), total('totalTokenCount'))
