import base64
import io
from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient
from PIL import Image

import app
import visual_api
from visual_memory import Caption, Frame, VisualMemory, prepare_image

NOW = 1_791_396_000.0


def jpeg():
    data = io.BytesIO()
    Image.new("RGB", (800, 600), "red").save(data, format="JPEG", exif=Image.Exif())
    return base64.b64encode(data.getvalue()).decode()


def frame(**kwargs):
    return Frame(jpeg=jpeg(), captured_at=datetime.fromtimestamp(NOW, UTC), **kwargs)


@pytest.fixture
def store(tmp_path):
    return VisualMemory(tmp_path / "visual", captioner=lambda _: Caption(
        caption="Keys on the kitchen counter next to the fruit bowl", objects=["keys", "fruit bowl"],
        place_type="kitchen", readable_text=[]), clock=lambda: NOW)


def test_capture_search_thumbnail_permissions_and_metadata(store):
    result = store.capture(frame(label="spare keys", location_label="home", pinned=True))
    assert result["status"] == "ok"
    found = store.find("Where did I leave my keys?")["matches"]
    assert len(found) == 1 and found[0]["pinned"]
    assert found[0]["location_label"] == "home"
    assert found[0]["captured_at"].endswith("+00:00")
    assert store.find("wallet")["matches"] == []
    path = store.directory / f"{result['id']}.jpg"
    with Image.open(path) as image:
        assert image.size == (320, 240) and not image.getexif()
    assert path.stat().st_mode & 0o777 == 0o600
    assert (store.directory / "visual.db").stat().st_mode & 0o777 == 0o600


def test_retention_exempts_pins_but_explicit_delete_removes_them(store, monkeypatch):
    monkeypatch.setenv("VISUAL_RETENTION_DAYS", "7")
    pin = store.capture(frame(pinned=True))
    regular = store.capture(frame())
    store.clock = lambda: NOW + 8 * 86400
    store.prune()
    assert [m["id"] for m in store.find("keys")["matches"]] == [pin["id"]]
    assert not (store.directory / f"{regular['id']}.jpg").exists()
    store.clock = lambda: NOW + 10
    assert store.delete_recent()["deleted"] == 1
    assert not list(store.directory.glob("*.jpg"))
    assert store.find("keys")["matches"] == []
    # An already queued capture cannot recreate an erased sighting, even after restart.
    reopened = VisualMemory(store.directory, captioner=lambda _: pytest.fail("must not caption"), clock=lambda: NOW + 10)
    assert reopened.capture(frame())["status"] == "discarded"


def test_deletion_does_not_touch_older_records_and_orphans_are_cleaned(store):
    result = store.capture(frame())
    store.clock = lambda: NOW + 3601
    assert store.delete_recent()["deleted"] == 0
    assert store.find("keys")["matches"][0]["id"] == result["id"]
    orphan = store.directory / "orphan.jpg"
    orphan.write_bytes(b"junk")
    store.prune()
    assert not orphan.exists()


def test_caption_failure_stores_nothing_and_expired_timestamp_is_rejected(store):
    def fail(_):
        raise RuntimeError("upstream")
    store.captioner = fail
    with pytest.raises(RuntimeError):
        store.capture(frame())
    assert not list(store.directory.glob("*.jpg"))
    assert store.find("keys")["matches"] == []
    store.clock = lambda: NOW + 301
    with pytest.raises(ValueError):
        store.capture(frame())


def test_restaurant_text_and_newest_tie_breaking(store):
    store.captioner = lambda _: Caption(caption="Restaurant storefront", objects=["sign"],
                                       place_type="restaurant", readable_text=["Maple Bistro"])
    store.capture(frame())
    later = NOW + 30
    store.clock = lambda: later
    second = store.capture(Frame(jpeg=jpeg(), captured_at=datetime.fromtimestamp(later, UTC)))
    assert store.find("restaurant Maple")["matches"][0]["id"] == second["id"]


@pytest.mark.parametrize("encoded", ["not base64!", base64.b64encode(b"not jpeg").decode()])
def test_invalid_images(encoded):
    with pytest.raises(ValueError):
        prepare_image(encoded)


def test_routes_auth_search_validation_and_safe_errors(store, monkeypatch):
    monkeypatch.setenv("APP_SHARED_SECRET", "test-secret")
    monkeypatch.setattr(visual_api, "memory", store)
    with TestClient(app.app) as client:
        assert client.post("/visual/frames", json=frame().model_dump(mode="json")).status_code == 401
        client.headers["X-App-Secret"] = "test-secret"
        response = client.post("/visual/frames", json=frame(pinned=True).model_dump(mode="json"))
        assert response.status_code == 200 and response.headers["cache-control"] == "no-store"
        for name in ("find_object", "when_did_i_see"):
            result = client.post(f"/tools/{name}", json={"query": "keys"})
            assert result.status_code == 200 and result.json()["matches"]
        assert client.post("/visual/delete", json={"minutes": -1}).status_code == 422
        assert client.post("/visual/delete", json={"minutes": True}).status_code == 422
        assert client.post("/visual/delete", json={}).json()["deleted"] == 1
        assert client.post("/tools/find_object", json={"query": " "}).status_code == 422
        broken = frame().model_dump(mode="json") | {"jpeg": "bad"}
        assert client.post("/visual/frames", json=broken).status_code == 422


def test_caption_sdk_sends_privacy_instruction_and_bounded_json(monkeypatch):
    import json
    import httpx
    from visual_memory import caption_image
    seen = []

    def send(_client, request, **kwargs):
        seen.append(json.loads(request.content))
        result = Caption(caption="A person beside a table with keys", objects=["keys", "table"],
                         place_type="room", readable_text=[]).model_dump_json()
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": result}]}}]}, request=request)

    monkeypatch.setenv("GEMINI_API_KEY", "fake-test-only")
    monkeypatch.setattr(httpx.Client, "send", send)
    result = caption_image(b"fake-jpeg")
    assert result.objects == ["keys", "table"]
    payload, = seen
    prompt = payload["systemInstruction"]["parts"][0]["text"]
    assert "Never identify" in prompt and "'a person'" in prompt
    assert "untrusted data" in prompt
    assert payload["contents"][0]["parts"][0]["inlineData"]["mime_type"] == "image/jpeg"
    assert "responseJsonSchema" in payload["generationConfig"]


def test_capture_keeps_phone_offset_and_strips_exif(store):
    from datetime import timedelta, timezone
    data = io.BytesIO()
    exif = Image.Exif()
    exif[270] = "private metadata"
    Image.new("RGB", (40, 20)).save(data, format="JPEG", exif=exif)
    encoded = base64.b64encode(data.getvalue()).decode()
    for output in prepare_image(encoded):
        with Image.open(io.BytesIO(output)) as image:
            assert not image.getexif()
    local_time = datetime.fromtimestamp(NOW, timezone(timedelta(hours=-4)))
    store.capture(Frame(jpeg=encoded, captured_at=local_time))
    assert store.find("keys")["matches"][0]["captured_at"] == local_time.isoformat()
