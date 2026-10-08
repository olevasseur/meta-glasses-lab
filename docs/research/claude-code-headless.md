# Claude Code headless jobs

Verified documentation **2026-10-08**. Installed CLI help supplied by the user:
[claude-cli-help-2026-10-08.txt](claude-cli-help-2026-10-08.txt), v2.1.292.
The CLI itself was not executed in this sandbox.

## Verified facts and chosen policy

- [Headless/print mode](https://code.claude.com/docs/en/headless) (redirect from
  https://docs.claude.com/en/docs/claude-code/headless): `-p` exits after its result;
  JSON output wraps metadata and a `result` string. With `--json-schema`, the
  schema result is in `structured_output`. `dontAsk` denies actions requiring
  ungranted permission; `--permission-prompts none` prevents interactive approval.
  `allowedTools` grants listed tools; it does not alone remove other tools.
- [CLI reference](https://code.claude.com/docs/en/cli-reference): `--restricted`
  confines file tools to working directories and ignores user/project/local
  settings. We name only Read/Grep/Glob/Edit/Write for coding, and only
  WebSearch/WebFetch/Read/Grep/Glob for research. `--strict-mcp-config` with an
  empty MCP config excludes integrations. `--safe-mode` disables customizations
  while preserving authentication. `--bare` would disable subscription/keychain
  authentication, so it is deliberately absent. Every emitted flag is also in
  the supplied local help; a test checks that intersection.
- [Permissions](https://code.claude.com/docs/en/permissions): deny rules precede
  allow rules. We deny Bash completely, plus explicit push/remote/rm/curl/wget
  patterns, PowerShell, Agent and MCP tools. This is intentionally stricter than
  `acceptEdits`, which auto-allows some filesystem shell commands. No arbitrary
  test runner is allowed: test scripts can execute network/destructive commands.
  File tools, under restricted mode, do the edits without permission prompts.
- [Settings](https://code.claude.com/docs/en/settings): CLI options/settings have
  defined precedence and managed policy remains authoritative. Local policy and
  safe mode do not establish an OS container. This runner targets the trusted
  user's Mac, using its existing subscription, not an untrusted multi-user host.

## Implementation evidence

`job_agents.py` builds explicit argv, accepts the JSON envelope and compares
reports through the existing Gemini text client/retry policy. `jobs.py` retains
`stdin=DEVNULL`, bounded separate stdout/stderr, process-group cancellation,
secret-filtered child environments and private artifacts. Git worktree creation
has hooks disabled. A comparison acquires one semaphore slot per CLI, including
when the limit is one; cancellation propagates to both children.

Agent selection is normalized and stored in the approval args/details hash and
job request. Existing tool names and tier mapping stay compatible with Android.
Dashboard Jobs displays each agent's own summary, branch, reported checks and
backend-measured tracked diff stats plus untracked names. Model claims about
checks are not treated as verified passes.

## Unverified

- Real v2.1.292 execution, subscription access to `claude-opus-5-5`, structured
  output/tool availability under restricted + safe mode, and runtime enforcement
  against symlink/path escape. Tests mock subprocesses; help/docs are evidence of
  intended behavior, not proof of installed enforcement.
- Real Gemini comparison quality, live voice routing/agent aliases, hardware
  approval presentation, and actual Google Docs append.
- Administrator-managed hooks/policy may change behavior. No hostile-code
  containment guarantee; the logged-in CLI can contact its provider.

Run the dedicated [smoke script](../../scripts/smoke_claude_jobs.py) outside the
sandbox for one tiny research and one tiny coding job. It uses a private temporary
repo/allowlist, retains artifacts, asserts the original checkout is unchanged,
and makes no Gemini Live, Gemini text or Google Docs calls.
