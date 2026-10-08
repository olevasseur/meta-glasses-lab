from datetime import UTC, datetime

import pytest

import dashboard
import visual_api
from visual_memory import Caption, Frame, VisualMemory
from test_dashboard import SECRET, client, login, post
from test_visual_memory import jpeg


@pytest.fixture
def archive(monkeypatch):
    store = visual_api.memory
    monkeypatch.setenv("VISUAL_RETENTION_DAYS", "30")
    store.clock = lambda: datetime.fromisoformat("2026-10-08T12:00:00+00:00").timestamp()
    store.captioner = lambda _: Caption(caption="Keys on café counter <script>bad()</script>",
        objects=["keys", "wallet"], place_type="café", readable_text=["Maple Bistro"])
    return store


def save(store, when="2026-10-08T08:00:00-04:00", **kwargs):
    clock = store.clock
    store.clock = lambda: datetime.fromisoformat(when).timestamp()
    try:
        return store.capture(Frame(jpeg=jpeg(), captured_at=when, **kwargs))["id"]
    finally:
        store.clock = clock


@pytest.mark.parametrize("path", ["/visual-memory", "/visual-memory/thumbnails/" + "a" * 32])
def test_visual_page_and_images_require_cookie_even_with_app_secret(client, path):
    response = client.get("/dashboard" + path, headers={"X-App-Secret": SECRET})
    assert response.status_code == 303
    assert response.headers["location"] == "/dashboard/login"
    assert response.headers["cache-control"] == "no-store"


@pytest.mark.parametrize("action,data", [
    ("pin", {"id": "absent", "pinned": "1"}),
    ("delete", {"id": "absent", "confirm": "delete"}),
    ("delete-range", {"start": "2026-10-01", "end": "2026-10-08", "confirm": "delete"}),
    ("retention", {"days": "1"}),
])
def test_every_visual_mutation_requires_cookie_csrf_and_same_origin(client, archive, action, data):
    frame_id = save(archive)
    path = "/dashboard/visual-memory/" + action
    assert client.post(path, data=data).status_code == 303
    token = login(client)
    assert client.post(path, data=data).status_code == 403
    assert client.post(path, data={**data, "csrf": token}, headers={"Origin": "https://elsewhere.test"}).status_code == 403
    assert archive.find("keys")["matches"][0]["id"] == frame_id
    with archive.database() as db:
        assert archive.retention_days(db) == 30


def test_registry_browsing_local_days_search_escaping_and_private_thumbnail(client, archive):
    first = save(archive, "2026-10-07T23:45:00-04:00", label="Parking B2", location_label="home")
    second = save(archive, "2026-10-08T00:15:00-04:00", pinned=True)
    login(client)
    assert dashboard.PAGES["visual-memory"]["title"] == "Visual memory"
    response = client.get("/dashboard/visual-memory")
    assert response.status_code == 200
    assert 'href="/dashboard/visual-memory"' in response.text
    assert "Visual memory is UNKNOWN" in response.text
    assert "&lt;script&gt;bad()&lt;/script&gt;" in response.text and "<script>bad()" not in response.text
    assert "keys, wallet" in response.text and "Maple Bistro" in response.text
    assert "Parking B2" in response.text and "home" in response.text
    assert response.text.index(second) < response.text.index(first)
    day = client.get("/dashboard/visual-memory", params={"day": "2026-10-07", "q": "CAFÉ"})
    assert first in day.text and second not in day.text
    for query in ["Parking B2", "wallet", "Maple Bistro", "home"]:
        assert first in client.get("/dashboard/visual-memory", params={"q": query}).text
    assert first not in client.get("/dashboard/visual-memory", params={"q": "%' OR 1=1 --"}).text
    image = client.get(f"/dashboard/visual-memory/thumbnails/{first}")
    assert image.status_code == 200 and image.headers["content-type"] == "image/jpeg"
    assert image.content == archive.thumbnail(first)
    assert image.headers["cache-control"] == "no-store"
    assert image.headers["x-content-type-options"] == "nosniff"
    assert client.get("/dashboard/visual-memory/thumbnails/not-an-id").status_code == 404


def test_visual_pagination_preserves_day_and_query(client, archive):
    for _ in range(51):
        save(archive)
    login(client)
    response = client.get("/dashboard/visual-memory?day=2026-10-08&q=keys")
    assert response.text.count('<img src="/dashboard/visual-memory/thumbnails/') == 50
    assert 'day=2026-10-08&amp;q=keys&amp;page=1' in response.text
    second = client.get("/dashboard/visual-memory?day=2026-10-08&q=keys&page=1")
    assert second.text.count('<img src="/dashboard/visual-memory/thumbnails/') == 1
    assert 'day=2026-10-08&amp;q=keys&amp;page=0' in second.text


def test_status_heartbeat_reports_on_off_expires_and_is_not_capture_control(client, archive):
    login(client)
    assert client.post("/visual/status", json={"on": True}).status_code == 401
    headers = {"X-App-Secret": SECRET}
    for on in [True, False]:
        response = client.post("/visual/status", json={"on": on}, headers=headers)
        assert response.status_code == 200 and response.headers["cache-control"] == "no-store"
        assert f"Visual memory is {'ON' if on else 'OFF'}" in client.get("/dashboard/visual-memory").text
    for invalid in [{"on": "true"}, {"on": 1}, {}, {"on": True, "extra": 1}]:
        assert client.post("/visual/status", json=invalid, headers=headers).status_code == 422
    archive.report_capture(True)
    now = archive.clock()
    archive.clock = lambda: now + 60
    assert "Visual memory is UNKNOWN" in client.get("/dashboard/visual-memory").text
    assert VisualMemory(archive.directory).capture_status() == "UNKNOWN"
    assert archive.find("keys")["matches"] == []  # Reporting ON never captures a frame.


def test_pin_retention_persists_and_unpin_expires_old_capture(client, archive):
    old = save(archive, "2026-10-02T08:00:00-04:00")
    expired = save(archive, "2026-10-02T09:00:00-04:00")
    recent = save(archive)
    token = login(client)
    assert post(client, "/visual-memory/pin", token, id=old, pinned="1", day="2026-10-02", q="keys").headers["location"] == "/dashboard/visual-memory?day=2026-10-02&q=keys"
    assert post(client, "/visual-memory/retention", token, days="2").status_code == 303
    assert {row["id"] for row in archive.find("keys")["matches"]} == {old, recent}
    assert not (archive.directory / f"{expired}.jpg").exists()
    reopened = VisualMemory(archive.directory, clock=archive.clock)
    with reopened.database() as db:
        assert reopened.retention_days(db) == 2
    assert post(client, "/visual-memory/pin", token, id=old, pinned="0").status_code == 303
    assert archive.thumbnail(old) is None
    assert post(client, "/visual-memory/pin", token, id=old, pinned="1").status_code == 404


def test_single_and_inclusive_range_deletion_remove_pins_files_and_block_queued_uploads(client, archive):
    before = save(archive, "2026-10-05T23:59:59-04:00", pinned=True)
    first = save(archive, "2026-10-06T00:00:00-04:00", pinned=True)
    last = save(archive, "2026-10-07T23:59:59-04:00")
    after = save(archive, "2026-10-08T00:00:00-04:00", pinned=True)
    token = login(client)
    assert post(client, "/visual-memory/delete", token, id=after).status_code == 422
    assert post(client, "/visual-memory/delete", token, id=after, confirm="delete").status_code == 303
    assert client.get(f"/dashboard/visual-memory/thumbnails/{after}").status_code == 404
    assert post(client, "/visual-memory/delete", token, id=after, confirm="delete").status_code == 404
    assert post(client, "/visual-memory/delete-range", token, start="2026-10-06", end="2026-10-07", confirm="delete").status_code == 303
    assert [row["id"] for row in archive.find("keys")["matches"]] == [before]
    assert {p.stem for p in archive.directory.glob("*.jpg")} == {before}
    assert archive.thumbnail(first) is None and archive.thumbnail(last) is None
    reopened = VisualMemory(archive.directory, captioner=lambda _: pytest.fail("Deleted queue must not caption"), clock=archive.clock)
    assert reopened.capture(Frame(jpeg=jpeg(), captured_at=datetime.fromtimestamp(archive.clock(), UTC)))["status"] == "discarded"


@pytest.mark.parametrize("query", ["day=bad", "day=2026-02-30", "page=-1", "page=bad", "q=" + "x" * 501])
def test_bad_browse_parameters(client, query):
    login(client)
    assert client.get("/dashboard/visual-memory?" + query).status_code == 422


@pytest.mark.parametrize("action,data", [
    ("retention", {"days": "0"}), ("retention", {"days": "366"}), ("retention", {"days": "1.5"}),
    ("pin", {"pinned": "yes"}),
    ("delete-range", {"start": "2026-10-08", "end": "2026-10-07", "confirm": "delete"}),
    ("delete-range", {"start": "2026-02-30", "end": "2026-10-07", "confirm": "delete"}),
    ("delete-range", {"start": "2026-10-01", "end": "2026-10-08"}),
])
def test_invalid_mutations_preserve_archive(client, archive, action, data):
    frame_id = save(archive, pinned=True)
    assert post(client, "/visual-memory/" + action, login(client), **data).status_code == 422
    assert archive.find("keys")["matches"][0]["id"] == frame_id
