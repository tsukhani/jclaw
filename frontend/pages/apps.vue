<script setup lang="ts">
// Apps page (SPEC-apps): a home-screen-style grid of operator-hosted mini-apps
// discovered from public/apps/<slug>/ via GET /api/apps. Clicking a card opens
// the app in a new tab at /apps/<slug>/. Pricing is metadata-only — a label.
// The "Create app" affordance (slice 3) will sit above the grid.
import { MagnifyingGlassIcon, PencilSquareIcon, PlusIcon, Squares2X2Icon, TrashIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import type { Agent } from '~/types/api'

interface AppEntry {
  id: string
  url: string
  name: string
  version: string
  creator: string | null
  icon: string | null
  price: string | null
  description: string | null
  // JCLAW-763: designated agent id this app may invoke, or null when non-invoking.
  agent: string | null
}

const { data, pending, refresh } = useLazyFetch<{ apps: AppEntry[] }>('/api/apps')
const apps = computed(() => data.value?.apps ?? [])

// Real-time name filter driven by the floating search bar (smartphone-style).
// Case-insensitive substring match on the app name; empty query = show all.
const search = ref('')
const filteredApps = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return apps.value
  return apps.value.filter(a => a.name.toLowerCase().includes(q))
})

const { confirm } = useConfirm()

// Agent picker source (JCLAW-763). Resilient default so the page still renders
// if the agents endpoint is slow/unavailable — the picker just shows "none".
const { data: agentsData } = useLazyFetch<Agent[]>('/api/agents', { default: () => [] })
const agents = computed(() => agentsData.value ?? [])

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
const newAppAuthor = ref('') // app.json "creator"
const newAppBrief = ref('')
const newAppAgent = ref('') // designated agent id ('' = none), JCLAW-763
// Assembled pricing label from <AppCostFields> ("Free" | "$9/mo" | "$29"), the
// app.json "price" metadata. Null while the choice is incomplete.
const newAppPrice = ref<string | null>(null)

function buildApp() {
  const brief = newAppBrief.value.trim()
  if (!brief) return
  const name = newAppName.value.trim()
  const author = newAppAuthor.value.trim()
  const lines = ['Use the app-creator skill to build a hosted app.', '']
  if (name) lines.push(`App name: ${name}`)
  if (author) lines.push(`Author (write it as the app.json "creator" field): ${author}`)
  lines.push(`What it should do: ${brief}`)
  if (newAppAgent.value) {
    lines.push(`Designated agent id (write it as the app.json "agent" field so the app can invoke this agent): ${newAppAgent.value}`)
  }
  if (newAppPrice.value) {
    lines.push(`Pricing label (write it as the app.json "price" field): ${newAppPrice.value}`)
  }
  navigateTo({ path: '/chat', query: { compose: lines.join('\n') } })
}

function cancelCreate() {
  creating.value = false
  newAppName.value = ''
  newAppAuthor.value = ''
  newAppBrief.value = ''
  newAppAgent.value = ''
  newAppPrice.value = null
}

// Update-app affordance: same app-creator hand-off, but scoped to an existing
// app and instructed to bump its version. Triggered per-card; the request names
// the app's slug so the skill edits public/apps/<slug>/ in place.
const updatingApp = ref<AppEntry | null>(null)
const updateBrief = ref('')
const updateAgent = ref('') // designated agent id ('' = none), JCLAW-763
// New pricing label from <AppCostFields allow-unchanged> — null means "keep
// current pricing" (the default), so a price edit isn't forced on every update.
const updatePrice = ref<string | null>(null)

// The picked agent differs from what's on disk — lets an agent-only change submit
// without also typing a change brief.
const updateAgentChanged = computed(() =>
  !!updatingApp.value && (updateAgent.value || null) !== (updatingApp.value.agent || null))
// A new pricing label was chosen (not "keep current") — like the agent change,
// it alone can enable submit without a text brief.
const updatePriceChanged = computed(() => updatePrice.value !== null)
// The app's stored agent id resolves to no current agent (deleted/renamed) — AC5:
// surface it as a selectable option so it's visible and removable, not silently blank.
const updateAgentStale = computed(() =>
  !!updateAgent.value && !agents.value.some(a => String(a.id) === updateAgent.value))

function startUpdate(app: AppEntry) {
  updatingApp.value = app
  updateBrief.value = ''
  updateAgent.value = app.agent ?? ''
  updatePrice.value = null
  creating.value = false // one affordance form at a time
}

function submitUpdate() {
  const app = updatingApp.value
  if (!app) return
  const changes = updateBrief.value.trim()
  const agentChanged = (updateAgent.value || null) !== (app.agent || null)
  const priceChanged = updatePrice.value !== null
  if (!changes && !agentChanged && !priceChanged) return
  const lines = [
    `Use the app-creator skill to update the existing hosted app "${app.name}" (public/apps/${app.id}/, currently v${app.version}).`,
    '',
  ]
  if (changes) {
    lines.push('Requested changes:', changes, '')
  }
  if (agentChanged) {
    lines.push(updateAgent.value
      ? `Set the designated agent: write app.json "agent" = ${updateAgent.value} (the id of the agent this app may invoke).`
      : 'Remove the designated agent: delete the "agent" field from app.json so the app is non-invoking.', '')
  }
  if (priceChanged) {
    lines.push(`Set the pricing label: write app.json "price" = "${updatePrice.value}".`, '')
  }
  lines.push('Apply the changes in place and bump the version in public/apps/'
    + `${app.id}/app.json (patch for small fixes, minor for new features, major for breaking changes).`)
  navigateTo({ path: '/chat', query: { compose: lines.join('\n') } })
}

function cancelUpdate() {
  updatingApp.value = null
  updateBrief.value = ''
  updateAgent.value = ''
  updatePrice.value = null
}

// Delete a hosted app from its card's trash button, behind a danger confirm. An
// app is purely a public/apps/<slug>/ directory (no DB row), so the DELETE fully
// removes it. `deletingId` disables just this card while the request is in flight.
const deletingId = ref<string | null>(null)

async function deleteApp(app: AppEntry) {
  const ok = await confirm({
    title: 'Delete app',
    message: `Delete "${app.name}"? This permanently removes public/apps/${app.id}/ and can't be undone.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  deletingId.value = app.id
  try {
    await $fetch(`/api/apps/${app.id}`, { method: 'DELETE' })
    if (updatingApp.value?.id === app.id) cancelUpdate() // close the update form if it targeted this app
    await refresh()
  }
  catch (e) {
    console.error('Failed to delete app:', e)
  }
  finally {
    deletingId.value = null
  }
}
</script>

<template>
  <div class="flex flex-col min-h-full">
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
        <label
          for="update-app-agent"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">Designated agent</span>
          <select
            id="update-app-agent"
            v-model="updateAgent"
            data-testid="update-app-agent"
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
          >
            <option value="">
              — No agent (non-invoking) —
            </option>
            <option
              v-for="a in agents"
              :key="a.id"
              :value="String(a.id)"
            >
              {{ a.name }}
            </option>
            <option
              v-if="updateAgentStale"
              :value="updateAgent"
            >
              Unknown agent (id {{ updateAgent }}) — removed
            </option>
          </select>
        </label>
        <AppCostFields
          :key="updatingApp.id"
          v-model:price-label="updatePrice"
          allow-unchanged
        />
        <div class="flex items-center gap-2">
          <button
            type="submit"
            :disabled="!updateBrief.trim() && !updateAgentChanged && !updatePriceChanged"
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
          for="new-app-author"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">Author <span class="text-fg-muted/70">(optional)</span></span>
          <input
            id="new-app-author"
            v-model="newAppAuthor"
            type="text"
            placeholder="Your name"
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
        <label
          for="new-app-agent"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">Designated agent <span class="text-fg-muted/70">(optional — lets the app invoke this agent)</span></span>
          <select
            id="new-app-agent"
            v-model="newAppAgent"
            class="w-full px-2 py-1.5 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
          >
            <option value="">
              — No agent (non-invoking) —
            </option>
            <option
              v-for="a in agents"
              :key="a.id"
              :value="String(a.id)"
            >
              {{ a.name }}
            </option>
          </select>
        </label>
        <AppCostFields v-model:price-label="newAppPrice" />
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
      v-else-if="!filteredApps.length"
      class="text-sm text-fg-muted border border-dashed border-border rounded-lg px-4 py-8 text-center"
    >
      No apps match “<span class="font-medium text-fg-strong">{{ search }}</span>”.
    </div>

    <div
      v-else
      class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-4"
    >
      <div
        v-for="app in filteredApps"
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
          class="absolute top-1 left-1 p-1 rounded-md text-fg-muted opacity-0 group-hover:opacity-100 focus:opacity-100 hover:text-emerald-700 dark:hover:text-emerald-400 transition-opacity"
          title="Update this app"
          aria-label="Update this app"
          @click="startUpdate(app)"
        >
          <PencilSquareIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
        <button
          type="button"
          :data-testid="`delete-app-${app.id}`"
          :disabled="deletingId === app.id"
          class="absolute top-1 right-1 p-1 rounded-md text-fg-muted opacity-0 group-hover:opacity-100 focus:opacity-100 hover:text-red-600 dark:hover:text-red-400 disabled:opacity-50 disabled:cursor-not-allowed transition-opacity"
          title="Delete this app"
          aria-label="Delete this app"
          @click="deleteApp(app)"
        >
          <TrashIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
      </div>
    </div>

    <!-- Floating search bar (smartphone-style): pinned to the bottom-center of
         the content, filters the grid by app name in real time. mt-auto pushes
         it to the bottom; sticky keeps it there as the grid scrolls under it. -->
    <div
      v-if="apps.length"
      class="sticky bottom-6 z-20 mt-auto mx-auto flex items-center gap-2 w-full max-w-xs px-4 py-2.5 rounded-full bg-surface-elevated/80 backdrop-blur-md border border-border shadow-lg"
    >
      <MagnifyingGlassIcon
        class="w-4 h-4 text-fg-muted shrink-0"
        aria-hidden="true"
      />
      <label
        for="apps-search"
        class="flex-1 min-w-0"
      >
        <span class="sr-only">Search apps by name</span>
        <input
          id="apps-search"
          v-model="search"
          type="search"
          placeholder="Search apps"
          class="w-full bg-transparent text-sm text-fg-strong placeholder:text-fg-muted focus:outline-none [&::-webkit-search-cancel-button]:appearance-none"
          @keydown.escape="search = ''"
        >
      </label>
      <button
        v-if="search"
        type="button"
        aria-label="Clear search"
        class="shrink-0 text-fg-muted hover:text-fg-strong transition-colors"
        @click="search = ''"
      >
        <XMarkIcon
          class="w-4 h-4"
          aria-hidden="true"
        />
      </button>
    </div>
  </div>
</template>
