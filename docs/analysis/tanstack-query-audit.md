# TanStack Query audit — is `@tanstack/vue-query` justified?

Date: 2026-09-18. Scope: `frontend/` (Nuxt 4.5.2, Vue 3.5.42, `ssr: false`). Investigation only; nothing was installed or changed.

**Verdict: HOLD.** The pain is real but it is a handful of specific, in-house-fixable defects, not a server-state architecture problem. The library's unique wins (a cross-mount cache, focus refetch, structural stale-response protection) have low value for a single-operator SPA talking to a localhost backend, and adopting it would put a second read primitive beside the 81 Nuxt `useFetch` sites and the 96 test files built around them. Concrete revisit triggers are in §3.

## 0. A correction to the brief

The brief assumes a Pinia stack. **Pinia is not installed** (`frontend/package.json` lists no `pinia` or `@pinia/*`; `grep defineStore` over the tree returns nothing). There is no `stores/` directory. Cross-component state lives in three places instead:

| Holder | Sites | What it holds |
| --- | --- | --- |
| Nuxt `useState` | 6 keys (`composables/useAuth.ts:52-53`, `useEventBus.ts:26`, `useGuidedTour.ts:165,170`, `useBreadcrumbExtra.ts:10`) | auth flag, username, tour progress, breadcrumb — all client |
| Module-level refs in composables | 6 files (`useConfirm.ts:28`, `useReadAloud.ts:10-16`, `useTheme.ts:3`, `useVoiceMode.ts:24-27`, `useToolMeta.ts:106`, `useSubagentTranscript.ts:44`) | four client, **two server caches** |
| provide/inject | 1 (`composables/useSettingsConfig.ts:62-171`) | the Settings page's `/api/config` + `/api/providers` snapshots plus a shared inline editor |

So "how much of the store is server state" becomes "where do server snapshots live", answered in §2.

## 1. Inventory of fetching patterns

Counts are over `pages components composables layouts`, test files excluded, comments excluded.

| Pattern | Sites | Files | Where it lives | Loading / error | Refetch | Sharing | Issues |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **A. Blocking `await useFetch`** (top-level in `<script setup>`) | 17 direct + 8 files via `await Promise.all([useFetch…])` | 22 | list/detail pages and 8 Settings panels: `pages/tasks.vue:86,93`, `pages/subagents.vue:71`, `pages/conversations/index.vue:40`, `pages/agents/[[name]].vue:34-37`, `pages/skills/[[name]].vue:38-47`, `pages/channels/*.vue`, `components/settings/SettingsProvidersPanel.vue:28`, `SettingsSubagentsPanel.vue:16`, `SettingsSkillsPanel.vue:17`, `SettingsVideoInterpPanel.vue:12`, `SettingsOcrPanel.vue:20`, `SettingsLoggingPanel.vue:22`, `SettingsTimezonePanel.vue:20`, `SettingsTasksPanel.vue:35` | Suspense fallback is the loading UI. `error`/`status` almost never destructured: `tasks.vue:86,93` take only `data`/`refresh`; `SettingsProvidersPanel.vue:28` takes only `data` | `refresh()` called by hand after writes | `data` ref passed as props / inject | A failed primary read on 3 list pages has **no error UI** (`tasks.vue`, `subagents.vue`, `conversations/index.vue`). Suspends the whole panel — the cold-boot stall that forced `useLazyFetch` on sidecar panels (AGENTS.md) |
| **B. Non-blocking `useLazyFetch` / `useLazyAsyncData`** | 33 + 6 | 20 | dashboard `pages/index.vue:47-99,149,212`, Settings status panels (`SettingsModelRouterPanel.vue:54`, `SettingsTelemetryPanel.vue:60`, `SettingsTranscriptionPanel.vue:41`, `SettingsImageGenPanel.vue:99,131,156`, `SettingsCodingPanel.vue:62,87`…), `components/ChatCostSection.vue:174,209`, `BreakerStatusSection.vue:31` | `status`/`pending` used for skeletons and height reservation; **`error` destructured in none of the 20 files** | `refresh()`, `execute()`, or `watch: [query]` (`ChatCostSection.vue:177`, `index.vue:152`) | `defineExpose({ refresh })` for parent-driven polling (`ChatCostSection.vue:1000` ← `index.vue:287`) | Failures render as empty states: "No models discovered — set a Replicate API key" on a transport error (`SettingsImageGenPanel.vue:575`), a dashboard count of `0` on a failed fetch (`index.vue:36-40,107-115`) |
| **C. Bare `$fetch` read in a handler / `onMounted` / watcher, into a local ref** | ≈70 (219 `$fetch` sites − 148 with a write method) | ≈35 | `pages/agents/[[name]].vue:342,921,1053,1062,1072,1297,1513` (7 loads per agent open), `pages/tasks.vue:271,284,488,561,576,630,690`, `pages/skills/[[name]].vue:119,234,495,565,590,747,807,837`, `pages/memories.vue:117`, `pages/conversations/index.vue:112,132,389`, `pages/subagents.vue:170,410`, `components/CommandPalette.vue:73-74`, `composables/useChatConversation.ts:102,141,177` | Hand-rolled: `loading.value = true` in 6 files; 18 empty catch blocks; 30 catches that only `console.error/warn`. `useChatConversation.loadConversation:174-198` has **no try/catch at all** | Manual: ≈200 `refresh()`/`reload()`/`refreshX()` calls across 57 files | Local refs; `shallowRef` + `triggerRef` for chat messages | **Stale-response overwrite** in 13 paths (§1.1). No dedupe: `CommandPalette.vue:73` refetches `/api/agents` on every open while 16 other sites fetch the same URL |
| **D. Hand-rolled polling** | 28 `setInterval` + 2 `setTimeout` chains | 24 | listed in §1.2 | none for the poll itself; errors swallowed (`useMediaGenPolling.ts:70-72`, `NotificationBar.vue:48-51`, `useChatAnnouncePoller.ts:239-242`) | fixed interval; 1 site has backoff (`layouts/default.vue:193`) | — | 6 files hand-roll `document.hidden` gating; 2 timers never cleared (`pages/agents/[[name]].vue:365-371`, `pages/tasks.vue:761-770` vs `:735-738`) |
| **E. Module-level server cache** | 2 | 2 | `composables/useToolMeta.ts:106-137` (`/api/tools/meta`, in-flight promise dedupe, manual `refresh()`), `composables/useSubagentTranscript.ts:44` (unbounded `Map<convoId, transcript>`, never evicted) | `useToolMeta` swallows to empty list (`:116-121`) | manual | module ref | The only two places that re-implement what a query cache does; both correct, both small |
| **F. provide/inject store** | 1 | 1 (+40 panels) | `composables/useSettingsConfig.ts:72-171` wrapping two `useFetch` handles | `saving` is **page-wide**, so one panel's write greys out every panel (`:91,130`) | `saveField`/`updateEntry` POST then `refresh()` (`:94,138`); `resync()` (`:81-88`) exists because a failed `refresh()` empties the page (JCLAW-1221) | inject | The `resync` discipline is applied unevenly: `SettingsModelRouterPanel.vue:199-211` `saveThreshold` omits it while its three siblings (`:111-167`) use it |
| **G. Mutation wrappers** | `useApiMutation` 24 sites / 18 files; `useSaveAttempt` ≈12 | — | `composables/useApiMutation.ts:27-63`, `useSaveAttempt.ts:7-23` | loading + `ApiErrorDetails` (JCLAW-1131 envelope) | caller's job | — | **40 of 58 files with write calls bypass both wrappers** and hand-roll `$fetch` + try/catch |
| **H. Schema-validated read** | 1 | 1 | `composables/useApiParsed.ts:40-52` (`fetchParsed`), used once at `components/CodingRunMonitor.vue:122` | throws `SchemaParseError` | — | — | JCLAW-287 "phase 1" never got a phase 2: 6 schemas defined, 3 referenced |
| **I. Streaming (out of scope for any query library)** | 2 raw `fetch` + 1 `EventSource` | 3 | `composables/useChatStream.ts:520`, `useReadAloud.ts:154`, `useEventBus.ts` | own `AbortController` (`useChatStream.ts:156,518`, `useReadAloud.ts:19,148`) | n/a | `shallowRef` messages mutated in place | Correct as is; must stay outside a query cache |

**AbortController usage.** Five occurrences in three files. Two are user-cancel of a stream (I above), one is a 5 s timeout on the status probe (`layouts/default.vue:133-134`). **None is used to supersede a stale read.** Nuxt's `useFetch` aborts internally under its default `dedupe: 'cancel'` (`node_modules/nuxt/dist/app/composables/asyncData.js:41,328-335`), so pattern A/B is covered without app code; pattern C is not.

### 1.1 Stale-response overwrite: the one real bug class

A read is issued, the key changes (route param, selected id, filter, page), a second read is issued, and whichever response lands last wins. Guards exist in exactly four places and are correct:

- `pages/subagents.vue:164-179` — monotonic `latestRefresh`, checked before assignment.
- `pages/skills/[[name]].vue:104,130,144,149` — `catalogSeq` for catalog search only.
- `composables/useChatSubagentChips.ts:62,88` — request id plus a re-check of `selectedConvoId`.
- `composables/useSubagentTranscript.ts:202-204,234,244` — in-flight serialisation plus an `active` flag.

Un-awaited `refresh()` calls on a `useFetch` handle (`SettingsModelRouterPanel.vue:161,208`, `pages/index.vue` latency) are already safe: Nuxt aborts the in-flight request under its default `dedupe: 'cancel'` (`asyncData.js:328`). The hazard is confined to bare `$fetch` reads.

Unguarded (a slow older response overwrites a newer one; verified by reading each assignment):

| Site | Trigger | What gets overwritten |
| --- | --- | --- |
| `composables/useChatConversation.ts:174-198` | sidebar click / deep link; `selectedConvoId` set at `:175`, `messages` assigned at `:195` | conversation B's header over conversation A's transcript |
| `composables/useChatConversation.ts:99-104` (`reconcileMessageIds`) | end of stream | id backfill into the wrong conversation |
| `composables/useChatAnnouncePoller.ts:233-263` | 5 s poll; `convoId` captured `:233`, never re-checked | appends A's rows onto B |
| `pages/agents/[[name]].vue:811-824` | route watcher `:842-856` fires 7 unawaited loads | agent A's tools/skills/workspace/queue mode in agent B's form |
| `pages/agents/[[name]].vue:450,560` | channel selector | prompt breakdown / text for the previous channel |
| `pages/tasks.vue:284` (`loadRuns`) | 2 s silent poll vs explicit load | runs list |
| `pages/tasks.vue:561,576` | `openTrace` while a previous trace is in flight; `peekRunId` never re-checked | run A's turns in run B's panel |
| `pages/skills/[[name]].vue:747-751,807-809` | route watcher `:780-794` | previous skill's files / file content in the new viewer |
| `pages/memories.vue:117-120` | `watch(url)` `:129` on filter/page | page 1 over page 2 |
| `pages/conversations/index.vue:112-115,132` | filter/sort/page | list |
| `components/settings/SettingsProvidersPanel.vue:499-502` | discover on provider A then B | A's models under B's header |
| `components/settings/SettingsTranscriptionPanel.vue:202-211` | `watch(diarizationIsLocal)` `:241` | model list |
| `components/CommandPalette.vue:68-82` | open/close/open | palette lists (low blast radius) |
| `layouts/default.vue:131-167` | `retryStatus` / `online` watcher re-enter `checkStatus` | `apiOnline` flag |

Two of these have already surfaced: commit `a1b657e2` (JCLAW-1196, "seen live": the SSE init frame assigned a new conversation id before the list refresh carrying its overrides landed) and the `test/agents.flows.test.ts` core-memory race, which is `pages/agents/[[name]].vue`'s post-open `$fetch` resolving after the test asserts (memory note `reference_agents_flows_core_migration_race`, still open as of 2026-09-15).

### 1.2 Polling

24 files, 28 `setInterval` sites. Every one pairs the interval with a `clearInterval` and an unmount hook (checked file by file). Fixed intervals: 1 s (`useMediaGenPolling.ts:99`), 1.5 s ×3, 2 s ×2, 5 s ×5, 10 s (`NotificationBar.vue:28`). One exponential backoff (`layouts/default.vue:174-193`, 10 s → 120 s). Six files gate on `document.hidden` by hand (`pages/logs.vue:26`, `pages/subagents.vue:241`, `NotificationBar.vue:37`, `SettingsJvmPanel.vue:89-106`, `useSubagentTranscript.ts:244`, `useChatSubagentChips.ts:134-142`); the other 18 poll hidden tabs. Two timers are not cleaned up: the self-rescheduling `pollCoreMigration` (`pages/agents/[[name]].vue:365-371`, no handle, no clear) and the 400 ms SSE debounce (`pages/tasks.vue:761-770`, absent from `onUnmounted` at `:735-738`).

The dashboard tick (`pages/index.vue:282-297`) fires 9 refreshes every 5 s, five of them separate `/api/tasks?status=…&limit=1` count requests (`:68-80`).

### 1.3 Nuxt's cache, precisely

Because `ssr: false`, the default `getCachedData` (`node_modules/nuxt/dist/app/composables/asyncData.js:413-416`) returns data only during hydration or from prerendered `static.data`, i.e. **never** here. Every page mount refetches. Within one page, `useFetch` sites with the same URL key share one entry and one in-flight promise (`:76-78`), and `purgeCachedData` (default `true`, `@nuxt/schema` `index.mjs:755`) drops the entry when its last consumer unmounts (`:402-407`). So `/api/agents`, fetched by 16 `useFetch`/`$fetch` sites, is requested once per page visit, on every visit. A failed `refresh()` resets `data` to `default()` — the trap behind JCLAW-1221's `resync` (memory note `reference_nuxt_refresh_error_resets_data`).

The consequence for tests: 68 of 159 test files call `clearNuxtData()` in `beforeEach` because the payload cache persists across `mountSuspended` calls in one file; 96 files stub the API with `registerEndpoint` (1,039 calls).

## 2. Client vs server state split

Counting reactive holders of data rather than lines, since there is no store to measure:

| Category | Holders | Share |
| --- | --- | --- |
| Server snapshots in `useFetch`/`useLazyFetch`/`useLazyAsyncData` `data` refs (page-scoped, refetched per mount) | 81 | ≈50% |
| Server snapshots in component-local refs filled by bare `$fetch` reads | ≈70 | ≈40% |
| Hand-rolled server caches (module refs, provide/inject) | 3 (`useToolMeta`, `useSubagentTranscript`, `useSettingsConfig`) | ≈2% |
| True client state (`useState` ×6, module refs for theme/confirm/read-aloud/voice, editor buffers, filters, wizard state) | ≈12 shared + per-page locals | ≈8% of shared holders |

Roughly **nine tenths of the reactive data holders are server snapshots**; the client-state share is small and already cleanly separated (auth, tour, theme, voice session, the Settings inline editor). Nothing here is "server state trapped in a client store" in the Pinia sense. The problem shape is the opposite: server snapshots with no shared owner, each page re-deriving its own loading, error and invalidation.

Worst offenders by hand-rolled plumbing (explorer line counts, fetch → loading → error → refetch only):

| File | Plumbing lines | Of | Notes |
| --- | --- | --- | --- |
| `pages/agents/[[name]].vue` | ≈330 | 3,657 | 7 unguarded loads per agent open; 3 optimistic-with-rollback toggles (`:929-945,960-986,1332-1348`) next to 11 refetch-after-write sites; an uncleared 1 s poll |
| `pages/tasks.vue` | ≈280 | 2,020 | 9 read paths, 12 `refreshAll()` sites, an N+1 export (`:690`), no error state on the two primary reads |
| `pages/skills/[[name]].vue` | ≈220 | 1,822 | `/api/skills/by-agent` fetched whole (`:46`) then patched per agent from `/api/agents/{id}/skills` (`:495,565,590`) |
| `components/settings/SettingsTranscriptionPanel.vue` | ≈190 | 339 script | two 1.5 s pollers, a watcher-driven unguarded model list, 8 write handlers each ending in `refresh()` |
| `components/settings/SettingsProvidersPanel.vue` | ≈150 | 622 script | a read with a write side effect (`fetchRanksForProvider:246-262` backfills prices → POST + `refresh()`); one shared `providerError` ref for every card (`:136`) |
| `composables/useChatAnnouncePoller.ts` | ≈130 | 304 | a 5 s poll gated by three hand-written predicates, one of which keeps polling for 30 min after any `task_manager` call (`:59,202-218`) |

Total across the 22 files read in depth: ≈2,400 plumbing lines against ≈54,000 non-test frontend lines, so **4–5% of the codebase is fetch plumbing**, concentrated in the six files above.

## 3. Verdict: HOLD

**Why not ADOPT.** The library's structural advantages are, in order: (a) a keyed cache that survives navigation with `staleTime`, (b) latest-wins per key, which erases §1.1 by construction, (c) `error`/`isError` on every read for free, (d) `refetchInterval` with background gating, (e) `invalidateQueries` after a mutation instead of hand-placed `refresh()`.

- (a) is worth little here. One operator, backend on `localhost:9000`, H2 reads in the low milliseconds. The cost of refetch-on-mount is not visible; the CLS work in `2ec36688`/`a6e66167` was about reserving heights, not about latency.
- (b) and (e) are the real gains, but the fix in-house is a 30-line `useLatest()` generation guard plus the discipline of keying handler reads on a `useAsyncData` with a reactive key. The four existing guards show the shape is already understood.
- (c) is an API affordance; the missing error UI on three list pages is a rendering omission that a library does not fill.
- (d) is 24 files that already work; the two leaked timers are two-line fixes.
- The migration surface is large for the payoff: 81 `useFetch` sites, ≈70 handler reads, 96 test files, 68 `clearNuxtData` hooks, and the AGENTS.md conventions written around Nuxt's primitive (lazy fetch for sidecar panels, `clearNuxtData` in `beforeEach`). A partial adoption leaves two read primitives with different cache and error semantics side by side, which is a worse steady state than either alone.
- The app's core surface, streaming chat, is in-place mutation of a `shallowRef` fed by SSE (`pages/chat.vue:82-87`, `useChatStream.ts`). It would stay outside the cache regardless, so the library would cover the periphery, not the centre.

**Why not REJECT.** Nothing would fight it. There is no Pinia, no axios interceptor layer, no global `$fetch` plugin (`frontend/plugins/` holds only axe, code-copy and theme); auth is a route middleware (`middleware/auth.global.ts`), not a fetch hook. `@tanstack/vue-table` is already a dependency. `registerEndpoint` mocks at the server edge, so a `queryFn` calling `$fetch` would keep working under the existing test harness. If the triggers below fire, adoption is mechanical rather than architectural.

**Revisit when any one of these holds:**

1. **A server entity is observed live in three or more places at once** and must stay consistent after a mutation from any of them — e.g. the agents list in the sidebar, the command palette and a page, all expected to reflect an enable toggle without a reload. Today each page owns its snapshot and the palette refetches on open (`CommandPalette.vue:68-82`), so the question does not arise.
2. **The backend stops being local.** If JClaw is served over Tailscale or a remote host to the SPA as the normal mode, refetch-on-every-mount becomes visible; measure with the existing `/api/metrics/latency` panel and revisit if median page-mount fetch time exceeds ~300 ms.
3. **The §1.1 class recurs after the in-house guard lands.** Land `useLatest()` (or equivalent) on the 15 sites above; if a new stale-overwrite defect is filed against a read that used it, the discipline is not holding and the structural fix is warranted.
4. **The handler-read count keeps growing.** The two newest panels this week (`SettingsModelRouterPanel.vue`, JCLAW-1222) still ship an unguarded `refreshStatus()` and no read-error path, so pattern C is the default being copied. If a count of bare `$fetch` reads outside a wrapper passes ~100 (from ≈70 today), the convention has failed and a primitive that makes the right thing the easy thing is justified.

## 4. Migration surface, if a trigger fires

Pilot in this order; each is self-contained and already has the worst of §1.1:

1. **`pages/agents/[[name]].vue`** — the seven per-agent loads (`:811-824`) become seven `useQuery` keyed on `['agent', id, 'tools' | 'skills' | …]`; the route watcher goes away; the three optimistic toggles map onto `useMutation` with `onMutate`/`onError` rollback; the 11 `refresh()` sites become one `invalidateQueries(['agents'])`. Also retires the uncleared core-migration poll via `refetchInterval: q => q.state.data?.running ? 1000 : false`.
2. **`pages/tasks.vue` + `components/ScheduleCalendar.vue`** — `runsByTask` (`:259`) is a per-task cache in a ref, which is exactly a `['task', id, 'runs']` query; the 2 s live poll and the 400 ms SSE debounce collapse into `refetchInterval` plus `invalidateQueries` from the `useEventBus` handlers at `:772-774`. The KPI strip going stale after narrow saves (`:373,416,457,516`) disappears with key-based invalidation.
3. **`composables/useSettingsConfig.ts`** — keep the provide/inject shape and the inline editor; swap the two `useFetch` handles for two queries. `resync()` becomes unnecessary because a failed refetch keeps `data` and sets `error`, which is the behaviour JCLAW-1221 had to hand-build.

Compose with, do not replace: `useApiMutation`/`useSaveAttempt` (their `ApiErrorDetails` normalisation is the `onError` payload), `useEventBus` (SSE events are the invalidation source), `fetchParsed` (a `queryFn`), `useToolMeta` (delete it; it is a query with `staleTime: Infinity`). Leave untouched: `useChatStream`, `useChatConversation`'s message array, `useReadAloud`, `useVoiceMode`, `useEventBus` itself.

Test impact: `registerEndpoint` stays; the 68 `clearNuxtData()` hooks become a fresh `QueryClient` per test via the plugin, one line in a shared setup.

## 5. Cost of staying

If nothing changes, this is what accrues, in the order it will be felt:

1. **Stale-overwrite defects keep shipping** at the rate new handler reads are written, because the default pattern (`$fetch` into a local ref, assign unconditionally) has no guard and the four guarded sites are not a shared helper. One has reached production (`a1b657e2`) and one is a known flaky test. Fixable now: a `useLatest()` composable and a sweep of the 15 sites in §1.1, roughly a day.
2. **Read failures stay invisible.** 18 empty catches, 30 console-only catches, and no `error` destructured from any of the 81 `useFetch`-family sites. A backend outage renders as "no data" everywhere except the memories list, the subagent transcript panel and provider discovery. Fixable now: destructure `error` and render `ApiErrorAlert`, page by page.
3. **The `resync` discipline drifts.** It exists because Nuxt's `refresh()` resets on failure; it is already missing from one of four handlers in the newest panel. Every future Settings write handler has to remember it. Not fixable without either wrapping `refresh()` once (small) or a different primitive.
4. **Wrapper bypass widens.** 40 of 58 write files already skip `useApiMutation`/`useSaveAttempt`; `fetchParsed` has one caller. Without a lint rule or a review norm, the JCLAW-287 and JCLAW-1131 envelopes cover a shrinking fraction of calls.
5. **Test setup stays coupled to the payload cache.** Every new page test needs the `clearNuxtData()` incantation or it silently reads the previous test's response (memory note `feedback_nuxt_test_usefetch_cache`). This is a documented trap, not growing debt, but it is paid per test file.
6. **Polling stays bespoke.** 24 hand-written loops, 18 of which poll hidden tabs, and two leaked timers. Low cost individually; the dashboard's 9-request tick is the one that would show up first if the backend were remote.

None of these requires the library. Items 1, 2 and 6 are a bounded sweep; 3 and 4 are a small wrapper plus a convention. Do those first. They are also the prerequisite for any later migration, because a query key is only useful once each read has a single owner and a stated error path.
