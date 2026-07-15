<script setup lang="ts">
// Apps page (SPEC-apps): a home-screen-style grid of operator-hosted mini-apps
// discovered from public/apps/<slug>/ via GET /api/apps. Clicking a card opens
// the app in a new tab at /apps/<slug>/. Pricing is metadata-only — a label.
// The "Create app" affordance (slice 3) will sit above the grid.
import { Squares2X2Icon } from '@heroicons/vue/24/outline'

interface AppEntry {
  id: string
  url: string
  name: string
  version: string
  creator: string | null
  icon: string | null
  price: string | null
  description: string | null
}

const { data, pending } = useLazyFetch<{ apps: AppEntry[] }>('/api/apps')
const apps = computed(() => data.value?.apps ?? [])

// Icons that are absent or fail to load fall back to a placeholder tile.
const iconFailed = reactive(new Set<string>())
function hasIcon(app: AppEntry): boolean {
  return !!app.icon && !iconFailed.has(app.id)
}
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-2">
      Apps
    </h1>
    <p class="text-sm text-fg-muted mb-6">
      Operator-hosted web apps, each a static site under
      <span class="font-mono">public/apps/&lt;slug&gt;/</span>. Click an app to open it in a new tab.
    </p>

    <div
      v-if="pending"
      class="flex items-center gap-2 text-sm text-fg-muted"
    >
      <span class="inline-block w-3.5 h-3.5 border-2 border-current border-t-transparent rounded-full animate-spin" />
      Loading apps…
    </div>

    <div
      v-else-if="!apps.length"
      class="text-sm text-fg-muted border border-dashed border-border rounded-lg px-4 py-8 text-center"
    >
      No apps yet. Create one with the <span class="font-mono">app-creator</span> skill —
      it builds a static app into <span class="font-mono">public/apps/</span>.
    </div>

    <div
      v-else
      class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-4"
    >
      <a
        v-for="app in apps"
        :key="app.id"
        :href="app.url"
        target="_blank"
        rel="noopener"
        :data-testid="`app-card-${app.id}`"
        class="group flex flex-col items-center gap-2 p-3 rounded-xl hover:bg-muted/40 transition-colors"
      >
        <img
          v-if="hasIcon(app)"
          :src="app.icon!"
          :alt="`${app.name} icon`"
          class="w-16 h-16 rounded-2xl object-cover border border-border shadow-sm group-hover:scale-105 transition-transform"
          @error="iconFailed.add(app.id)"
        >
        <div
          v-else
          class="w-16 h-16 rounded-2xl bg-muted border border-border shadow-sm flex items-center justify-center text-fg-muted group-hover:scale-105 transition-transform"
        >
          <Squares2X2Icon
            class="w-8 h-8"
            aria-hidden="true"
          />
        </div>
        <div class="text-center min-w-0 w-full">
          <div class="text-sm font-medium text-fg-strong truncate">
            {{ app.name }}
          </div>
          <div class="text-[11px] text-fg-muted truncate">
            v{{ app.version }}<span v-if="app.creator"> · {{ app.creator }}</span>
          </div>
          <div
            v-if="app.price"
            class="text-[11px] text-emerald-700 dark:text-emerald-400"
          >
            {{ app.price }}
          </div>
        </div>
      </a>
    </div>
  </div>
</template>
