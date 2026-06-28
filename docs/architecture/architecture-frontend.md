# Frontend Architecture — Nuxt 4 SPA

## Executive summary

The frontend is a Nuxt 4 SPA (`ssr: false`) serving as the single-page control plane for the Play backend. It uses file-based routing, Tailwind CSS v4, a shadcn-nuxt component library on Reka UI primitives, and composables for cross-page state. Shared state is built on `useState` + module-level locks + a singleton `EventSource` (`useEventBus`); **there is no Pinia** and none is planned unless state outgrows this pattern.

## Technology stack

| Category | Choice | Version |
|---|---|---|
| Framework | Nuxt | 4.4.8 |
| Render mode | SPA (`ssr: false`) | — |
| View layer | Vue 3 Composition API | via Nuxt |
| Language | TypeScript | 6.x |
| UI components | shadcn-nuxt on Reka UI, `class-variance-authority` + `tailwind-merge` | 2.7 / 2.9 |
| Styling | Tailwind CSS v4 via `@tailwindcss/vite` | 4.3 |
| Icons / font | Lucide + Heroicons; Inter variable font | — |
| HTTP | `$fetch` (Nitro/ofetch) | — |
| Validation | Zod (`types/schemas.ts`, via `useApiParsed`) | 4.4 |
| Tables | `@tanstack/vue-table` | 8.21 |
| Markdown rendering | `marked` + `dompurify` | 18.0 / 3.4 |
| Package manager | pnpm | 11.7.0 (pinned with `+sha512` in `package.json`) |
| Unit tests | Vitest (`jsdom` env) | 4.1 |
| Component test utils | `@vue/test-utils` + `@nuxt/test-utils` | 2.4 / 4.0 |
| E2E tests | Playwright | 1.60 |
| A11y | `vue-axe` / `axe-core` (dev-only runtime scanner) | 3.1 / 4.11 |

## File layout

File-based routing (20 page files under `frontend/pages/`, including nested `conversations/` and `channels/`), a single layout, a shadcn-nuxt UI primitive library plus feature components, and a set of composables for cross-page state.

```
frontend/
├── app.vue                   # Root: <NuxtLayout><NuxtPage/></NuxtLayout> + global dialogs
├── layouts/default.vue       # Sidebar + topbar + content slot (the only layout)
├── middleware/auth.global.ts # Routes to /login or /setup-password based on auth state
├── pages/                    # File-routed; see list below (incl. conversations/, channels/)
├── components/
│   ├── ui/                   # 74 shadcn-nuxt / Reka UI primitives (auto-imported)
│   └── *.vue, guide/         # 17 feature components (DataTable, ChatContextMeter, …)
├── composables/              # Cross-page state & data layer (~17)
├── plugins/                  # theme.client.ts, axe.client.ts
├── utils/                    # Pure helpers (format, usage-cost, tool-calls, schedule, …)
├── types/                    # api.ts (wire types) + schemas.ts (Zod) + ambient .d.ts
├── test/                     # Vitest unit tests
└── tests/e2e/ + playwright.config.ts  # Playwright E2E
```

## Routing

Nuxt file-based. Pages (roles):

| Route | File | Role |
|---|---|---|
| `/` | `index.vue` | Dashboard (status widgets, latency/cost metrics, recent activity). |
| `/chat` | `chat.vue` | Streaming chat with an agent. Largest functional page. |
| `/conversations` | `conversations/index.vue` | History browser + bulk delete. |
| `/conversations/:id` | `conversations/[id].vue` | Single conversation transcript. |
| `/channels` | `channels/index.vue` | Channel overview. |
| `/channels/{slack,telegram,whatsapp}` | `channels/*.vue` | Per-channel binding config. |
| `/agents` | `agents.vue` | Agent CRUD, tool/skill toggles, prompt breakdown, workspace editor. |
| `/subagents` | `subagents.vue` | Subagent run monitor (kill, read transcripts). |
| `/tasks` | `tasks.vue` | Task list, cancel, retry, run history. |
| `/reminders` | `reminders.vue` | Reminder list (web-channel notifications). |
| `/skills` | `skills.vue` | Skill inventory, file tree, promotion, catalog import. |
| `/tools` | `tools.vue` | Tool catalog + per-agent enable. |
| `/mcp-servers` | `mcp-servers.vue` | MCP server CRUD + connection test. |
| `/settings` | `settings.vue` | Global Config keys. Largest page in the project. |
| `/logs` | `logs.vue` | Event-log viewer. |
| `/guide` | `guide.vue` | In-app user guide (rendered from `docs/user-guide/*.md`). |
| `/login` | `login.vue` | Exempt from `auth.global.ts`. |
| `/setup-password` | `setup-password.vue` | First-boot admin password setup. |

## Auth model

**Cookie-session with client-side guard.** `middleware/auth.global.ts` runs on every nav (except `/login` and `/setup-password`) and uses `useAuth()` to check both whether an admin password is set (`checkPasswordSet`) and whether the session is valid (`checkAuth`), routing to `/setup-password` (first boot) or `/login` (unauthenticated) accordingly. `checkAuth` uses a module-level lock to coalesce concurrent navigations into one request.

Login/logout/setup/reset are `POST /api/auth/{login,logout,setup,reset-password}`; session state is kept in `useState('auth:authenticated')` + `useState('auth:username')`.

## Shared state (no Pinia)

~17 composables form the state + data layer, all `useState`-backed singletons:

- `useAuth()` — `authenticated`, `username`, `login`/`logout`/`checkAuth`/`checkPasswordSet`/`setupPassword`/`resetPassword`. Module-level lock prevents racing `checkAuth` calls.
- `useEventBus()` — singleton `EventSource` to `/api/events`; reconnect with exponential backoff; survives navigation.
- `useApiParsed()` — `$fetch` + Zod validation (`types/schemas.ts`); throws a distinct `SchemaParseError` on boundary mismatch.
- `useApiMutation()` — POST/PUT/DELETE wrapper with `loading`/`error` refs.
- `useConfirm()` + `<ConfirmDialog />` — imperative confirm modal (supports text-confirm for destructive ops).
- `useTheme()` — light/dark/system, persisted to `localStorage` (`jclaw-theme`) by toggling the `dark` class on `<html>`, with a View Transitions reveal-on-toggle.
- `useBulkSelect()` — multi-select state for admin list pages (Tasks, Subagent Runs).
- `useBindingAgents()` — agent-availability filtering for channel bindings.
- `useProviders()`, `useToolMeta()`, `useModelAutocomplete()` — provider/model/tool catalogs + `/model` completion.
- `useGuidedTour()`, `useBreadcrumbExtra()`, `useTailscaleStatus()` — onboarding tour, in-page breadcrumb crumbs, Tailscale Funnel status.

## API access conventions

All backend calls go through these layers:

1. **`$fetch`** directly for one-off reads with page-local state.
2. **`useApiParsed`** for schema-validated high-risk reads (chat messages, tool calls, conversation rows).
3. **`useApiMutation`** for POST/PUT/DELETE operations that need loading/error state.
4. **SSE** via the singleton `useEventBus` for server-push events.

Compile-time wire shapes live in `frontend/types/api.ts`; runtime-validated shapes in `frontend/types/schemas.ts` (Zod).

## Styling approach

- Tailwind CSS **v4** wired through `@tailwindcss/vite` (not the PostCSS `@nuxtjs/tailwindcss` module).
- Real light/dark theming via the `dark` class on `<html>` + shadcn/Reka design tokens; `plugins/theme.client.ts` applies the persisted theme before first paint to avoid a flash-of-wrong-theme.
- shadcn-nuxt primitives (Reka UI) provide the component base; a consistent neutral/emerald palette is layered on top.

## Testing strategy

- **Vitest** under `frontend/test/` (~69 files). Environment: **`jsdom`** (a `test/setup.ts` polyfills `matchMedia`, `scrollIntoView`, `DataTransfer`). Run with `pnpm test` (alias for `vitest run`).
- **Playwright** E2E under `frontend/tests/e2e/` with `playwright.config.ts`. `pnpm test:e2e`, UI mode `test:e2e:ui`, headed-slow `test:e2e:headed`.
- Any edit under `frontend/` must be validated with `cd frontend && pnpm test` (per user feedback — `play autotest` does NOT cover Vue/Vitest).
- `plugins/axe.client.ts` runs a vue-axe/axe-core a11y scan in dev only (tree-shaken from production).

## Dev proxy / prod serving

- **Dev (:3000):** `nuxt.config.ts` → `nitro.devProxy['/api'] = http://localhost:${JCLAW_BACKEND_PORT||9000}/api`. No CORS needed.
- **Prod:** `nuxi generate` → `frontend/.output/public/` → copied into `public/spa/`. The bare-metal path (`./jclaw.sh start`) generates and stages the SPA; the Dockerfile bakes it into the image during the multi-stage build. Play's `/_nuxt/` route maps the static bundle; `Application.spa` serves `index.html` for all unmatched frontend routes.

## Constraints

- SSR is **off** (`ssr: false`). All dynamic behavior is in Play; the `routeRules.proxy` clause is a leftover for an SSR mode that isn't active.
- `@nuxt/test-utils` mounts run under `jsdom`; keep component tests free of browser APIs not polyfilled in `test/setup.ts`.
- The SPA emits an inline `window.__NUXT__` block, so any backend CSP must allow `'unsafe-inline'` for `script-src` (the shipped CSP is intentionally empty).
