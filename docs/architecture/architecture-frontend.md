# Frontend Architecture — Nuxt 3 SPA

## Executive summary

The frontend is a Nuxt 3 SPA (`ssr: false`) serving as the single-page control plane for the Play backend. It uses file-based routing, Tailwind CSS, and composables for cross-page state. Shared state is built on `useState` + module-level locks + a singleton `EventSource` (`useEventBus`); **there is no Pinia** and none is planned unless state outgrows this pattern.

## Technology stack

| Category | Choice | Version |
|---|---|---|
| Framework | Nuxt | 3.16+ |
| Render mode | SPA (`ssr: false`) | — |
| View layer | Vue 3 composition API | via Nuxt |
| Language | TypeScript | — |
| Styling | Tailwind CSS + `@nuxtjs/tailwindcss` | 6.13 |
| HTTP | `$fetch` (Nitro/ohmyfetch) | — |
| Markdown rendering | `marked` + `dompurify` | 17.0 / 3.3 |
| Package manager | pnpm | 10.33.0 (pinned in `package.json` + Jenkins) |
| Unit tests | Vitest (`happy-dom` env) | 4.1 |
| Component test utils | `@vue/test-utils` + `@nuxt/test-utils` | 2.4 / 4.0 |
| E2E tests | Playwright | 1.59 |

## File layout

File-based routing (11 pages under `frontend/pages/`), a single layout, and a small set of reusable composables. There is no generic component library — page-specific UI is inlined.

```
frontend/
├── app.vue                   # Root: <NuxtLayout><NuxtPage/></NuxtLayout> + ConfirmDialog
├── layouts/default.vue       # Sidebar + topbar + content slot (the only layout)
├── middleware/auth.global.ts # Redirects to /login if checkAuth() fails
├── pages/                    # File-routed; see list below
├── components/               # Only 2: ConfirmDialog, SkillFileTree
├── composables/              # Cross-page logic
├── utils/                    # Pure helpers (format, usage-cost)
├── types/api.ts              # Wire types mirrored from backend
├── test/                     # Vitest unit tests
└── tests/ + playwright.config.ts  # Playwright E2E
```

## Routing

Nuxt file-based. Pages:

| Page | Role | LOC |
|---|---|---|
| `index.vue` | Dashboard (status widgets, latency metrics). | 197 |
| `chat.vue` | Streaming chat with an agent. Largest functional page. | 1036 |
| `conversations.vue` | History browser + bulk delete. | 314 |
| `agents.vue` | Agent CRUD, tool/skill toggles, prompt breakdown. | 1007 |
| `channels.vue` | Channel config (Slack/Telegram/WhatsApp). | 110 |
| `tasks.vue` | Task list, cancel, retry. | 88 |
| `skills.vue` | Skill inventory, file tree, promotion. | 859 |
| `tools.vue` | Tool catalog + per-agent enable. | 343 |
| `settings.vue` | Global Config keys. **Largest page in the project.** | 1575 |
| `logs.vue` | Event log viewer. | 107 |
| `login.vue` | Only page exempt from `auth.global.ts`. | 69 |

## Auth model

**Cookie-session with client-side guard.** `middleware/auth.global.ts` runs on every nav (except `/login`) and calls `useAuth().checkAuth()` which probes `GET /api/config` — a 2xx response means the session is valid (a 401 from the backend means it isn't). `checkAuth` uses a module-level `checkInProgress` lock to coalesce concurrent navigations into one request.

Login/logout is `POST /api/auth/login|logout`; session state is kept in `useState('auth:authenticated')` + `useState('auth:username')`, which Nuxt scopes to the current SSR request or SPA lifetime.

## Shared state (no Pinia)

- `useAuth()` — `authenticated`, `username`, `login`, `logout`, `checkAuth`. State via `useState`. Module-level lock prevents racing `checkAuth` calls.
- `useEventBus()` — singleton `EventSource` to `/api/events`. Guards against opening before auth is confirmed (prevents infinite 401 reconnect loop). Reconnect with backoff on error.
- `useTheme()` — light/dark/system; persisted to `localStorage`, applied by toggling `html.light-mode` (light inverts the dark UI via CSS filter — see `app.vue`).
- `useApiMutation()` — `$fetch` wrapper with `loading` / `error` refs to replace scattered try/catch.
- `useConfirm()` + `<ConfirmDialog />` — imperative confirm modal mounted once in `app.vue`.
- `useProviders()`, `useToolMeta()` — thin caches for `/api/providers` and `/api/tools`.

## API access conventions

All backend calls go through three layers:

1. **`$fetch`** directly for one-off reads with page-local state (via `useFetch` or `$fetch` in `onMounted`).
2. **`useApiMutation`** for POST/PUT/DELETE operations that need loading/error state.
3. **SSE** via the singleton `useEventBus` for server-push events.

Typed requests/responses live in `frontend/types/api.ts`, mirroring the backend entities (`Agent`, `Conversation`, `Message`, `ConfigEntry`, `LatencyMetrics`, `Skill`, `SkillFile`).

## Styling approach

- Tailwind 3 with `@nuxtjs/tailwindcss` module.
- Dark UI by default; light mode is an **inversion filter** (`filter: invert(1) hue-rotate(180deg)`) applied to `html.light-mode`, with images/videos/no-invert SVGs restored via a second filter. This is a deliberate shortcut — avoid adding genuine light-mode tokens unless the user asks for a design overhaul.
- No component library; design is inlined with consistent neutral/emerald palette.

## Testing strategy

- **Vitest** under `frontend/test/` (7 files): `auth`, `chat`, `composables`, `markdown`, `pages`, `skills`, `usage-cost`. Environment: `happy-dom`. Run with `pnpm test` (alias for `vitest run`).
- **Playwright** E2E under `frontend/tests/` with `playwright.config.ts`. `pnpm test:e2e`, UI mode `test:e2e:ui`, headed-slow `test:e2e:headed`.
- Any edit under `frontend/` must be validated with `cd frontend && pnpm test` (per user feedback — `play auto-test` does NOT cover Vue/Vitest).

## Dev proxy / prod serving

- **Dev (:3000):** `nuxt.config.ts` → `nitro.devProxy['/api'] = http://localhost:9000/api`. No CORS needed.
- **Prod:** `nuxi generate` → `frontend/.output/public/` → copied into `public/spa/`. The bare-metal path (`./jclaw.sh start`) generates and stages the SPA on every start; the Dockerfile bakes it into the image during the multi-stage build. Play's `/_nuxt/` route maps the static bundle; `Application.spa` serves `index.html` for all unmatched frontend routes.

## Constraints

- SSR is **off** (`ssr: false`). There is no Nitro server code on the backend-path — all dynamic behavior is in Play.
- Nuxt's `devProxy` is in use only in development; the `routeRules.proxy` clause is a leftover for an SSR mode that isn't active.
- `@nuxt/test-utils` requires `happy-dom` for most mounts; keep component tests free of browser APIs not polyfilled there.
