"""Google Docs notes for one personal Google account."""

import json
import os
import re
from datetime import UTC, datetime
from pathlib import Path
from tempfile import NamedTemporaryFile
from threading import Lock
from time import monotonic

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from rapidfuzz import fuzz, process, utils

BACKEND_DIR = Path(__file__).resolve().parent
SCOPES = [
    "https://www.googleapis.com/auth/drive.file",
    "https://www.googleapis.com/auth/documents",
    "https://www.googleapis.com/auth/drive.metadata.readonly",
]
TOKEN_PATH = BACKEND_DIR / "secrets" / "token.json"
INBOX_TITLE = "Glasses Inbox"
CACHE_SECONDS = 300
CREATE_INBOX = "__glasses_create_inbox__"


def save_credentials(credentials: Credentials, path: Path = TOKEN_PATH) -> None:
    """Replace the token atomically, with permissions restricted to its owner."""
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as f:
        temporary = Path(f.name)
        try:
            f.write(credentials.to_json())
            f.flush()
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def match_document(doc_name: str, documents: list[dict]) -> dict:
    matches = process.extract(
        doc_name,
        [document["title"] for document in documents],
        scorer=fuzz.token_set_ratio,
        processor=utils.default_process,
        limit=3,
    )
    if not matches or matches[0][1] < 60:
        return {"status": "not_found", "suggestion": "create_or_inbox"}
    best_score = matches[0][1]
    runner_up = matches[1][1] if len(matches) > 1 else 0
    if best_score >= 85 and best_score - runner_up >= 10:
        return {"status": "matched", "document": documents[matches[0][2]]}
    return {
        "status": "needs_confirmation",
        "candidates": [documents[index] for _, _, index in matches],
    }


class Notes:
    def __init__(
        self,
        token_path: Path = TOKEN_PATH,
        aliases_path: Path = BACKEND_DIR / "doc_aliases.json",
        on_append=None,
    ):
        self.token_path = token_path
        self.aliases_path = aliases_path
        self.on_append = on_append
        self.credentials = None
        self.docs = None
        self.drive = None
        self.cached_documents: list[dict] = []
        self.cache_expires_at = 0.0
        # ponytail: one account/worker; use a shared lock/cache before scaling out.
        # Serializes httplib2 transports and concurrent Inbox find/create calls.
        self.lock = Lock()

    def _connect(self) -> None:
        if self.credentials is None:
            self.credentials = Credentials.from_authorized_user_file(
                str(self.token_path), SCOPES
            )
        if not self.credentials.valid:
            self.credentials.refresh(Request())
            save_credentials(self.credentials, self.token_path)
        if self.docs is None:
            self.docs = build("docs", "v1", credentials=self.credentials, cache_discovery=False)
        if self.drive is None:
            self.drive = build("drive", "v3", credentials=self.credentials, cache_discovery=False)

    def _list_documents(self, *, refresh: bool = False) -> list[dict]:
        if not refresh and monotonic() < self.cache_expires_at:
            return self.cached_documents
        documents = []
        page_token = None
        while True:
            page = self.drive.files().list(
                q="mimeType='application/vnd.google-apps.document' and trashed=false",
                fields="nextPageToken,incompleteSearch,files(id,name)",
                pageSize=1000,
                pageToken=page_token,
                orderBy="modifiedTime desc",
            ).execute()
            if page.get("incompleteSearch"):
                raise RuntimeError("Google Drive returned an incomplete document search")
            documents.extend({"id": f["id"], "title": f["name"]} for f in page.get("files", []))
            page_token = page.get("nextPageToken")
            if not page_token:
                break
        self.cached_documents = documents
        self.cache_expires_at = monotonic() + CACHE_SECONDS
        return documents

    def _document(self, document_id: str) -> dict:
        document = self.docs.documents().get(
            documentId=document_id, fields="documentId,title"
        ).execute()
        return {"id": document["documentId"], "title": document["title"]}

    def _resolve(self, doc_name: str, confirmed_doc_id: str | None, *, create=True) -> dict:
        if confirmed_doc_id and confirmed_doc_id != CREATE_INBOX:
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", confirmed_doc_id):
                raise ValueError("Invalid document ID")
            return {"status": "matched", "document": self._document(confirmed_doc_id)}
        aliases = ({"inbox": None} if confirmed_doc_id == CREATE_INBOX else
                   json.loads(self.aliases_path.read_text(encoding="utf-8")))
        if not isinstance(aliases, dict) or any(
            not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", value)
            for key, value in aliases.items()
            if not (key.strip().casefold() == "inbox" and value is None)
        ):
            raise ValueError("Aliases must map names to document IDs; only inbox may be null")
        aliases = {key.strip().casefold(): value for key, value in aliases.items()}
        name = "inbox" if confirmed_doc_id == CREATE_INBOX else doc_name.strip().casefold()
        if name in aliases and aliases[name] is not None:
            return {"status": "matched", "document": self._document(aliases[name])}
        if name == "inbox" and name in aliases:
            was_cached = monotonic() < self.cache_expires_at
            documents = self._list_documents()
            inboxes = [doc for doc in documents if doc["title"] == INBOX_TITLE]
            if not inboxes and was_cached:
                # Recheck before creating: another client may have made Inbox since caching.
                documents = self._list_documents(refresh=True)
                inboxes = [doc for doc in documents if doc["title"] == INBOX_TITLE]
            if len(inboxes) > 1:
                return {"status": "needs_confirmation", "candidates": inboxes[:3]}
            if inboxes:
                return {"status": "matched", "document": inboxes[0]}
            if not create:
                return {"status": "matched", "document": {"id": CREATE_INBOX, "title": INBOX_TITLE, "create_if_absent": True}}
            created = self.docs.documents().create(body={"title": INBOX_TITLE}).execute()
            document = {"id": created["documentId"], "title": INBOX_TITLE}
            self.cached_documents.append(document)
            return {"status": "matched", "document": document}
        return match_document(doc_name, self._list_documents())

    def preview(self, doc_name: str, text: str, confirmed_doc_id: str | None = None) -> dict:
        with self.lock:
            self._connect()
            return self._resolve(doc_name, confirmed_doc_id, create=False)

    def append(self, doc_name: str, text: str, confirmed_doc_id: str | None = None) -> dict:
        with self.lock:
            self._connect()
            result = self._resolve(doc_name, confirmed_doc_id)
            if result["status"] != "matched":
                return result
            document = result["document"]
            timestamp = datetime.now(UTC).strftime("%Y-%m-%d %H:%M:%S UTC")
            self.docs.documents().batchUpdate(
                documentId=document["id"],
                body={"requests": [{"insertText": {
                    "endOfSegmentLocation": {"segmentId": ""},
                    "text": f"\n{timestamp}\n{text}\n",
                }}]},
            ).execute()  # No automatic retries: an append is not idempotent.
        # Journal publishing holds memory before acquiring the Google transport.
        # Release this lock before callbacks acquire memory to avoid deadlock.
        result = {"status": "ok", "doc_title": document["title"]}
        if self.on_append:
            result.update(self.on_append(document, text) or {})
        return result

    def append_research(self, doc_name: str, text: str) -> dict:
        """Create only the default research document; keep normal alias/fuzzy policy."""
        if doc_name.strip().casefold() != "glasses research":
            return self.append(doc_name, text)
        with self.lock:
            self._connect()
            documents = self._list_documents(refresh=True)
            matches = [doc for doc in documents if doc["title"] == "Glasses Research"]
            if len(matches) > 1:
                return {"status": "needs_confirmation", "candidates": matches[:3]}
            if matches:
                document_id = matches[0]["id"]
            else:
                created = self.docs.documents().create(body={"title": "Glasses Research"}).execute()
                document_id = created["documentId"]
                self.cache_expires_at = 0
        return self.append(doc_name, text, confirmed_doc_id=document_id)
