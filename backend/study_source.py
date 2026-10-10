"""Read-only Docs parsing and local heading-weighted BM25. No model calls."""
import hashlib
import json
import math
import re
from collections import Counter

from doc_nicknames import alias_key, find_alias, read_aliases
from notes import INBOX_TITLE

CHUNK_BYTES = 1000
MAX_DOCUMENT_BYTES = 2_000_000
MAX_CHUNKS = 4000


def packed(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def clipped(text, size):
    return text.encode("utf-8")[:size].decode("utf-8", errors="ignore")


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()[:24]


def chunks(text):
    """Lossless, bounded UTF-8 chunks; prefer paragraph, sentence, then word ends."""
    while text:
        piece = clipped(text, CHUNK_BYTES)
        while len(packed(piece).encode()) > CHUNK_BYTES + 2:
            piece = piece[:max(1, len(piece) * 3 // 4)]
        if len(piece) < len(text):
            boundaries = [piece.rfind("\n"), piece.rfind(". ") + 1, piece.rfind(" ")]
            end = next((n + 1 for n in boundaries if n >= len(piece) // 2), len(piece))
            piece = piece[:end]
        yield piece
        text = text[len(piece):]


def paragraphs(content):
    for element in content:
        if "paragraph" in element:
            yield element["paragraph"]
        for row in element.get("table", {}).get("tableRows", []):
            for cell in row.get("tableCells", []):
                yield from paragraphs(cell.get("content", []))
        # Table of contents duplicates headings; omit it, images, headers and footers.


def tab_bodies(document):
    def walk(tabs, parents=()):
        for tab in tabs:
            props = tab.get("tabProperties", {})
            path = (*parents, props.get("title", "Tab"))
            yield props.get("tabId", "/".join(path)), path, tab.get("documentTab", {}).get("body", {})
            yield from walk(tab.get("childTabs", []), path)
    if document.get("tabs"):
        yield from walk(document["tabs"])
    else:
        yield "body", (), document.get("body", {})


def parse_document(document):
    if len(packed(document).encode()) > MAX_DOCUMENT_BYTES:
        raise ValueError("Document too large for study")
    sections = []
    occurrences = Counter()
    for tab_id, tab_path, body in tab_bodies(document):
        heading_path = []
        current = None
        for paragraph in paragraphs(body.get("content", [])):
            text = "".join(e.get("textRun", {}).get("content", "") for e in paragraph.get("elements", []))
            if not text.strip():
                continue
            style = paragraph.get("paragraphStyle", {})
            named = style.get("namedStyleType", "")
            is_heading = named.startswith("HEADING_") or named in {"TITLE", "SUBTITLE"}
            if is_heading:
                level = int(named[-1]) if named.startswith("HEADING_") and named[-1].isdigit() else 1
                while heading_path and heading_path[-1][0] >= level:
                    heading_path.pop()
                heading_path.append((level, text.strip()))
                path = " / ".join((*tab_path, *(h for _, h in heading_path)))
                anchor = tab_id + ":" + (style.get("headingId") or path)
                occurrences[anchor] += 1
                current = {"key": digest(anchor + str(occurrences[anchor])), "heading": path, "text": ""}
                sections.append(current)
            else:
                if current is None:
                    current = {"key": digest(tab_id + ":intro"),
                               "heading": " / ".join((*tab_path, "Introduction")), "text": ""}
                    sections.append(current)
                bullet = paragraph.get("bullet")
                prefix = ("  " * min(bullet.get("nestingLevel", 0), 8) + "- ") if bullet is not None else ""
                current["text"] += prefix + text.rstrip("\n") + "\n"
    result = []
    for section in sections:
        # Empty outline headings remain navigable, but are not quiz concepts.
        texts = list(chunks(section.pop("text"))) or [""]
        section["chunks"] = [{"text": t, "id": digest(section["key"] + t + str(i))} for i, t in enumerate(texts)]
        result.append(section)
    if sum(len(s["chunks"]) for s in result) > MAX_CHUNKS:
        raise ValueError("Too many study passages")
    return result


def resolve(notes, doc_name, selected=None):
    """Caller owns notes.lock. Never call Notes._resolve: Inbox may be created there."""
    aliases = read_aliases(notes)
    alias = find_alias(aliases, doc_name)
    if aliases.get(alias):
        matches = [notes._document(aliases[alias])]
    else:
        title = INBOX_TITLE if alias == "inbox" else doc_name
        matches = [d for d in notes._list_documents(refresh=True) if alias_key(d["title"]) == alias_key(title)]
    if selected:
        matches = [d for d in matches if d["id"] == selected]
    if not matches:
        return {"status": "not_found", "nicknames": sorted(aliases)[:8]}
    if len(matches) != 1:
        return {"status": "choose_document", "candidates": matches[:6], "candidate_count": len(matches)}
    return {"status": "ok", "document": matches[0]}


STOP = set("a an and are as at author about be book by can could did do does for from had has have how i in is it me my notes of on or say says should that the their them these they this to was were what when where which who why with would wrote you your".split())


def words(text):
    tokens = re.findall(r"[^\W_]+", text.casefold(), re.UNICODE)
    # Small plural normalizer; avoid domain-specific synonyms or model-based expansion.
    return [t[:-1] if len(t) > 4 and t.endswith("s") and not t.endswith("ss") else t
            for t in tokens if t not in STOP]


def passages(sections):
    return [{"section": si + 1, "chunk": ci + 1, "heading": section["heading"],
             "concept_id": chunk["id"], "text": chunk["text"]}
            for si, section in enumerate(sections) for ci, chunk in enumerate(section["chunks"]) if chunk["text"].strip()]


def search(sections, question, limit=3):
    candidates = passages(sections)
    query = set(words(question))
    if not candidates or not query:
        return []
    bags = [Counter(words(p["text"]) + words(p["heading"]) * 2) for p in candidates]
    lengths = [sum(b.values()) for b in bags]
    avg = max(1, sum(lengths) / len(bags))
    df = Counter(t for bag in bags for t in bag)
    ranked = []
    for p, bag, length in zip(candidates, bags, lengths):
        score = sum(math.log(1 + (len(bags) - df[t] + .5) / (df[t] + .5)) *
                    bag[t] * 2.2 / (bag[t] + 1.2 * (.25 + .75 * length / avg))
                    for t in query if bag[t])
        if score > 0:
            ranked.append((score, p))
    return [p for _, p in sorted(ranked, key=lambda item: -item[0])[:limit]]
