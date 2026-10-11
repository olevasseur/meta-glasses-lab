"""Read-only Docs parsing and local heading-weighted BM25. No model calls."""
import hashlib
import json
import math
import re
import unicodedata
from collections import Counter

from doc_nicknames import alias_key, find_alias, read_aliases
from notes import INBOX_TITLE

CHUNK_BYTES = 1000
MAX_DOCUMENT_BYTES = 1_500_000  # Extracted UTF-8 text, not API JSON metadata.
MAX_CHUNKS = 4000


def packed(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def clipped(text, size):
    return text.encode("utf-8")[:size].decode("utf-8", errors="ignore")


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()[:24]


def normalized(text):
    return " ".join(unicodedata.normalize("NFC", text).split())


def chunks(text):
    """Keep paragraphs independent; oversized ones use independent sentences.

    A sentence longer than the budget must fall back to words/UTF-8 boundaries.
    Never pack adjacent units: inserting one must not shift subsequent IDs.
    """
    for paragraph in text.splitlines(keepends=True):
        units = ([paragraph] if len(packed(paragraph).encode()) <= CHUNK_BYTES + 2
                 else re.split(r"(?<=[.!?])(?=\s+\S)", paragraph))
        for unit in units:
            while unit:
                piece = clipped(unit, CHUNK_BYTES)
                while len(packed(piece).encode()) > CHUNK_BYTES + 2:
                    piece = piece[:max(1, len(piece) * 3 // 4)]
                if len(piece) < len(unit):
                    end = piece.rfind(" ")
                    if end >= len(piece) // 2:
                        piece = piece[:end + 1]
                yield piece
                unit = unit[len(piece):]


# Select prose fields, excluding per-character formatting and embedded images.
PARAGRAPH_FIELDS = ("paragraph(elements(textRun/content,person/personProperties,"
                    "richLink/richLinkProperties,dateElement/dateElementProperties/displayText),"
                    "paragraphStyle(namedStyleType,headingId),bullet)")
CONTENT_FIELDS = PARAGRAPH_FIELDS + ",table(tableRows/tableCells/content)"
TAB_FIELDS = f"tabProperties(tabId,title),documentTab/body/content({CONTENT_FIELDS}),childTabs"
# Bound the mask, not traversal: deeper descendants are still fetched in full.
for _ in range(2):
    TAB_FIELDS = f"tabProperties(tabId,title),documentTab/body/content({CONTENT_FIELDS}),childTabs({TAB_FIELDS})"
DOCUMENT_FIELDS = f"documentId,title,revisionId,body/content({CONTENT_FIELDS}),tabs({TAB_FIELDS})"


def paragraph_text(paragraph):
    parts = []
    for element in paragraph.get("elements", []):
        person = element.get("person", {}).get("personProperties", {})
        link = element.get("richLink", {}).get("richLinkProperties", {})
        date = element.get("dateElement", {}).get("dateElementProperties", {})
        parts.append(element.get("textRun", {}).get("content", "") or person.get("name")
                     or person.get("email") or link.get("title") or link.get("uri")
                     or date.get("displayText", ""))
    return "".join(parts)


def paragraphs(content):
    for element in content:
        if "paragraph" in element:
            yield element["paragraph"]
        for row in element.get("table", {}).get("tableRows", []):
            cells = [" ".join(paragraph_text(p).strip() for p in paragraphs(cell.get("content", [])))
                     for cell in row.get("tableCells", [])]
            yield {"elements": [{"textRun": {"content": " | ".join(cells) + "\n"}}]}
        # Table of contents duplicates headings; omit it, images, headers and footers.


def tab_bodies(document):
    def walk(tabs, parents=()):
        for tab in tabs:
            props = tab.get("tabProperties", {})
            title = props.get("title", "").strip()
            path = (*parents, title) if title and not re.fullmatch(r"Tab \d+", title, re.I) else parents
            yield props.get("tabId", "/".join(path)), path, tab.get("documentTab", {}).get("body", {})
            yield from walk(tab.get("childTabs", []), path)
    if document.get("tabs"):
        bodies = list(walk(document["tabs"]))
        for tab_id, path, body in bodies:
            # A tab is a grouping, not part of the actual heading hierarchy.
            yield tab_id, " / ".join(path) if len(bodies) > 1 else "", body
    else:
        yield "body", "", document.get("body", {})


def parse_document(document):
    sections = []
    occurrences = Counter()
    text_bytes = 0
    for tab_id, tab_title, body in tab_bodies(document):
        heading_path = []
        current = None
        for paragraph in paragraphs(body.get("content", [])):
            text = paragraph_text(paragraph)
            text_bytes += len(text.encode())
            if text_bytes > MAX_DOCUMENT_BYTES:
                raise ValueError("Document too large for study")
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
                path = " / ".join(h for _, h in heading_path)
                anchor = tab_id + ":" + (style.get("headingId") or path)
                occurrences[anchor] += 1
                current = {"key": digest(anchor + str(occurrences[anchor])), "heading": path,
                           "depth": len(heading_path), "tab": tab_title, "text": ""}
                sections.append(current)
            else:
                if current is None:
                    current = {"key": digest(tab_id + ":intro"), "heading": "Introduction",
                               "depth": 1, "tab": tab_title, "text": ""}
                    sections.append(current)
                bullet = paragraph.get("bullet")
                prefix = ("  " * min(bullet.get("nestingLevel", 0), 8) + "- ") if bullet is not None else ""
                current["text"] += prefix + text.rstrip("\n") + "\n"
    count = 0
    for section in sections:
        texts = list(chunks(section.pop("text"))) or [""]
        section["chunks"] = [{"text": t, "id": digest(section["key"] + digest(normalized(t)))} for t in texts]
        count += len(texts)
        if count > MAX_CHUNKS:
            raise ValueError("Too many study passages")
    return sections


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
             "concept_id": chunk["id"], "text": chunk["text"], "tab": section.get("tab", "")}
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
