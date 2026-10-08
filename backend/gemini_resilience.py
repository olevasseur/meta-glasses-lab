"""Shared Gemini failure classification, bounded text retries and Live model ordering."""
import os
import random
import time

import httpx
from google.genai import types
from usage import meter

LIVE_DEFAULTS = "gemini-3.8-live,gemini-3.1-flash-live-preview"
TEXT_DEFAULTS = "gemini-3.5-flash-lite,gemini-2.5-flash"


def models(kind, preferred=None):
    configured = os.getenv(f"GEMINI_{kind}_MODELS")
    defaults = LIVE_DEFAULTS if kind == "LIVE" else TEXT_DEFAULTS
    names = (configured or defaults).split(",")
    if not configured and preferred:
        names.insert(0, preferred)
    ordered = list(dict.fromkeys(name.strip().removeprefix("models/") for name in names if name.strip()))
    return ordered or defaults.split(",")


def live_model(avoid=()):
    ordered = models("LIVE", os.getenv("GEMINI_LIVE_MODEL"))
    return next((name for name in ordered if name not in avoid), ordered[0])


def transient(exc):
    code = getattr(exc, "code", None) or getattr(getattr(exc, "response", None), "status_code", None)
    return code in (408, 429, 500, 502, 503, 504) or isinstance(exc, (httpx.TransportError, ConnectionError, TimeoutError))


class GeminiUnavailable(RuntimeError):
    """All configured text models are temporarily unavailable; retain the source work."""


def generate_content(client, *, model, sleep=time.sleep, jitter=random.random, clock=time.monotonic, **kwargs):
    ordered = models("TEXT", model)
    last = None
    saw_transient = False
    deadline = clock() + 25
    config = types.GenerateContentConfig.model_validate(kwargs.pop("config", None) or {})
    # Two rounds, bounded here; durable feature queues own longer outage retries.
    for attempt in range(2):
        for candidate in ordered:
            remaining = min(20, deadline - clock())
            # Gemini rejects explicit deadlines below ten seconds as INVALID_ARGUMENT.
            if remaining < 10:
                raise GeminiUnavailable("Gemini text deadline exceeded; retry queued work") from last
            request_config = config.model_copy(update={"http_options": types.HttpOptions(
                timeout=max(1, int(remaining * 1000)), retry_options=types.HttpRetryOptions(attempts=1))})
            try:
                response = client.models.generate_content(model=candidate, config=request_config, **kwargs)
            except Exception as exc:
                meter.record(candidate, failed=True)
                if not transient(exc) and getattr(exc, "code", None) != 404:
                    raise
                last = exc
                saw_transient |= transient(exc)
            else:
                meter.record(candidate, response=response)
                return response
        if not saw_transient:
            raise last  # All models missing is configuration, not an outage.
        if attempt == 0:
            sleep(min(1 + jitter(), max(0, deadline - clock())))
    raise GeminiUnavailable("Gemini text models unavailable; work retained for retry") from last
