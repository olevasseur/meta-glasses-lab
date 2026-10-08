"""Real Gemini generation from fake day data; no Google Docs writes or real memory.

From backend/: uv run python ../scripts/smoke_journal.py [--live-setup]
"""

import asyncio
import json
import os
import sys
from datetime import datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
from dotenv import load_dotenv
import gemini
from journal import Journal
from journal_data import record_event
from memory import Memory
from notes import Notes
from tools import REGISTRY


async def live_setup(memory):
    from websockets.asyncio.client import connect

    token = gemini.create_token(os.environ["GEMINI_API_KEY"], os.getenv("GEMINI_LIVE_MODEL") or gemini.DEFAULT_MODEL,
                                "auto", memory.context(gemini.PROMPT_PATH.read_text()))
    endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
    async with connect(endpoint, additional_headers={"Authorization": f"Token {token['token']}"}, open_timeout=20) as ws:
        await ws.send(json.dumps({"setup": {"model": f"models/{token['model']}", "sessionResumption": {}}}))
        response = json.loads(await asyncio.wait_for(ws.recv(), timeout=20))
        if "setupComplete" not in response:
            raise RuntimeError("Live setup rejected")
    print(f"PASS: constrained Live setupComplete with {len(REGISTRY)} tools; token redacted.")


def main():
    load_dotenv(Path(__file__).resolve().parents[1] / ".env")
    if not os.getenv("GEMINI_API_KEY"):
        raise SystemExit("SKIPPED: GEMINI_API_KEY is not configured; no value printed.")
    try:
        with TemporaryDirectory(prefix="glasses-journal-smoke-") as directory:
            service = Journal(Memory(Path(directory) / "memory.db"), Notes())
            today = service.now().date()
            day = today - timedelta(days=1)
            stamp = datetime.combine(day, datetime.min.time().replace(hour=12), service.timezone).astimezone().isoformat()
            service.memory.ingest("fake-journal-smoke", [
                {"seq": 0, "speaker": "user", "timestamp": stamp,
                 "text": "I walked by the harbour with Alex today and enjoyed the sunshine. I need to email Alex tomorrow about lunch. I already bought the groceries."},
                {"seq": 1, "speaker": "model", "timestamp": stamp, "text": "Sounds like a lovely walk."},
            ])
            with service.memory.db() as db:
                db.execute("INSERT INTO summaries VALUES (?,?,?)",
                           ("fake-journal-smoke", "Harbour walk with Alex; email tomorrow; groceries done.", stamp))
                record_event(db, "note_appended", {"text": "Lunch idea: soup with Alex", "doc_title": "Inbox"}, stamp)
                record_event(db, "fact_learned", {"key": "user.hobby", "text": "I enjoy harbour walks."}, stamp)
            # Morning brief uses the actual production collect/generate/store path, locally only.
            brief = service.morning_brief()
            result = service.read_journal(day.isoformat())
            if result["status"] != "ok" or "Alex" not in result["text"] or not brief["due"]:
                raise RuntimeError("Generated journal missing fake activity or due task")
            if not any(m["kind"] == "journal" for m in service.memory.recall("harbour")["matches"]):
                raise RuntimeError("Journal recall failed")
            print("PASS: real Gemini journal, stored/read in disposable memory, recall and morning due task.")
            print(json.dumps({"fake_day": day.isoformat(), "entry": result["entry"], "due": brief["due"]}, indent=2))
            if "--live-setup" in sys.argv:
                asyncio.run(live_setup(service.memory))
            print("No real Google Docs or user memory modified.")
    except Exception as exc:
        raise SystemExit(f"FAILED: journal smoke ({type(exc).__name__}); upstream details redacted.") from None


if __name__ == "__main__":
    main()
