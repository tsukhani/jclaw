# Frontend Component Inventory

The frontend uses a **shadcn-nuxt component library on Reka UI primitives** for its base UI (auto-imported from `frontend/components/ui/`), with a layer of feature components on top. Pages compose these rather than inlining everything. **179 components total: 74 UI primitives + 105 feature components**, across 26 pages, 47 composables and 41 utility modules.

## UI primitives (`frontend/components/ui/`)

74 shadcn-nuxt / Reka UI primitives, auto-imported, grouped by widget family:

| Family | Count | Family | Count |
|---|---|---|---|
| accordion | 4 | popover | 4 |
| alert | 3 | select | 11 |
| button | 1 | sheet | 9 |
| command | 9 | table | 9 |
| dialog | 10 | dropdown-menu | 13 |

Variants are composed with `class-variance-authority` + `tailwind-merge` (`composables/ui-utils.ts` exposes the `cn()` helper).

## Feature components

105 in six families:

| Family | Count | Scope |
|---|---|---|
| `components/` (root) | 30 | Cross-page components — the table below |
| `components/settings/` | 49 | One panel per Settings section, registered in `sections.ts` |
| `components/chat/` | 18 | Chat-surface pieces split out of `chat.vue` (JCLAW-690) |
| `components/prompts/`, `components/guide/` | 3 | Prompt dialog + the user-guide renderer |

### Settings panels (`components/settings/`)

36 panels, each rendered by `pages/settings.vue` from the grouped registry in `sections.ts` (groups: System, Providers, Audio, Image, Video, Agents & Automation, Memory, Security):

`Chat` · `ImageCaption` · `ImageGen` · `Logging` · `Malware` · `MemoryEmbeddings` · `MemoryLimits` · `MemoryReranker` · `Ocr` · `Password` · `Performance` · `Printers` · `Providers` · `Restart` · `Search` · `Shell` · `Skills` · `Speech` · `Subagents` · `Tasks` · `Timezone` · `Transcription` · `Uploads` · `VideoGen` · `VideoInterp`, plus `UnmanagedBanner`.

Section **ids are stable across renames** — `memory` still addresses the panel now titled "Embeddings" because bookmarks and deep links point at it.

### Chat components (`components/chat/`)

11 components extracted from `chat.vue` during the JCLAW-690 decomposition: `ChatAgentSelector`, `ChatAttachmentChip`, `ChatAudioAttachment`, `ChatGeneratedImage`, `ChatGeneratedVideo`, `ChatMessage`, `ChatReembedNotice`, `ChatSubagentRow`, `ChatThinkingCard`, `ChatToolCalls`, `ChatVoiceOverlay`.

### Cross-page components (`components/`)

| Component | Purpose |
|---|---|
| `DataTable.vue` | `@tanstack/vue-table` wrapper for admin lists (tasks, subagent runs). |
| `CodingRunMonitor.vue` | Live view of an ACP coding-harness subagent run. |
| `ChatVoiceOverlay` / `IconVoiceWaveform.vue` | Voice-mode overlay and waveform indicator. |
| `PromptSizeDonut.vue` | System-prompt composition by section (pairs with `/api/agents/{id}/prompt-breakdown`). |
| `AppCostFields.vue` | Cost inputs for hosted apps. |
| `GithubStarsButton.vue`, `GithubStarNudge.vue` | Repo star count + nudge. |
| `ConfirmDialog.vue` | Imperative confirm/destructive-action modal (driven by `useConfirm`, mounted in `app.vue`). |
| `CommandPalette.vue` | Global `/`-triggered command palette. |
| `ChatContextMeter.vue`, `ChatCompressionSection.vue`, `ChatCostSection.vue` | Chat context/token/cost meters. |
| `ChatModelCombobox.vue`, `ModelCapabilityPills.vue` | Model selection + capability badges (vision/audio/reasoning). |
| `LatencyOverlayChart.vue` | Latency histogram overlay. |
| `FilterBar.vue` | Conversation/log filtering UI. |
| `NotificationBar.vue`, `StatusBanner.vue` | In-app notifications + system/auth/network banners. |
| `PeekPanel.vue` | Drawer-style side panel for in-page editors. |
| `ScheduleCalendar.vue` | Calendar widget for reminders/task scheduling. |
| `SkillFileTree.vue` | File browser for skill editing. |
| `TourIntroDialog.vue` | Onboarding-tour intro modal (driven by `useGuidedTour`). |
| `guide/GuideRenderer.vue` | Renders `docs/user-guide/*.md` (marked + DOMPurify) for the in-app `/guide`. |

## Layout (`frontend/layouts/`)

| Layout | File | Notes |
|---|---|---|
| `default` | `default.vue` | The only layout. Sidebar (Dashboard / Chat / Ops / Admin / Help groups), topbar (breadcrumb + theme toggle + logout), and a `<slot/>` content area. Heroicons for nav, Lucide for accent icons. Mounts `TourIntroDialog`; wires `useAuth`/`useTheme`/`useGuidedTour`. |

## Composables (`frontend/composables/`)

~47 — the `useState`-backed state + data layer (no Pinia):

| Composable | Role |
|---|---|
| `useAuth` | Session state + login/logout/checkAuth/checkPasswordSet/setupPassword/resetPassword (module-level lock). |
| `useEventBus` | Singleton `EventSource('/api/events')` with typed `on(event, handler)` fan-out + reconnect backoff. |
| `fetchParsed` (`useApiParsed.ts`) | `$fetch` + Zod validation (`types/schemas.ts`); throws `SchemaParseError` on boundary mismatch. |
| `useApiMutation` | POST/PUT/DELETE wrapper returning `{mutate, loading, error}`. |
| `useConfirm` | Imperative `confirm({title, message, requireText?})` → Boolean, rendered by `<ConfirmDialog />`. |
| `useTheme` | `themeMode` (system/light/dark), `setTheme`; toggles the `dark` class on `<html>` with a View Transitions reveal. |
| `useBulkSelect` | Multi-select state for admin list pages. |
| `useBindingAgents` | Agent-availability filtering for channel bindings. |
| `useProviders`, `useToolMeta`, `useComposerCompleter` | Provider/model/tool catalogs + `/model` completion. |
| `useGuidedTour`, `useBreadcrumbExtra`, `useTailscaleStatus` | Onboarding tour, in-page breadcrumb crumbs, Tailscale Funnel status. |

## Plugins (`frontend/plugins/`)

| Plugin | Role |
|---|---|
| `theme.client.ts` | Applies the persisted theme (`localStorage.jclaw-theme`) before first paint to avoid a flash-of-wrong-theme. |
| `axe.client.ts` | Dev-only vue-axe/axe-core a11y scanner (tree-shaken from production). |

## Middleware (`frontend/middleware/`)

| Middleware | Behavior |
|---|---|
| `auth.global.ts` | Runs on every nav except `/login` and `/setup-password`; routes to `/setup-password` (no admin password set) or `/login` (unauthenticated) via `useAuth`. |

## Utilities (`frontend/utils/`)

~41 pure helpers: `format.ts` (date/number/byte), `usage-cost.ts` (token→cost), `tool-calls.ts` + `display-message-filter.ts` (message rendering), `thinking.ts` + `thinking-lock.ts` (reasoning UI), `schedule.ts` + `calendar.ts` + `task-steps.ts` (scheduling), `linkify.ts` + `markdown-links.ts` (link handling), `video-job.ts` and `latency-rows.ts`.

## Types (`frontend/types/`)

- `api.ts` — compile-time wire shapes mirroring backend JSON.
- `schemas.ts` — Zod runtime schemas for boundary-validated reads (used by `useApiParsed`).
- ambient `.d.ts` — `markdown-raw.d.ts` (`?raw` markdown imports), `vue-axe.d.ts`.

## Test coverage

~201 Vitest specs under `frontend/test/` exercise composables and page-level logic under the **`jsdom`** environment (`test/setup.ts` polyfills `matchMedia`, `scrollIntoView`, `DataTransfer`). Playwright E2E under `frontend/tests/e2e/` drives the booted stack through the browser.

## Intentional choices

- **No Pinia** — shared state is `useState` + composables.
- **shadcn-nuxt + Reka UI** is the component base; new cross-page UI should extend it rather than re-inline.
- **Real light/dark theming** via the `dark` class + design tokens (not a CSS inversion filter).
- **No SSR/Nitro API** — `ssr: false`; `routeRules.proxy` is a leftover and not active.
