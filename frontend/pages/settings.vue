<script setup lang="ts">
/**
 * Settings page (JCLAW-680).
 *
 * Two-pane layout mirroring the User Guide: left = sticky vertical TOC rail of
 * every section; right = the ONE active section, swapped via `<component :is>`.
 * Unlike the Guide (which stacks everything and scroll-spies), Settings swaps —
 * each section is independent and fetch-heavy (probes, discovery, polling), so
 * mounting only the active one means a backend's probes fire only when you open
 * its section. The swap target is wrapped in <Suspense> because panels use
 * top-level `await useFetch`; the fallback shows while a freshly-opened section
 * resolves.
 *
 * Deep-linking: the active section lives in `?section=<id>` so refreshes and
 * inbound links land on the right panel; the rail keeps the URL in sync.
 *
 * Sections come from `components/settings/sections.ts` — one entry per panel.
 */
import { Bars3Icon, XMarkIcon } from '@heroicons/vue/24/outline'
import { sectionGroups, sections } from '~/components/settings/sections'

// The shared /api/config store, inline config-row editor, and /api/providers
// billing projection live in the composable; every panel injects this context.
const { asyncConfig, asyncProviders } = useProvideSettingsConfig()
await asyncConfig
await asyncProviders

const route = useRoute()
const router = useRouter()
const mobileNavOpen = ref(false)

function isKnownSection(id: unknown): id is string {
  return typeof id === 'string' && sections.some(s => s.id === id)
}

const activeSectionId = ref<string>(
  isKnownSection(route.query.section) ? route.query.section : (sections[0]?.id ?? ''),
)
const activeSection = computed(() =>
  sections.find(s => s.id === activeSectionId.value) ?? sections[0],
)

function selectSection(id: string) {
  activeSectionId.value = id
  mobileNavOpen.value = false
  // replace (not push) — flipping settings sections shouldn't stack history.
  router.replace({ query: { ...route.query, section: id } })
}

// Keep the active section in sync with the URL for deep links and browser
// back/forward (which change route.query without remounting the page).
watch(() => route.query.section, (s) => {
  if (isKnownSection(s) && s !== activeSectionId.value) activeSectionId.value = s
})
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Settings
    </h1>

    <!-- Mobile TOC toggle. On md+ the sticky rail is always visible; on
         narrower viewports it collapses behind this button so the active
         section gets the full width. -->
    <button
      type="button"
      class="md:hidden mb-4 flex items-center gap-2 px-3 py-2 text-sm text-fg-strong border border-border rounded-lg bg-surface-elevated hover:bg-muted/40 transition-colors"
      :aria-expanded="mobileNavOpen"
      aria-controls="settings-toc"
      @click="mobileNavOpen = !mobileNavOpen"
    >
      <component
        :is="mobileNavOpen ? XMarkIcon : Bars3Icon"
        class="w-4 h-4"
        aria-hidden="true"
      />
      <span>{{ mobileNavOpen ? 'Hide sections' : (activeSection?.title ?? 'Sections') }}</span>
    </button>

    <div class="flex flex-col md:flex-row gap-8">
      <!-- TOC rail. Sticky on desktop; an inline collapsible panel on mobile
           that closes on selection via selectSection. -->
      <aside
        id="settings-toc"
        :class="[
          'md:w-56 md:shrink-0 md:sticky md:top-4 md:self-start',
          mobileNavOpen ? 'block' : 'hidden md:block',
        ]"
        aria-label="Settings sections"
      >
        <nav class="border border-border rounded-xl bg-surface-elevated overflow-hidden">
          <!-- One block per functional group: an uppercase header, then its
               section items. A top border separates each group from the last. -->
          <div
            v-for="(group, gi) in sectionGroups"
            :key="group.label"
            :class="gi > 0 ? 'border-t border-border' : ''"
          >
            <div class="px-3 pt-2.5 pb-1 text-[11px] font-semibold uppercase tracking-wide text-fg-muted">
              {{ group.label }}
            </div>
            <ul class="pb-1.5">
              <li
                v-for="s in group.sections"
                :key="s.id"
              >
                <button
                  type="button"
                  :class="[
                    'w-full flex items-center gap-2 px-3 py-2 text-sm text-left transition-colors',
                    activeSectionId === s.id
                      ? 'bg-emerald-50/60 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-300 font-medium'
                      : 'text-fg-strong hover:bg-muted/40',
                  ]"
                  :aria-current="activeSectionId === s.id ? 'page' : undefined"
                  :data-testid="`settings-toc-item-${s.id}`"
                  @click="selectSection(s.id)"
                >
                  <component
                    :is="s.icon"
                    class="w-3.5 h-3.5 shrink-0"
                    aria-hidden="true"
                  />
                  <span class="truncate">{{ s.title }}</span>
                </button>
              </li>
            </ul>
          </div>
        </nav>
      </aside>

      <!-- Active section. `:key` forces a fresh mount on section change so the
           new panel's setup (and its lazy fetches) runs; <Suspense> covers the
           panels' top-level `await useFetch` during that mount. -->
      <div class="flex-1 min-w-0">
        <Suspense>
          <component
            :is="activeSection?.component"
            :key="activeSection?.id"
          />
          <template #fallback>
            <div class="flex items-center gap-2 px-1 py-4 text-sm text-fg-muted">
              <span class="inline-block w-3.5 h-3.5 border-2 border-current border-t-transparent rounded-full animate-spin" />
              Loading {{ activeSection?.title }}…
            </div>
          </template>
        </Suspense>
      </div>
    </div>
  </div>
</template>
