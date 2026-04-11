## Purpose

Shell command execution tool for JClaw agents, providing host-level command access with allowlist security, workspace-scoped directories, output limits, timeout enforcement, and environment sanitization.

## Requirements

### Requirement: Shell command execution

The system SHALL provide a `ShellExecTool` that executes shell commands on the host via `ProcessBuilder` using `/bin/sh -c <command>`. The tool SHALL accept `command` (required string), `workdir` (optional string), `timeout` (optional integer, seconds), and `env` (optional key-value map). The tool SHALL return a JSON string containing `exitCode`, `output`, `durationMs`, `truncated`, and `timedOut` fields.

#### Scenario: Basic command execution
- **WHEN** agent calls exec with `{"command": "echo hello"}`
- **THEN** tool spawns `/bin/sh -c echo hello` in the agent's workspace directory and returns `{"exitCode": 0, "output": "hello\n", "durationMs": <elapsed>, "truncated": false, "timedOut": false}`

#### Scenario: Command with pipes and shell syntax
- **WHEN** agent calls exec with `{"command": "echo 'line1\nline2' | wc -l"}`
- **THEN** the full pipeline executes as a single shell invocation and returns the line count as output

#### Scenario: Non-zero exit code
- **WHEN** agent calls exec with `{"command": "exit 42"}`
- **THEN** tool returns `{"exitCode": 42, ...}` without throwing an exception — non-zero exit codes are normal results, not errors

#### Scenario: Process spawn failure
- **WHEN** the ProcessBuilder fails to start (e.g., `/bin/sh` not found)
- **THEN** tool returns an error string describing the failure

### Requirement: Safe-binary allowlist enforcement

The system SHALL validate all commands against a configurable allowlist before execution. The allowlist SHALL contain permitted command prefixes (the first whitespace-delimited token of the command). Commands not matching any allowlist entry SHALL be rejected with a descriptive error message. The allowlist SHALL be stored in the Config database table as `shell.allowlist` (comma-separated) and SHALL be editable from the Settings UI.

#### Scenario: Allowed command passes validation
- **WHEN** allowlist contains `git,npm,ls` and agent calls `{"command": "git status"}`
- **THEN** first token `git` matches allowlist and command executes normally

#### Scenario: Blocked command rejected
- **WHEN** allowlist contains `git,npm,ls` and agent calls `{"command": "rm -rf /"}`
- **THEN** tool returns error `"Command 'rm' is not in the allowed commands list. Allowed: git, npm, ls"` and no process is spawned

#### Scenario: Pipe chain validates first command only
- **WHEN** allowlist contains `git` and agent calls `{"command": "git log | head -20"}`
- **THEN** first token `git` is validated against allowlist and command executes — pipe targets are not individually validated

#### Scenario: Empty or whitespace command
- **WHEN** agent calls exec with `{"command": "  "}`
- **THEN** tool returns an error indicating the command is empty

### Requirement: Workspace-scoped working directory

The default working directory SHALL be the agent's workspace directory. If a `workdir` parameter is provided, it SHALL resolve relative to the workspace and MUST NOT escape the workspace boundary (path traversal prevention). A per-agent config `agent.{name}.shell.allowGlobalPaths` (default false) SHALL unlock arbitrary absolute working directories, and SHALL only take effect when the named agent is the main agent (i.e., the agent whose name is `main`). All other agents SHALL NOT be able to escape the workspace boundary regardless of config state. See "Main-agent-only privilege escapes" for the full security model.

#### Scenario: Default workspace directory
- **WHEN** agent calls `{"command": "pwd"}` with no workdir
- **THEN** output is the agent's workspace absolute path

#### Scenario: Relative subdirectory within workspace
- **WHEN** agent calls `{"command": "ls", "workdir": "src/main"}`
- **THEN** working directory resolves to `<workspace>/src/main` and command executes there

#### Scenario: Path traversal blocked
- **WHEN** `agent.main.shell.allowGlobalPaths` is false and the main agent calls `{"command": "ls", "workdir": "../../etc"}`
- **THEN** tool returns error indicating the working directory must be within the agent workspace and no process is spawned

#### Scenario: Absolute path blocked when global paths disabled
- **WHEN** `agent.main.shell.allowGlobalPaths` is false and the main agent calls `{"command": "ls", "workdir": "/tmp"}`
- **THEN** tool returns error indicating the working directory must be within the agent workspace

#### Scenario: Global paths enabled for main agent
- **WHEN** `agent.main.shell.allowGlobalPaths` is true and the main agent calls `{"command": "ls", "workdir": "/tmp"}`
- **THEN** command executes with `/tmp` as working directory

#### Scenario: Non-main agent cannot escape workspace
- **WHEN** a non-main agent `foo` calls `{"command": "ls", "workdir": "/tmp"}`
- **THEN** the tool short-circuits on the identity check before consulting Config at all, returns the workspace-boundary error, and no process is spawned — even if a Config row `agent.foo.shell.allowGlobalPaths=true` exists in the database

### Requirement: Main-agent-only privilege escapes

Two shell-exec privilege escapes SHALL exist as per-agent Config keys: `agent.{name}.shell.bypassAllowlist` (skip the safe-binary allowlist) and `agent.{name}.shell.allowGlobalPaths` (allow absolute working directories outside the workspace). Both SHALL only take effect when the named agent is the main agent — identified by the literal name `main`. Every other agent SHALL NOT be able to use these escapes under any circumstances.

The system SHALL enforce this gate in two layers (defense-in-depth):

- **Write-side guard**: the config save endpoint SHALL reject with HTTP 403 any attempt to set a key matching `agent.{name}.shell.(bypassAllowlist|allowGlobalPaths)` when the named agent does not exist or is not the main agent. This prevents the privilege keys from being created for non-main agents in the first place.
- **Read-side guard**: the shell exec tool SHALL check whether the calling agent is the main agent at execution time before honoring either config value. The identity check MUST short-circuit before any Config lookup, so an orphaned Config row on a non-main agent has no effect. This ensures that even if a privilege row is inserted out-of-band (DB import, manual SQL, restored backup), non-main agents cannot grant themselves privilege escapes.

Both layers are required: the write-side guard keeps operators from persisting misleading rows for non-main agents via the Settings UI; the read-side guard is the actual enforcement point and MUST NOT depend on the write-side having held.

**Rationale**: the previous design used a mutable `isDefault` boolean column on the `agent` table. That column was removed in favor of name-based identity because main-ness is already structurally enforced — the `main` agent is pre-seeded on first boot (`DefaultConfigJob`), its name is unique at the schema level, the reserved name is blocked at the API layer (`ApiAgentsController` rejects create/rename/delete that would touch `main`), and no API endpoint allows any other agent to become main. A separate `isDefault` flag added a mutation surface without adding any security — it could only ever be `true` for `main` and `false` for everyone else, making it a footgun rather than a control.

#### Scenario: Write-side rejects bypassAllowlist for non-main agent
- **WHEN** admin POSTs to `/api/config` with `{"key": "agent.helper.shell.bypassAllowlist", "value": "true"}` and agent `helper` is not named `main`
- **THEN** the endpoint returns HTTP 403 with a message indicating shell exec privileges are restricted to the main agent, and no Config row is written

#### Scenario: Write-side rejects allowGlobalPaths for non-main agent
- **WHEN** admin POSTs to `/api/config` with `{"key": "agent.helper.shell.allowGlobalPaths", "value": "true"}` and agent `helper` is not named `main`
- **THEN** the endpoint returns HTTP 403 and no Config row is written

#### Scenario: Write-side accepts privilege escape for main agent
- **WHEN** admin POSTs to `/api/config` with `{"key": "agent.main.shell.bypassAllowlist", "value": "true"}`
- **THEN** the row is persisted and subsequent main-agent shell calls bypass the allowlist

#### Scenario: Read-side check is identity-based, not Config-based
- **GIVEN** a Config row `agent.helper.shell.bypassAllowlist=true` exists (e.g., inserted via SQL or left over from before the write-side guard was added)
- **WHEN** agent `helper` calls `{"command": "rm -rf /"}`
- **THEN** the tool checks `helper.name != "main"`, ignores the Config row without reading it, validates `rm` against the allowlist, and rejects the command

#### Scenario: Read-side honors privilege escape for main agent
- **WHEN** agent `main` has `agent.main.shell.bypassAllowlist=true` and calls `{"command": "rsync -av src dst"}`
- **THEN** the tool confirms the identity check, reads the Config row, bypasses the allowlist, and executes the command even though `rsync` is not on the default allowlist

### Requirement: Output size limits

Combined stdout and stderr SHALL be captured into a single output string. Maximum output SHALL be configurable via `shell.maxOutputBytes` (default 102400 / 100KB). Output exceeding the limit SHALL be truncated and the response SHALL include `truncated: true` with a notice appended indicating total output size.

#### Scenario: Normal output within limits
- **WHEN** command produces 500 bytes of output
- **THEN** full output returned with `truncated: false`

#### Scenario: Large output truncated
- **WHEN** command produces 500KB of output
- **THEN** first 100KB returned with `truncated: true` and notice appended: `[Output truncated at 100KB. Total output: 512000 bytes]`

### Requirement: Timeout enforcement

Default timeout SHALL be configurable via `shell.defaultTimeoutSeconds` (default 30). Per-call override SHALL be accepted via `timeout` parameter, capped at `shell.maxTimeoutSeconds` (default 300). When timeout is exceeded, the process SHALL be destroyed forcibly and the result SHALL include `timedOut: true`.

#### Scenario: Command completes within timeout
- **WHEN** agent calls `{"command": "sleep 1", "timeout": 5}`
- **THEN** command completes normally and result has `timedOut: false`

#### Scenario: Command exceeds timeout
- **WHEN** agent calls `{"command": "sleep 60", "timeout": 5}`
- **THEN** after 5 seconds process is destroyed and result has `exitCode: -1`, `timedOut: true`, output contains `[Process killed: timeout after 5 seconds]`

#### Scenario: Timeout capped at maximum
- **WHEN** agent calls `{"command": "long-build", "timeout": 9999}` and `shell.maxTimeoutSeconds` is 300
- **THEN** effective timeout is 300 seconds

#### Scenario: No timeout specified uses default
- **WHEN** agent calls `{"command": "sleep 1"}` with no timeout parameter and `shell.defaultTimeoutSeconds` is 30
- **THEN** process timeout is 30 seconds

### Requirement: Environment variable sanitization

The child process SHALL inherit a filtered subset of the host environment. Variables whose names contain `KEY`, `SECRET`, `TOKEN`, `PASSWORD`, or `CREDENTIAL` (case-insensitive) or start with `AWS_`, `ANTHROPIC_`, `OPENAI_`, `GOOGLE_`, or `AZURE_` SHALL be removed. Custom env vars from the `env` tool parameter SHALL be merged after filtering.

#### Scenario: Sensitive variables filtered
- **WHEN** host environment contains `OPENAI_API_KEY=sk-xxx` and `PATH=/usr/bin`
- **THEN** child process receives `PATH` but not `OPENAI_API_KEY`

#### Scenario: Custom env vars merged
- **WHEN** agent calls `{"command": "printenv MY_VAR", "env": {"MY_VAR": "hello"}}`
- **THEN** child process receives `MY_VAR=hello` and output is `hello`

#### Scenario: Custom var cannot override filtered sensitive var
- **WHEN** agent calls `{"command": "printenv", "env": {"OPENAI_API_KEY": "injected"}}`
- **THEN** `OPENAI_API_KEY` is not present in child environment — sensitive name patterns are blocked even for custom vars

### Requirement: Opt-in tool registration

ShellExecTool SHALL be registered in `ToolRegistrationJob` only when `jclaw.tools.shell.enabled` is true (default false). Individual agents SHALL be able to disable the tool via the existing `AgentToolConfig` mechanism.

#### Scenario: Tool disabled by default
- **WHEN** JClaw starts with default configuration
- **THEN** ShellExecTool is not registered and agents cannot use it

#### Scenario: Tool enabled globally
- **WHEN** admin sets `jclaw.tools.shell.enabled=true`
- **THEN** ShellExecTool is registered and available to all agents (unless individually disabled)

#### Scenario: Tool disabled for specific agent
- **WHEN** tool is enabled globally but agent "customer-support" has `AgentToolConfig(toolName="exec", enabled=false)`
- **THEN** that agent's LLM requests exclude the exec tool definition

### Requirement: Shell configuration UI

The Settings page SHALL include a "Shell Execution" section with controls for enabling/disabling the tool, editing the allowlist, configuring the default timeout, and toggling global path access.

#### Scenario: Enable shell from settings
- **WHEN** admin toggles shell execution to enabled in Settings UI
- **THEN** `jclaw.tools.shell.enabled` config is set to `true` and tool becomes available after next registration cycle

#### Scenario: Edit allowlist
- **WHEN** admin modifies the allowlist in Settings UI to add `docker`
- **THEN** `shell.allowlist` config is updated and subsequent exec calls accept `docker` commands
