# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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
```

### Running Both Together
Start the Play backend (`play run`) and the Nuxt frontend (`cd frontend && pnpm dev`) in separate terminals. The frontend proxies `/api/**` requests to `localhost:9000`.

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
