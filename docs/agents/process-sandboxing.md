# Process sandboxing

The capability table in [capabilities.md](capabilities.md) says which classes may start a process; this says what one can reach once
started. Every process JClaw spawns that can be steered by model output goes through
`app/tools/HarnessSandbox.java` — one class holding both platform profile builders, so the
coding-harness boundary and the native-tool boundary cannot drift apart:

- **macOS** — `sandbox-exec -p '<inline Seatbelt profile>'`: allow-default, deny all writes,
  then grant back the write root, any caller-passed allowances, `/private/tmp`,
  `/private/var/folders` and `/dev`;
  deny reads of `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud`, `~/.kube`, `~/.netrc`, and
  reads and writes of JClaw's `data/`, `certs/`, `conf/`, `logs/` and backup directory
  (`utils.SandboxHiddenRoots`), reopening a write root or allowance beneath one.
- **Linux** — `bwrap --ro-bind / / --tmpfs $HOME --bind <writeRoot> <writeRoot>`, then a tmpfs
  over each of those JClaw directories: the visible
  filesystem is built from nothing, so secrets are *absent* rather than merely denied. Mount
  order is load-bearing: bwrap applies mounts in argument order and a tmpfs over an ancestor
  hides every earlier bind beneath it, so the `$HOME` tmpfs must precede the write-root bind
  (the workspace lives under `$HOME` on every non-container install).
  `HarnessSandboxTest.linuxBindsTheWriteRootAfterTheHomeTmpfs` pins that order on every host.
  Allowances follow the write root as `--ro-bind-try`: readable here, but writable on macOS.

Two independent tri-state keys drive it, both `false` by default, both accepting
`true` (confine every run) or `untrusted` (confine only runs whose origin channel is not the
operator's own web chat or voice mode):

| Key | Covers | Write root |
| --- | --- | --- |
| `subagent.acp.sandbox` | ACP coding-harness processes | the run's session directory |
| `shell.sandbox` | `exec` (`/bin/sh -c`), `diarize_audio`'s ffmpeg extraction, and `LlmAudio`'s ffmpeg transcode of an audio attachment | the agent's resolved workspace for `exec`; the temp directory for the two ffmpeg runs, with the workspace root (`diarize_audio`) or the source file's directory (`LlmAudio`) as an allowance |

They are separate on purpose: confining a coding harness is not the same operator decision as
confining every shell command. Neither key is seeded into the Config DB — an absent key already
means "off", so both are documented in `conf/application.conf` and left unset. A third,
`shell.sandbox.network` (`allow`, the default, or `deny`), cuts the network of a run `shell.sandbox`
confines (`--unshare-net`; `(deny network*)`) and never reaches a coding harness, which needs its model's API.

**Both fail closed.** With a key on and no mechanism (native Windows; a host missing
`sandbox-exec`/`bwrap`), `HarnessSandbox.wrap` throws `SandboxUnavailableException` and the caller
aborts the run rather than launching unconfined. A `bwrap` that is present but cannot create its
namespaces (a WSL2 kernel with unprivileged user namespaces disabled) fails inside `bwrap`
instead, which surfaces as the command's non-zero exit — still no unconfined launch. Never add
a fallback that launches anyway.

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
