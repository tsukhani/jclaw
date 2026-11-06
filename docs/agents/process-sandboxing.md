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
| `shell.sandbox` | `main`'s `exec` (`/bin/sh -c`, for `main` and its copies), `diarize_audio`'s ffmpeg extraction, and `LlmAudio`'s ffmpeg transcode of an audio attachment | the agent's resolved workspace for `exec`; the temp directory for the two ffmpeg runs, with the workspace root (`diarize_audio`) or the source file's directory (`LlmAudio`) as an allowance |

They are separate on purpose: confining a coding harness is not the same operator decision as
confining every shell command. Neither key is seeded into the Config DB — an absent key already
means "off", so both are documented in `conf/application.conf` and left unset. A third,
`shell.sandbox.network` (`allow`, the default, or `deny`), cuts the network of a run `shell.sandbox`
confines (`--unshare-net`; `(deny network*)`), and every custom agent's `exec` even while `shell.sandbox` is off; it never reaches a coding harness, which needs its model's API.

**Custom agents are always confined (JF-165).** `ShellExecTool.confinement` walks the agent's
`parentAgent` chain with `WorkspaceFiles.workspaceOwner` (32 hops). Only a walk that ends at a
parentless `main` is main-rooted and follows `shell.sandbox` byte for byte; any other agent, and a
chain the walk gives up on, runs through `HarnessSandbox.wrapCustomAgentShell` whatever the key
says, writing only to `AgentService.workspacePath(agent.name)` (a copy writes its root's workspace),
with the confined package caches. On Linux that is the same `linuxArgv`, which already hides
`$HOME`. On macOS the profile also hides the home folder, in this order, last match winning:
today's lines through the write grants; `(deny file-read* (subpath "<home>"))`; the workspace
reopened for reading; each PATH reopen; `(allow file-read-metadata (literal …))` for `<home>` and
every directory between it and a reopen, so `getcwd` and `realpath` resolve (an inference about
Seatbelt, to be confirmed on macOS); then the hidden-root denies, the reopen beneath a hidden root
for the write root only, and the `DENY_READ_HOME` denies. A PATH reopen above `data/`, `certs/` or
`~/.ssh` therefore never reopens them. `HarnessSandbox.homeReadReopens` picks the reopens from
JClaw's own `PATH` (`SubprocessEnv.filteredHostEnv()`), never from a `PATH` the model passes in
`exec`'s `env`: an entry qualifies when it is absolute and strictly below `<home>` on a segment
boundary, plus its canonical path when that differs and is also below `<home>`.
`HarnessSandboxTest.macProfileForACustomAgentHidesTheHomeFolder` pins the full text.

**Readiness is a launch, not a lookup.** `HarnessSandbox.availability()` is the one check behind
`GET /api/sandbox`, every key-confined `wrap` and `wrapCustomAgentShell`. On Linux it runs
`bwrap --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp true` (`ExecutableProbeSupport.probeCapturing`'s
argv overload; `--unshare-user` goes after `bwrap` when the process runs as root, which is how the container
runs — a root bwrap makes no user namespace of its own and needs CAP_SYS_ADMIN, which Docker drops; `linuxArgv` adds it too)
and reports bwrap's first output line, trimmed to 200 characters, plus a pointer to
the compose seccomp profile under `JCLAW_CONTAINER=1`; a success is cached for the JVM's life, a
failure is probed again on the next call. On macOS it is still the `sandbox-exec` lookup. Where it
fails, a custom agent's `exec` returns `ToolErrorTemplates.shellSandboxUnavailable`
(`shell_sandbox_unavailable`) and starts nothing; `main`'s keeps the `SandboxUnavailableException` abort.

**Docker.** The image installs `bubblewrap`, and `docker-compose.yml` starts the container with
`security_opt: ["seccomp=./seccomp-bwrap.json"]`: Docker's default profile (moby/profiles
`seccomp/default.json`, pinned by the SHA the compose comment names) plus one appended rule allowing
`clone`, `unshare`, `mount`, `umount2` and `pivot_root`. `clone3` keeps its ENOSYS so glibc falls back to
`clone`; `setns` is absent because bwrap calls it only for `--userns`/`--pidns`, which JClaw never
passes. Never `seccomp=unconfined`, `privileged` or `cap_add: SYS_ADMIN`; `DockerSandboxConfigTest`
pins the three files. The file ships in the dist beside the compose file, which `jclaw.sh` reads.

**Both fail closed.** With a key on and no working mechanism (native Windows; a host missing
`sandbox-exec`/`bwrap`; a `bwrap` that cannot create its namespaces, such as a WSL2 kernel with
unprivileged user namespaces disabled or Docker's default seccomp profile), `HarnessSandbox.wrap`
throws `SandboxUnavailableException` and the caller aborts the run rather than launching
unconfined. Never add a fallback that launches anyway.

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
`ShellExecTool.execute`, and play1 runs test classes concurrently. Any test that flips it, or
runs `exec`, must take `ShellSandboxSync.acquire()` / `release()`, the sibling of `LuceneTestSync` and
`LoadTestHarnessSync`. A class whose subject is how `exec` behaves rather than confinement takes
`acquireUnconfined()`, which sets `HarnessSandbox.setCustomAgentOverrideForTest(CustomAgentOverride.PASSTHROUGH)`
so a custom agent's run is not refused on a host without a working sandbox;
`CustomAgentOverride.unavailable(reason)` stands in for a failed readiness probe, and `release()`
resets it to `CustomAgentOverride.NONE`. The override is read only by
`wrapCustomAgentShell`, never by `main`'s path or a coding harness's. A real confined run needs
`@EnabledOnOs(OS.MAC)`, or Linux with `assumeTrue(HarnessSandbox.availability().available())`, and
must target a genuinely-denied path under `$HOME` — **not** the temp tree, which the profile grants
for `TMPDIR`. Tests that need `main` use an unsaved `Agent` named `main`, never a seeded row.
