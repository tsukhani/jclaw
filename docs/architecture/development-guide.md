# Development Guide

How to set up, run, test, and iterate on jclaw locally.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 25+ | Zulu recommended (Docker and Jenkins both pin to Azul Zulu 25). |
| Play Framework CLI | 1.13.x (custom fork) | Install from [github.com/tsukhani/play1](https://github.com/tsukhani/play1); `play` on `$PATH`. In 1.13.x the `play` CLI is Gradle-driven (PF-90 removed the legacy Python CLI). Pinned in `.play-version` (`1.13.45`). |
| Node.js | 20+ (24 recommended) | For the Nuxt frontend. |
| pnpm | 11.11.0 | Pinned (with `+sha512` hash) in `frontend/package.json`; managed via corepack. |
| Python | 3.10+ (optional) | Only for the local image/video generation sidecars (`sidecar/`); not needed for the `play` CLI. |
| Tesseract | optional | OCR for the `documents` tool (image / scanned-PDF text). |

> Fastest path: use the **Dev Container** (`.devcontainer/`, Ubuntu 26.04 + Zulu 25 + Node 24 + the Play fork). "Reopen in Container" runs `./jclaw.sh setup` for you.

## Clone + bootstrap

```bash
git clone https://bitbucket.abundent.com/scm/jclaw/jclaw.git
cd jclaw
./jclaw.sh setup            # wires git hooks, installs frontend deps, generates .env, verifies remotes
```

`./jclaw.sh setup` is the one-time per-clone bootstrap (idempotent). The launcher installs Play deps (via the gradle `org.playframework.play1` plugin from `/opt/play1`) and frontend deps on first run. Manual frontend install: `cd frontend && pnpm install --frozen-lockfile`.

## Run locally

### Unified launcher (preferred)

```bash
./jclaw.sh --dev start      # backend on :9000 AND frontend on :3000
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

**Backend tests: always use `play autotest`, NOT `play test`.** `play test` is interactive and requires a browser session; it hangs CI and local automation. `play autotest` runs headless (and auto-runs `compileJava` since 1.13.9, so no separate compile step). Stop any live `play run` first — a running dev app lags the FirePhoque harness and fails controller tests.

```bash
# Backend — JUnit 6 under test/
play autotest

# Frontend — Vitest unit tests (MUST run after any frontend/ change)
cd frontend && pnpm test

# Or both at once with a consolidated pass/fail summary:
./jclaw.sh test

# Frontend — Playwright E2E
cd frontend && pnpm test:e2e
cd frontend && pnpm test:e2e:ui        # headed + UI mode
cd frontend && pnpm test:e2e:headed    # slow-mo, single worker
```

Test DB: `%test.db.url=jdbc:h2:mem:play;MODE=MYSQL;LOCK_MODE=0` (set in `conf/application.conf`). Each test run boots a fresh in-memory DB.

## Git hooks

`./jclaw.sh setup` wires `core.hooksPath=.githooks`:
- **`pre-commit`** — `lint-staged` (ESLint + Stylelint `--fix`) on staged `frontend/**` files only; short-circuits for backend-only commits. Also guards the pnpm `packageManager` hash.
- **`pre-push`** — runs the full backend + frontend suite (`./jclaw.sh test`), cached per-HEAD so the two-remote deploy flow pays the cost once. Bypass a single push with `JCLAW_SKIP_TESTS=1` (sparingly).

Never use `--no-verify`.

## Post-coding workflow (required per repo convention)

Every code change goes through this order — do not skip steps:

1. **Write tests first** (see CLAUDE.md & feedback memory).
2. `play autotest` — backend green.
3. `cd frontend && pnpm test` — frontend green (required if any file under `frontend/` was touched).
4. **Stop at the local commit.** The `pre-commit` hook runs here; fix every issue it surfaces and re-commit (never `--no-verify`).

**Releasing is a separate, deliberate step.** Pushing is *not* part of ordinary coding — the `/deploy` slash command bumps `application.version` in `conf/application.conf`, creates the signed release commit + tag, and pushes to **both** remotes (`origin` Bitbucket + `github`). Do not `git push` as part of a coding task.

## Codebase conventions

- **Play 1.x static-method pattern:** controllers, services, and jobs are static methods on classes. No Spring, no DI container.
- **Frontend state:** `useState` + composables. No Pinia.
- **Transactions:** `services.Tx.run(...)` wraps `JPA.withTransaction` (no-ops if already inside a tx). After a nested `Tx.run`, re-fetch entities — they may be detached.
- **Outbound HTTP:** use `utils.HttpFactories` (OkHttp 5: `llmStreaming` / `llmSingleShot` / `general`). No `java.net.http.HttpClient`.
- **JSON:** use `utils.GsonHolder.INSTANCE` — a single project-wide Gson.
- **Enums in DB:** string-backed (`@Enumerated(STRING)` / manual conversion) — Play 1.x hot-reload clashes with JPA enum classloader identity.
- **MIME types:** configure via `mimetype.*` in `application.conf` — don't override at the controller level.
- **Comments:** only for non-obvious WHY (see the javadocs on `AgentSkillAllowedTool` for an exemplar).

## Debugging

- **Play JPDA port:** 8100 (`jpda.port`). `play run --debug` attaches on this port.
- **SQL logging:** uncomment `jpa.default.debugSQL=true` in `application.conf`.
- **Structured events:** `EventLogger` → `event_log` table → `/api/logs` (and `pages/logs.vue`).
- **Latency metrics:** `/api/metrics/latency` returns HdrHistogram buckets per segment; `/api/metrics/cost` is durable.
- **Load testing:** `./jclaw.sh loadtest` drives the in-process harness against `/api/chat/stream` (mock or a real `--provider`/`--model`).

## Gotchas

- H2 file DB is configured `AUTO_SERVER=TRUE`, so you can attach with a GUI client while Play is running.
- Logs written to `./logs/` (dev) and `/app/logs` (container).
- Dev-mode hot-reload works for Java classes, but adding new `@Entity` classes requires a JVM restart.
- A fresh git worktree is missing `.nuxt/tsconfig.json` — validate frontend tests in the primary tree (or run `nuxi prepare`).
