"""Local, content-free Gemini usage ledger. Quota days follow Pacific midnight."""
import json
import os
import sqlite3
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

PACIFIC = ZoneInfo("America/Los_Angeles")
DEFAULTS = {"live_sessions": 20, "live_minutes": 120, "text_calls": 400, "total_tokens": 1_000_000}


def day_at(now):
    return datetime.fromtimestamp(now, PACIFIC).date().isoformat()


def caps():
    # Local budgets, deliberately not presented as Google's project quotas.
    return {key: max(1, int(os.getenv("USAGE_" + key.upper(), value))) for key, value in DEFAULTS.items()}


def known_limits():
    """Optional per-model snapshot copied from AI Studio; null means unknown."""
    path = os.getenv("GEMINI_QUOTA_FILE")
    data = json.loads(Path(path).read_text()) if path else {}
    if not isinstance(data, dict):
        raise ValueError("GEMINI_QUOTA_FILE must contain a model-to-limits object")
    for model, limits in data.items():
        if not isinstance(limits, dict):
            raise ValueError("Each model must contain quota dimensions")
        for key, value in limits.items():
            if key in {"rpm", "tpm", "rpd", "tpd", "concurrent_sessions", "live_minutes_per_day"}:
                if value is not None and (type(value) not in (int, float) or not 0 <= value < float('inf')):
                    raise ValueError("Quota dimensions must be nonnegative numbers or null")
    return data


class UsageMeter:
    def __init__(self, path=None, clock=time.time):
        self.path = path
        self.clock = clock

    @contextmanager
    def database(self):
        path = Path(self.path or os.getenv("USAGE_DB", Path(__file__).parent / "data/usage.sqlite3"))
        path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS usage (
                    id TEXT PRIMARY KEY, day TEXT NOT NULL, model TEXT NOT NULL,
                    kind TEXT NOT NULL, calls INTEGER NOT NULL DEFAULT 0,
                    seconds REAL NOT NULL DEFAULT 0, input_tokens INTEGER,
                    output_tokens INTEGER, total_tokens INTEGER, failed INTEGER NOT NULL DEFAULT 0);
                CREATE INDEX IF NOT EXISTS usage_day ON usage(day);
                CREATE TABLE IF NOT EXISTS live_reports (
                    id TEXT PRIMARY KEY, model TEXT NOT NULL, seconds REAL NOT NULL,
                    input_tokens INTEGER, output_tokens INTEGER, total_tokens INTEGER);
                CREATE TABLE IF NOT EXISTS eval_budget (
                    day TEXT NOT NULL, metric TEXT NOT NULL, amount REAL NOT NULL,
                    PRIMARY KEY(day, metric));
                CREATE TABLE IF NOT EXISTS capture_gate (id INTEGER PRIMARY KEY, next_at REAL NOT NULL);
            ''')
            yield db
            db.commit()
        finally:
            db.close()

    def record(self, model, kind="text", response=None, failed=False):
        metadata = getattr(response, "usage_metadata", None)
        def token(name):
            value = getattr(metadata, name, None)
            return value if isinstance(value, int) and value >= 0 else None
        with self.database() as db:
            db.execute("INSERT INTO usage VALUES (?,?,?,?,?,?,?,?,?,?)", (
                uuid.uuid4().hex, day_at(self.clock()), model, kind, 1, 0,
                token("prompt_token_count"), token("candidates_token_count"), token("total_token_count"), int(failed)))

    def live(self, id, model, seconds, input_tokens=None, output_tokens=None, total_tokens=None):
        """Idempotent cumulative connection reports; split elapsed time at midnight.

        Tokens are assigned to the day received; no invented token estimates.
        Reconnects (including resumption) use new IDs and count as connections.
        """
        now = self.clock()
        values = (seconds, input_tokens, output_tokens, total_tokens)
        with self.database() as db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute("SELECT * FROM live_reports WHERE id=?", (id,)).fetchone()
            if old and old["model"] != model:
                raise ValueError("Connection model cannot change")
            previous = tuple(old[k] for k in ("seconds", "input_tokens", "output_tokens", "total_tokens")) if old else (0, None, None, None)
            merged = tuple(max(a or 0, b or 0) if a is not None or b is not None else None for a, b in zip(values, previous))
            delta = [None if a is None else a - (b or 0) for a, b in zip(merged, previous)]
            db.execute("INSERT OR REPLACE INTO live_reports VALUES (?,?,?,?,?,?)", (id, model, *merged))
            # Report time approximates interval end; heartbeat latency is not provider billing time.
            start = now - delta[0]
            while start < now:
                local = datetime.fromtimestamp(start, PACIFIC)
                midnight = datetime.combine(local.date() + timedelta(days=1), datetime.min.time(), PACIFIC).timestamp()
                end = min(now, midnight)
                self._live_row(db, id, day_at(start), model, end-start)
                start = end
            day = day_at(now)
            self._live_row(db, id, day, model, 0)
            db.execute('''UPDATE usage SET calls=calls+?,
                input_tokens=CASE WHEN ? IS NULL THEN input_tokens ELSE COALESCE(input_tokens,0)+? END,
                output_tokens=CASE WHEN ? IS NULL THEN output_tokens ELSE COALESCE(output_tokens,0)+? END,
                total_tokens=CASE WHEN ? IS NULL THEN total_tokens ELSE COALESCE(total_tokens,0)+? END
                WHERE id=?''', (int(old is None), delta[1], delta[1], delta[2], delta[2], delta[3], delta[3], id+":"+day))

    @staticmethod
    def _live_row(db, id, day, model, seconds):
        db.execute('''INSERT INTO usage(id,day,model,kind,seconds) VALUES (?,?,?,'live',?)
            ON CONFLICT(id) DO UPDATE SET seconds=seconds+excluded.seconds''', (id+":"+day, day, model, seconds))

    @staticmethod
    def _totals(db, day):
        row = db.execute('''SELECT COALESCE(SUM(CASE WHEN kind='live' THEN calls ELSE 0 END),0) live_sessions,
            COALESCE(SUM(seconds)/60,0) live_minutes,
            COALESCE(SUM(CASE WHEN kind IN ('text','tts') THEN calls ELSE 0 END),0) text_calls,
            COALESCE(SUM(total_tokens),0) total_tokens FROM usage WHERE day=?''', (day,)).fetchone()
        return dict(row)

    def snapshot(self):
        today = day_at(self.clock())
        since = (datetime.fromisoformat(today) - timedelta(days=6)).date().isoformat()
        with self.database() as db:
            totals = self._totals(db, today)
            rows = [dict(row) for row in db.execute('''SELECT day,model,kind,SUM(calls) calls,
                ROUND(SUM(seconds)/60,2) minutes, SUM(input_tokens) input_tokens,
                SUM(output_tokens) output_tokens, SUM(total_tokens) total_tokens,
                SUM(failed) failed, SUM(CASE WHEN total_tokens IS NULL THEN calls ELSE 0 END) unreported
                FROM usage WHERE day>=? GROUP BY day,model,kind ORDER BY day DESC,model''', (since,))]
        limits = caps()
        warnings = [f"{key.replace('_', ' ')} at {totals[key] / value:.0%} of local daily soft cap"
                    for key, value in limits.items() if totals[key] >= value * .8]
        known = known_limits()
        comparisons = []
        for model, dimensions in known.items():
            model_rows = [r for r in rows if r["model"] == model and r["day"] == today]
            actual = {"rpd": sum(r["calls"] for r in model_rows),
                      "tpd": sum(r["total_tokens"] or 0 for r in model_rows),
                      "live_minutes_per_day": sum(r["minutes"] for r in model_rows)}
            for metric, value in actual.items():
                limit = dimensions.get(metric)
                if limit is not None:
                    comparisons.append({"model": model, "metric": metric, "used": value, "limit": limit})
                    if value >= limit * .8:
                        warnings.append(f"{model} {metric}: {value:g} / {limit:g} configured project limit")
        week = {"calls": sum(r["calls"] for r in rows), "minutes": sum(r["minutes"] for r in rows),
                "total_tokens": sum(r["total_tokens"] or 0 for r in rows)}
        if not known:
            warnings.append("Project quotas and billing tier unverified; check AI Studio. Local usage excludes other apps/keys.")
        return {"day": today, "timezone": "America/Los_Angeles", "today": totals,
                "soft_caps": limits, "known_limits": known, "comparisons": comparisons,
                "seven_days": week, "warnings": warnings, "rows": rows,
                "tier": os.getenv("GEMINI_BILLING_TIER", "unknown (check AI Studio)")}

    def visual_interval(self):
        report = self.snapshot()
        ratios = [report["today"][k] / report["soft_caps"][k] for k in ("text_calls", "total_tokens")]
        model = (os.getenv("GEMINI_TEXT_MODELS") or os.getenv("GEMINI_VISUAL_MODEL") or "gemini-3.5-flash-lite").split(",")[0].strip().removeprefix("models/")
        ratios += [c["used"] / c["limit"] if c["limit"] else 1
                   for c in report["comparisons"] if c["model"] == model and c["metric"] in {"rpd", "tpd"}]
        return 300 if max(ratios) >= 1 else 60 if max(ratios) >= .8 else 20

    def allow_capture(self):
        interval = self.visual_interval()
        if interval == 300:
            return False, interval
        if interval == 20:
            return True, interval
        now = self.clock()
        with self.database() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT next_at FROM capture_gate WHERE id=1").fetchone()
            if row and row[0] > now:
                return False, max(interval, int(row[0] - now) + 1)
            report = self._totals(db, day_at(now))
            if any(report[k] >= caps()[k] for k in ("text_calls", "total_tokens")):
                return False, interval
            db.execute("INSERT OR REPLACE INTO capture_gate VALUES (1,?)", (now + interval,))
            return True, interval

    def reserve_eval(self, amounts, model=None):
        """Atomic, conservative daily reservation. Failed/cancelled runs keep budget."""
        percent = float(os.getenv("USAGE_EVAL_PERCENT", "10"))
        if not 0 < percent <= 100:
            raise ValueError("USAGE_EVAL_PERCENT must be in (0,100]")
        limits = caps()
        dimensions = known_limits().get(model, {})
        for metric, quota in (("live_sessions", "rpd"), ("live_minutes", "live_minutes_per_day"), ("text_calls", "rpd"), ("total_tokens", "tpd")):
            if dimensions.get(quota) is not None:
                limits[metric] = min(limits[metric], dimensions[quota])
        with self.database() as db:
            db.execute("BEGIN IMMEDIATE")
            day = day_at(self.clock())
            used = self._totals(db, day)
            for metric, amount in amounts.items():
                if metric not in limits or not isinstance(amount, (int, float)) or not 0 <= amount < float('inf'):
                    raise ValueError("Invalid evaluation budget")
                row = db.execute("SELECT amount FROM eval_budget WHERE day=? AND metric=?", (day, metric)).fetchone()
                reserved = row[0] if row else 0
                if reserved + amount > limits[metric] * percent / 100 or used[metric] + reserved + amount > limits[metric]:
                    raise ValueError(f"Evaluation refused: {metric} exceeds {percent:g}% daily eval allowance or remaining local cap")
            for metric, amount in amounts.items():
                db.execute('''INSERT INTO eval_budget VALUES (?,?,?) ON CONFLICT(day,metric)
                    DO UPDATE SET amount=amount+excluded.amount''', (day, metric, amount))


meter = UsageMeter()
