"""Workout records in the memory database and phone-owned coaching tools."""

import json
from datetime import UTC, datetime, timedelta
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, HTTPException
from google.genai import types
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator

import memory_api
from memory import MemoryConflict

Exercise = Literal["squats", "push_ups", "sit_ups", "crunches", "lunges",
                   "jumping_jacks", "burpees", "generic", "plank", "intervals"]
Period = Literal["today", "this_week", "this_month", "all", "last_7_days"]
EXERCISES = list(Exercise.__args__)
PERIODS = list(Period.__args__)
Count = Annotated[int, Field(ge=0, le=100_000, strict=True)]
router = APIRouter()


class WorkoutLog(BaseModel):
    model_config = ConfigDict(extra="forbid")
    workout_id: UUID
    exercise: Exercise
    started_at: AwareDatetime
    ended_at: AwareDatetime
    reps_per_set: list[Count] = Field(max_length=100)
    completed_sets: int = Field(ge=0, le=100, strict=True)
    planned_sets: int = Field(ge=1, le=100, strict=True)
    target_reps: int | None = Field(default=None, ge=1, le=1000, strict=True)
    work_seconds: int | None = Field(default=None, ge=1, le=3600, strict=True)
    rest_seconds: int = Field(ge=0, le=3600, strict=True)
    status: Literal["completed", "stopped", "interrupted"]
    active_seconds: int = Field(ge=0, le=604_800, strict=True)

    @model_validator(mode="after")
    def consistent(self):
        self.started_at = self.started_at.astimezone(UTC)
        self.ended_at = self.ended_at.astimezone(UTC)
        if self.ended_at < self.started_at:
            raise ValueError("Workout ends before it starts")
        if not self.completed_sets <= len(self.reps_per_set) <= self.planned_sets:
            raise ValueError("Set counts do not match the workout plan")
        if self.status == "completed" and self.completed_sets != self.planned_sets:
            raise ValueError("A completed workout must finish all planned sets")
        timed = self.exercise in {"plank", "intervals"}
        if timed and (self.work_seconds is None or self.target_reps is not None or any(self.reps_per_set)):
            raise ValueError("Timed workouts require work_seconds and zero rep counts")
        if not timed and self.work_seconds is not None:
            raise ValueError("Rep workouts cannot specify work_seconds")
        if not timed and self.target_reps is None and self.planned_sets != 1:
            raise ValueError("Open-ended counting has one set")
        return self


class HistoryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    period: Period = "this_week"
    exercise: Exercise | None = None


@router.post("/workouts")
def save_workout(request: WorkoutLog):
    data = request.model_dump(mode="json")
    payload = json.dumps(data, sort_keys=True)
    memory = memory_api.memory
    try:
        with memory.lock, memory.db() as db:
            previous = db.execute("SELECT payload FROM workouts WHERE workout_id=?",
                                  (data["workout_id"],)).fetchone()
            if previous:
                if previous[0] != payload:
                    raise MemoryConflict("A saved workout cannot change; use a new workout_id")
                stored = False
            else:
                db.execute("INSERT INTO workouts VALUES (?,?,?,?,?,?,?,?)", (
                    data["workout_id"], request.exercise, request.started_at.isoformat(),
                    request.ended_at.isoformat(), sum(request.reps_per_set),
                    request.completed_sets, request.active_seconds, payload,
                ))
                stored = True
        return {"status": "ok", "workout_id": data["workout_id"], "stored": stored}
    except MemoryConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except OSError as exc:
        raise HTTPException(503, "Memory storage unavailable") from exc


def period_start(period: Period, now: datetime) -> datetime | None:
    midnight = now.replace(hour=0, minute=0, second=0, microsecond=0)
    return {
        "today": midnight,
        "this_week": midnight - timedelta(days=now.weekday()),
        "this_month": midnight.replace(day=1),
        "last_7_days": now - timedelta(days=7),
        "all": None,
    }[period]


def history(period: Period = "this_week", exercise: Exercise | None = None) -> dict:
    now = datetime.now(UTC)
    start = period_start(period, now)
    conditions, values = ["started_at <= ?"], [now.isoformat()]
    if start:
        conditions.append("started_at >= ?")
        values.append(start.isoformat())
    if exercise:
        conditions.append("exercise = ?")
        values.append(exercise)
    where = " AND ".join(conditions)
    memory = memory_api.memory
    with memory.lock, memory.db() as db:
        groups = [dict(row) for row in db.execute(f"""
            SELECT exercise, count(*) AS workouts, sum(total_reps) AS total_reps,
                   sum(completed_sets) AS completed_sets, sum(active_seconds) AS active_seconds
            FROM workouts WHERE {where} GROUP BY exercise ORDER BY exercise
        """, values)]
        logs = [json.loads(row[0]) for row in db.execute(
            f"SELECT payload FROM workouts WHERE {where} ORDER BY started_at DESC,workout_id LIMIT 100", values)]
    totals = {key: sum(group[key] for group in groups)
              for key in ("workouts", "total_reps", "completed_sets", "active_seconds")}
    return {"status": "ok", "period": period, "exercise": exercise, "timezone": "UTC",
            "from": start.isoformat() if start else None, "through": now.isoformat(),
            "totals": totals, "by_exercise": groups, "logs": logs,
            "truncated": totals["workouts"] > len(logs)}


START_WORKOUT = types.FunctionDeclaration(
    name="start_workout",
    description="Start phone workout coaching. Reps wait for glasses motion to become available; check the returned motion status before claiming counting is ready. Plank and intervals use a timer. Returns already_active if a workout is running.",
    parameters=types.Schema(type="OBJECT", required=["exercise"], properties={
        "exercise": types.Schema(type="STRING", enum=EXERCISES),
        "target_reps": types.Schema(type="INTEGER", minimum=1, maximum=1000,
                                    description="Reps per set; omit for open-ended counting."),
        "sets": types.Schema(type="INTEGER", minimum=1, maximum=100,
                             description="Number of sets or timed rounds; default 1."),
        "work_seconds": types.Schema(type="INTEGER", minimum=1, maximum=3600,
                                     description="Seconds per timed round, default 30; plank or intervals only."),
        "rest_seconds": types.Schema(type="INTEGER", minimum=0, maximum=3600,
                                     description="Rest between sets/rounds; default 30. Zero skips rest."),
    }),
)
STOP_WORKOUT = types.FunctionDeclaration(
    name="stop_workout", description="Stop the active workout and save its partial progress to workout history.",
    parameters=types.Schema(type="OBJECT", properties={}),
)
WORKOUT_STATUS = types.FunctionDeclaration(
    name="workout_status", description="Get the phone's current workout, set, rep count, or remaining timer/rest time.",
    parameters=types.Schema(type="OBJECT", properties={}),
)
WORKOUT_HISTORY = types.FunctionDeclaration(
    name="workout_history", description="Get saved workout totals and recent logs, including partial stopped workouts. UTC calendar periods; this_week starts Monday. Totals include all matches even if logs are truncated.",
    parameters=types.Schema(type="OBJECT", properties={
        "period": types.Schema(type="STRING", enum=PERIODS, description="Default this_week; last_7_days is a rolling seven-day window."),
        "exercise": types.Schema(type="STRING", enum=EXERCISES, description="Optional exercise filter."),
    }),
)
WORKOUT_REGISTRY = {
    "start_workout": (None, "client", None, START_WORKOUT),
    "stop_workout": (None, "client", None, STOP_WORKOUT),
    "workout_status": (None, "client", None, WORKOUT_STATUS),
    "workout_history": (HistoryRequest, "workouts", "history", WORKOUT_HISTORY),
}
