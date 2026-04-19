# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JClaw is an AI-powered automation platform built on **Play Framework 1.x** (Java) with a **Nuxt 3** (Vue 3 + TypeScript) SPA frontend. It combines OpenClaw agent orchestration and JavaClaw job scheduling into a single Java-first platform.

**Status**: v0.1.0-alpha, work in progress.

## Development Commands

### Backend (Play 1.x)
```bash
play run                  # Start dev server on :9000
play test                 # Run all tests (unit + functional)
play auto-test            # Run tests with auto-reload
play deps --sync          # Resolve and sync dependencies from conf/dependencies.yml
play dist                 # Build production distribution
```

### Frontend (Nuxt 3)
```bash
cd frontend
pnpm install              # Install dependencies
pnpm dev                  # Dev server on :3000
pnpm build                # Production build
pnpm preview              # Preview production build

pnpm lint                 # ESLint (logic + Vue + TS + a11y + stylistic)
pnpm format               # eslint --fix — stylistic auto-fix across the tree
pnpm stylelint            # Stylelint on .css + <style> blocks
pnpm stylelint:fix        # stylelint --fix
pnpm typecheck            # vue-tsc --noEmit via nuxi typecheck
pnpm audit                # pnpm audit --prod --audit-level=moderate
pnpm test                 # Vitest (unit)
```

### Running Both Together
Start the Play backend (`play run`) and the Nuxt frontend (`cd frontend && pnpm dev`) in separate terminals. The frontend proxies `/api/**` requests to `localhost:9000`.

**SLF4J provider:** when running `play run` directly (outside `jclaw.sh` / Docker), add `-Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider` so log4j2 wins ServiceLoader order over Playwright's transitive `slf4j-simple`. `jclaw.sh --dev start` and the Dockerfile `CMD` pass this automatically; the bare `play run` invocation does not. Without it, third-party library logs (Hibernate, HikariCP, OkHttp) render with the stderr-only simple-provider format instead of `conf/log4j2.xml`. See JCLAW-88 for context.

## Git Hooks

Checked-in hooks live in `.githooks/`. Enable them on a fresh clone with:

```bash
git config core.hooksPath .githooks
```

Two hooks, layered by speed:

- **`pre-commit`** — runs `lint-staged` on staged frontend files only (ESLint + Stylelint `--fix`). Target: < 5 s typical. Auto-fixes formatting and re-stages; blocks the commit if a non-fixable rule violates. Short-circuits instantly when no `frontend/**` file is staged, so backend-only commits pay zero cost. Requires `cd frontend && pnpm install` first; before that, the hook fails open with a note.
- **`pre-push`** — runs the full backend + frontend test suite. Caches per-HEAD so the two-remote deploy flow (origin + github) only pays the ~30 s cost once.

Bypass for a single commit / push (use sparingly):

```bash
git commit --no-verify -m "…"          # skip pre-commit
JCLAW_SKIP_TESTS=1 git push …          # skip pre-push tests
```

Disable per-worktree (overrides the checked-in hook for this clone only):

```bash
git config --unset core.hooksPath      # opts out entirely
# or, to keep pre-push but disable pre-commit:
echo '#!/bin/sh' > .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
git config core.hooksPath .git/hooks
```

## Architecture

### Backend
- **Play 1.x** conventions: controllers are static methods in `app/controllers/`, models in `app/models/`, views (Groovy templates) in `app/views/`
- Routes defined in `conf/routes` — uses Play's `{controller}.{action}` catch-all pattern
- Configuration in `conf/application.conf` — supports environment prefixes (`%prod.`, `%test.`)
- Dependencies managed via `conf/dependencies.yml` (Play module system, not Maven/Gradle)
- Tests in `test/` — JUnit 5, extending Play's `UnitTest` or `FunctionalTest`
- Test mode uses H2 in-memory database (`%test.db.url` in application.conf)

### Frontend
- Nuxt 3 SPA in `frontend/` with Tailwind CSS
- API proxy: dev requests to `/api/*` are forwarded to the Play backend via Nitro devProxy (see `frontend/nuxt.config.ts`)
- `useApi<T>(path)` composable in `frontend/composables/useApi.ts` wraps `useFetch` for backend calls
- Package manager: **pnpm** (pinned at 10.8.0)

### API Contract
Backend exposes JSON endpoints under `/api/` (e.g., `ApiController.status` at `GET /api/status`). The frontend consumes these through the proxy — no CORS configuration needed.

## Prerequisites

- JDK 25+ (Zulu recommended)
- `play` command in PATH (custom fork: github.com/tsukhani/play1)
- Node.js 20+
- pnpm
