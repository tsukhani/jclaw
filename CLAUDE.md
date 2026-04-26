# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

JClaw is an AI-powered automation platform built on **Play Framework 1.x** (Java) with a **Nuxt 3** (Vue 3 + TypeScript) SPA frontend. It combines OpenClaw agent orchestration and JavaClaw job scheduling into a single Java-first platform.

**Status**: pre-v1 (alpha), work in progress.

## First-time setup

After every fresh clone (including `rm -rf` + re-clone cycles), run:

```bash
./jclaw.sh setup
```

This wires git hooks (`.githooks/`), installs frontend dependencies (so the pre-commit hook's `lint-staged` is available), and verifies both `origin` (Bitbucket) and `github` remotes are configured. Idempotent — safe to re-run any time.

Why it's needed: `.git/config` lives inside `.git/` which git refuses to track, and `frontend/node_modules/` is gitignored. So `core.hooksPath`, the `github` remote, and `lint-staged` itself don't survive a fresh clone. Without the setup, hooks silently don't fire and `/deploy` fails on the github push.

## Development Commands

### Backend (Play 1.x)
```bash
play run                  # Start dev server on :9000
play autotest             # Run all tests (unit + functional)
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

## Git Hooks

Checked-in hooks live in `.githooks/`. They're wired up automatically by `./jclaw.sh setup` (see [First-time setup](#first-time-setup) above). The underlying command, for reference or manual use:

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

## Commit and Push Workflow

**Stop at the local commit. Do not push.** When you finish a unit of work:

1. Stage the files that belong to the change.
2. Create a local commit. The `pre-commit` hook runs here; fix every issue it surfaces and re-commit until the commit succeeds. Never use `--no-verify` to bypass the hook. If a rule is wrong, fix the rule; if a formatting auto-fix modifies files, re-stage and try again.
3. Stop. Report the commit hash and wait.

**Pushing is the user's job via `/deploy`.** The `/deploy` slash command is the only automation that bumps `application.version`, creates the release commit, and pushes to both remotes (`origin` + `github`). Do not `git push` as part of ordinary coding, and do not combine a final commit with a push in one step. A prior `/deploy` authorises only that release — the next one requires a new explicit invocation.

Why this matters: every push to `main` triggers the `pre-push` hook's full backend + frontend suite, occupies the two-remote deploy flow, and mutates shared state. Keeping push behind `/deploy` makes releases a deliberate act rather than a side-effect of finishing a task.

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


## Behavioral Guidelines

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
