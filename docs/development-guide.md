# Development Guide

How to set up, run, test, and iterate on jclaw locally.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 25+ | Zulu recommended (Docker and Jenkins both pin to Azul Zulu 25). |
| Play Framework CLI | 1.11.x (custom fork) | Install from [github.com/tsukhani/play1](https://github.com/tsukhani/play1). `play` must be on `$PATH`. |
| Python | 3.9+ (3.12 recommended) | Play's CLI wrapper is a Python script. |
| Node.js | 20+ (24 recommended) | For the Nuxt frontend. |
| pnpm | 10.33.0 | Pinned in `frontend/package.json` + Jenkins. |

## Clone + bootstrap

```bash
git clone https://bitbucket.abundent.com/scm/jclaw/jclaw.git
cd jclaw
```

The `jclaw.sh` launcher installs Play deps (via the gradle `org.playframework.play1` plugin from `/opt/play1`) and frontend deps on first run. Manual:

```bash
# Backend deps (reads build.gradle.kts)
./gradlew classes

# Frontend deps
cd frontend && pnpm install --frozen-lockfile && cd ..
```

## Run locally

### Unified launcher (preferred)

```bash
./jclaw.sh --dev start      # starts backend on :9000 AND frontend on :3000
./jclaw.sh --dev stop
./jclaw.sh --dev status
./jclaw.sh --dev logs
```

Custom ports: `./jclaw.sh --dev --backend-port 8080 --frontend-port 4000 start`.

### Manual (two terminals)

```bash
# Terminal 1 — backend on :9000
play run

# Terminal 2 — frontend on :3000 with /api proxy → :9000
cd frontend && pnpm dev
```

Browse `http://localhost:3000`. API calls are proxied via Nitro `devProxy`.

## Testing

**Backend tests: always use `play auto-test`, NOT `play test`.** `play test` is interactive and requires a browser session; it hangs CI and local automation. `play auto-test` runs headless.

```bash
# Backend — JUnit 5, 44 test classes under test/
play auto-test

# Frontend — Vitest unit tests (MUST run after any frontend/ change)
cd frontend && pnpm test

# Frontend — Playwright E2E
cd frontend && pnpm test:e2e
cd frontend && pnpm test:e2e:ui        # headed + UI mode
cd frontend && pnpm test:e2e:headed    # slow-mo, single worker
```

Test DB: `%test.db.url=jdbc:h2:mem:play;MODE=MYSQL` (set in `conf/application.conf`). Each test run boots a fresh in-memory DB.

## Post-coding workflow (required per repo convention)

Every code change must go through this order — do not skip steps:

1. **Write tests first** (TDD — see CLAUDE.md & user feedback memory).
2. `play auto-test` — backend green.
3. `cd frontend && pnpm test` — frontend green (required even if changes look backend-only if any file under `frontend/` was touched).
4. Bump `application.version` in `conf/application.conf`.
5. `git commit` and push to **both remotes**: `origin` (Bitbucket) and `github` (per feedback memory).

## Codebase conventions

- **Play 1.x static-method pattern:** controllers, services, and jobs are static methods on classes. No Spring, no DI container.
- **Frontend state:** `useState` + composables. No Pinia today.
- **Transactions:** `services.Tx.run(Runnable/Callable)` wraps `JPA.withTransaction`. After a nested `Tx.run`, re-fetch entities — they may be detached.
- **JSON:** use `utils.GsonHolder.INSTANCE` — a single project-wide Gson.
- **Enums in DB:** string-backed with manual conversion (see `ChannelType`, `MessageRole`). Play 1.x's hot-reload clashes with JPA enum classloader identity.
- **MIME types:** configure via `mimetype.*` in `application.conf` — do NOT override at the controller level.
- **Comments:** only for non-obvious WHY (see existing javadocs on `AgentSkillAllowedTool` for an exemplar). No narrating what the code does.
- **Test commands to prefer (from feedback memory):**
  - `play auto-test` — not `play test`.
  - `pnpm test` — required after any frontend edit.

## Debugging

- **Play JPDA port:** 8100 (`jpda.port`). `play run --debug` attaches on this port.
- **SQL logging:** uncomment `jpa.default.debugSQL=true` in `application.conf`.
- **Structured events:** `EventLogger.info|warn|error(category, agentId?, channel?, message, details?)` → `event_log` table → `/api/logs` (and `pages/logs.vue`).
- **Latency metrics:** `/api/metrics/latency` returns HdrHistogram buckets per segment.

## IntelliJ

`JClaw.iml` / `JClaw.ipr` / `JClaw.iws` are checked into the repo. Opening the folder in IntelliJ picks up the module layout automatically.

## OpenSpec workflow

Active changes live under `openspec/changes/` (currently `v020-backlog`). Ratified specs under `openspec/specs/` (e.g. `shell-exec`). Use `/opsx:*` commands (see skill list) to create, continue, and archive change proposals.

## Gotchas

- H2 file DB is configured `AUTO_SERVER=TRUE`, so you can attach with a GUI client while Play is running.
- Logs written to `./logs/` (dev) and `/app/logs` (container).
- Dev-mode hot-reload works for Java classes, but adding new `@Entity` classes requires a JVM restart.
