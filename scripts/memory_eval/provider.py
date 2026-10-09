"""Count every text attempt, pace serial calls and stop safely on provider failures."""
import json
import os
import time
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import gemini_resilience
from google import genai
from google.genai import models, types
from usage import meter


class DailyQuotaExceeded(RuntimeError):
    """A known daily quota cannot be fixed by retrying this evaluation."""


def quota_period(exc):
    body = getattr(exc, "details", {})
    if not isinstance(body, dict):
        return "unknown"
    error = body.get("error", body)
    if not isinstance(error, dict):
        return "unknown"
    details = error.get("details")
    if not isinstance(details, list):
        return "unknown"
    ids = []
    for detail in details:
        violations = detail.get("violations") if isinstance(detail, dict) else None
        if isinstance(violations, list):
            ids.extend(v.get("quotaId", "") for v in violations if isinstance(v, dict))
    if any("PerDay" in value for value in ids if isinstance(value, str)):
        return "daily"
    if any("PerMinute" in value for value in ids if isinstance(value, str)):
        return "minute"
    return "unknown"


@contextmanager
def budget(path: Path, limit=1500, interval=1.0):
    counts = json.loads(path.read_text()) if path.exists() else {"text_attempts": 0, "failures": 0, "models": {}}
    original = models.Models.generate_content
    last = 0.0

    def counted(self, *args, **kwargs):
        nonlocal last
        if counts["text_attempts"] >= limit:
            raise RuntimeError("Evaluation text budget exhausted")
        time.sleep(max(0, last + interval - time.monotonic()))
        model = kwargs.get("model", "unknown").removeprefix("models/")
        config = types.GenerateContentConfig.model_validate(kwargs.get("config") or {})
        options = (config.http_options or types.HttpOptions()).model_copy(update={
            "retry_options": types.HttpRetryOptions(attempts=1)})
        timeout = os.getenv("MEMORY_EVAL_TIMEOUT_MS")
        if timeout:
            options = options.model_copy(update={"timeout": int(timeout)})
        kwargs["config"] = config.model_copy(update={"http_options": options})
        # Conservative input-byte estimate plus output cap; reserve every fallback/retry too.
        estimated_tokens = len(json.dumps(kwargs, default=str, ensure_ascii=False).encode()) + (config.max_output_tokens or 10000)
        meter.reserve_eval({"text_calls": 1, "total_tokens": max(2000, estimated_tokens)}, model=model)
        counts["text_attempts"] += 1
        counts["models"][model] = counts["models"].get(model, 0) + 1
        path.write_text(json.dumps(counts, indent=2) + "\n")
        response = None
        try:
            response = original(self, *args, **kwargs)
            counts["last_response"] = {"model": model, "text_length": len(response.text or ""),
                "finish_reasons": [str(c.finish_reason) for c in response.candidates or []]}
            path.write_text(json.dumps(counts, indent=2) + "\n")
            return response
        except Exception as exc:
            counts["failures"] += 1
            counts["last_error"] = {"type": type(exc).__name__, "code": getattr(exc, "code", None)}
            if getattr(exc, "code", None) == 429:
                counts["last_error"]["quota_period"] = quota_period(exc)
            path.write_text(json.dumps(counts, indent=2) + "\n")
            if counts["last_error"].get("quota_period") == "daily":
                raise DailyQuotaExceeded("Gemini daily text quota exhausted") from None
            if getattr(exc, "code", None) in (429, 503):
                time.sleep(30)
            raise
        finally:
            meter.record(model, response=response, failed=response is None)
            last = time.monotonic()

    # This serial CLI meters at the SDK boundary, including direct probes and baseline summaries.
    # Suppress the shared helper's duplicate record only while this boundary owns accounting.
    with patch.object(models.Models, "generate_content", counted), patch.object(
        gemini_resilience, "meter", SimpleNamespace(record=lambda *args, **kwargs: None)
    ):
        yield counts


def generate(contents, schema, instruction=""):
    with genai.Client(api_key=os.environ["GEMINI_API_KEY"], vertexai=False,
                      http_options=types.HttpOptions(api_version="v1beta", timeout=60000,
                                                    retry_options=types.HttpRetryOptions(attempts=1))) as client:
        response = client.models.generate_content(
            model=os.getenv("MEMORY_EVAL_MODEL", "gemini-3.5-flash-lite"), contents=contents,
            config=types.GenerateContentConfig(system_instruction=instruction or None,
                response_mime_type="application/json", response_json_schema=schema,
                temperature=0, max_output_tokens=10000,
                automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True)))
        return json.loads(response.text or "")
