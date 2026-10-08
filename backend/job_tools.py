"""Validation and Gemini declarations for the asynchronous job tools."""

from google.genai import types
from pydantic import BaseModel, ConfigDict, Field, field_validator
from typing import Literal
from job_agents import normalize_agent


class JobRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class AgentRequest(JobRequest):
    agent: Literal["codex", "claude", "both"] = "codex"

    @field_validator("agent", mode="before")
    @classmethod
    def agent_alias(cls, value):
        return normalize_agent(value)


class CodexRequest(AgentRequest):
    repo: str = Field(min_length=1, max_length=200)
    instruction: str = Field(min_length=1, max_length=20_000)
    confirm: bool = Field(default=False, strict=True)
    job_id: str | None = Field(default=None, pattern=r"^[a-f0-9]{32}$")


class ResearchRequest(AgentRequest):
    topic: str = Field(min_length=1, max_length=10_000)
    doc_name: str = Field(default="Glasses Research", min_length=1, max_length=1000)


class StatusRequest(JobRequest):
    job_id: str | None = Field(default=None, pattern=r"^[a-f0-9]{32}$")


class CancelRequest(JobRequest):
    job_id: str = Field(pattern=r"^[a-f0-9]{32}$")


REQUESTS = {"codex_task": CodexRequest, "deep_research": ResearchRequest,
            "job_status": StatusRequest, "cancel_job": CancelRequest}


def declaration(name, description, properties, required=()):
    return types.FunctionDeclaration(name=name, description=description, parameters=types.Schema(
        type="OBJECT", properties={key: types.Schema(type=kind, description=desc) for key, (kind, desc) in properties.items()},
        required=list(required),
    ))


JOB_DECLARATIONS = [
    declaration("codex_task", "Propose a Codex or Claude coding task in an allowlisted repo. Android displays exact repo and instruction and requires capture or on-screen approval; never nod or spoken yes. Never resubmit confirmation fields. Never pushes or merges.", {
        "agent": ("STRING", "codex (default), claude (Claude Code), or both (compare)."),
        "repo": ("STRING", "Spoken repository alias, never a filesystem path."),
        "instruction": ("STRING", "Exact requested task; unchanged on confirmation."),
        "confirm": ("BOOLEAN", "Compatibility only; omit. This field never authorizes execution."),
        "job_id": ("STRING", "Compatibility only; omit. Android owns approval handling."),
    }, ["repo", "instruction"]),
    declaration("deep_research", "Queue web research and save a sourced report to Google Docs. Returns promptly; report completion later.", {
        "agent": ("STRING", "codex (default), claude (Claude Code), or both (compare)."),
        "topic": ("STRING", "Question/topic to research."),
        "doc_name": ("STRING", "Google Doc title or alias; defaults to Glasses Research."),
    }, ["topic"]),
    declaration("job_status", "Get a job or list the latest jobs with spoken summaries.", {
        "job_id": ("STRING", "Optional job ID; omit to list jobs."),
    }),
    declaration("cancel_job", "Cancel a queued/running job, retaining its worktree and logs. Already performed changes or document writes cannot be undone.", {
        "job_id": ("STRING", "Job ID returned by a job tool."),
    }, ["job_id"]),
]
