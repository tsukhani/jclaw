# Frontend Component Inventory

The frontend does not ship a component library — JClaw deliberately keeps page-specific UI inlined in the page file. Only components with cross-page reach live in `frontend/components/`.

## Standalone components (`frontend/components/`)

| Component | File | Purpose | Consumers |
|---|---|---|---|
| `<ConfirmDialog />` | `ConfirmDialog.vue` | Imperative confirm modal, mounted once in `app.vue`. Driven by the `useConfirm()` composable — any page can `await confirm({title, message})` and receive `true`/`false`. | Mounted globally in `app.vue`; triggered from `agents.vue`, `conversations.vue`, `skills.vue`, `channels.vue`, `settings.vue`, etc. |
| `<SkillFileTree />` | `SkillFileTree.vue` | Tree view for skill-authoring files (reads `/api/skills/{name}/files`). Handles expand/collapse and selection. | `skills.vue` |

## Layout (`frontend/layouts/`)

| Layout | File | Notes |
|---|---|---|
| `default` | `default.vue` | Only layout. Renders the sidebar (nav groups: Dashboard / Chat-group / Ops-group / Settings-group), topbar (breadcrumb + search placeholder + theme toggle), and a `<slot/>` content area. Polls `/api/status` every 30s for the status dot. |

## Composables (`frontend/composables/`)

| Composable | File | Role |
|---|---|---|
| `useAuth` | `useAuth.ts` | Session state (`authenticated`, `username`), `login`, `logout`, `checkAuth` (coalesced module-level lock). |
| `useApiMutation` | `useApiMutation.ts` | `$fetch` wrapper returning `{mutate, loading, error}` — consistent error handling for POST/PUT/DELETE. |
| `useEventBus` | `useEventBus.ts` | Singleton `EventSource('/api/events')` with typed `on(event, handler)` fan-out, reconnect backoff, auth-gate to prevent 401 reconnect loops. |
| `useConfirm` | `useConfirm.ts` | Imperative `confirm({title, message})` — resolves Boolean, rendered by `<ConfirmDialog />`. |
| `useTheme` | `useTheme.ts` | `themeMode` (`system`/`light`/`dark`), `setTheme`. Applies `.light-mode` class on `<html>`. |
| `useProviders` | `useProviders.ts` | Cached `/api/providers` lookups. |
| `useToolMeta` | `useToolMeta.ts` | Cached tool catalog (name → schema) for UI rendering. |

## Middleware (`frontend/middleware/`)

| Middleware | Scope | Behavior |
|---|---|---|
| `auth.global.ts` | Global, every route | Skips `/login`. If `useAuth().authenticated` is false, calls `checkAuth()`; on fail, `navigateTo('/login')`. |

## Utilities (`frontend/utils/`)

| Module | Purpose |
|---|---|
| `format.ts` | Date/number/byte formatters. |
| `usage-cost.ts` | Converts `MessageUsage` (prompt/completion tokens) into estimated cost per configured provider pricing. |

## Types (`frontend/types/api.ts`)

Wire types mirroring the backend JSON shapes:
- `Agent`, `Conversation`, `Message`, `ConfigEntry`, `ConfigResponse`
- `LatencyHistogram`, `LatencyMetrics`
- `Skill`, `SkillFile`
- `MessageUsage` (imported into `Message` for token accounting)

## Test coverage

Vitest specs under `frontend/test/` directly exercise the composables and page-level logic (not a full integration harness — E2E lives in Playwright):

| Spec | Target |
|---|---|
| `auth.test.ts` | `useAuth` + middleware behavior. |
| `chat.test.ts` | `pages/chat.vue` streaming logic (mocked SSE). |
| `composables.test.ts` | `useApiMutation`, `useEventBus`, `useTheme`, `useConfirm`, `useProviders`, `useToolMeta`. |
| `markdown.test.ts` | `marked + DOMPurify` sanitization on message rendering. |
| `pages.test.ts` | Page-level smoke (agents, tools, settings). |
| `skills.test.ts` | `pages/skills.vue` + `SkillFileTree.vue`. |
| `usage-cost.test.ts` | `utils/usage-cost.ts`. |

Playwright E2E (under `frontend/tests/`) drives the fully booted stack through the browser — see `playwright.config.ts` for base URL and trace settings.

## Intentional absences

- **No Pinia** — shared state is handled by `useState` + composables. Pinia is the natural upgrade path if state outgrows this.
- **No component library** — page-specific UI is inlined with Tailwind. Cross-page extraction should only happen when the third consumer appears.
- **No custom theming tokens** — light mode is an inversion filter on `html`, not a real palette.
- **No SSR/Nitro API** — `ssr: false`; `routeRules.proxy` is a leftover and not active.
