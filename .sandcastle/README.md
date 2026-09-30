# AFK factory

An unattended software factory for JClaw, built on [Sandcastle](https://github.com/mattpocock/sandcastle). It picks up
Jira stories labelled `afk` in the active sprint, has a coding agent implement each one in its own isolated container,
runs the full test suite itself, has a second agent review the work, and hands you a local branch `agent/<KEY>` with a
brief on the ticket. It never pushes and never merges. You review the branch, merge it into `main` and ship it with
`/deploy`.

## Architecture

```
                         Jira (JCLAW, active sprint)
                              ▲    intake, claims, comments, transitions
┌─────────────────────────────┼──────────────────────────── your Mac ─┐
│  Harness  (LaunchAgent → run.sh → main.ts)                          │
│    plans rounds · drives Sandcastle · runs the gate · publishes     │
│                                                                     │
│    ~/.jclaw-factory/jclaw ── agent/<KEY> ──►  your checkout         │
│    (clone it works in)        (copied when done; you merge)         │
│             │ docker                                                │
│  ┌──────────┼─────────────────────────────── Docker Desktop VM ──┐  │
│  │          ▼                                                    │  │
│  │   sandbox        sandbox        planner    (devcontainer      │  │
│  │   story A        story B                    image, one each)  │  │
│  │      └──────────────┴──────────────┘                          │  │
│  │                     │  jclaw-factory: internal, no route out  │  │
│  │                     ▼                                         │  │
│  │               gateway (distroless)                            │  │
│  │                     │  jclaw-factory-egress: gateway only     │  │
│  └─────────────────────┼─────────────────────────────────────────┘  │
└────────────────────────┼────────────────────────────────────────────┘
                         ▼
     api.anthropic.com (POST /v1/messages, credential injected)
     allowlisted package hosts (Gradle, Maven, npm, PyPI, GitHub), HTTPS only
```

Three components, each with one job and one trust level. The harness on your Mac holds the Jira access and writes to
your checkout. The sandboxes run the agents and the code they write, and hold nothing. The gateway holds the model
credential and is the sandboxes' only way out.

### Harness

TypeScript that runs on your Mac: `main.ts` for the loop, `factory.ts` for the Docker side, `jira.ts` and `plan.ts`. The
LaunchAgent `com.jclaw.factory` keeps it running through `run.sh`, which reinstalls its dependencies whenever the
lockfile changes. Every two minutes it:

1. Polls Jira for `afk` stories in To Do, and keeps those whose blockers are Done.
2. Rebuilds the sandbox image if `main` has moved, and has a planner agent predict each story's files and decide
   whether it needs BMAD. A story waits while its files overlap a branch awaiting review or a story already running.
3. Claims a story by assigning it to its own Jira user, moves it to In Progress, and runs it in a fresh sandbox:
   - **Implement**, or **rework** if you sent it back. A story that needs BMAD is **specced**, then **built**, by
     BMAD instead (see [BMAD stories](#bmad-stories)).
   - **Gate:** the full suite. A failing class is re-run alone. If it passes alone, or also fails on `main`, it is not
     counted, but the brief names it. There are up to two repair rounds and a 30-minute deadline.
   - **Review** by a second agent, then a **brief**.
4. Copies `agent/<KEY>` into your checkout, moves the ticket to Review and posts the brief.

**Why it is necessary.** Everything that needs trust or judgement stays out of the model's hands. The agent never sees
a Jira token and cannot push or touch your checkout: it produces commits, and the harness decides what becomes of
them. The harness runs the gate itself because a ten-minute suite outlasts an agent's idle timeout, and because an
agent should not grade its own work. Deterministic code, not a model, handles ordering, claiming, recovery and
bookkeeping: blocked and overlapping stories wait, and an interrupted story is sent back to To Do and resumed from its
branch on the next start.

### Sandboxes (the devcontainer image)

One Docker container per story, and one for the planner, created by Sandcastle from `jclaw-devcontainer:local`. That
image is built from `.devcontainer/Dockerfile`, the same one the dev container and Jenkins use: JDK 25, Node 26, pnpm,
uv, Claude Code and the Play fork. The story's worktree is mounted at `/home/agent/workspace`, and Claude Code runs
there as the unprivileged user `agent`.

**Why it is necessary.** The agent runs code it wrote itself or downloaded, steered by ticket text anyone with Jira
access can edit. A container keeps that code off your Mac. A fresh container per story means parallel stories cannot
collide on ports, the test database or files. Sharing the dev container's image means a green gate here is green in
your dev container and in CI. It also means one toolchain to maintain, where a separate factory image would drift.

**How it is locked down:**
- **The clone's `.git` is read-only**, apart from what a commit writes: objects, the `agent/` branches and the
  sandbox's own worktree directory. Git on the Mac honours `.git`'s hooks, config and attributes whenever Sandcastle
  or the harness runs it in the clone, so a sandbox that could write them could run code outside Docker. As a second
  guard, the harness runs git on the Mac with hooks and fsmonitor switched off.
- **Limits:** no root, no sudo, no setuid binaries, no Docker socket, 6 GB of memory and 8192 processes.
- **Network:** the `jclaw-factory` network is internal. It has no DNS and no route out, and the Mac's
  `host.docker.internal` is unreachable. The only way out is the gateway.

### Gateway

A small Node server (`gateway/gateway.mjs`) in a distroless container: no shell, no package manager, non-root,
read-only, no Linux capabilities. It has two ports:
- **`:8080`, the model route.** It forwards `POST /v1/messages` and `count_tokens` to Anthropic, attaching the real
  credential. Sandboxes carry only a placeholder. Every other Anthropic endpoint is refused.
- **`:3128`, the egress proxy.** It tunnels HTTPS to the hosts in `gateway/egress-allowlist.txt` and refuses
  everything else, `api.anthropic.com` included, so the model route is the only way to reach the model.

It reads the credential from `~/.jclaw-factory/.env`, mounted read-only, rather than from its environment. Docker keeps
a container's environment in its config, where `docker inspect` would show it. It reaches the internet through its own
network, `jclaw-factory-egress`, which no other container joins. On Docker's default bridge, any container on the
machine could have used its model route and billed your credential. It restarts on its own after a crash or a Docker
restart. If you stop it, the harness leaves it stopped: stopping it pauses the factory.

**Why it is necessary.** A sandbox needs exactly two things from the internet: the model and package downloads.
Without the gateway, one of two things goes wrong:
- The credential sits inside the sandbox, where a prompt-injected agent could read it and send it anywhere.
- The sandbox gets open internet access, and any file it can read can be sent out.

The gateway holds the credential and allows only those two paths. Everything that leaves goes through it, and it logs
every decision (`docker logs jclaw-factory-gateway`).

### Where things live

| Where | What |
|---|---|
| `.sandcastle/` (this directory) | Harness code, prompts, BMAD overrides, gateway code and allowlist, checks, installer |
| `~/.jclaw-factory/` (`FACTORY_HOME`) | Everything the harness writes: the clone it works in (`jclaw/`), `logs/`, `state/`, the Gradle and BMAD seeds, `lessons.md` |
| `~/.jclaw-factory/.env` | The model credential. It is mounted into the gateway only. |
| `~/.jclaw-factory/jira.env` | Jira access. Only the harness on your Mac reads it. |
| `~/.jclaw-factory/settings.env` | Your [settings](#settings), optional. Only the harness on your Mac reads it. |

Nothing the harness writes lives in the checkout, because `/deploy` stages the whole working tree.

## A story's path

```
To Do (afk) ─► claimed, In Progress + afk-running ─► implement / rework ─► gate ─► review ─► brief
     ▲                                                                                   │
     │ you send it back with a comment                          agent/<KEY> copied to your checkout
     │                                                                                   ▼
     └──────────────────────────────── Review ◄───────────────────── brief posted on the ticket
                                          │
                              you merge it, then mark it Done
```

- **When it fails:** a story the factory gives up on gets the `afk-blocked` label and a comment saying why. Remove the
  label to retry.
- **When the harness stops:** an interrupted story still has `afk-running`. The next start sends it back to To Do,
  and its branch keeps what it had committed.

### BMAD stories

The planner decides which stories BMAD builds, as it predicts their files. BMAD takes about twice the agent time, so the
planner chooses it for a ticket that leaves work to judgement: vague or open acceptance criteria, a spike or research,
a design decision the rest depends on, or a new component or a change across layers. A precise, mechanical ticket or a
small bug with a clear fix is built plain. Labels override it: `bmad` forces BMAD and `no-bmad` forbids it, and
`no-bmad` wins when a ticket has both, because the harness adds `bmad` itself when BMAD starts. That way a ticket's
labels always show how it was built. The plan log line and the review comment give the planner's reason, and each
story's `report.json` records it. A story resumed from commits on its branch keeps the way it was first built.

BMAD's unattended `bmad-build-auto` then does the implementing, in two runs in the same sandbox:

1. **Spec:** it reads the ticket and the code and writes a ready-for-development story spec: tasks with file paths,
   Given/When/Then acceptance criteria and a code map. It halts there. If it can read the ticket more than one way, the
   story is blocked with its questions in the comment, before any code is written. Answer them on the ticket, then
   remove `afk-blocked`.
2. **Build:** it implements the spec in a subagent, runs the spec's verification, reviews the diff with four reviewer
   subagents, fixes what they find and commits.

The harness then gates, reviews and briefs the branch as it does any other. BMAD's spec, with its review's triage and
deferred findings, is saved as `~/.jclaw-factory/logs/<KEY>-spec.md`, and the review comment names it. A rejected
`bmad` story is reworked with the ordinary rework prompt.

BMAD is gitignored, so the sandboxes get the install `./jclaw.sh setup` made in your checkout. The harness copies it,
with `.sandcastle/bmad/bmad-build-auto.toml`, into `~/.jclaw-factory/bmad-seed`, which every sandbox mounts read-only.
That file adds the factory's rules for BMAD's runs and its implementation subagent: stay on the branch, never run the
full suite, no push, no Jira. Without an install in the checkout, a `bmad` story is blocked, and other stories are
unaffected.

## Security model

**What an agent can reach:**
- the model, through the gateway
- allowlisted package hosts
- its own worktree
- commits on its own `agent/` branch

**What it cannot reach:**
- any credential
- the internet beyond that allowlist
- your Mac, or the clone's hooks and config
- `main`
- Jira, and your checkout

**Risks that remain, and how they are handled:**
- **Merging runs its code.** Once you merge a branch, its code runs on your Mac through hooks, build scripts and the
  suite `/deploy` runs. The review comment lists every changed file of that kind under
  "(!) Runs on your Mac once merged", and you should read those line by line.
- **A sandbox can move another story's `agent/` branch** in the clone, because commits need that ref directory
  writable. `main` stays out of reach, and a tampered branch shows up in its review.
- **Sandboxes can reach each other** on the factory network. Cutting that would cut them off from the gateway too.
- **uv and Claude Code** install their latest release whenever the image rebuilds, so a bad upstream release would
  arrive without review.
- **An agent can spend your model quota** through the model route. That comes with letting it think.

## Following main

The factory takes its versions from main:
- **The sandbox image** is rebuilt from main's `.devcontainer/Dockerfile` before a round plans or starts stories,
  whenever main has moved.
  - The Dockerfile reads the Play fork from `.play-version` and pnpm from `frontend/package.json`.
  - It pins the base image by digest, for Renovate to move.
  - It installs the latest uv and Claude Code whenever one of those inputs changes.
  - If a build fails, stories wait until main is fixed (`~/.jclaw-factory/logs/image-build.log`).
- **The gateway** is replaced when its image pin, code or allowlist changes on main. Renovate moves its image digest.
- **The harness itself** exits, while idle, when `.sandcastle/` changes on main, and launchd starts it again on the new
  code. Sandcastle bumps are reviewed rather than automerged: it ships breaking changes as patches before 1.0, and the
  `.git` lockdown wraps its Docker provider.

BMAD is the exception: it follows your checkout, not main. The BMAD seed is replaced, while idle, when
`./jclaw.sh setup` changes the install or `.sandcastle/bmad/bmad-build-auto.toml` changes.

## Setup on a Mac

Prerequisites: Docker Desktop running, Node 24 or newer, a JClaw checkout, and a Jira personal access token.

1. Create `~/.jclaw-factory/.env` holding the model credential: `CLAUDE_CODE_OAUTH_TOKEN=…` (from `claude setup-token`) or
   `ANTHROPIC_API_KEY=…`.
2. Create `~/.jclaw-factory/jira.env` holding `JIRA_URL=…` and `JIRA_PERSONAL_TOKEN=…`. The token stays out of `.env`
   because the gateway mounts that file, so no container ever holds it.
3. Run `.sandcastle/install-agent.sh`. It checks the prerequisites, builds the sandbox image if it is missing (several
   minutes, once), installs dependencies, and loads the LaunchAgent `com.jclaw.factory`.

On first start the harness clones your checkout into `~/.jclaw-factory/jclaw`, creates the gateway, and seeds the
sandboxes' Gradle cache from `~/.gradle`. Without that cache, each sandbox downloads its dependencies through the
gateway. Re-run the installer after moving the checkout or changing your Node install, because the agent records both
paths.

## Several developers

Each developer's harness takes unassigned `afk` stories and its own. It claims a story by assigning it to its own Jira
user, because every JCLAW transition is global, so moving a story locks nothing. The claim is best effort: the harness
re-reads the assignment two seconds later before it moves the story to In Progress.

A story's branch lives only on the machine that built it. So a rejected story stays assigned to that developer, and
only their harness reworks it. Any other harness that picks it up blocks it with a note saying so. Run one harness per
Jira user.

## Operating

- **Watch:** `tail -f ~/.jclaw-factory/logs/factory.log`. Each story's phases are logged under `~/.jclaw-factory/logs/<KEY>-*`.
- **Pause:** `docker stop jclaw-factory-gateway`. No new stories start, and stories already running fail. Resume with
  `docker start jclaw-factory-gateway`.
- **Stop the harness:** `.sandcastle/install-agent.sh --remove`.
- **Review:** merge `agent/<KEY>` into `main`. Read any file listed under "(!) Runs on your Mac once merged" line by line.
  The agent's commits are unsigned, because the sandbox holds no key, and GitHub's `main` refuses unsigned commits. So
  re-sign them as you merge: `git rebase --force-rebase --gpg-sign main agent/<KEY>`, then
  `git merge --no-ff agent/<KEY>`. Mark the story Done only once it is merged, because Done is what lets the stories it
  blocks start.
- **Reject:** move the story back to To Do with a comment saying what to change. The next round reworks it on the same
  branch. The general rule behind your comment goes into `~/.jclaw-factory/lessons.md`, which every prompt includes.
  Promote a lesson into `AGENTS.md`, or delete it there.

## Settings

The first four go in `~/.jclaw-factory/settings.env`, one `KEY=VALUE` per line, because launchd starts the harness
without your shell's environment. An idle harness restarts itself when that file changes, so an edit applies once no
story is running, and the log's `watching the active sprint` line shows the values in effect. The same variable set in
the environment wins over the file. The file refuses any other key, and a number that is not valid stops the harness
with the reason in the log. A model name is checked only when the first story runs.

| Variable | Default | |
|---|---|---|
| `FACTORY_MAX_PARALLEL` | 2 | Stories at once; each sandbox peaks near 5 GB |
| `FACTORY_CPUS` | 6 | CPU quota per sandbox, at most the Docker VM's CPU count |
| `FACTORY_POLL_SECONDS` | 120 | How often Jira is polled |
| `FACTORY_MODEL` | `claude-opus-5-5` | |
| `FACTORY_HOME` | `~/.jclaw-factory` | Environment only; the LaunchAgent records it at install |
| `FACTORY_TICKET` | | Environment only: run these keys (comma-separated) for one round, then exit |
| `FACTORY_PLAN_ONLY` | | Environment only: print the plan for one round and exit, changing nothing |

## Checks

- `npm run check`: typecheck plus the offline logic checks.
- `npx tsx sandbox-check.ts`: live check of a sandbox's `.git` lockdown and limits, and of hook-free git on the Mac.
- `npx tsx gateway-check.ts`: live check of the gateway, egress and route allowlist. It spends one small model call.
- `npx tsx bmad-check.ts`: live check that a `bmad` story's sandbox gets BMAD, that BMAD renders there with the factory's
  overrides, and that the tree stays clean. No model call.
