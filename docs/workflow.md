# Workflow — who does what

- **Claude Code** orchestrates: plans, splits work, reviews, commits.
- **OpenAI Codex CLI** (`codex exec`, logged in via the user's ChatGPT subscription) does the heavy lifting: research and implementation subtasks. Use it generously — the subscription credits are meant to be spent.
- **Gemini** is only the *runtime* AI inside the app (free included tokens).

## Running a Codex subtask
```sh
# research (read-only, web search allowed)
codex exec -s read-only -C . -o /tmp/out.md "Research X. Write findings with URLs."
# implementation in a sandboxed workspace
codex exec -s workspace-write -C . -o /tmp/out.md "Implement Y per docs/decisions.md. Run tests."
```
Codex reads `AGENTS.md` automatically. Verified working 2026-10-06 (codex-cli 0.160.0, model GPT-6).
