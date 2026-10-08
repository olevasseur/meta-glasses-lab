"""Replace a day's marked block in the first tab of the journal document."""

import re


TITLE = "Glasses Journal"


def utf16(text: str) -> int:
    return len(text.encode("utf-16-le")) // 2


def replacement(document: dict, day: str, text: str) -> dict:
    """Fresh indices + revision precondition protect adjacent days and user edits."""
    if not document.get("revisionId") or not document.get("body", {}).get("content"):
        raise RuntimeError("Journal body and editable revision are required")
    content = document["body"]["content"]
    # Fail closed on tables/images/etc: this app creates a plain paragraph journal.
    paragraphs = [p for p in content if "paragraph" in p]
    if any("paragraph" not in p and "sectionBreak" not in p for p in content):
        raise RuntimeError("Journal body must contain only paragraphs")
    chunks, expected = [], 1
    for paragraph in paragraphs:
        for element in paragraph["paragraph"]["elements"]:
            if "textRun" not in element or element["startIndex"] != expected:
                raise RuntimeError("Journal contains unsupported content")
            chunk = element["textRun"]["content"]
            chunks.append(chunk)
            expected += utf16(chunk)
    body = "".join(chunks)
    begin, end = f"[glasses-journal:{day}:begin]\n", f"[glasses-journal:{day}:end]\n"
    # Generated prose must never introduce our structural markers or stripped controls.
    if "[glasses-journal:" in text or re.search(r"[\x00-\x08\x0b-\x1f\ue000-\uf8ff]", text):
        raise ValueError("Invalid journal text")
    block = begin + day + "\n" + text.rstrip() + "\n" + end
    requests = []
    separator = ""
    if begin in body or end in body:
        if body.count(begin) != 1 or body.count(end) != 1:
            raise RuntimeError("Journal markers are damaged or duplicated")
        left, right = body.index(begin), body.index(end) + len(end)
        if right <= left or "[glasses-journal:" in body[left + len(begin):right - len(end)]:
            raise RuntimeError("Journal markers overlap")
        start, finish = 1 + utf16(body[:left]), 1 + utf16(body[:right])
        if finish >= expected:  # Never delete the document's final newline.
            raise RuntimeError("Journal final separator is missing")
        requests.append({"deleteContentRange": {"range": {"startIndex": start, "endIndex": finish}}})
    else:
        # A damaged marker for this date must not silently produce a duplicate day.
        if f"[glasses-journal:{day}" in body or day in body.splitlines():
            raise RuntimeError("Journal markers are damaged")
        start = expected - 1
        if body != "\n" and not body.endswith("\n\n"):
            separator = "\n"  # Finish an existing last paragraph before starting our block.
    requests.append({"insertText": {"location": {"index": start}, "text": separator + block}})
    start += len(separator)
    requests.append({"updateParagraphStyle": {
        "range": {"startIndex": start, "endIndex": start + utf16(block)},
        "paragraphStyle": {"namedStyleType": "NORMAL_TEXT"}, "fields": "namedStyleType",
    }})
    heading = start + utf16(begin)
    requests.append({"updateParagraphStyle": {
        "range": {"startIndex": heading, "endIndex": heading + len(day)},
        "paragraphStyle": {"namedStyleType": "HEADING_1"}, "fields": "namedStyleType",
    }})
    return {"writeControl": {"requiredRevisionId": document["revisionId"]}, "requests": requests}


def resolve_document(notes, configured_id: str | None) -> str:
    """Use the existing Notes transport lock; never fuzzy-match the journal title."""
    with notes.lock:
        notes._connect()
        if configured_id:
            return configured_id
        matches = [d for d in notes._list_documents(refresh=True) if d["title"] == TITLE]
        if len(matches) > 1:
            raise RuntimeError("Multiple journal documents; configure JOURNAL_DOC_ID")
        if matches:
            return matches[0]["id"]
        return notes.docs.documents().create(body={"title": TITLE}).execute()["documentId"]


def write_document(notes, document_id: str, day: str, text: str):
    with notes.lock:
        notes._connect()
        document = notes.docs.documents().get(documentId=document_id).execute()
        notes.docs.documents().batchUpdate(
            documentId=document_id, body=replacement(document, day, text),
        ).execute()
