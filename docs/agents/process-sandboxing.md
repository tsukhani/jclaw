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
A fourth, `shell.sandbox.homeReads` (unset by default), lists folders under `$HOME`, comma-separated, that a
custom agent's `exec` may read read-only, for a toolchain whose files sit outside the `PATH` folders (a `python3`
wrapper in `~/.local/bin` over `~/.local/share/uv`): `HarnessSandbox.extraHomeReads` feeds them to
`homeReadReopens` on macOS and to `linuxArgv`'s `--ro-bind-try` allowances on Linux. The secret folders and
JClaw's own directories stay denied last, and `rejectionFor` refuses a value that is relative, outside `$HOME` or inside a
secret folder. Nothing is reopened by following a `PATH` entry's symlink or wrapper; the operator names the folder
(the model of Claude Code's `allowRead` inside a `denyRead` and Codex's per-path `read` entries).

**Custom agents are always confined (JF-165).** `ShellExecTool.confinement` walks the agent's
`parentAgent` chain with `WorkspaceFiles.workspaceOwner` (32 hops). Only a walk that ends at a
parentless `main` is main-rooted and follows `shell.sandbox` byte for byte; any other agent, and a
chain the walk gives up on, runs through `HarnessSandbox.wrapCustomAgentShell` whatever the key
says, writing only to `AgentService.workspacePath(agent.name)` (a copy writes its root's workspace),
with the confined package caches. On Linux that is the same `linuxArgv`, which already hides
`$HOME`, with `--unshare-user` whoever runs it and runtime folders of its own (both below). On macOS
the profile also hides the home folder, in this order, last match winning:
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

**The Main Agent's shell is fenced from JClaw's own files (JF-325).** While `shell.sandbox` does not
confine it, `main`'s `exec` and its copies run through `HarnessSandbox.wrapMainFence` (`Confinement.FENCE`):
everything stays as it was except `HarnessSandbox.MainFence`. The secret folders (`SandboxHiddenRoots.current()`
minus `conf/`, which holds no secret) can be neither read nor written, except the backup folder, which stays
readable because the operator's backup task uploads it. The program files (`Play.applicationPath`,
`Play.frameworkPath` and `~/.gradle`, which a restart loads its jars from) are read-only, with the workspace root
writable again inside them. It fails closed like a custom agent's run, and honours `setCustomAgentOverrideForTest`
the same way. A command that names a skill secret still reaches JClaw's loopback secrets proxy (JF-324).
On macOS the profile ends with two more things. macOS starts no setuid program inside a sandbox (measured on
27.0.1 under a bare `(allow default)`: `ps`, `top`, `sudo`, `crontab`, `su` and `traceroute` fail with EPERM), so
`/bin/ps` and `/usr/bin/top` run outside it (`with no-sandbox`); the others stay unavailable to the fenced shell.
And JClaw's own attach socket, `.java_pid<JClaw's pid>`, takes no connection, rename or link, so `jcmd` cannot read
from JClaw's memory what the fence hides on disk; any other JVM still attaches
(`SandboxJvmAttachTest.theMainAgentsFencedShellCannotAttachToJclawsOwnJvm`). The Linux fence has no such rule yet:
it shares `/tmp` and the PID namespace with the host. Another process's environment is not a route on macOS 27:
`ps -E` prints none for a same-user process (measured).
`ShellExecSandboxTest` pins both platform forms and probes the real one on the host.

**Every sandbox takes the secrets proxy's trust folder read-only (JF-324).** A command holding a skill secret is
pointed at two files in the temp folder, `SecretProxy.trustFolder()`. A Linux sandbox replaces `/tmp` with an empty
one, where they would be missing and every HTTPS call would fail its certificate check, and a macOS sandbox may write
the shared temp folders, where a command could remove or replace what other commands trust. So
`HarnessSandbox.withTrustFolder` adds the folder to the allowances, which Linux binds read-only after its temp folder
(`HarnessSandboxTest.aLinuxSandboxBindsAnAllowanceAfterItsOwnTempFolder`), `macTrustFolderReadOnly` ends a macOS
profile with a write deny, and `MainFence.current` lists it with the program files. Measured in the dev image
(bubblewrap 0.11.1): with the allowance the file reads and `rm` fails with "Read-only file system"; without it the
file is absent.

**A custom agent's shell cannot attach to a JVM outside it (JF-290).** An attached client can load
code into a JVM or dump its heap, and JClaw's own holds the internal bearer token. HotSpot's attach
handshake has two steps: SIGQUIT makes the JVM look for a trigger file (`.attach_pid<pid>`, in its
working directory or its temp directory) and start a listener, and the client then connects to the
listener's socket, `.java_pid<pid>` in that temp directory. Both are refused, and both rules are
the custom agent's alone: `main`'s `exec` and a coding harness keep their profiles
(`HarnessSandboxTest.macOnlyACustomAgentsRunCarriesTheLocalServiceSignalAndJvmAttachDenies`).

- **macOS** — `customAgentMacosProfile` ends the profile with two denies, last so that no grant
  outranks them. `(deny signal (require-not (target same-sandbox)))`: SIGQUIT, like every other
  signal since JF-303 (below), reaches only the run's own processes.
  `(deny network-outbound file-write* file-link (regex #"/\.java_pid[^/]*$"))`: the socket takes no
  connection, and no rename, unlink or hard link that
  would give it a name the rule misses (measured: a symlink, a case variant and a `/.vol` path all
  resolve to the same name and are refused). The temp directory stays writable, as `TMPDIR` needs.
  The rule goes by name, not owner, so a JVM started inside the sandbox cannot be attached to
  either: `jcmd` against it gives up after its ten seconds (measured), and a Java agent that
  attaches to its own JVM, instead of being passed with `-javaagent`, goes through the same socket.
- **Linux** — no rule is needed for the files: HotSpot uses `/tmp` whatever `TMPDIR` says, the
  sandbox has a `/tmp` of its own, and JClaw's working directory is under the read-only root. What
  is left is `/proc/<pid>/cwd` and `/proc/<pid>/root`, which lead to both, and which the kernel
  refuses across a user namespace. A bwrap that is not setuid always makes one; a setuid bwrap makes
  none unless asked, so `inOwnUserNamespace` adds `--unshare-user` to a custom agent's argv when
  `linuxArgv` has not (measured with a setuid bwrap: the flag is what makes the attempt fail). The
  non-root readiness probe does not carry the flag, so on a setuid bwrap whose kernel makes no user
  namespace that probe would pass; the namespace probe that follows it (JF-303, below) does carry it,
  so the run is refused with bwrap's own line (an inference, not measured). A JVM inside the sandbox can still be attached to from inside it
  (measured). A SIGQUIT from the sandbox finds no JVM outside it either, since JF-303 put the run
  in a PID namespace and a session of its own (below).

**A custom agent's shell signals no process outside its run (JF-303).** A `kill` from a custom
agent's `exec` used to stop a process of the operator's (measured with throwaway processes on macOS
and in both container images), and JClaw runs as that same user. Now a signal reaches only the
processes of the run that sends it. Each run
is a sandbox of its own, so it does not reach what an earlier run of the same agent left in the
background either: `exec` stops that for the agent, from the JVM. The rule is the custom agent's
alone: `main`'s `exec` and a coding harness signal as before
(`SandboxSignalTest.mainsExecStillSignalsAProcessOutsideItWithAndWithoutTheKey`,
`HarnessSandboxTest.linuxOnlyACustomAgentsArgvIsInItsOwnPidNamespaceAndSession`).

- **macOS** — `(deny signal (require-not (target same-sandbox)))`, where `same-sandbox` is one
  `sandbox-exec` launch. Measured on 27.0.1 with throwaway `sleep` processes: each of signal 0, HUP,
  INT, QUIT, KILL, USR1, TERM, STOP and CONT fails with `Operation not permitted` at a process started
  outside, at a process group outside, and at a process an earlier launch of the same profile left
  running; inside one launch a child, a grandchild and the run's own shell still take a signal. Under
  the rule it replaces, `kill` succeeded for every one of those but QUIT. A signal to the caller's
  own process group, which is the one JClaw started the run in, is checked member by member: TERM and
  KILL ended the run's shell and left a throwaway launcher and the `sleep` beside it running, where
  under the old rule both died (measured). launchd needs no rule:
  `launchctl kill`, `stop`, `kickstart -k`, `bootout` and `remove` against a throwaway job outside all
  came back `Not privileged` or `Operation not permitted` from inside a profile of `(allow default)`
  plus this rule, and the same `kill` outside ended the job.
- **Linux** — bwrap has no rule for a signal's target, so the run gets a PID namespace
  (`--unshare-pid`, `HarnessSandbox.LinuxPids`): a process outside has no pid there and `kill`
  answers `No such process`. A PID namespace does not bound a process group, though, and JClaw starts
  the run, so the run is born into JClaw's group: under `--unshare-pid` alone a signal to the caller's
  own group (`kill 0`, TERM and KILL, from the command and from a background job of it) ended a
  throwaway launcher and the `sleep` beside it along with the run (measured in the runtime image as
  root and in the dev image as uid 1000, there with `/proc` unmasked too). So the run also gets a
  session of its own (`--new-session`, bwrap's `setsid`), whose process groups hold only the run: the
  same signals then end the run's shell and nothing outside it, a job of the run still takes a signal,
  a background command still outlives the call, and the stop from outside finds and ends it as before
  (measured in the same containers, and in the dev image with JClaw's compiled `HarnessSandbox`
  building the argv, as root and as uid 1000, both `/proc` shapes;
  `SandboxSignalTest.aSignalToTheRunsOwnProcessGroupReachesNothingOutsideTheRun`). The run then has no
  controlling terminal: where its launcher has one, opening `/dev/tty` inside fails with `No such
  device or address`, and without the flag it opened (measured in the dev image). A Ctrl-C in a
  terminal JClaw was started from goes to JClaw's process group, so it no longer reaches such a run
  (an inference, not measured); `jclaw.sh` stops JClaw by pid, which never reached one. The run's
  `--proc /proc` is the PID namespace's own procfs, where the kernel mounts one. In Docker it does not: the runtime mounts over parts of `/proc`, and the kernel then
  refuses a new procfs to a user namespace (`Can't mount proc on /newroot/proc: Operation not
  permitted`, under `seccomp-bwrap.json`, as root and as uid 1000). So `customAgentPids` starts
  `pidNamespaceProbe` (the readiness probe with `--unshare-user`, `--unshare-pid` and `--new-session`) with `--proc /proc`, and where that fails, without it, keeping the `/proc` the
  root bind brings (`LinuxPids.OWN_UNDER_OUTER_PROC`, logged once). A success is kept for the JVM's
  life; where neither starts, the run is refused with `shell_sandbox_unavailable`, never started in
  JClaw's own namespace. What the kept `/proc` costs, measured in both images: it is read-only and
  lists the processes outside by pids that mean nothing inside, so `ps` fails (`fatal library error,
  lookup self`), `pgrep` prints those pids and `pkill` signals nothing of the run's own, and
  `/proc/<pid>` of the run's own child does not exist. `/proc/self` still resolves, shell process
  substitution works, a JVM, node and python3 start, and the JVM starts and stops a child; its
  `ProcessHandle.info()` is empty and `allProcesses()` lists the processes outside. Naming them
  gives no way to signal them: `pidfd_send_signal` on an open `/proc/<pid>` directory fails with
  `Invalid argument`, the kernel's refusal of a signal from a PID namespace to its ancestor, and
  `pidfd_open` finds no such process (measured with python3, where both land under the argv
  without the namespace). With a procfs of its own (measured in the same images started with
  `systempaths=unconfined`, standing in for a host that is not a container) all of that is
  consistent and `ps` lists only the run. Both shapes were also built and started by JClaw's own
  compiled `HarnessSandbox` in the dev image, as root and as uid 1000; the Linux run tests skip on
  the build host, so `HarnessSandboxTest` pins the argv.
- **Stopping across runs** — `exec` takes `"stop": <pid>` in place of a command
  (`ShellExecTool.stopBackground`) and calls `BackgroundProcesses.stop`, which already tracked what
  each agent's commands left running: the processes holding, on descriptor 4, an output file `exec`
  itself made in that agent's workspace. `POST /api/processes/{pid}/stop` goes the same way. A pid
  that is not among the calling agent's holders is answered as a pid that names nothing, whether it
  is another agent's process, one JClaw never started or none at all, and the refusal lists the pids
  the agent can stop (`ExecStopBackgroundTest`). The pid is the one the earlier result named, as
  JClaw sees it: on Linux `$!` inside the run is the namespace's own number. From outside, the
  namespace's processes are ordinary ones: `BackgroundProcesses.pidsHolding` found the background
  process of a PID-namespace run and `terminate` ended it, after which bwrap's own init exited
  (measured with the compiled classes, both shapes). A foreground command killed on timeout or by a
  stopped turn is ended the same way: TERM to the tree took the command, its shell, bwrap and that
  init (measured from a shell). The run's pipe still closes within milliseconds of a foreground
  command's exit, so the one-second grace that tells a command that left something running is
  unchanged (measured over 30 runs a shape). The tracker's limit is the stop's limit: a process that
  closes the descriptors it inherited is in no list, and since no later run can signal it either,
  only the operator or `main` stops it. Before JF-303 a later `kill` did.
- **`--die-with-parent` is not used.** Under the PID namespace it ends a background command the
  moment the call returns, because the namespace's init then dies with the outer bwrap, which exits
  with the shell (measured: `background` left nothing running). Without the namespace the flag
  changes nothing for the processes the shell started (measured: a foreground and a background
  `sleep` both survived the launcher's SIGKILL, with the flag as without it). And `exec` promises
  the opposite: a background process outlives the call and a restart, is found again after one, and
  ends on its lifetime, with its conversation or agent, or on a stop.

**A custom agent's shell reaches no local service outside it through a socket file (JF-302).** The
sandbox bounds files, and a service that runs unconfined as the operator acts outside it for whoever
connects: a container runtime starts a container with any host folder mounted, which undoes the file
confinement. So while `shell.sandbox.network` is `allow`, a socket file takes a custom agent's connection
only in its workspace and in its own temp folder on macOS, and on Linux the folders where services keep
their sockets are empty for it. The macOS rule refuses every socket but a few instead of
naming the known ones: on the Mac it was measured on, the shared temp folder alone held the control
sockets of several other programs, none of them a container runtime, which no list would have named.
Like the JVM attach rules it is the custom agent's alone: `main`'s `exec` and a coding harness keep their
profiles (`SandboxLocalServiceTest.mainConfinedUnderTheKeyStillConnectsToASocketOutsideItsWorkspace`,
`HarnessSandboxTest.macOnlyACustomAgentsRunCarriesTheLocalServiceSignalAndJvmAttachDenies`).

- **macOS** — `customAgentMacosProfile` adds `(deny network-outbound (remote unix-socket))`, then an
  allow for the workspace, for the run's own temp folder and for `/private/var/run/mDNSResponder`, which
  name resolution goes through (measured: without that allow `curl` resolves no host;
  `SandboxLocalServiceTest.onMacANameStillResolvesInsideTheSandbox`). Docker Desktop's socket is under
  `$HOME`, with `/var/run/docker.sock` a link to it (seen here). Colima's is under `$HOME` too, and a
  Podman machine's is under `$HOME` or, in newer versions, in the temp folder, which macOS shares between
  every program the user runs (their documentation's paths: neither is installed here). So the run gets a
  temp folder of its own, `HarnessSandbox.customAgentTempDir`: `jclaw-<hash of the write root>` in `java.io.tmpdir`,
  made by the wrap, named in the profile by its path with the folders above it resolved and in the run's
  environment by `TMPDIR`
  (`ConfinedShell.env`, put after the model's `env`, so a `TMPDIR` the model passes changes nothing). The
  name is short because a socket path holds 103 bytes. A tool that keeps a socket beside its temp files
  still reaches it (measured with Python's `multiprocessing.Manager`: it works under that `TMPDIR` and
  fails with `Operation not permitted` in the shared folder). A tool that puts its socket in `/tmp` by a
  fixed name does not, and needs a path in the workspace or a TCP port. Files in the shared temp folders
  stay writable, and JClaw never empties the run's folder. Under
  `network=deny` the socket rules are left out: `(deny network*)` already refuses every socket, and an
  allow after it would reopen the workspace's
  (`HarnessSandboxTest.macProfileForACustomAgentUnderNetworkDenyReopensNoSocket`).

  The socket rules go by path, and the run writes to the shared temp folders (`/private/tmp` and
  `/private/var/folders`), so two more rules hold them up. A socket in a shared temp folder, or the folder
  that holds it, renamed under the workspace or the run's temp folder takes the connection at its new path
  (measured without the rule: `mv` of the socket, `mv` of its folder, and `renameatx_np` with `RENAME_SWAP`,
  each answered). `sharedTempStaysInPlace` adds
  `(deny file-write-unlink (require-all (vnode-type DIRECTORY SOCKET) …))` for the two shared folders,
  leaving out what is inside the workspace and the run's temp folder. Seatbelt has one operation for
  rename and removal, so the run cannot remove or rename a folder it made in a shared temp folder either:
  `rm -rf "$(mktemp -d)"` empties the folder, leaves it and exits 1, because macOS `mktemp` uses the shared
  folder unless its template names `$TMPDIR`. Files there, and everything under `TMPDIR` and the workspace,
  still come and go (`SandboxLocalServiceTest.onMacFilesInASharedTempFolderAndFoldersOfTheRunsOwnStillComeAndGo`).
  The workspace is left out by the path JClaw spells it with, so one reached through a link inside a shared
  temp folder (`/tmp/…`) is not. A hard link from a shared folder into the workspace or the run's temp
  folder needs no rule: Seatbelt refuses it under this profile (measured). The rule is there under
  `network=deny` too, so a socket moved then is not waiting when the network is allowed again
  (`onMacACustomAgentCannotMoveOrLinkASocketOutOfTheSharedTempFolder`). The second: the run's temp folder
  sits in a folder every run writes to, so a link can stand at its name, and a profile built from the
  resolved path would open the sockets of whatever folder the link leads to (measured with such a profile:
  the run removed its temp folder, left a link to a folder under `$HOME`, and its next connection to a
  socket there was answered).
  So the profile names the folder by its parent's real path plus its own name, never resolving the name;
  the wrap replaces whatever is there that is not a folder; and `(deny file-write-unlink (literal …))`
  keeps the run from removing or renaming the folder itself
  (`onMacALinkAtTheRunsTempFolderNameOpensNoSocketBehindIt`, `onMacACustomAgentCannotRemoveOrRenameItsOwnTempFolder`).
- **Linux** — a socket is a file, and the `$HOME` and `/tmp` tmpfs already hid Docker Desktop's, Colima's
  and everything in `/tmp`. The root bind left `/run` visible (and `/var/run` where that is a directory
  of its own): Docker's and Podman's sockets, the rootless ones under `/run/user/<uid>`, the session and
  system D-Bus, the systemd user instance. `HarnessSandbox.linuxOwnRuntime` puts a tmpfs over each,
  between `--proc /proc` and the `$HOME` tmpfs, and binds back read-only what a command needs from under
  it: the file `/etc/resolv.conf` points at, which systemd-resolved and NetworkManager keep there
  (measured: with `/run` hidden and that file not bound back, no name resolves), and the folders of
  JClaw's own `PATH` under `/run`, where NixOS keeps its programs (measured with a stand-in folder and a
  linked one, not on NixOS). The file is bound, never its folder, so a socket beside it stays out. A run
  that outlives a change of DNS servers keeps reading the file it started with, and a host that looks up
  names or accounts through a daemon socket under `/run` (nscd, nslcd) loses that lookup inside the
  sandbox (both inferences, not measured). A socket file anywhere else on the root stays reachable, under
  `network=deny` as well: see the table. The Linux run tests skip where bwrap cannot start, the build
  host included, so `HarnessSandboxTest` pins the argv on every host and the argv itself was run in
  throwaway containers.

The other local-service channels, each measured from inside a custom agent's sandbox with the same
attempt outside it as the control. macOS is 27.0.1; Linux is the two container images, bwrap 0.11.1, as
root and as uid 1000, with throwaway servers in place of every real service.

| Channel | Before | Now, and why |
| --- | --- | --- |
| A container runtime's control socket (macOS, Linux) | answered | Refused by the rules above: `SandboxLocalServiceTest.aCustomAgentCannotConnectToASocketWhereAContainerRuntimeKeepsIts`. |
| The operator's ssh-agent (macOS) | answered `ssh-add -l` | Refused by the socket rule (`Error connecting to agent: Operation not permitted`). It signs with the operator's keys for any caller, and `~/.ssh` was already denied. |
| A socket in a shared temp folder, moved under the workspace or the run's temp folder (macOS) | answered at its new path, for a throwaway server in `/private/tmp` and one in the user's temp folder | Refused: the socket and its folder stay where they are, at the cost described above. A Podman machine's API socket is in the user's temp folder (its documentation's path). The ssh-agent's is under `/var/run` here, where the run writes nothing; earlier macOS versions kept it in a folder under `/private/tmp` (an inference, not seen here). |
| A socket file elsewhere on the root (Linux) | answered | Kept: bwrap decides what is mounted, not what is connected to, and hiding more of the root one folder at a time would be a list of known services. A throwaway server under `/var/lib`, `/srv` and `/opt` answered, with `--unshare-net` too, while the one at `/run/docker.sock` did not (measured in one container image, as root). LXD and Incus keep their control sockets under `/var/lib` or `/var/snap` (their documentation's paths, not installed here), and only a member of their group may connect. |
| LaunchServices, `open` (macOS) | started a throwaway app outside the sandbox, which wrote where the sandbox cannot | Closed with `(deny lsopen)`: `open` fails with error -54 and starts nothing (`onMacACustomAgentCannotHaveLaunchServicesStartAnApp`). |
| launchd job submission, `launchctl submit`, `bootstrap`, `load` (macOS) | refused | Kept as it is, no rule: launchd refuses a sandboxed caller, under `(allow default)` alone too. Outside, all three ran a throwaway job; inside, none did (`Bootstrap failed: 5: Input/output error`), and `launchctl setenv` is `Not privileged`. |
| Apple Events, `osascript` (macOS) | refused | Kept as it is, no rule: a `quit` sent to a throwaway applet outside came back `-10004` and the applet kept running, under `(allow default)` alone too; outside the sandbox it quit. Only that event was measured: another may ask the operator for consent on screen. |
| The session D-Bus at `/run/user/<uid>/bus` (Linux) | a throwaway server at that path answered, and saw the operator's uid as its peer | Absent, with the rest of `/run`: the system bus and the systemd user instance go the same way. Measured with a stand-in, because neither image has a D-Bus daemon; that a real bus admits a peer by that uid is an inference. |
| An abstract socket (Linux) | answered | Kept: it belongs to the network namespace, which the run shares for its outbound connections, so only `shell.sandbox.network=deny` cuts it. An X server listens on one, and so does a session bus started by `dbus-launch` (an inference, not measured with either). |
| A service on a loopback TCP port (macOS, Linux) | answered | Kept: the network is allowed, and a local port is part of it. `shell.sandbox.network=deny` closes it. |
| The preferences daemon, `defaults` (macOS) | a write to a throwaway domain inside was read back outside | Kept, and not closed here: it is a Mach service, not a socket, and denying one takes its own measurement of which tools stop working. The keychain service is the same case: it answered a lookup for an item that does not exist, and reading a real item was not measured. The clipboard was empty, so it was not measured. |

**Readiness is a launch, not a lookup.** `HarnessSandbox.availability()` is the one check behind
`GET /api/sandbox`, every key-confined `wrap` and `wrapCustomAgentShell`. On Linux it runs
`bwrap --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp true` (`ExecutableProbeSupport.probeCapturing`'s
argv overload; `--unshare-user --cap-drop ALL` goes after `bwrap` when the process runs as root, which is how the container
runs — a root bwrap makes no user namespace of its own and needs CAP_SYS_ADMIN, which Docker drops; `linuxArgv` adds it too)
and reports bwrap's first output line, trimmed to 200 characters, plus a pointer to
the compose seccomp profile under `JCLAW_CONTAINER=1`; a success is cached for the JVM's life, a
failure is probed again on the next call. On macOS it is still the `sandbox-exec` lookup. Where it
fails, a custom agent's `exec` returns `ToolErrorTemplates.shellSandboxUnavailable`
(`shell_sandbox_unavailable`) and starts nothing; `main`'s keeps the `SandboxUnavailableException` abort.
On Linux a custom agent's run needs one launch more, `customAgentPids` (above), which picks its PID
namespace and refuses the run the same way where none starts; `GET /api/sandbox` does not report it.

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
A test that needs a JVM to attach to starts a throwaway one and stops it (`SandboxJvmAttachTest`),
never a JVM that is already running. A test that sends a signal aims it at a throwaway `sleep` it
started (`SandboxSignalTest`), never at a pid it did not start and never at `-1`. A test that signals a
whole process group (`kill 0`) builds the run's argv with `wrapCustomAgentShell` and starts it from a
launcher that has made a session of its own and checked that it leads its group
(`SandboxSignalTest.inAProcessGroupOfItsOwn`), never through `exec`, whose run is in the test JVM's group. A test that needs a local service starts its own Unix socket server at
the path in question (`SandboxLocalServiceTest`): it never connects to a real runtime's socket and never
starts a container. A test that sends SIGQUIT inside a run aims it at the run's
shell from a subshell, `(kill -QUIT $$)`: `sh` starts a background job with SIGQUIT ignored, so
`cmd & kill -QUIT $!` ends the job only when the signal arrives before the job has set that up
(measured under CPU load: 13 of 300 runs ignored it).
