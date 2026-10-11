"""Bound decoded JPEG allocation before loading pixels; never preserve metadata."""
import io

from PIL import Image, ImageOps, UnidentifiedImageError


def decode_jpeg(raw: bytes, *, max_bytes: int, max_pixels: int) -> Image.Image:
    if not raw or len(raw) > max_bytes:
        raise ValueError("JPEG exceeds byte limit")
    try:
        with Image.open(io.BytesIO(raw)) as source:
            if source.format != "JPEG" or source.width * source.height > max_pixels:
                raise ValueError("JPEG exceeds pixel limit or has wrong format")
            image = ImageOps.exif_transpose(source).convert("RGB")
            image.info.clear()
            return image
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as exc:
        raise ValueError("Invalid JPEG") from exc
