<script setup lang="ts">
import type { ToolMeta } from '~/composables/useToolMeta'

const { TOOL_META, ORDERED_TOOLS } = useToolMeta()

const CATEGORIES = ['All', 'System', 'Web', 'Files', 'Utilities'] as const

// ─── Data fetching ─────────────────────────────────────────────────────────────

const { data: apiTools } = await useFetch<{ name: string }[]>('/api/tools')
const { data: agents } = await useFetch<{ id: number, name: string }[]>('/api/agents')

// Per-agent enabled state: [agentId][toolName] = boolean
const agentToolEnabled = ref<Record<number, Record<string, boolean>>>({})
const configLoading = ref(false)

async function loadAgentToolConfigs() {
  if (!agents.value?.length) return
  configLoading.value = true
  const map: Record<number, Record<string, boolean>> = {}
  await Promise.all(agents.value.map(async (agent) => {
    try {
      const tools = await $fetch<{ name: string, enabled: boolean }[]>(`/api/agents/${agent.id}/tools`)
      map[agent.id] = Object.fromEntries(tools.map(t => [t.name, t.enabled]))
    }
    catch {
      map[agent.id] = {}
    }
  }))
  agentToolEnabled.value = map
  configLoading.value = false
}

watch(agents, () => loadAgentToolConfigs(), { immediate: true })

// ─── Global enabled state ─────────────────────────────────────────────────────
// A tool is "globally enabled" when every agent has it enabled (or no override exists, which defaults to true).

function isGloballyEnabled(toolName: string): boolean {
  const ids = Object.keys(agentToolEnabled.value)
  if (!ids.length) return true
  return ids.every(id => agentToolEnabled.value[Number(id)]?.[toolName] !== false)
}

// ─── Toggle ───────────────────────────────────────────────────────────────────

const togglingSet = ref(new Set<string>())

async function toggleTool(toolName: string, enabled: boolean) {
  if (!agents.value?.length || togglingSet.value.has(toolName)) return
  togglingSet.value = new Set([...togglingSet.value, toolName])
  try {
    await Promise.all(
      agents.value.map(agent =>
        $fetch(`/api/agents/${agent.id}/tools/${toolName}`, {
          method: 'PUT',
          body: { enabled },
        }),
      ),
    )
    // Optimistic local state update
    const updated: typeof agentToolEnabled.value = {}
    for (const [id, toolMap] of Object.entries(agentToolEnabled.value)) {
      updated[Number(id)] = { ...toolMap, [toolName]: enabled }
    }
    agentToolEnabled.value = updated
  }
  finally {
    const s = new Set(togglingSet.value)
    s.delete(toolName)
    togglingSet.value = s
  }
}

// ─── Derived lists ─────────────────────────────────────────────────────────────

const registeredNames = computed(() => new Set((apiTools.value ?? []).map(t => t.name)))

interface ToolCard {
  name: string
  registered: boolean
  meta: ToolMeta
}

const allTools = computed<ToolCard[]>(() =>
  ORDERED_TOOLS.value
    .map(name => ({
      name,
      registered: registeredNames.value.has(name),
      meta: TOOL_META.value[name],
    }))
    .filter((t): t is ToolCard => !!t.meta && !t.meta.system),
)

const activeCategory = ref<typeof CATEGORIES[number]>('All')

const filteredTools = computed(() =>
  activeCategory.value === 'All'
    ? allTools.value
    : allTools.value.filter(t => t.meta.category === activeCategory.value),
)

// ─── Global enable/disable all ────────────────────────────────────────────────

const togglingAll = ref(false)

// true = every visible registered tool is enabled
const allEnabled = computed(() =>
  filteredTools.value.filter(t => t.registered).every(t => isGloballyEnabled(t.name)),
)

async function toggleAllEnabled() {
  if (!agents.value?.length || togglingAll.value) return
  const enabled = !allEnabled.value
  togglingAll.value = true
  try {
    const registeredVisible = filteredTools.value.filter(t => t.registered).map(t => t.name)
    await Promise.all(
      registeredVisible.flatMap(toolName =>
        agents.value!.map(agent =>
          $fetch(`/api/agents/${agent.id}/tools/${toolName}`, {
            method: 'PUT',
            body: { enabled },
          }),
        ),
      ),
    )
    // Optimistic local update
    const updated: typeof agentToolEnabled.value = {}
    for (const [id, toolMap] of Object.entries(agentToolEnabled.value)) {
      const patch = Object.fromEntries(registeredVisible.map(n => [n, enabled]))
      updated[Number(id)] = { ...toolMap, ...patch }
    }
    agentToolEnabled.value = updated
  }
  finally {
    togglingAll.value = false
  }
}

// ─── Expand/collapse ──────────────────────────────────────────────────────────

const expandedSet = ref(new Set<string>())

function toggleExpand(name: string) {
  const s = new Set(expandedSet.value)
  if (s.has(name)) s.delete(name)
  else s.add(name)
  expandedSet.value = s
}

const allExpanded = computed(() =>
  filteredTools.value.length > 0
  && filteredTools.value.every(t => expandedSet.value.has(t.name)),
)

function toggleAllExpanded() {
  if (allExpanded.value) {
    expandedSet.value = new Set()
  }
  else {
    expandedSet.value = new Set(filteredTools.value.map(t => t.name))
  }
}
</script>

<template>
  <div>
    <!-- Header -->
    <div class="mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        Tools
      </h1>
      <p class="mt-1 text-sm text-fg-muted">
        Built-in capabilities available to every agent. Toggle a tool to enable or disable it globally across all agents.
      </p>
    </div>

    <!-- Category filter + global expand/collapse -->
    <div class="flex items-center justify-between gap-3 mb-6">
      <div class="flex gap-1.5 flex-wrap">
        <button
          v-for="cat in CATEGORIES"
          :key="cat"
          class="px-3 py-1 text-xs border transition-colors"
          :class="activeCategory === cat
            ? 'bg-emerald-500/10 border-emerald-600 dark:border-emerald-500/40 text-emerald-700 dark:text-emerald-400'
            : 'bg-surface-elevated border-border text-fg-muted hover:text-fg-primary hover:border-input'"
          @click="activeCategory = cat"
        >
          {{ cat }}
        </button>
      </div>

      <div class="flex items-center gap-2 shrink-0">
        <!-- Global enable/disable all -->
        <button
          :disabled="togglingAll"
          class="flex items-center gap-2 px-3 py-1 text-xs border transition-colors disabled:cursor-not-allowed"
          :class="allEnabled
            ? 'border-emerald-600/40 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 hover:bg-emerald-500/20'
            : 'border-input bg-surface-elevated text-fg-muted hover:text-fg-primary hover:border-ring'"
          :title="allEnabled ? 'Disable all visible tools' : 'Enable all visible tools'"
          @click="toggleAllEnabled"
        >
          <div
            class="relative w-7 h-[15px] rounded-full transition-colors duration-200 shrink-0"
            :class="togglingAll ? 'bg-muted' : allEnabled ? 'bg-emerald-500' : 'bg-muted'"
          >
            <div
              class="absolute top-[1.5px] w-3 h-3 rounded-full bg-white shadow-sm transition-all duration-200"
              :class="allEnabled && !togglingAll ? 'left-[13px]' : 'left-[1.5px]'"
            />
          </div>
          {{ allEnabled ? 'All enabled' : 'All disabled' }}
        </button>

        <!-- Expand/collapse all -->
        <button
          class="flex items-center gap-1.5 px-3 py-1 text-xs border border-border bg-surface-elevated text-fg-muted hover:text-fg-primary hover:border-input transition-colors"
          @click="toggleAllExpanded"
        >
          <svg
            class="w-3 h-3 transition-transform duration-200"
            :class="allExpanded ? 'rotate-180' : ''"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M19 9l-7 7-7-7"
            />
          </svg>
          {{ allExpanded ? 'Collapse all' : 'Expand all' }}
        </button>
      </div>
    </div>

    <!-- Loading overlay for agent configs -->
    <p
      v-if="configLoading"
      class="text-xs text-fg-muted mb-4"
    >
      Loading agent configurations…
    </p>

    <!-- Grid -->
    <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
      <div
        v-for="tool in filteredTools"
        :key="tool.name"
        class="bg-surface-elevated border border-border flex flex-col transition-opacity"
        :class="tool.registered ? '' : 'opacity-40'"
      >
        <!-- Card header -->
        <div class="p-4 flex items-start gap-3">
          <!-- Icon -->
          <div
            class="w-9 h-9 rounded flex items-center justify-center shrink-0"
            :class="tool.meta.iconBg"
          >
            <svg
              class="w-5 h-5"
              :class="tool.meta.iconColor"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                v-if="tool.meta.icon === 'terminal'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.75"
                d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
              />
              <path
                v-if="tool.meta.icon === 'folder'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.75"
                d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"
              />
              <path
                v-if="tool.meta.icon === 'document'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.75"
                d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
              />
              <path
                v-if="tool.meta.icon === 'globe'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.75"
                d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9"
              />
              <path
                v-if="tool.meta.icon === 'search'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.75"
                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
              />
              <path
                v-if="tool.meta.icon === 'browser'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.75"
                d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
              />
              <path
                v-if="tool.meta.icon === 'clock'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.75"
                d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
              />
              <path
                v-if="tool.meta.icon === 'check'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.75"
                d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
              />
              <path
                v-if="tool.meta.icon === 'tasks'"
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="1.75"
                d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"
              />
            </svg>
          </div>

          <!-- Name + badge + toggle -->
          <div class="flex-1 min-w-0">
            <div class="flex items-center justify-between gap-2">
              <span class="text-sm font-mono font-semibold text-fg-strong truncate">{{ tool.name }}</span>

              <!-- Toggle (registered tools only) -->
              <button
                v-if="tool.registered"
                :disabled="togglingSet.has(tool.name)"
                :title="isGloballyEnabled(tool.name) ? 'Disable for all agents' : 'Enable for all agents'"
                class="shrink-0 disabled:cursor-not-allowed"
                @click="toggleTool(tool.name, !isGloballyEnabled(tool.name))"
              >
                <div
                  class="relative w-9 h-5 rounded-full transition-colors duration-200"
                  :class="togglingSet.has(tool.name)
                    ? 'bg-muted'
                    : isGloballyEnabled(tool.name)
                      ? 'bg-emerald-500'
                      : 'bg-muted'"
                >
                  <div
                    class="absolute top-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-all duration-200"
                    :class="isGloballyEnabled(tool.name) && !togglingSet.has(tool.name)
                      ? 'left-[18px]'
                      : 'left-0.5'"
                  />
                </div>
              </button>

              <!-- Unregistered: needs config -->
              <span
                v-else
                class="text-[10px] text-fg-muted shrink-0"
                :title="`Enable ${tool.meta.requiresConfig} in Settings to activate this tool`"
              >
                requires config
              </span>
            </div>

            <!-- Category badge -->
            <span
              class="mt-1.5 inline-block text-[10px] font-medium px-1.5 py-px rounded-sm leading-tight"
              :class="tool.meta.categoryColor"
            >
              {{ tool.meta.category }}
            </span>
          </div>
        </div>

        <!-- Description — fixed height so Functions header aligns across all cards in a row -->
        <p class="px-4 pb-4 text-xs text-fg-muted leading-relaxed min-h-[4rem] line-clamp-3">
          {{ tool.meta.shortDescription }}
        </p>

        <!-- Functions accordion header -->
        <button
          class="px-4 py-2.5 border-t border-border flex items-center justify-between w-full group transition-colors"
          :class="expandedSet.has(tool.name) ? 'bg-muted' : 'hover:bg-muted'"
          @click="toggleExpand(tool.name)"
        >
          <div class="flex items-center gap-2">
            <span class="text-[11px] font-medium text-fg-muted group-hover:text-fg-primary transition-colors">
              Functions
            </span>
            <span class="text-[10px] text-fg-primary bg-muted px-1.5 py-px rounded tabular-nums">
              {{ tool.meta.functions.length }}
            </span>
          </div>
          <svg
            class="w-3.5 h-3.5 text-fg-muted group-hover:text-fg-muted transition-all duration-200 shrink-0"
            :class="expandedSet.has(tool.name) ? 'rotate-180' : ''"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M19 9l-7 7-7-7"
            />
          </svg>
        </button>

        <!-- Functions panel -->
        <div
          v-if="expandedSet.has(tool.name)"
          class="border-t border-border"
        >
          <div
            v-for="fn in tool.meta.functions"
            :key="fn.name"
            class="px-4 py-2 flex items-start gap-3 border-b border-border last:border-b-0"
          >
            <code class="text-[10px] font-mono text-emerald-700 dark:text-emerald-400/80 shrink-0 mt-px w-32 truncate">{{ fn.name }}</code>
            <span class="text-[10px] text-fg-muted leading-relaxed">{{ fn.description }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
