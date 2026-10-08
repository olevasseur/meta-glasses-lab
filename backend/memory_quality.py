"""Memory metadata migration and conservative lexical retrieval, without embeddings."""
import re

from rapidfuzz import fuzz

PROFILE_BYTES = 8000  # Approximate 2k English tokens for memory, excluding app instructions.
STOP_WORDS = set("a an and are as at be been did do does for from has have how i in is it me my of on or our s t that the their this to user users was we what when where which who with you your current now please tell know about".split())
ALIASES = {"named": "name", "names": "name", "lives": "live", "living": "live",
           "prefers": "prefer", "preferred": "prefer", "preference": "prefer",
           "drinks": "drink", "beverage": "drink", "vacation": "holiday"}


def migrate(db):
    columns = {row[1] for row in db.execute("PRAGMA table_info(facts)")}
    for name, declaration in {
        "observed_at": "TEXT NOT NULL DEFAULT ''",
        "confidence": "REAL NOT NULL DEFAULT 1.0",
        "priority": "INTEGER NOT NULL DEFAULT 2",
        "supersedes": "TEXT NOT NULL DEFAULT '[]'",
    }.items():
        if name not in columns:
            db.execute(f"ALTER TABLE facts ADD COLUMN {name} {declaration}")
    db.execute("UPDATE facts SET observed_at=updated_at WHERE observed_at=''")


def terms(text):
    return {ALIASES.get(word, word) for word in re.findall(r"[^\W_]+", text.casefold()) if word not in STOP_WORDS}


def search_score(query, text):
    wanted, available = terms(query), terms(text)
    if not wanted or not available:
        return 0.0
    # Word-level typos are useful; whole-string partial matching rewarded shared boilerplate.
    matches = sum(any(word == other or (min(len(word), len(other)) >= 4 and
                  fuzz.ratio(word, other) >= 80) for other in available) for word in wanted)
    return 100 * matches / len(wanted)
