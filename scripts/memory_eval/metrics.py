"""Strict answer spans; no model-based judge or grading against its own answers."""
import re


def contains(text, value):
    return bool(re.search(r"(?<!\w)" + re.escape(value.casefold()) + r"(?!\w)", text.casefold()))


def grade(probe, answer):
    if not isinstance(answer, str):
        return {"correct": False, "resurrected": False, "hallucinated": False, "available": False}
    unknown = answer.strip().casefold() == "unknown"
    forbidden = any(contains(answer, value) for value in probe["forbidden"])
    correct = (unknown if not probe["expected"] else
               all(contains(answer, value) for value in probe["expected"]) and not forbidden)
    return {"available": True, "correct": correct, "resurrected": probe["kind"] == "forget" and forbidden,
            "hallucinated": probe["kind"] == "unknown" and not unknown}


def aggregate(rows):
    def fraction(key, kinds):
        selected = [r for r in rows if r["kind"] in kinds and r.get("available", True)]
        return {"count": sum(bool(r[key]) for r in selected), "total": len(selected)}
    return {"answer_coverage": {"count": sum(r.get("available", True) for r in rows), "total": len(rows)},
            "recall_accuracy": fraction("correct", {"recall"}),
            "latest_wins": fraction("correct", {"correction"}),
            "forget_accuracy": fraction("correct", {"forget"}),
            "resurrection_rate": fraction("resurrected", {"forget"}),
            "hallucination_rate": fraction("hallucinated", {"unknown"})}
