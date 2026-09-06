# JCLAW-1153 — AGENTS.md fragment

**Target section:** `## Architecture` → new subsection `### Process sandboxing`, placed after
`### Outbound HTTP — OkHttp 5` (both describe a single shared mechanism every call site routes
through, so they read as siblings).

---

### Process sandboxing

Every process JClaw spawns that can be steered by model output goes through
`app/tools/HarnessSandbox.java` — one class holding both platform profile builders, so the
coding-harness boundary and the native-tool boundary cannot drift apart:

- **macOS** — `sandbox-exec -p '<inline Seatbelt profile>'`: allow-default, deny all writes,
  then grant back the one write root plus `/private/tmp`, `/private/var/folders` and `/dev`;
  deny reads of `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud`, `~/.kube`, `~/.netrc`.
- **Linux** — `bwrap --ro-bind / / --tmpfs $HOME --bind <writeRoot> <writeRoot>`: the visible
  filesystem is built from nothing, so secrets are *absent* rather than merely denied.

Two independent tri-state keys drive it, both `false` by default, both accepting
`true` (confine every run) or `untrusted` (confine only runs whose origin channel is not the
operator's own web chat):

| Key | Covers | Write root |
| --- | --- | --- |
| `subagent.acp.sandbox` | ACP coding-harness processes | the run's session directory |
| `shell.sandbox` | `exec` (`/bin/sh -c`) and `diarize_audio`'s ffmpeg extraction | the agent's resolved workspace |

They are separate on purpose: confining a coding harness is not the same operator decision as
confining every shell command. Neither key is seeded into the Config DB — an absent key already
means "off", so both are documented in `conf/application.conf` and left unset.

**Both fail closed.** With a key on and no usable mechanism (native Windows; a host missing
`sandbox-exec`/`bwrap`; a WSL2 kernel with unprivileged user namespaces disabled),
`HarnessSandbox.wrap` throws `SandboxUnavailableException` and the caller aborts the run rather
than launching unconfined. Never add a fallback that launches anyway.

**What the shell sandbox does and does not change.** It bounds *reach*, not grammar. `exec`'s
first-token allowlist is still a UX guardrail rather than a metacharacter defence — `echo hi;
rm -rf ~/Documents` still passes it and still runs both statements — but with `shell.sandbox`
on, the `rm` fails on every path outside the workspace. Do not "harden" the allowlist into
per-token gating; `ShellExecToolTest.commandCompositionRunsBothCommands` pins that posture
deliberately.

**The `browser` tool sits outside the boundary**, and this is a measured verdict rather than an
omission. Two independent reasons, either sufficient:

1. Playwright's Java client spawns its own driver, which spawns Chromium — JClaw never builds a
   Chromium argv, so there is nothing for `wrap` to prefix.
2. Under a macOS Seatbelt profile, Chromium's own child-process sandbox cannot initialize
   (`sandbox initialization failed: Operation not permitted`) and the browser aborts with
   `GPU process isn't usable. Goodbye.` It launches only with `--no-sandbox`, which trades the
   per-renderer confinement that actually defends against a hostile page for a coarse
   filesystem jail. (Under Linux `bwrap` it does launch — the block is macOS-specific, but
   reason 1 is not.)

Confining the browser means confining the whole JVM (a container, firejail), which is the
operator-side mitigation `ShellExecTool`'s security-posture Javadoc already names.

**Testing note.** `shell.sandbox` is process-global Config-DB state read on every
`ShellExecTool.execute`, and play1 runs test classes concurrently. Any test that flips it must
take `ShellSandboxSync.acquire()` / `release()`, the sibling of `LuceneTestSync` and
`LoadTestHarnessSync`. A real confined run needs `@EnabledOnOs(OS.MAC)` and must target a
genuinely-denied path — **not** the temp tree, which the profile grants for `TMPDIR`.
