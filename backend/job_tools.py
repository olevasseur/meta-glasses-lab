"""Validation and Gemini declarations for the asynchronous job tools."""

from google.genai import types
from pydantic import BaseModel, ConfigDict, Field


class JobRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class CodexRequest(JobRequest):
    repo: str = Field(min_length=1, max_length=200)
    instruction: str = Field(min_length=1, max_length=20_000)
    confirm: bool = Field(default=False, strict=True)
    job_id: str | None = Field(default=None, pattern=r"^[a-f0-9]{32}$")


class ResearchRequest(JobRequest):
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
    declaration("codex_task", "Preview a Codex task in an allowlisted repo. ALWAYS call first with confirm=false. Read the preview and ask for explicit verbal confirmation before calling again with confirm=true, returned job_id, and identical repo/instruction. Never infer consent. Never pushes or merges.", {
        "repo": ("STRING", "Spoken repository alias, never a filesystem path."),
        "instruction": ("STRING", "Exact requested task; unchanged on confirmation."),
        "confirm": ("BOOLEAN", "True ONLY after the user explicitly approves the preview."),
        "job_id": ("STRING", "The preview's job_id, required when confirming."),
    }, ["repo", "instruction"]),
    declaration("deep_research", "Queue web research and save a sourced report to Google Docs. Returns promptly; report completion later.", {
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
