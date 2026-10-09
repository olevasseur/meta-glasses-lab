"""Exact Doc destinations and nickname changes, executed only after human approval."""

import json
import re
from urllib.parse import urlsplit

TOOLS = {"create_doc", "set_doc_alias", "remove_doc_alias"}
ID_PATTERN = r"[A-Za-z0-9_-]{1,200}"


def alias_name(value):
    if not isinstance(value, str):
        raise ValueError("Nickname must be text")
    value = value.strip().casefold()
    if not 1 <= len(value) <= 60 or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError("Nickname must be 1–60 characters without control characters")
    return value


def document_id(value):
    value = value.strip()
    if re.fullmatch(ID_PATTERN, value):
        return value
    url = urlsplit(value)
    if url.scheme == "https" and url.netloc == "docs.google.com":
        match = re.fullmatch(r"/document/(?:u/\d+/)?d/(" + ID_PATTERN + r")(?:/[^/]*)?/?", url.path)
        if match:
            return match[1]
    raise ValueError("Use a Google Docs HTTPS URL or document ID")


def validate_aliases(value):
    if not isinstance(value, dict):
        raise ValueError("Aliases must be a JSON object")
    result = {}
    for key, doc_id in value.items():
        name = alias_name(key)
        if name in result:
            raise ValueError("Nicknames must be unique ignoring case and whitespace")
        if not (name == "inbox" and doc_id is None) and not (
            isinstance(doc_id, str) and re.fullmatch(ID_PATTERN, doc_id)
        ):
            raise ValueError("Aliases map names to document IDs; only inbox may be null")
        result[name] = doc_id
    return result


def read_aliases(notes):
    try:
        return validate_aliases(json.loads(notes.aliases_path.read_text(encoding="utf-8")))
    except FileNotFoundError:
        return {"inbox": None}


def write_aliases(notes, aliases):
    # Reuse the dashboard's atomic, owner-only replacement; caller holds notes.lock.
    from dashboard import atomic_json
    atomic_json(notes.aliases_path, validate_aliases(aliases))


def replace_aliases(notes, value):
    value = validate_aliases(value)
    with notes.lock:
        try:
            current = read_aliases(notes)
        except ValueError:
            current = {}  # Advanced editor can repair malformed data, but cannot assign Inbox.
        if value.get("inbox") is not None and value["inbox"] != current.get("inbox"):
            raise ValueError("Inbox is reserved; only the app can assign its document")
        write_aliases(notes, value)


def edit_alias(notes, alias, destination=None):
    name = alias_name(alias)
    if name == "inbox":
        raise ValueError("Inbox is reserved")
    with notes.lock:
        aliases = read_aliases(notes)
        if destination is None:
            aliases.pop(name, None)
        else:
            aliases[name] = document_id(destination)
        write_aliases(notes, aliases)


def alias_rows(notes):
    with notes.lock:
        aliases = read_aliases(notes)
        titles = {}
        if any(aliases.values()):
            try:
                notes._connect()
                titles = {doc["id"]: doc["title"] for doc in notes._list_documents()}
            except Exception:
                pass  # Settings remain editable when OAuth/Google is unavailable.
        return [{"alias": name, "id": doc_id,
                 "title": titles.get(doc_id, "Title unavailable") if doc_id else "Find or create Inbox"}
                for name, doc_id in sorted(aliases.items())]


def duplicate_title(notes, title):
    matches = [doc for doc in notes._list_documents(refresh=True) if doc["title"] == title]
    if matches:
        return {"status": "already_exists", "candidates": matches,
                "message": "Offer to use an existing document or set_doc_alias instead. No document was created."}


def prepare(notes, name, args):
    """Return a snapshot or a non-mutating resolution result. Never create here."""
    alias = alias_name(args.get("alias") if args.get("alias") is not None else args["title"])
    if alias == "inbox":
        raise ValueError("Inbox is reserved; use append_to_doc for Inbox")
    with notes.lock:
        aliases = read_aliases(notes)
        previous = {"exists": alias in aliases, "id": aliases.get(alias)}
        snapshot = {"alias": alias, "previous": previous}
        if name == "remove_doc_alias":
            if alias not in aliases:
                return {"status": "not_found", "message": "No such nickname"}
            summary = f"Remove nickname '{alias}' (document is kept)"
        else:
            notes._connect()
            if name == "create_doc":
                duplicate = duplicate_title(notes, args["title"])
                if duplicate:
                    return duplicate
                snapshot["title"] = args["title"]
                summary = f"Create Google Doc '{args['title']}' (nickname '{alias}')"
            else:
                target = args["doc_name"].strip().casefold()
                if aliases.get(target):
                    matches = [notes._document(aliases[target])]
                else:
                    matches = [doc for doc in notes._list_documents(refresh=True)
                               if doc["title"].casefold() == args["doc_name"].casefold()]
                selected = args.get("confirmed_doc_id")
                if selected:
                    matches = [doc for doc in matches if doc["id"] == selected]
                if not matches:
                    return {"status": "not_found", "message": "Use an exact title or existing nickname; no fuzzy matches."}
                if len(matches) != 1:
                    return {"status": "needs_confirmation", "candidates": matches,
                            "message": "Ask which document, then supply its confirmed_doc_id. Selection is not approval."}
                snapshot["document"] = matches[0]
                summary = f"Set nickname '{alias}' for Google Doc '{matches[0]['title']}'"
            if previous["exists"]:
                summary += f"; overwrite existing nickname '{alias}' (document ID '{previous['id']}')"
        return {"status": "prepared", "args": snapshot, "details": {**snapshot, "summary": summary}}


def execute(notes, name, snapshot):
    with notes.lock:
        aliases = read_aliases(notes)
        alias = snapshot["alias"]
        if {"exists": alias in aliases, "id": aliases.get(alias)} != snapshot["previous"]:
            return {"status": "conflict", "message": "Nickname changed since preview; request a new approval."}
        if name == "remove_doc_alias":
            del aliases[alias]
            write_aliases(notes, aliases)
            return {"status": "ok", "alias": alias, "removed": True}
        notes._connect()
        if name == "create_doc":
            duplicate = duplicate_title(notes, snapshot["title"])
            if duplicate:
                return duplicate
            created = notes.docs.documents().create(body={"title": snapshot["title"]}).execute()
            document = {"id": created["documentId"], "title": snapshot["title"]}
            notes.cache_expires_at = 0
        else:
            document = snapshot["document"]
        aliases[alias] = document["id"]
        try:
            write_aliases(notes, aliases)
        except OSError:
            if name != "create_doc":
                raise
            return {"status": "alias_save_failed", "doc_id": document["id"], "doc_title": document["title"],
                    "message": "Document created but nickname was not saved. Use set_doc_alias; do not repeat creation."}
        return {"status": "ok", "alias": alias, "doc_id": document["id"], "doc_title": document["title"]}
