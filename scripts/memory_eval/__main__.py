"""Run from backend: uv run python ../scripts/memory_eval generate|run ..."""
import argparse
import hashlib
import json
import os
import sys
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "backend"))
if os.getenv("MEMORY_EVAL_BASELINE"):
    sys.path.insert(0, os.environ["MEMORY_EVAL_BASELINE"])
from dotenv import load_dotenv
load_dotenv(ROOT / ".env")

import gemini
import memory
import memory_summary
from memory import Memory
from memory_summary import Fact, Summary
from unittest.mock import patch
from memory_eval.corpus import History, specifications
from memory_eval.metrics import aggregate, contains, grade
from memory_eval.provider import budget, generate
from gemini_resilience import GeminiUnavailable, transient
from pydantic import ValidationError


def retry(call):
    attempts = 1 + int(os.getenv("MEMORY_EVAL_RETRIES", "2"))
    for attempt in range(attempts):
        try:
            return call()
        except Exception as exc:
            if attempt == attempts - 1 or not (isinstance(exc, GeminiUnavailable) or transient(exc)):
                raise
            print(f"Provider unavailable; retry {attempt + 1}/{attempts - 1} after backoff", flush=True)
            time.sleep(30 * (attempt + 1))


def save(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def generate_corpus(path):
    corpus = json.loads(path.read_text()) if path.exists() else []
    for spec in list(specifications())[len(corpus):]:
        response = generate(json.dumps(spec["seeds"]), History.model_json_schema(),
            "Write twelve short realistic multi-session user/assistant transcripts, one per supplied seed, "
            "in exactly that order. Preserve every factual detail, exact proper noun, correction, negation, "
            "and forget request verbatim within natural dialogue. Do not introduce other personal facts. "
            "The assistant acknowledges without inventing, repeating outdated values, or claiming tools ran. "
            "Each item has user and model utterances. These are synthetic fictional people.")
        history = History.model_validate(response)
        # Retain the seed verbatim as user evidence; the model contributes natural dialogue.
        sessions = [{"user": seed + " " + item.user, "model": item.model}
                    for seed, item in zip(spec.pop("seeds"), history.sessions)]
        corpus.append({**spec, "sessions": sessions})
        save(path, corpus)
        print(f"Generated {len(corpus)}/10 personas", flush=True)


def close_session(store, identifier, session, index):
    stamp = datetime(2026, 1, 1, tzinfo=UTC) + timedelta(days=index)
    store.ingest(identifier, [{"seq": seq, "speaker": speaker, "text": session[speaker],
                              "timestamp": (stamp + timedelta(seconds=seq)).isoformat()}
                             for seq, speaker in enumerate(("user", "model"))])
    return retry(lambda: store.end(identifier))


def instruction(store):
    # The exact production /token context builder and constraint serializer.
    config = gemini.token_config(gemini.DEFAULT_MODEL, "auto",
                                store.context(gemini.assistant_instruction("auto")))
    assert config.lock_additional_fields == []
    assert config.live_connect_constraints.config.session_resumption is None
    return config.live_connect_constraints.config.system_instruction


def probe_answers(system, probes, retrieval=None):
    schema = {"type": "object", "properties": {p["id"]: {"type": "string"} for p in probes},
              "required": [p["id"] for p in probes], "additionalProperties": False}
    payload = {"questions": {p["id"]: p["query"] for p in probes},
               "format": "Return only the minimal current factual answer for each question, no historical alternatives. "
                         "Use exactly UNKNOWN if unknown, hypothetical, or forgotten. Do not guess."}
    if retrieval is not None:
        payload["recall_tool_results"] = retrieval
    return retry(lambda: generate(json.dumps(payload), schema, system))


def measured_answers(system, probes, retrieval=None, cached=None):
    if cached is not None:
        return cached, None
    try:
        return probe_answers(system, probes, retrieval), None
    except Exception as exc:
        if not (transient(exc) or isinstance(exc, (GeminiUnavailable, json.JSONDecodeError))):
            raise
        return {}, type(exc).__name__


def incomplete_evaluation(person):
    return bool(person.get("summary_failures") or any(person.get("probe_errors", {}).values()))


def reusable_answers(previous, mode, changed):
    if previous and not changed and not previous["probe_errors"].get(mode):
        field = "answer" if mode == "profile" else "retrieval_answer"
        return {row["id"]: row[field] for row in previous["probes"]}
    return None


def run(corpus_path, output, state, retry_incomplete=False):
    corpus = json.loads(corpus_path.read_text())
    result = json.loads(output.read_text()) if output.exists() else {
        "corpus_sha256": hashlib.sha256(corpus_path.read_bytes()).hexdigest(),
        "model": os.getenv("MEMORY_EVAL_MODEL", "gemini-3.5-flash-lite"),
        "summary_models": os.getenv("GEMINI_TEXT_MODELS", "production defaults"),
        "evaluation_timeout_ms": os.getenv("MEMORY_EVAL_TIMEOUT_MS", "production defaults"),
        "source_sha256": {module.__name__: hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest()
                          for module in (memory, memory_summary)},
        "probe_path": "production context + token_config system_instruction; stateless text model",
        "support_sha256": {name: hashlib.sha256(Path(sys.modules[name].__file__).read_bytes()).hexdigest()
                           for name in ("memory_quality", "memory_assertions") if name in sys.modules},
        "live_sessions": 0, "personas": []}
    assert result["corpus_sha256"] == hashlib.sha256(corpus_path.read_bytes()).hexdigest()
    assert result["model"] == os.getenv("MEMORY_EVAL_MODEL", "gemini-3.5-flash-lite")
    for module in (memory, memory_summary):
        assert result.get("source_sha256", {}).get(module.__name__, hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest()) == hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest()
    for persona in corpus:
        previous = next((p for p in result["personas"] if p["id"] == persona["id"]), None)
        if previous and not (retry_incomplete and incomplete_evaluation(previous)):
            continue
        store = Memory(state / (persona["id"] + ".db"))
        sizes, failures = [], []
        changed = False
        for i, session in enumerate(persona["sessions"]):
            try:
                closed = close_session(store, f"s{i}", session, i)
            except (GeminiUnavailable, ValidationError) as exc:
                closed = {}
                failures.append({"session": i + 1, "error_type": type(exc).__name__})
                print(f"{persona['id']}: summary {i + 1} deferred ({type(exc).__name__})", flush=True)
            changed |= closed.get("status") == "ok" and not closed.get("already_ended", False)
            if i == persona["forget_after"]:
                preview = store.forget(persona["forget_query"])
                if preview["status"] == "needs_confirmation":
                    # Simulate approved service call in this isolated synthetic DB only.
                    store.forget(persona["forget_query"], preview["confirmation_token"])
            # A resumed DB already contains later sessions; do not invent historical sizes.
            sizes.append(None if closed.get("already_ended") else len(instruction(store).encode()))
            print(f"{persona['id']}: session {i + 1}/12", flush=True)
        system = instruction(store)
        probes = persona["probes"]
        retrieval = {p["id"]: store.recall(p["query"]) for p in probes}
        answers, profile_error = measured_answers(system, probes,
            cached=reusable_answers(previous, "profile", changed))
        assisted, retrieval_error = measured_answers(system, probes, retrieval,
            cached=reusable_answers(previous, "retrieval", changed))
        rows = [{"id": p["id"], "kind": p["kind"], "answer": answers.get(p["id"]),
                 **grade(p, answers.get(p["id"])), "retrieval_answer": assisted.get(p["id"]),
                 **{f"retrieval_{key}": value for key, value in grade(p, assisted.get(p["id"])).items()},
                 "hit_at_3": bool(p["expected"]) and all(any(contains(m["text"], value)
                    for m in retrieval[p["id"]]["matches"][:3]) for value in p["expected"])} for p in probes]
        with store.db() as db:
            facts = store._facts(db)
        measured = {"id": persona["id"], "context_bytes": sizes, "facts": facts,
                                   "summary_failures": failures,
                                   "probe_errors": {"profile": profile_error, "retrieval": retrieval_error},
                                   "probes": rows, "recall": retrieval}
        if previous:
            result["personas"][result["personas"].index(previous)] = measured
        else:
            result["personas"].append(measured)
        all_rows = [row for person in result["personas"] for row in person["probes"]]
        result["metrics"] = aggregate(all_rows)
        completed_sessions = sum(len(person["context_bytes"]) - len(person.get("summary_failures", [])) for person in result["personas"])
        result["metrics"]["summary_completion"] = {"count": completed_sessions,
            "total": sum(len(person["context_bytes"]) for person in result["personas"])}
        assisted_rows = [{"kind": row["kind"], **{key: row.get(f"retrieval_{key}", key == "available")
                         for key in ("correct", "resurrected", "hallucinated", "available")}} for row in all_rows]
        result["retrieval_metrics"] = aggregate(assisted_rows)
        known = [r for r in all_rows if r["kind"] in {"recall", "correction"}]
        result["metrics"]["recall_hit_at_3"] = {"count": sum(r["hit_at_3"] for r in known), "total": len(known)}
        result["metrics"]["retrieval_assisted_accuracy"] = {"count": sum(r["retrieval_correct"] for r in all_rows), "total": sum(r.get("retrieval_available", True) for r in all_rows)}
        save(output, result)
        print(json.dumps(result["metrics"]), flush=True)


def growth(output, state):
    with TemporaryDirectory(prefix="growth-", dir=state) as directory:
        _growth(output, Path(directory))


def _growth(output, state):
    # Deterministic budget stress: real ingest/end/storage/context, controlled summary output.
    # This measures retention/cap behavior, not extraction quality; no provider requests.
    store = Memory(state / "growth.db")
    points = []
    for i in range(105):
        session = {"user": ("My sister is Ana. I prefer brief spoken answers. " if i == 0 else "") +
                   f"Remember reading list entry {i}: Book number {i}, by Writer number {i}, recommended by my book club. "
                   "This is a different book from all earlier entries; keep them separately.",
                   "model": "Understood."}
        values = [{"key": f"reading.entry.{i}", "text": f"Reading entry {i}: " + "A book club recommendation about local history and hiking. " * 6,
                   "priority": 1}]
        if i == 0:
            values.append({"key": "user.sister.name", "text": "The user's sister is Ana.", "priority": 3})
        # Baseline schema predates priority. Filter only metadata unsupported by that revision.
        summary = Summary(summary=f"Discussed reading entry {i}.", facts=[
            Fact(**{k: v for k, v in value.items() if k in Fact.model_fields}) for value in values])
        with patch("memory.summarize", return_value=summary):
            close_session(store, f"growth{i}", session, i)
        context = instruction(store)
        with store.db() as db:
            facts = store._facts(db)
        points.append({"sessions": i + 1, "facts": len(facts), "context_bytes": len(context.encode()),
                       "memory_bytes": len(context.encode()) - len(gemini.assistant_instruction("auto").encode()),
                       "sister_retained": contains(context, "Ana")})
        save(output, {"mode": "deterministic summary replay; real storage/context, no provider", "points": points, "maximum_bytes": max(p["memory_bytes"] for p in points),
                      "cap_8000_passed": all(p["memory_bytes"] <= 8000 for p in points)})
        print(f"Growth {i + 1}/105: {len(context.encode())} bytes, {len(facts)} facts", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("generate", "run", "growth", "regressions", "adversarial"))
    parser.add_argument("--corpus", type=Path, default=ROOT / "scripts/memory_eval/corpus.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--state", type=Path, default=Path("/tmp/glasses-memory-eval"))
    parser.add_argument("--ledger", type=Path, default=Path("/tmp/glasses-memory-eval-budget.json"))
    parser.add_argument("--limit", type=int, default=1500)
    parser.add_argument("--interval", type=float, default=10,
                        help="Minimum seconds between completed serial text calls (default: 10)")
    parser.add_argument("--retry-incomplete", action="store_true",
                        help="Retry deferred summaries/missing probe batches, never merely wrong answers")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.state.mkdir(parents=True, exist_ok=True)
    with budget(args.ledger, limit=args.limit, interval=args.interval):
        if args.command == "generate":
            generate_corpus(args.output)
        elif args.command == "run":
            run(args.corpus, args.output, args.state, args.retry_incomplete)
        elif args.command == "adversarial":
            from memory_eval.adversarial import run as adversarial
            adversarial(args.corpus, args.output, args.state, sys.modules[__name__])
        elif args.command == "growth":
            growth(args.output, args.state)
        else:
            from memory_eval.regressions import run as regressions
            with TemporaryDirectory(prefix="regressions-", dir=args.state) as directory:
                save(args.output, regressions(Path(directory)))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        # Provider exceptions can contain credentials. Persist successful progress, print only type.
        from pydantic import ValidationError
        if isinstance(exc, ValidationError):
            print(json.dumps([{"location": e["loc"], "type": e["type"]} for e in exc.errors()]), flush=True)
        raise SystemExit(f"Stopped safely: {type(exc).__name__}; progress retained, no upstream body printed.") from None
