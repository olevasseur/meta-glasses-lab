"""Call the real /token handler once, without printing credentials or the token."""

import os
import secrets
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
import app  # Loads the repo-root .env, preserving explicit environment values.
from fastapi.testclient import TestClient


def main() -> None:
    if not os.getenv("GEMINI_API_KEY"):
        print("SKIPPED: GEMINI_API_KEY is absent from the environment and repo-root .env.")
        return
    if not os.getenv("APP_SHARED_SECRET"):
        os.environ["APP_SHARED_SECRET"] = secrets.token_urlsafe(32)
        print("Using a temporary app secret for this in-process smoke test.")
    with TestClient(app.app) as client:
        response = client.post("/token", json={}, headers={"X-App-Secret": os.environ["APP_SHARED_SECRET"]})
    if response.status_code != 200:
        raise SystemExit(f"FAILED: POST /token returned HTTP {response.status_code}. No token printed.")
    result = response.json()
    if not result.get("token") or not result.get("model"):
        raise SystemExit("FAILED: /token returned an incomplete response.")
    datetime.fromisoformat(result["expires_at"])
    print(f"PASS: POST /token HTTP 200; model={result['model']}; expires_at={result['expires_at']}; token redacted.")


if __name__ == "__main__":
    main()
