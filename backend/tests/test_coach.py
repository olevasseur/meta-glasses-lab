from concurrent.futures import ThreadPoolExecutor
from fractions import Fraction
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

import app
import coach
import coach_source


@pytest.fixture
def project():
    return coach.Project(title="Pasta", ingredients=[
        coach.Ingredient(name="pasta", quantity="200", unit="g", note=""),
        coach.Ingredient(name="olive oil", quantity="3/2", unit="tbsp", note=""),
        coach.Ingredient(name="chili oil", quantity="1/4", unit="tsp", note="optional"),
        coach.Ingredient(name="salt", quantity="", unit="", note="to taste"),
    ], tools=["pot"], steps=[coach.Step(instruction="Boil water.", duration_seconds=0),
                            coach.Step(instruction="Cook pasta.", duration_seconds=480)])


@pytest.fixture
def store(tmp_path, monkeypatch, project):
    monkeypatch.setattr(coach, "extract_project", lambda *_: project)
    store = coach.Projects(tmp_path / "project.sqlite3")
    store.run("start_project", {"kind": "cooking", "source": "Test recipe"})
    return store


def test_navigation_bounds_complete_repeat_restart_and_persistence(store):
    assert store.run("previous_step", {})["step_number"] == 1
    assert store.run("next_step", {})["step_number"] == 2
    assert store.run("repeat_step", {})["step"]["duration_seconds"] == 480
    assert coach.Projects(store.path).run("current_step", {})["step_number"] == 2
    assert store.run("next_step", {})["status"] == "complete"
    assert store.run("next_step", {})["step"] is None
    assert store.run("repeat_step", {})["status"] == "complete"
    assert store.run("previous_step", {})["step_number"] == 2
    assert store.run("start_project", {"kind": "diy", "source": "Test"})["step_number"] == 1
    assert store.run("scale_project", {"factor": 2})["status"] == "error"


def test_scaling_is_absolute_exact_and_leaves_time_tools_and_unspecified_alone(store):
    for factor in [2, 2, 0.5, 1]:
        result = store.run("scale_project", {"factor": factor})
        assert Fraction(result["ingredients"][1]["quantity"]) == Fraction("3/2") * Fraction(str(factor))
        assert result["ingredients"][-1]["quantity"] == ""
        assert result["tools"] == ["pot"]
    store.run("scale_project", {"factor": 2})
    assert store.run("how_much", {"ingredient": "OLIVE OIL"})["ingredients"][0]["quantity"] == "3"
    assert store.run("how_much", {"ingredient": "oil"})["status"] == "needs_clarification"
    assert store.run("how_much", {"ingredient": "eggs"})["status"] == "not_found"
    assert store.run("next_step", {})["step"]["duration_seconds"] == 480


@pytest.mark.parametrize("factor", [0, -1, 101, float("nan"), float("inf")])
def test_invalid_scale_preserves_state(store, factor):
    with pytest.raises(ValueError):
        store.run("scale_project", {"factor": factor})
    assert store.run("current_step", {})["scale"] == "1"


def test_failed_replacement_preserves_project(store, monkeypatch):
    monkeypatch.setattr(coach, "extract_project", Mock(side_effect=RuntimeError("private upstream detail")))
    with pytest.raises(RuntimeError):
        store.run("start_project", {"kind": "cooking", "source": "Bad recipe"})
    assert store.run("current_step", {})["title"] == "Pasta"


def test_concurrent_navigation_is_atomic(store, monkeypatch, project):
    project.steps *= 20
    store.run("start_project", {"kind": "cooking", "source": "Long recipe"})
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda _: store.run("next_step", {}), range(20)))
    assert store.run("current_step", {})["step_number"] == 21


def test_missing_project(tmp_path):
    assert coach.Projects(tmp_path / "empty.db").run("current_step", {})["status"] == "no_project"


def test_route_validation_errors_and_client_tools(store, monkeypatch):
    monkeypatch.setattr(app, "projects", store)
    monkeypatch.setenv("APP_SHARED_SECRET", "test-only")
    with TestClient(app.app, headers={"X-App-Secret": "test-only"}) as client:
        assert client.post("/tools/current_step", json={}).json()["title"] == "Pasta"
        assert client.post("/tools/next_step", json={"extra": True}).status_code == 422
        assert client.post("/tools/start_project", json={"kind": "other", "source": "x"}).status_code == 422
        for name in coach.COACH_CLIENT_TOOL_NAMES:
            assert client.post(f"/tools/{name}", json={}).status_code == 400
        monkeypatch.setattr(coach, "extract_project", Mock(side_effect=RuntimeError("secret-payload")))
        result = client.post("/tools/start_project", json={"kind": "cooking", "source": "x"})
        assert result.status_code == 502 and "secret-payload" not in result.text


def test_url_source_is_fetched_before_extraction(store, monkeypatch, project):
    fetch = Mock(return_value="Recipe text")
    extract = Mock(return_value=project)
    monkeypatch.setattr(coach, "fetch_source", fetch)
    monkeypatch.setattr(coach, "extract_project", extract)
    store.run("start_project", {"kind": "cooking", "source": "https://example.com/recipe"})
    fetch.assert_called_once_with("https://example.com/recipe")
    extract.assert_called_once_with("cooking", "Recipe text")


@pytest.mark.parametrize("url", ["http://example.com", "https://u:p@example.com", "https://example.com:8443"])
def test_rejects_unsafe_urls_before_dns(url, monkeypatch):
    dns = Mock()
    monkeypatch.setattr(coach_source.socket, "getaddrinfo", dns)
    with pytest.raises(ValueError):
        coach_source.public_address(url)
    dns.assert_not_called()


@pytest.mark.parametrize("address", ["127.0.0.1", "10.0.0.1", "169.254.169.254", "::1", "::ffff:127.0.0.1"])
def test_blocks_private_dns_even_mixed_with_public(address, monkeypatch):
    monkeypatch.setattr(coach_source.socket, "getaddrinfo", lambda *a, **k: [(2, 1, 6, "", ("8.8.8.8", 443)), (2, 1, 6, "", (address, 443))])
    with pytest.raises(ValueError):
        coach_source.public_address("https://example.com")


def test_page_extract_limits_redirects_and_pinning(monkeypatch):
    monkeypatch.setattr(coach_source.socket, "getaddrinfo", lambda *a, **k: [(2, 1, 6, "", ("8.8.8.8", 443))])
    response = Mock(status=200)
    response.isclosed.return_value = False
    response.getheader.side_effect = lambda key, default="": {"Content-Type": "text/html"}.get(key, default)
    response.read1.side_effect = [b'<h1>Pasta</h1><script>ignore()</script><p>Boil &amp; stir.</p>', b'']
    connection = Mock()
    connection.getresponse.return_value = response
    factory = Mock(return_value=connection)
    monkeypatch.setattr(coach_source, "PinnedHTTPS", factory)
    assert coach_source.fetch_source("https://example.com/r?q=1") == "Pasta\nBoil & stir."
    factory.assert_called_once_with("example.com", "8.8.8.8")
    connection.request.assert_called_once()
    assert connection.request.call_args.args[:2] == ("GET", "/r?q=1")
    response.status = 302
    with pytest.raises(ValueError):
        coach_source.fetch_source("https://example.com")
    response.status = 200
    response.read1.side_effect = [b"x" * 1_000_001]
    with pytest.raises(ValueError):
        coach_source.fetch_source("https://example.com")


def test_gemini_schema_and_response_validation(project, monkeypatch):
    client = Mock()
    client.models.generate_content.return_value = SimpleNamespace(text=project.model_dump_json())
    context = Mock(__enter__=Mock(return_value=client), __exit__=Mock(return_value=False))
    monkeypatch.setattr(coach.genai, "Client", Mock(return_value=context))
    monkeypatch.setenv("GEMINI_API_KEY", "test-only")
    assert coach.extract_project("cooking", "sample") == project
    config = client.models.generate_content.call_args.kwargs["config"]
    assert config.response_json_schema == coach.EXTRACTION_SCHEMA
    client.models.generate_content.return_value.text = '{"steps":[]}'
    with pytest.raises(ValueError):
        coach.extract_project("diy", "bad")


@pytest.mark.parametrize("quantity", ["1/0", "1e999999999", "-2", "0", "1 1/2", "1/1000000000000"])
def test_bad_quantity_is_bounded_and_rejected(quantity):
    with pytest.raises(ValueError):
        coach.Ingredient(name="oil", quantity=quantity, unit="tbsp", note="")


def test_real_sdk_extraction_wire_schema(monkeypatch, project):
    import json
    import httpx
    captured = []

    def send(_client, request, **kwargs):
        captured.append(json.loads(request.content))
        return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": project.model_dump_json()}]}}]}, request=request)

    monkeypatch.setenv("GEMINI_API_KEY", "test-only")
    monkeypatch.setattr(httpx.Client, "send", send)
    assert coach.extract_project("cooking", "Sample") == project
    config = captured[0]["generationConfig"]
    assert config["responseMimeType"] == "application/json"
    assert config["responseJsonSchema"] == coach.EXTRACTION_SCHEMA
    assert not captured[0].get("tools")
