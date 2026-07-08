# JCLAW-671 — OS-level sandboxing for coding-harness processes (spike)

**Date**: 2026-07-08 · **Verdict: FEASIBLE on macOS via `sandbox-exec`, recommended as an opt-in config, ~1–2 day story.**

## What was prototyped

A `sandbox-exec` profile wrapping the harness command, measured empirically
(all five checks pass on macOS 15 / Darwin 25):

| Check | Result |
|---|---|
| Write inside `coding/<slug>/` session dir | ✅ allowed |
| Write anywhere else (e.g. `~/pwned.txt`) | ✅ blocked (`Operation not permitted`) |
| Read `~/.ssh` | ✅ blocked |
| Read `~/.claude.json` | ✅ blocked (base profile) |
| Normal reads (repo files, binaries) | ✅ work |
| `claude --version` under the strict profile | ✅ launches |

Profile shape (the working prototype):

```scheme
(version 1)
(allow default)
(deny file-write* (subpath "/"))
(allow file-write* (subpath "<session dir>"))
(allow file-write* (subpath "/private/tmp"))
(allow file-write* (subpath "/private/var/folders"))
(allow file-write* (subpath "/dev"))
(deny file-read* (subpath "~/.ssh"))
```

## Minimum surface a real harness needs (enumerated)

- **claude**: write access to `~/.claude/` (session/state) and read of
  `~/.claude.json` (its own credentials) for a full `-p` run; `--version`
  works without either. The Keychain path may also need allowing for
  API-key auth setups.
- **pi**: analogous state dir (verify at implementation; the profile is
  per-adapter anyway).
- Both need broad *read* (node/toolchains) — the profile allows reads by
  default and denies only listed secrets, which is the right polarity for
  a coding tool.

## Honest limitations

1. **The harness's own credentials cannot be hidden from it** — a full run
   requires reading its own config. The sandbox therefore protects *other*
   secrets (`~/.ssh`, other apps' tokens, arbitrary home files), not the
   harness's API key. That is still most of the win.
2. `sandbox-exec` is deprecated-but-functional (Apple uses the underlying
   Seatbelt everywhere; the CLI has survived every recent macOS). Risk:
   a future removal forces the container path.
3. Network egress is unrestricted in this profile (the harness needs its
   API). A `(deny network*)`+allowlist variant is possible but brittle.
4. Linux needs the bubblewrap equivalent (untested here — no Linux host);
   the profile concept maps 1:1 (`--bind` session dir, `--ro-bind` rest).

## Recommendation

Implement as **opt-in config** (`subagent.acp.sandbox=true`): when enabled
on macOS, `SubagentSpawnTool` wraps the argv in
`sandbox-exec -f <generated profile> …`, generating the profile per session
with the `coding/<slug>` dir and the adapter's declared state-dir
allowances. Off by default until bubblewrap parity lands (a config that
only works on one OS should say so loudly). Estimated 1–2 days including
tests; independent of JCLAW-669/670, which remain the first line.

## Alternatives considered

- **Per-session containers** (docker run, workspace-mount-only): strongest
  boundary, but heavyweight — image maintenance, harness credentials must
  be injected, macOS Docker filesystem performance penalizes coding runs.
  Right answer only if/when JClaw targets server deployment.
- **Dedicated OS user**: real isolation but painful interactive setup
  (sudoers, workspace group permissions) — wrong fit for Personal Edition.
