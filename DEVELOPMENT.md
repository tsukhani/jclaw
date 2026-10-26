# Developing JClaw

This guide is for the Abundent team that builds JClaw. It covers how to set up a
development machine, run JClaw from source, test it, and find your way around the code.

- JClaw does not accept outside contributions. [CONTRIBUTING.md](CONTRIBUTING.md) explains why,
  and holds the team's working standards.
- Coding agents read [AGENTS.md](AGENTS.md), which holds the project's conventions with their reasons.
- To install and run a released JClaw, read [INSTALL.md](INSTALL.md).

## Contents

- [Prerequisites](#prerequisites)
- [Clone](#clone)
- [Dev Container (recommended)](#dev-container-recommended)
- [Development](#development)
- [Building a release bundle](#building-a-release-bundle)
- [Testing](#testing)
- [Running evals](#running-evals)
- [Directory structure](#directory-structure)
- [Architecture](#architecture)
- [Key principles](#key-principles)
- [Further reading](#further-reading)

---

## Prerequisites

Building from source needs more than running a bundle:

- **JDK 25+** (Zulu recommended)
- **Node.js** 24.11+ or 26+
- **pnpm** 12+, installed standalone rather than through corepack
- the **`play` CLI** from the [Abundent Play 1.x fork](https://github.com/tsukhani/play1)

The [Dev Container](#dev-container-recommended) installs all of them for you. The optional
system dependencies (Tesseract, Ollama, LM Studio, uv, ffmpeg) are the same as for a
released install: see [Optional extras](INSTALL.md#optional-extras).

---

## Clone

```bash
git clone https://bitbucket.abundent.com/scm/jclaw/jclaw.git
cd jclaw
```

Dependencies are automatically installed when you start with `jclaw.sh`.

---

## Dev Container (Recommended)

The fastest way to start coding without installing any of the [Prerequisites](#prerequisites) on your host machine is to use the included dev container. The `.devcontainer/Dockerfile` ships a pinned toolchain (Java 25, Python 3, Node 26, pnpm, the Play fork at the version recorded in `.play-version`, tesseract-ocr, ffmpeg, uv, Claude Code) on top of Ubuntu 26.04 LTS — all the prerequisites listed above, already installed.

### Host prerequisites

Just two things on your machine:

1. **Docker Desktop** (macOS/Windows) or **Docker Engine** (Linux) — the dev container runs in a Docker container, so the Docker daemon needs to be running.
2. **An IDE that supports the [Dev Containers spec](https://containers.dev/)** — any of:
   - [Cursor](https://cursor.com/) (built-in support)
   - [VS Code](https://code.visualstudio.com/) with the **Dev Containers** extension
   - [JetBrains Gateway](https://www.jetbrains.com/remote-development/gateway/) with the Dev Containers plugin
   - [GitHub Codespaces](https://github.com/features/codespaces) (cloud, no local Docker needed)

### First-time launch

After cloning, open the project in your IDE and trigger the "Reopen in Container" command:

| IDE | How to launch |
|---|---|
| **Cursor** | <kbd>Cmd</kbd>/<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>P</kbd> → `Dev Containers: Reopen in Container` |
| **VS Code** | Click the blue corner icon (bottom-left) → `Reopen in Container`, or <kbd>Cmd</kbd>/<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>P</kbd> → same command |
| **GitHub Codespaces** | Push your branch to GitHub → click `Code` → `Codespaces` tab → `Create codespace on main` |
| **JetBrains Gateway** | New Connection → Dev Containers → point at the local `jclaw` folder |

What happens automatically once you click:

1. Docker builds the image from `.devcontainer/Dockerfile` (~5–10 min the first time, cached on subsequent rebuilds).
2. Your local jclaw directory is bind-mounted into the container at `/workspaces/jclaw`. **Edits you make inside the container persist on your host** — the container is an environment, not a copy.
3. The IDE runs the `postCreateCommand` automatically — `./jclaw.sh setup`, then `./gradlew playClasspath` to resolve the Gradle dependency cache, then a Playwright Chromium install (`com.microsoft.playwright.CLI install chromium`). `setup` itself:
   - Validates all prerequisites (every check passes — they're baked into the image)
   - Wires git hooks (`.githooks/pre-commit`, `.githooks/pre-push`, `.githooks/post-checkout`)
   - Runs `./jclaw.sh init-worktree` (seeds `certs/.env` and `PLAY_TEST_PORT`)
   - Resolves the pinned pnpm version, which pnpm verifies against the lockfile's signed package-manager record
   - Runs `pnpm install` for the frontend
   - Installs BMAD into `.claude/skills/` and registers the graphify hook in `.claude/settings.json` when graphify is installed
   - Adds the canonical `github` remote (`https://github.com/tsukhani/jclaw.git`)
4. Recommended VS Code/Cursor extensions install (Volar, Java Pack, ESLint, Stylelint, YAML).
5. The IDE attaches to the container — your terminal, file explorer, and editor are now running inside it.

### Day-to-day inside the container

Everything works the same as it would on a native host. The container *is* a Linux dev box with the pre-installed toolchain:

```bash
./jclaw.sh --dev start    # dev mode (Play autoreload + Nuxt HMR)
./jclaw.sh test           # backend + frontend test suites
./jclaw.sh status         # check what's running
./jclaw.sh backup         # online backup of the database into data/backups/
./jclaw.sh stop
```

Ports `9000` (backend) and `3000` (Nuxt) are forwarded to your host automatically. Open `http://localhost:9000` and `http://localhost:3000` in your **host's browser** while the dev server runs inside the container. The Nuxt port is configured to auto-open the browser when it boots; the backend port emits a notification.

### Commits and `/deploy`

The pre-commit hook (frontend lint-staged) and pre-push hook (full test suite) work inside the container without any extra setup. Two nuances:

- **Signed commits** — `/deploy` produces signed commits and signed tags (`commit -S`, `tag -s`). Your host's GPG/SSH keys aren't visible inside the container by default. Two recovery options:
  1. **Easiest**: do `/deploy` from your host shell (open a host terminal, `cd` into the project, run the slash command). Code inside the container, deploy from outside.
  2. **More setup**: add a `mounts` block to `.devcontainer/devcontainer.json` to bind-mount `~/.ssh` and `~/.gnupg` into the container. Same end result, more configuration.
- **File ownership** — files written inside the container land on your host with the UID of the container's `agent` user, 1000 unless the image is built with `AGENT_UID`/`AGENT_GID`. On macOS this maps to your user automatically; on Linux you may see "owned by 1000" in `ls -l` if your host UID differs — rebuild with `AGENT_UID`/`AGENT_GID` set to yours to match. Usually harmless.

### Rebuilding

When the toolchain changes (e.g., a new Play version, a JDK bump, a base-image bump), you'll want a fresh build:

| IDE | How to rebuild |
|---|---|
| **Cursor / VS Code** | <kbd>Cmd</kbd>/<kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>P</kbd> → `Dev Containers: Rebuild Container` |
| **JetBrains Gateway** | Container settings → `Rebuild` |
| **CLI fallback** | `docker build -f .devcontainer/Dockerfile -t jclaw-devcontainer:latest .` from the repo root — the build context is the repo, since the Dockerfile copies `.play-version` (manual, you'd then need to update the IDE config to use the rebuilt image) |

Most rebuilds reuse cached apt + JDK + Node layers and only re-download what changed (e.g., the Play release zip if `.play-version` was bumped). Full cold rebuilds run ~5–10 min.

### Troubleshooting

- **"Docker not running"** — start Docker Desktop / `sudo systemctl start docker`.
- **First build hangs on apt-get** — your network is slow or the Ubuntu mirror is rate-limiting. Retry; layers are cached so progress isn't lost.
- **Postcreate fails on `./jclaw.sh setup`** — read the error; it'll point at the failing prereq. Open `.devcontainer/Dockerfile` to see what's installed; if a tool is missing, file an issue or patch the Dockerfile and rebuild.
- **Edits in the IDE don't appear on host** — verify you opened the folder via "Reopen in Container," not by mounting a Docker volume. The bind-mount is what makes the edits round-trip.
- **`docker rmi` to clean up** — `docker rmi jclaw-devcontainer:latest` (or the container image name your IDE assigns) removes the cached image. The next "Reopen in Container" rebuilds from scratch.

---

## Development

```bash
# Start both backend and frontend in dev mode
./jclaw.sh --dev start

# Stop
./jclaw.sh --dev stop

# Check status
./jclaw.sh --dev status

# View logs (tails both backend and frontend logs)
./jclaw.sh --dev logs

# Restart only the Play backend, leaving the Nuxt dev server running
./jclaw.sh --dev restart --backend-only
```
Default ports: backend on **:9000**, frontend on **:3000**. To change them, see
[Custom ports](INSTALL.md#custom-ports).

---

## Building a release bundle

Run `./jclaw.sh bundle` — it produces a self-contained `dist/jclaw-bundle.zip` that runs with only a Java 25 JRE. (`./jclaw.sh dist` builds the lighter `dist/jclaw.zip`, which needs a local Java 25, Gradle and Play fork install to run.) Unzip it wherever you want to install JClaw, then start it in place, as [Managing JClaw](INSTALL.md#managing-jclaw) describes.

---

## Testing

Run the backend and frontend test suites together and print a consolidated pass/fail summary:

```bash
./jclaw.sh test
```

This runs `play autotest` (backend JUnit + functional tests), `pnpm test` (frontend Vitest), and the three frontend quality gates — `stylelint`, `lint`, and `typecheck` — streaming each check's output live, and finishes with a five-line verdict like:

```
 backend  : PASSED  (47 classes, 26s)
 frontend : PASSED  (40 files, 199 tests, 5s)
 stylelint: PASSED  (3s)
 lint     : PASSED  (4s)
 typecheck: PASSED  (8s)
```

Each check writes its full output to `logs/test-<check>.log` (e.g. `logs/test-backend.log`, `logs/test-typecheck.log`) for post-mortem on failure. The command exits non-zero if any check failed, so it's safe to wire into git hooks or CI.

When a Playwright headless Chromium is cached (under `PLAYWRIGHT_BROWSERS_PATH`, else Playwright's per-OS cache), the backend run also includes the live-browser tests; otherwise it prints one line saying they stay skipped. Set `JCLAW_PLAYWRIGHT_TEST=0` to skip them.

A few more developer commands sit beside it:

```bash
./jclaw.sh diagnostics [--tests]   # compile errors (and test failures) as one JSON array
./jclaw.sh e2e                     # Playwright UAT suite against an already-running server
./jclaw.sh loadtest                # in-process load-test harness against /api/chat/stream
./jclaw.sh scrapetest              # scrape-ladder access rates over the CF-100 corpus (needs the backend)
./jclaw.sh grapheval run           # certify local Ollama decision models for graph extraction (needs the backend)
```

`grapheval` is one of the evals: see [Running evals](#running-evals).

### Git hooks

`./jclaw.sh setup` wires the three in-repo hooks from `.githooks/` once per clone. `pre-commit` runs lint-staged over staged `frontend/**` files; `pre-push` skips by itself when the push changes only documentation (`docs/`, `*.md`, `.github/`) or only the `application.version` line; otherwise it runs a wildcard-import check, `spotlessCheck` and `compileJava`, then `./jclaw.sh test`, before a push reaches the remote, and caches the tested SHA in `$GIT_DIR/jclaw-last-tested-sha`, so the second push in a two-remote deploy flow (origin + github) reuses the result instead of re-running the suite; `post-checkout` seeds a new worktree via `./jclaw.sh init-worktree`. To wire them by hand instead:

```bash
git config core.hooksPath .githooks
```

A human can bypass it for a one-off push (e.g. urgent hotfix) with `JCLAW_SKIP_TESTS=1 git push origin HEAD`; an agent never does.

---

## Running evals

Evals measure how well JClaw's agents behave. Tests check that the code works; evals check that an
agent, on a real model, does the right thing. The datasets live in [`evals/`](evals/README.md).

Agent behaviour is measured against datasets in `evals/suites/` — tool selection, structured output, and grounding, each a set of cases with deterministic pass criteria:

| Suite | What it checks |
| --- | --- |
| `tool-selection` | The agent picks the right tool, and only the tools the job needs. |
| `structured-output` | Asked for JSON, the agent returns JSON that validates: no prose around it, no invented fields, no numbers as strings. |
| `grounding` | The agent answers from the supplied source only, cites the right part of it, and says so when the source does not hold the answer. |

```bash
./jclaw.sh evals                                              # validate the dataset
./jclaw.sh evals --responses run.json --out reports/now.json  # score a recorded run
./jclaw.sh evals --responses run.json --baseline reports/last.json  # catch regressions
./jclaw.sh evals --capture run.json --agent __evaltest__ --suite tool-selection  # drive a live agent
```

Validating and scoring are offline — no backend, no model call, no database — and `play autotest` validates the dataset on every run, so a malformed suite fails the build. `--capture` is the exception: it drives real agent turns, so it needs the backend running and spends model calls. See [evals/README.md](evals/README.md) for the format and for why a suite is edited in place, with a content fingerprint guarding comparability between runs.

### Measuring an agent

Capture and scoring are two steps, so you can score one recorded run many times without paying for
the model again.

> **Caution:** Tools run for real during a capture. A case that makes the agent call `task_manager`
> creates a real scheduled task. Capture against `__evaltest__`, never against an agent you work with.

1. Start JClaw with a configured provider. Capture needs the backend.
2. Capture a run:

   ```bash
   ./jclaw.sh evals --capture run.json --agent __evaltest__ --suite tool-selection
   ```

   The first capture creates `__evaltest__`, with the provider and model of `main`. Each suite names
   the tools it needs, and capture grants the agent exactly those.
3. Score the run:

   ```bash
   ./jclaw.sh evals --responses run.json --out reports/now.json
   ```

4. To catch a regression, score the run against an earlier report:

   ```bash
   ./jclaw.sh evals --responses run.json --baseline reports/last.json
   ```

A **failed** case was measured and counts against the pass rate. An **errored** case was not
measured, so it is left out of the pass rate, but it still fails the run.

### Other evals

- **Memory graph** — `./jclaw.sh grapheval run` certifies local Ollama decision models for graph extraction.
- **Scraping** — `./jclaw.sh scrapetest` measures the scrape ladder's access rates over the CF-100 corpus in `evals/scrape/`.

Both need the backend running.

`grapheval run` stores each case in `evals/graph/cases.json` as a memory of `--agent`, scores it and deletes it afterwards, `--set heldout` measures `data/graph-eval/heldout.json` instead, and `--set sequences` replays `evals/graph/sequences.json` with no agent; every run spends model calls. `--decision-model` (repeatable, default the models selected in Settings), `--runs 1-3`, `--recall-floor`, `--concurrency 1-4`, `--timeout` and `--out FILE` tune it. `grapheval blind-sheet` and `grapheval heldout-sample --agent NAME --count N` write labelling inputs under `data/graph-eval/`. See [evals/graph/README.md](evals/graph/README.md).

---

## Directory Structure

```
jclaw/
├── app/                          # Application code
│   ├── controllers/              # HTTP controllers (Play 1.x pattern)
│   ├── models/                   # JPA domain entities
│   ├── services/                 # Business logic (incl. db-scheduler bridge)
│   ├── agents/                   # AI agent implementations
│   ├── channels/                 # Messaging channels (web, Telegram, Slack, WhatsApp)
│   ├── llm/                      # LLM provider drivers (OkHttp 5)
│   ├── tools/                    # Agent tool implementations
│   ├── memory/                   # Agent memory stores (JPA-backed)
│   ├── mcp/                      # Model Context Protocol client
│   ├── slash/                    # Slash-command handlers
│   ├── jobs/                     # Play @Every jobs + db-scheduler handlers
│   ├── views/                    # Groovy server templates
│   └── utils/                    # Utility classes
├── bin/                          # Dev tooling: diagnostics.mjs (+ tests), coverage-blend.mjs, JaCoCo jars, sign-release.sh, verify-debug-stripped.sh
├── certs/                        # Generated .env secret + optional TLS cert (gitignored)
├── conf/                         # Play configuration
│   ├── application.conf          # Main app config
│   ├── routes                    # URL routing
│   ├── play.plugins              # Play plugin registration
│   └── log4j2.xml                # Logging configuration
├── docs/                         # User guide + generated architecture docs (other subdirectories are gitignored, local to each clone)
├── frontend/                     # Nuxt 4 SPA (SPA-only; ssr: false)
│   ├── app.vue                   # Root component
│   ├── layouts/                  # Page layouts
│   ├── pages/                    # Nuxt file-based routes
│   ├── components/               # Reusable Vue components
│   ├── composables/              # Shared reactive state (useAuth, useEventBus, ...)
│   ├── middleware/               # Global route middleware (auth guard)
│   ├── public/                   # Static assets
│   └── nuxt.config.ts            # Nuxt configuration
├── evals/                        # Agent-behaviour eval datasets
├── modules/                      # Play modules (auto-managed)
├── public/                       # Static web assets
├── sidecar/                      # Python sidecars (asr, diarize, fetch, image, stealth, tts, video)
├── skills/                       # Skill definitions loaded by SkillLoader
├── test/                         # Unit and integration tests
├── tmp/                          # Play temp/runtime files
├── logs/                         # Application logs
├── README.md                     # Project overview
├── INSTALL.md                    # Installing and running JClaw
└── DEVELOPMENT.md                # This file
```

---

## Architecture

JClaw is Abundent's AI-powered automation platform, built from scratch in **pure Java** on a customized [Play Framework 1.x](https://github.com/tsukhani/play1) foundation. It draws ideas and feature designs from three predecessor projects:

- **[OpenClaw](https://github.com/tsukhani/openclaw)** (Node.js/TypeScript) — agent orchestration, memory system, conversational AI patterns
- **[JavaClaw](https://github.com/jobrunr/javaclaw)** (Spring Boot) — job scheduling, background task processing, browser automation
- **[Hermes](https://github.com/NousResearch/hermes-agent)** (Python) — cron/task scheduling parity, subagent delegation patterns

The implementation is entirely original — no code is shared with any of them. JClaw is built on lean library primitives (OkHttp 5, db-scheduler, ProcessBuilder, virtual threads, JPA) with no Spring, no heavy framework bloat, and no Node.js in the application itself — the one piece that runs on Node.js is the browser tool's Playwright driver, which a bundle or Docker install downloads on first use, and the only Python is in the optional local sidecars the JVM spawns (ASR, diarization, TTS, image, video, fetch, stealth). The result is a leaner, faster, more maintainable platform for building AI agents and automation workflows.

### Features

- 🤖 **Agent System** — Conversational AI agents with memory and context
- 💬 **Channels** — Web chat plus per-agent Telegram, Slack and WhatsApp bindings (the official Cloud API or unofficial WhatsApp Web)
- ⚡ **Job Scheduling** — Persistent cron & scheduled tasks via db-scheduler, with automatic retries and crash recovery
- 🔧 **Pure Java** — The server is all Java; Python is needed only by the optional local sidecars, and Node.js only to build the SPA and to run the browser tool's Playwright driver
- 📦 **Built-in Frontend** — Nuxt 4 SPA (Vue 3 + TypeScript); chat and the conversation viewer render Markdown, typeset math with KaTeX and draw Lewis electron-dot structures from SMILES
- 🧭 **Model Router** — Choose `router/auto` wherever a model is picked: each prompt is classed as chat, summarize, agentic, reasoning or coding, sent to the first usable model on that class's list, and given a reasoning effort of its own; the lists live under **Settings → Agents & Automation → Model Router**
- 🌐 **Browsing & Scraping** — A browser tool whose every Chromium connection is screened inside the JVM, so a page cannot reach loopback, private or other internal addresses, with TypeSafe AI's Jev as an optional operator-selected engine; large site crawls run as background scrape jobs you start, pause, resume and read from the Scrapes page
- 🔌 **Plugin Architecture** — Modular, extensible design
- 🧠 **Memory & Context** — Persistent conversations across sessions
- ⏰ **Tasks & Reminders** — User-facing scheduled tasks and reminders, managed from the Tasks and Reminders pages
- 🧩 **Skills, MCP & Subagents** — Reusable skills, MCP server tools, and subagent delegation including ACP coding harnesses
- 📡 **OpenTelemetry** — Opt-in OTLP traces and metrics with GenAI spans per model call, reconfigurable live without a restart
- 🎙️ **Voice, Image & Video** — Real-time voice mode, local ASR/TTS and image/video generation through the optional Python sidecars
- 🗂️ **Apps** — Static mini-apps under `public/apps`, managed from the Apps page and installable as desktop PWAs
- 📁 **Workspace Manager** — Browse each agent's workspace with sizes from the Agents page, download a file or a folder as a zip, back up the whole workspace, and delete entries, with Standing Orders files protected
- 🔔 **Operator Alerts** — Name one channel under **Settings → System → Alerts** and JClaw messages you there when an LLM provider or MCP server circuit breaker opens or recovers, an MCP server is moved to the Handshake protocol, stored credentials do not decrypt at boot, or a recurring task's occurrence fails for good
- 🛡️ **Tool Approvals & Sandboxing** — Dangerous actions (shell commands, coding-harness launches) ask you for approval; opt-in OS sandboxing (`shell.sandbox`, `subagent.acp.sandbox`) confines what those processes can write and read
- 🚀 **Lightweight** — Minimal resource footprint, fast startup

### Backend (Play 1.x + Java)

- **Models**: JPA entities with Play's model pattern
- **Controllers**: RESTful API endpoints
- **Services**: Business logic, without a dependency-injection container
- **Agents**: Conversational AI with memory/context persistence
- **Jobs**: Internal maintenance (cleanup, probes, boot checks) on Play's built-in `@Every` / `@OnApplicationStart` job system
- **Scheduling**: User-facing Tasks — immediate, scheduled, interval, and cron — run on [db-scheduler](https://github.com/kagkarlsson/db-scheduler), persisted in a `scheduled_tasks` table with atomic row-claim, pluggable retries, and heartbeat-based dead-execution recovery

### Frontend (Nuxt 4)

- **Framework**: Vue 3 + TypeScript + Nuxt 4 (SPA mode, `ssr: false`)
- **UI components**: [shadcn-nuxt](https://www.shadcn-vue.com/) on [Reka UI](https://reka-ui.com/) primitives, with `class-variance-authority` + `tailwind-merge` for variants
- **Styling**: Tailwind CSS v4 (via `@tailwindcss/vite`), Lucide + Heroicons icons, Inter variable font
- **State**: Composables backed by `useState` (no Pinia); `@vueuse/core` utilities
- **Data & rendering**: `@tanstack/vue-table` for tables, `marked` + `dompurify` for safe Markdown, `katex` for math, `zod` for validation
- **API**: Cookie-session authenticated `$fetch` to the Play backend, proxied via Nitro in dev
- **Tooling**: Vitest + `@nuxt/test-utils` + jsdom, Playwright e2e, ESLint + Stylelint, `vue-tsc` typecheck, a11y via `vue-axe`/`axe-core`

---

## Key Principles

1. **Java-First** — Server-side logic is Java, not Node; Python only in the optional local sidecars.
2. **Minimal Dependencies** — Only bring in what we absolutely need.
3. **Memory & Context** — Agents remember. Context persists. Conversations flow.
4. **Async by Default** — Jobs run in background. APIs are non-blocking.
5. **Modular Skills** — Agents can automatically create, share, and chain skills. Skill primitives are reusable across agents and shareable with other JClaw users.

---

## Further reading

- [Play Framework 1.x](https://github.com/tsukhani/play1)
- [OpenClaw Reference](https://docs.openclaw.ai)
- [Nuxt 4 Docs](https://nuxt.com/docs)
- [JavaClaw Concepts](https://github.com/jobrunr/javaclaw)
- [docs/README.md](docs/README.md) — every kind of documentation in this repository, and how each is kept accurate
