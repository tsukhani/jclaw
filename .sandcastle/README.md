# AFK factory

A local harness on [Sandcastle](https://github.com/mattpocock/sandcastle) that works Jira stories unattended. It picks
`afk`-labelled stories in To Do from the active sprint and, for each one, in its own Docker sandbox:
implement → full-suite gate (the harness runs it, baselined against `main`, with up to two repair rounds) → review agent →
brief. It then copies the branch `agent/<KEY>` into this checkout, moves the ticket to Review and comments the brief. It
never pushes: you merge the branch into `main` and ship it with `/deploy`.

## Layout

| Where | What |
|---|---|
| `.sandcastle/` (this directory) | Harness code, prompts, and the gateway |
| `~/.jclaw-factory/` (`FACTORY_HOME`) | Everything the harness writes: the clone it works in (`jclaw/`), `logs/`, `state/`, the Gradle seed, `lessons.md`, and `.env` holding the model credential |

Nothing the harness writes lives in the checkout, because `/deploy` stages the whole working tree.

## Isolation

Sandboxes use the devcontainer image and sit on the `jclaw-factory` Docker network, which has no route out. Their only
exit is the gateway container (`gateway/gateway.mjs`, a distroless Node image). It forwards model calls, injecting the
credential so that no sandbox ever holds it, and proxies HTTPS to the hosts in `gateway/egress-allowlist.txt`. It reads
the credential from `~/.jclaw-factory/.env`, mounted read-only, not from its environment, which Docker would keep in
the container's config for `docker inspect` to show.
The gateway reaches the internet through its own network, `jclaw-factory-egress`, which no other container joins. On
Docker's default bridge, any container could reach the gateway's ports and have your credential attached to its model
calls. `gateway-check.ts` checks this from a container on the default bridge. The gateway forwards model calls only
(`POST /v1/messages` and `count_tokens`); every other Anthropic endpoint is refused.

Inside a sandbox, the clone's `.git` is read-only apart from what a commit writes: objects, the `agent/` branches and
the sandbox's own worktree directory. Git on the Mac honours `.git`'s hooks, config and attributes whenever Sandcastle
or the harness runs it in the clone, so a sandbox that could write them could run code outside Docker. As a second
guard, git on the Mac runs with hooks and fsmonitor switched off. Each sandbox is capped at 6 GB of memory and 8192
processes.

## Setup on a Mac

Prerequisites: Docker Desktop running, Node 24 or newer, a JClaw checkout, and a Jira personal access token.

1. Create `~/.jclaw-factory/.env` holding the model credential: `CLAUDE_CODE_OAUTH_TOKEN=…` (from `claude setup-token`) or
   `ANTHROPIC_API_KEY=…`.
2. Create `~/.jclaw-factory/jira.env` holding `JIRA_URL=…` and `JIRA_PERSONAL_TOKEN=…`, the only place the harness reads
   Jira access from. The token stays out of `.env` because the gateway mounts that file, so no container ever holds
   it.
3. Run `.sandcastle/install-agent.sh`. It checks the prerequisites, builds the sandbox image `jclaw-devcontainer:local`
   if it is missing (several minutes, once), installs dependencies, and loads the LaunchAgent `com.jclaw.factory`. The
   agent starts the harness at login and restarts it if it exits.

On first start the harness clones your checkout into `~/.jclaw-factory/jclaw`, creates the gateway, and seeds the
sandboxes' Gradle cache from `~/.gradle`. Without that cache, each sandbox downloads its dependencies through the
gateway. Re-run the installer after moving the checkout or changing your Node install, because the agent records both
paths.

## Several developers

Each developer's harness takes unassigned `afk` stories and its own. It claims a story by assigning it to its Jira user,
because every JCLAW transition is global, so moving a story locks nothing. The claim is best effort: it re-reads the
assignment two seconds later before moving the story to In Progress. A story's branch lives only on the machine that
built it, so a rejected story stays assigned to that developer and only their harness reworks it. Another harness that
picks it up blocks it with a note saying so. Run one harness per Jira user.

## Operating

- **Watch:** `tail -f ~/.jclaw-factory/logs/factory.log`. Each story's phases are logged under `~/.jclaw-factory/logs/<KEY>-*`.
- **Pause:** `docker stop jclaw-factory-gateway`. No new stories start, and stories already running fail. Resume with
  `docker start jclaw-factory-gateway`. The harness never restarts a gateway you stopped.
- **Stop the harness:** `.sandcastle/install-agent.sh --remove`. Stories it was running are interrupted: they carry the
  `afk-running` label, and the next start moves them back to To Do and resumes them from their branch.
- **Review:** merge `agent/<KEY>` into `main`. When a branch changes files that run on your Mac once merged (git
  hooks, build, install and CI scripts, package manifests, this harness, `AGENTS.md` or `CLAUDE.md`), the review
  comment lists them under "(!) Runs on your Mac once merged": read those line by line. Mark the story Done only once it
  is merged, because Done is what lets the stories it blocks start.
- **Reject:** move the story back to To Do with a comment saying what to change. The next round reworks it on the same
  branch. The general rule behind your comment goes into `~/.jclaw-factory/lessons.md`, which every prompt includes;
  promote a lesson into `AGENTS.md`, or delete it there.
- **Blocked:** a story the factory gave up on carries `afk-blocked` and a comment explaining why. Remove the label to retry.

Stories wait while a blocker is not Done, and while the files they are predicted to change overlap a branch awaiting
review or a story already running.

## Settings

| Variable | Default | |
|---|---|---|
| `FACTORY_MAX_PARALLEL` | 2 | Stories at once; each sandbox peaks near 5 GB |
| `FACTORY_POLL_SECONDS` | 120 | How often Jira is polled |
| `FACTORY_MODEL` | `claude-opus-5-5` | |
| `FACTORY_HOME` | `~/.jclaw-factory` | |
| `FACTORY_TICKET` | | Run these keys (comma-separated) for one round, then exit |
| `FACTORY_PLAN_ONLY` | | Print the plan for one round and exit, changing nothing |

## Checks

- `npm run check`: typecheck plus the offline logic checks.
- `npx tsx gateway-check.ts`: live check of the gateway and egress. It spends one small model call.
- `npx tsx sandbox-check.ts`: live check of a sandbox's `.git` lockdown and limits, and of hook-free git on the Mac.
