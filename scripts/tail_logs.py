#!/usr/bin/env python3
"""Run: cd backend && uv run python ../scripts/tail_logs.py [--feature audio]."""
import argparse
import json
import os
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]


def request(path, url="http://127.0.0.1:8787"):
    parts = urlsplit(url)
    if parts.username or parts.password or parts.query or parts.fragment or parts.path not in ("", "/"):
        raise ValueError("Use a backend origin without credentials, query, or path")
    if parts.scheme != "https" and not (parts.scheme == "http" and parts.hostname in {"localhost", "127.0.0.1", "::1"}):
        raise ValueError("Use HTTPS or local loopback HTTP")
    secret = os.getenv("APP_SHARED_SECRET", "")
    if not secret:
        raise ValueError("Set APP_SHARED_SECRET in repo-root .env")
    # Reject redirects so a remote redirect cannot forward the shared secret.
    from urllib.request import HTTPRedirectHandler, build_opener

    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    req = Request(url.rstrip("/") + path, headers={"X-App-Secret": secret})
    with build_opener(NoRedirect).open(req, timeout=5) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--feature", default="")
    parser.add_argument("--level", choices=["DEBUG", "INFO", "WARN", "ERROR"], default="")
    parser.add_argument("--url", default="http://127.0.0.1:8787")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--health", action="store_true")
    args = parser.parse_args()
    load_dotenv(ROOT / ".env")
    if args.health:
        try:
            return 0 if request("/diagnostics/health", args.url).get("status") == "ok" else 1
        except (OSError, ValueError):
            return 1
    cursor = 0
    try:
        while True:
            try:
                result = request("/logs?" + urlencode({"after": cursor, "feature": args.feature, "level": args.level}), args.url)
                if result.get("gap"):
                    print("[logs] Older events expired from the live window; inspect rotated files.", file=sys.stderr)
                for row in result["entries"]:
                    print(f'{row["timestamp"]} {row["level"]:5} {row["source"]}/{row["feature"]}: {row["event"]}', flush=True)
                cursor = result["cursor"]
            except HTTPError as exc:
                print(f"[logs] HTTP {exc.code}; check backend/shared secret.", file=sys.stderr)
                if args.once or exc.code in (401, 403):
                    return 1
            except (URLError, TimeoutError, OSError, ValueError, KeyError, TypeError):
                print("[logs] Backend unavailable or invalid response; retrying.", file=sys.stderr)
                if args.once:
                    return 1
            if args.once:
                return 0
            time.sleep(2)
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
