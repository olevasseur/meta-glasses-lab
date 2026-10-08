# Claude Code headless jobs

Verified documentation **2026-10-08**. Installed CLI help supplied by the user:
[claude-cli-help-2026-10-08.txt](claude-cli-help-2026-10-08.txt), v2.1.292.
The CLI itself was not executed in this sandbox.

## Verified facts

- [Headless mode](https://code.claude.com/docs/en/headless) (redirect from
  https://docs.claude.com/en/docs/claude-code/headless): `-p` returns a result;
  JSON schema output appears in `structured_output`. We use `dontAsk` and
  `--permission-prompts none` for unattended execution.
- [CLI reference](https://code.claude.com/docs/en/cli-reference) and saved help:
  `--settings` accepts a JSON string or file. `--restricted` ignores user,
  project and local settings, but retains managed and explicit CLI settings.
  It confines file tools to working directories; explicitly naming Bash in
  `--tools` restores it. `--safe-mode` disables customizations, retaining login.
  `--bare` disables subscription/keychain login, so is deliberately absent.
  Strict empty MCP configuration excludes integrations. The local help warns
  that invalid settings are silently ignored in print mode: runtime smoke matters.
- [Sandboxing](https://code.claude.com/docs/en/sandboxing) (redirect from
  https://docs.claude.com/en/docs/claude-code/sandboxing): macOS uses Seatbelt;
  Linux/WSL2 use bubblewrap. Bash children inherit OS restrictions. Default writes
  include cwd, per-user temp and added directories. **Linked worktrees also grant
  writes to the shared Git directory**, requiring an explicit deny here.
  Sandboxing does not isolate the provider-connected Claude process itself.
  Bash reads are broader than restricted file-tool reads by default.
- [Sandbox settings reference](https://code.claude.com/docs/en/settings-reference#sandbox-settings):
  `enabled=true` enables isolation; `failIfUnavailable=true` exits if it cannot
  start. `allowUnsandboxedCommands=false` disables `dangerouslyDisableSandbox`
  retries, but `excludedCommands` can still exempt commands, so keep it empty.
  `filesystem.allowWrite` adds paths; it does not replace defaults. `denyWrite`
  restricts otherwise writable paths. `filesystem.disabled=false` preserves
  filesystem isolation. An empty domain allowlist plus `strictAllowlist=true`
  denies unknown hosts without prompting; `deniedDomains` overrides allows.
  Local binding and Unix sockets can widen access, so disable both.
- [Environment reference](https://code.claude.com/docs/en/env-vars):
  `CLAUDE_CODE_TMPDIR` selects internal temp storage, with a `claude-{uid}`
  suffix on Unix. Long overrides can cause sandboxed Bash to use a short shared
  fallback instead. We allocate a short `/tmp/gc-*` directory per invocation,
  resolve macOS's `/tmp` alias, set both this variable and `TMPDIR`, then clean it
  after execution/cancellation. The smoke asserts Bash's actual temp stays there.
- [Settings precedence](https://code.claude.com/docs/en/settings): managed policy
  remains authoritative. Filesystem/domain arrays can merge across loaded scopes;
  restricted mode removes user/repo/local sources, not administrator policy.
- [Permissions](https://code.claude.com/docs/en/permissions): explicit denies
  take precedence over allows. Prefix rules alone cannot contain arbitrary test
  programs; the OS sandbox must constrain their child processes too.

## Chosen policy and implementation evidence

`job_agents.py` emits per-job inline settings JSON for coding: worktree/private
scratch writes, shared Git and worktree `.git` write denies, no network domains,
no local binding/Unix sockets, no excluded commands, no escape or weaker isolation.
Bash joins Read/Grep/Glob/Edit/Write. Push/remote/rm/curl/wget/ssh, PowerShell,
Agent, MCP and web tools remain denied. Research retains only
WebSearch/WebFetch/Read/Grep/Glob and denies Bash and edits.

`jobs.py` discovers the actual common Git directory (including when the allowlisted
repo is itself a worktree), injects only the private temp variables into the
existing filtered environment, and removes the obsolete “tests not run” claim.
Checks remain agent-reported. Existing approvals, process-group cancellation,
private bounded logs, Gemini comparison, subscription auth and Codex policy remain.
No Gemini runtime/token configuration changed.

Unit tests cover settings/argv, required paths, shared Git denial, filtered env,
private temp cleanup, research policy, and existing job orchestration. Full backend
suite: **559 passed, 3 existing warnings**. Smoke `--help` and Python syntax checks
pass. Android unchanged. No real API checks ran.

## Unverified and external smoke

- Real v2.1.292 settings enforcement, subscription/model availability, Bash test
  execution, temp fallback, symlink escape prevention and OS network/write denial.
  Documentation plus mocked tests do not prove installed runtime enforcement.
- Managed policy may override the job policy. This is a personal trusted-host
  runner, not a multi-user hostile-code container. Read access outside the worktree
  is not a blanket secret-isolation guarantee. Provider traffic remains necessary.
- Gemini comparison quality, hardware approvals and Google Docs publication.

Run [smoke_claude_jobs.py](../../scripts/smoke_claude_jobs.py) **outside Codex's
sandbox**. It checks host curl/pytest availability, sourced research, an isolated
edit and a fixed five-case pytest suite (edit, private temp, curl/urllib denial,
outside sentinel denial, shared Git denial). It checks JUnit results and unchanged
fixture/sentinels, rather than trusting the prose summary. Inspect retained logs;
model-writable JUnit is supporting evidence, not adversarial attestation. No Gemini
or Google Docs calls; the disposable allowlist does not change backend config.
