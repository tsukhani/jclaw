<script setup lang="ts">
// Apps page (SPEC-apps): a home-screen-style grid of operator-hosted mini-apps
// discovered from public/apps/<slug>/ via GET /api/apps. Clicking a card opens
// the app in a new tab at /apps/<slug>/. Pricing is metadata-only — a label.
// The "Create app" affordance (slice 3) will sit above the grid.
import { PencilSquareIcon, PlusIcon, Squares2X2Icon } from '@heroicons/vue/24/outline'

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

// Create-app affordance (slice 3): collect a brief, then hand it to the Chat
// composer's app-creator skill via ?compose=. Review-then-send — we prefill the
// request rather than auto-firing a multi-file build.
const creating = ref(false)
const newAppName = ref('')
const newAppBrief = ref('')

function buildApp() {
  const brief = newAppBrief.value.trim()
  if (!brief) return
  const name = newAppName.value.trim()
  const lines = ['Use the app-creator skill to build a hosted app.', '']
  if (name) lines.push(`App name: ${name}`)
  lines.push(`What it should do: ${brief}`)
  navigateTo({ path: '/chat', query: { compose: lines.join('\n') } })
}

function cancelCreate() {
  creating.value = false
  newAppName.value = ''
  newAppBrief.value = ''
}

// Update-app affordance: same app-creator hand-off, but scoped to an existing
// app and instructed to bump its version. Triggered per-card; the request names
// the app's slug so the skill edits public/apps/<slug>/ in place.
const updatingApp = ref<AppEntry | null>(null)
const updateBrief = ref('')

function startUpdate(app: AppEntry) {
  updatingApp.value = app
  updateBrief.value = ''
  creating.value = false // one affordance form at a time
}

function submitUpdate() {
  const app = updatingApp.value
  const changes = updateBrief.value.trim()
  if (!app || !changes) return
  const lines = [
    `Use the app-creator skill to update the existing hosted app "${app.name}" (public/apps/${app.id}/, currently v${app.version}).`,
    '',
    'Requested changes:',
    changes,
    '',
    'Apply the changes in place and bump the version in public/apps/'
    + `${app.id}/app.json (patch for small fixes, minor for new features, major for breaking changes).`,
  ]
  navigateTo({ path: '/chat', query: { compose: lines.join('\n') } })
}

function cancelUpdate() {
  updatingApp.value = null
  updateBrief.value = ''
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

    <!-- Create / update affordance: describe the app (or the changes), hand it to the app-creator skill in Chat. -->
    <div class="mb-6">
      <!-- Update form (per-card): scoped to an existing app + instructs a version bump. -->
      <form
        v-if="updatingApp"
        class="border border-border rounded-lg p-4 space-y-3 max-w-xl"
        @submit.prevent="submitUpdate"
      >
        <div class="text-sm font-medium text-fg-strong">
          Update <span class="text-emerald-700 dark:text-emerald-400">{{ updatingApp.name }}</span>
          <span class="text-xs font-normal text-fg-muted">v{{ updatingApp.version }} · public/apps/{{ updatingApp.id }}/</span>
        </div>
        <label
          for="update-app-brief"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">What should change?</span>
          <textarea
            id="update-app-brief"
            v-model="updateBrief"
            rows="3"
            placeholder="Add a dark-mode toggle; fix the total calculation."
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden resize-y"
          />
        </label>
        <div class="flex items-center gap-2">
          <button
            type="submit"
            :disabled="!updateBrief.trim()"
            data-testid="update-app-submit"
            class="px-3 py-1.5 text-sm font-medium bg-emerald-600 text-white rounded hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            Update in Chat →
          </button>
          <button
            type="button"
            class="px-3 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors"
            @click="cancelUpdate"
          >
            Cancel
          </button>
        </div>
        <p class="text-[11px] text-fg-muted">
          Hands the changes to the <span class="font-mono">app-creator</span> skill in Chat, which
          updates <span class="font-mono">public/apps/{{ updatingApp.id }}/</span> and bumps its version. Review + send it there.
        </p>
      </form>
      <button
        v-else-if="!creating"
        type="button"
        data-testid="create-app-button"
        class="inline-flex items-center gap-2 px-3 py-2 text-sm font-medium text-fg-primary border border-border rounded-lg hover:border-emerald-500 hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
        @click="creating = true"
      >
        <PlusIcon
          class="w-4 h-4"
          aria-hidden="true"
        />
        Create app
      </button>
      <form
        v-else
        class="border border-border rounded-lg p-4 space-y-3 max-w-xl"
        @submit.prevent="buildApp"
      >
        <label
          for="new-app-name"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">App name <span class="text-fg-muted/70">(optional)</span></span>
          <input
            id="new-app-name"
            v-model="newAppName"
            type="text"
            placeholder="Proposal Generator"
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
          >
        </label>
        <label
          for="new-app-brief"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">What should the app do?</span>
          <textarea
            id="new-app-brief"
            v-model="newAppBrief"
            rows="3"
            placeholder="An RFP + proposal builder where users create RFPs and generate proposals from them."
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden resize-y"
          />
        </label>
        <div class="flex items-center gap-2">
          <button
            type="submit"
            :disabled="!newAppBrief.trim()"
            data-testid="build-app-submit"
            class="px-3 py-1.5 text-sm font-medium bg-emerald-600 text-white rounded hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            Build in Chat →
          </button>
          <button
            type="button"
            class="px-3 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors"
            @click="cancelCreate"
          >
            Cancel
          </button>
        </div>
        <p class="text-[11px] text-fg-muted">
          Hands your description to the <span class="font-mono">app-creator</span> skill in Chat,
          which builds the app into <span class="font-mono">public/apps/</span>. Review + send it there.
        </p>
      </form>
    </div>

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
      <div
        v-for="app in apps"
        :key="app.id"
        class="group relative"
      >
        <a
          :href="app.url"
          target="_blank"
          rel="noopener"
          :data-testid="`app-card-${app.id}`"
          class="flex flex-col items-center gap-2 p-3 rounded-xl hover:bg-muted/40 transition-colors"
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
        <button
          type="button"
          :data-testid="`update-app-${app.id}`"
          class="absolute top-1 right-1 p-1 rounded-md bg-surface-elevated/90 border border-border text-fg-muted opacity-0 group-hover:opacity-100 focus:opacity-100 hover:text-emerald-700 dark:hover:text-emerald-400 transition-opacity"
          title="Update this app"
          aria-label="Update this app"
          @click="startUpdate(app)"
        >
          <PencilSquareIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
      </div>
    </div>
  </div>
</template>
