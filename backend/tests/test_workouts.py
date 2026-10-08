from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
import sqlite3
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

import app
import gemini
import memory_api
import workouts
from memory import Memory
from workouts import WorkoutLog

NOW = datetime(2026, 10, 7, 12, tzinfo=UTC)


class Clock(datetime):
    @classmethod
    def now(cls, tz=None):
        return NOW


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("APP_SHARED_SECRET", "test-secret")
    monkeypatch.setattr(memory_api, "memory", Memory(tmp_path / "memory.db"))
    monkeypatch.setattr(workouts, "datetime", Clock)
    with TestClient(app.app, headers={"X-App-Secret": "test-secret"}) as client:
        yield client


def log(**changes):
    return {"workout_id": str(uuid4()), "exercise": "push_ups",
            "started_at": "2026-10-07T10:00:00Z", "ended_at": "2026-10-07T10:02:00Z",
            "reps_per_set": [12, 7], "completed_sets": 1, "planned_sets": 2,
            "target_reps": 12, "work_seconds": None, "rest_seconds": 30,
            "status": "stopped", "active_seconds": 90, **changes}


@pytest.mark.parametrize("path", ["/workouts", "/tools/workout_history"])
def test_authenticated(client, path):
    assert client.post(path, json={}, headers={"X-App-Secret": "wrong"}).status_code == 401


def test_retry_is_idempotent_but_conflicting_rewrite_fails(client):
    record = log()
    assert client.post("/workouts", json=record).json()["stored"] is True
    same = {**record, "started_at": "2026-10-07T06:00:00-04:00"}
    assert client.post("/workouts", json=same).json()["stored"] is False
    changed = {**record, "reps_per_set": [12, 8]}
    assert client.post("/workouts", json=changed).status_code == 409
    result = client.post("/tools/workout_history", json={"period": "all"}).json()
    assert result["totals"] == {"workouts": 1, "total_reps": 19, "completed_sets": 1, "active_seconds": 90}
    assert result["logs"][0]["reps_per_set"] == [12, 7]


@pytest.mark.parametrize("change", [
    {"workout_id": "not-a-uuid"}, {"exercise": "swimming"},
    {"started_at": "2026-10-07T10:00:00"}, {"ended_at": "2026-10-06T10:02:00Z"},
    {"reps_per_set": [-1]}, {"reps_per_set": [1.1]}, {"reps_per_set": [True]},
    {"reps_per_set": [12] * 101}, {"completed_sets": 3}, {"planned_sets": 101},
    {"target_reps": 0}, {"target_reps": 1001}, {"target_reps": None},
    {"work_seconds": 30}, {"rest_seconds": -1}, {"rest_seconds": 3601},
    {"status": "completed"}, {"status": "running"}, {"active_seconds": -1},
    {"exercise": "intervals"}, {"surprise": "extra"},
])
def test_log_validation(client, change):
    assert client.post("/workouts", json=log(**change)).status_code == 422


def test_timed_and_empty_interrupted_logs(client):
    timed = log(exercise="intervals", reps_per_set=[0, 0], completed_sets=2,
                work_seconds=30, target_reps=None, status="completed", active_seconds=60)
    assert client.post("/workouts", json=timed).status_code == 200
    assert client.post("/workouts", json={**timed, "work_seconds": 3601}).status_code == 422
    assert client.post("/workouts", json={**timed, "target_reps": 12}).status_code == 422
    assert client.post("/workouts", json={**timed, "reps_per_set": [1, 0]}).status_code == 422
    empty = log(reps_per_set=[], completed_sets=0, status="interrupted", active_seconds=0)
    assert client.post("/workouts", json=empty).status_code == 200


@pytest.mark.parametrize("period,expected", [
    ("today", "2026-10-07T00:00:00+00:00"),
    ("this_week", "2026-10-05T00:00:00+00:00"),
    ("this_month", "2026-10-01T00:00:00+00:00"),
    ("last_7_days", "2026-09-30T12:00:00+00:00"),
])
def test_history_period_boundaries_and_filter(client, period, expected):
    start = datetime.fromisoformat(expected)
    for when, exercise in [(start - timedelta(microseconds=1), "push_ups"),
                           (start, "push_ups"), (NOW, "push_ups"),
                           (NOW + timedelta(microseconds=1), "push_ups"), (start, "squats")]:
        assert client.post("/workouts", json=log(exercise=exercise, started_at=when.isoformat(),
                                                ended_at=(when + timedelta(seconds=120)).isoformat())).status_code == 200
    result = client.post("/tools/workout_history", json={"period": period, "exercise": "push_ups"}).json()
    assert result["from"] == expected
    assert result["timezone"] == "UTC"
    assert result["totals"]["workouts"] == 2
    assert result["totals"]["total_reps"] == 38
    assert [g["exercise"] for g in result["by_exercise"]] == ["push_ups"]


def test_empty_history_and_invalid_args(client):
    response = client.post("/tools/workout_history", json={})
    assert response.status_code == 200
    assert response.json()["totals"]["total_reps"] == 0
    assert response.json()["logs"] == []
    for args in ({"period": "yesterday"}, {"exercise": "anything"}, {"limit": 1}):
        assert client.post("/tools/workout_history", json=args).status_code == 422


def test_totals_include_logs_beyond_display_cap_and_survive_reopen(client, monkeypatch):
    for _ in range(102):
        workouts.save_workout(WorkoutLog.model_validate(log()))
    monkeypatch.setattr(memory_api, "memory", Memory(memory_api.memory.path))
    result = client.post("/tools/workout_history", json={"period": "all"}).json()
    assert len(result["logs"]) == 100 and result["truncated"]
    assert result["totals"]["total_reps"] == 102 * 19


def test_concurrent_retries_store_once(client):
    record = WorkoutLog.model_validate(log())
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(workouts.save_workout, [record] * 8))
    assert sum(result["stored"] for result in results) == 1


@pytest.mark.parametrize("error", [OSError, sqlite3.OperationalError])
def test_storage_errors_do_not_expose_paths(client, monkeypatch, error):
    def broken():
        raise error("private location")
    monkeypatch.setattr(memory_api.memory, "db", broken)
    for path, body in [("/workouts", log()), ("/tools/workout_history", {})]:
        response = client.post(path, json=body)
        assert response.status_code == 503 and "private location" not in response.text


@pytest.mark.parametrize("mode", ["auto", "ptt", "over"])
def test_phone_tools_advertised_and_cannot_run_on_backend(client, mode):
    config = gemini.token_config(gemini.DEFAULT_MODEL, mode)
    declarations = {d.name: d for d in config.live_connect_constraints.config.tools[0].function_declarations}
    assert declarations["start_workout"].parameters.required == ["exercise"]
    assert declarations["start_workout"].parameters.properties["exercise"].enum == workouts.EXERCISES
    for name in ("start_workout", "stop_workout", "workout_status"):
        response = client.post(f"/tools/{name}", json={})
        assert response.status_code == 400
        assert response.json() == {"detail": "This tool must be executed on the glasses app"}
    assert "workout_history" in declarations
    assert config.lock_additional_fields == []
    assert config.live_connect_constraints.config.session_resumption is None
