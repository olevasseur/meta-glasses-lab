"""Exercise browser login + the real Gemini token button, without exposing secrets."""

import os
import re
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
import app  # Loads .env without printing it.
import dashboard
import memory_api
from fastapi.testclient import TestClient
from memory import Memory


def csrf(response):
    return re.search(r'name="csrf" value="([^"]+)"', response.text)[1]


def main():
    if not os.getenv("GEMINI_API_KEY") or not os.getenv("APP_SHARED_SECRET"):
        print("SKIPPED: GEMINI_API_KEY or APP_SHARED_SECRET is absent.")
        return
    original_memory, original_settings = memory_api.memory, dashboard.SETTINGS_PATH
    try:
        with TemporaryDirectory(prefix="dashboard-smoke-") as directory:
            memory_api.memory = Memory(Path(directory) / "memory.db")
            dashboard.SETTINGS_PATH = Path(directory) / "settings.json"
            with TestClient(app.app, follow_redirects=False) as client:
                page = client.get("/dashboard/login")
                response = client.post("/dashboard/login", data={
                    "csrf": csrf(page), "secret": os.environ["APP_SHARED_SECRET"]})
                if response.status_code != 303:
                    raise SystemExit("FAIL: dashboard login")
                page = client.get("/dashboard/status")
                response = client.post("/dashboard/status/token", data={"csrf": csrf(page)})
                if response.status_code != 200 or "Gemini token created successfully" not in response.text:
                    raise SystemExit("FAIL: real dashboard token test; provider details withheld")
                if any(value in response.text for value in (
                    os.environ["GEMINI_API_KEY"], os.environ["APP_SHARED_SECRET"], "auth_tokens/")):
                    raise SystemExit("FAIL: credential exposure detected; values withheld")
                print("PASS: real dashboard login and CSRF-protected Gemini token test; "
                      "no credentials/token in HTML; disposable memory DB; no Docs writes.")
    finally:
        memory_api.memory, dashboard.SETTINGS_PATH = original_memory, original_settings


if __name__ == "__main__":
    main()
