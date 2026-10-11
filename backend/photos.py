"""Append-only, metadata-free Mac photos. No AI or model-selected filesystem paths."""
import asyncio
import errno
import hashlib
import io
import os
import re
import sqlite3
import stat
import tempfile
import threading
from pathlib import Path
from uuid import UUID

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import AwareDatetime

from jpeg_images import decode_jpeg

MAX_BYTES = 12_000_000
MAX_PIXELS = 24_000_000  # Above DAT FULL stills (4032 × 3024); checked before decode.
router = APIRouter()


def clean_label(label: str | None) -> str:
    if label is None:
        return ""
    return re.sub(r"[^a-zA-Z0-9_-]+", "-", label[:80]).strip("-_")[:60]


class PhotoStore:
    def __init__(self, receipts: Path | None = None):
        self.receipts = receipts or Path(__file__).parent / "data" / "photo-receipts.sqlite3"
        self.lock = threading.Lock()

    def save(self, raw: bytes, upload_id: UUID, captured_at, label: str | None):
        label = clean_label(label)
        directory = Path(os.getenv("PHOTO_SAVE_DIR") or Path.home() / "Downloads").expanduser().resolve(strict=True)
        if not directory.is_dir():
            raise OSError("Photo destination is not an existing directory")
        fingerprint = hashlib.sha256(raw).hexdigest()
        stem = captured_at.strftime("glasses-%Y-%m-%d_%H-%M-%S") + (f"-{label}" if label else "")
        # Single-worker service: serialize decoding too, bounding full-resolution memory use.
        with self.lock:
            image = decode_jpeg(raw, max_bytes=MAX_BYTES, max_pixels=MAX_PIXELS)
            try:
                output = io.BytesIO()
                image.save(output, format="JPEG", quality=95, subsampling=0)
                jpeg = output.getvalue()
            finally:
                image.close()
            self.receipts.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            self.receipts.touch(mode=0o600, exist_ok=True)
            with sqlite3.connect(self.receipts) as db:
                db.execute("CREATE TABLE IF NOT EXISTS photos (id TEXT PRIMARY KEY, fingerprint TEXT, stem TEXT, directory TEXT, filename TEXT, UNIQUE(directory, filename))")
                row = db.execute("SELECT fingerprint, stem, directory, filename FROM photos WHERE id=?", (str(upload_id),)).fetchone()
                if row:
                    if row[:3] != (fingerprint, stem, str(directory)):
                        raise HTTPException(409, "Upload ID already used with different photo metadata")
                    filename = row[3]
                else:
                    filename = f"{stem}.jpg"
                    suffix = 1
                    while os.path.lexists(directory / filename) or db.execute(
                        "SELECT 1 FROM photos WHERE directory=? AND filename=?", (str(directory), filename)
                    ).fetchone():
                        suffix += 1
                        filename = f"{stem}-{suffix}.jpg"
                    db.execute("INSERT INTO photos VALUES (?,?,?,?,?)", (str(upload_id), fingerprint, stem, str(directory), filename))
                    db.commit()  # Reserve before publication so restart/lost ACK retries use the same name.
                if Path(filename).name != filename or not re.fullmatch(r"glasses-[A-Za-z0-9_-]+\.jpg", filename):
                    raise ValueError("Invalid saved filename")
                destination = directory / filename
                temporary = None
                try:
                    fd, temporary = tempfile.mkstemp(prefix=".glasses-", suffix=".tmp", dir=directory)
                    with os.fdopen(fd, "wb") as stream:
                        stream.write(jpeg)
                        stream.flush()
                        os.fchmod(stream.fileno(), 0o644)
                        os.fsync(stream.fileno())
                    try:
                        os.link(temporary, destination)  # Atomic, never replaces an existing file or symlink.
                    except FileExistsError:
                        conflict = "Reserved photo filename is occupied; phone copy retained"
                        if not stat.S_ISREG(destination.lstat().st_mode):
                            raise HTTPException(409, conflict)
                        try:
                            fd = os.open(destination, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
                        except OSError as exc:
                            if exc.errno == errno.ELOOP:
                                raise HTTPException(409, conflict) from exc
                            raise
                        try:
                            if not stat.S_ISREG(os.fstat(fd).st_mode):
                                raise HTTPException(409, conflict)
                            with os.fdopen(fd, "rb", closefd=False) as existing:
                                if existing.read(len(jpeg) + 1) != jpeg:
                                    raise HTTPException(409, conflict)
                        finally:
                            os.close(fd)
                    dir_fd = os.open(directory, os.O_RDONLY)
                    try:
                        os.fsync(dir_fd)
                    finally:
                        os.close(dir_fd)
                finally:
                    if temporary is not None:
                        Path(temporary).unlink(missing_ok=True)
        location = "your Mac's Downloads" if directory == (Path.home() / "Downloads").resolve() else "your Mac's configured photo folder"
        return {"status": "ok", "filename": filename, "message": f"Saved to {location} as {filename}"}


store = PhotoStore()


@router.post("/photos")
async def save_photo(request: Request, upload_id: UUID, captured_at: AwareDatetime,
                     label: str | None = Query(default=None)):
    if set(request.query_params) - {"upload_id", "captured_at", "label"}:
        raise HTTPException(422, "Unknown photo parameter")
    if request.headers.get("content-type", "").split(";")[0] != "image/jpeg":
        raise HTTPException(415, "Expected image/jpeg")
    try:
        return await asyncio.to_thread(store.save, await request.body(), upload_id, captured_at, label)
    except ValueError as exc:
        raise HTTPException(422, "Invalid JPEG or photo label") from exc
    except PermissionError as exc:
        raise HTTPException(507, "save_dir_permission") from exc
    except (OSError, sqlite3.Error) as exc:
        raise HTTPException(503, "Photo storage unavailable; phone copy retained") from exc
