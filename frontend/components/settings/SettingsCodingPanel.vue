<script setup lang="ts">
// Coding settings panel: the external coding-harness (ACP) surface — the harness
// command, the harnesses detected on this host, and the model the harness runs
// with. Split out of the Subagents panel, which keeps the recursion caps.
import {
  CheckIcon,
  InformationCircleIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const { configData, saving, refresh, getProviderModels } = useSettingsConfig()

// JCLAW-229: image-generation-only providers are NOT chat LLM providers — their
// keys are set in the Image Generation section, so skip them when listing the
// providers the harness-model picker can choose from.
const IMAGE_ONLY_PROVIDERS = new Set(['bfl', 'replicate'])

const availableProviderNames = computed(() => {
  const names = new Set<string>()
  for (const e of configData.value?.entries ?? []) {
    if (!e.key.startsWith('provider.')) continue
    const name = e.key.split('.')[1]!
    if (IMAGE_ONLY_PROVIDERS.has(name)) continue
    names.add(name)
  }
  return [...names]
})

const allModelOptions = computed(() => {
  const opts: { value: string, label: string }[] = []
  for (const provider of availableProviderNames.value) {
    for (const m of getProviderModels(provider)) {
      opts.push({ value: `${provider}::${m.id}`, label: `${provider} / ${m.name || m.id}` })
    }
  }
  return opts
})

// JCLAW-499: external-harness (ACP) runtime command. Empty = disabled
// (runtime="acp" spawns are refused until set). Operator-set only.
const subagentAcpCommand = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'subagent.acp.command')?.value ?? ''
})

const editingField = ref<string | null>(null)
const fieldEdit = ref('')

// What a runtime="acp" spawn actually launches under the current config. Derived
// on the backend (AcpCommandPreview) because the per-harness model flags, and
// whether an ACP adapter replaces the command, are only knowable there. Lazy:
// it probes the adapter binary, so a slow probe must not suspend the panel.
interface CommandPreview {
  command: string
  harness: string
  effective: string
  env: string[]
  rejection: string | null
  acpAdapter: boolean
}
const { data: previewData, refresh: refreshPreview }
  = useLazyFetch<CommandPreview>('/api/subagents/acp-command')

// Only worth showing when the launch differs from the stored command — a bare
// harness with no override would just repeat the row above it.
const showsLaunch = computed(() => {
  const p = previewData.value
  if (!p || !p.command) return false
  return p.effective !== p.command || p.env.length > 0 || !!p.rejection
})

// Auto-detect the ACP coding harnesses installed on the host (claude/pi/codex).
// The probe shells out per binary, so use useLazyFetch — a slow/hung probe must
// never suspend the panel. Re-probed only on Settings (re)open.
interface DetectedHarness {
  id: string
  name: string
  command: string
  harness: string // the subagent.acp.harness adapter id to write (generic for custom)
  available: boolean
  reason: string
  custom: boolean
  acpSupport: string // native | adapter | adapter-missing | none
  acpDetail: string // tooltip: the ACP command, or the adapter install hint
}
const { data: harnessData, pending: harnessPending, refresh: refreshHarnesses }
  = useLazyFetch<{ harnesses: DetectedHarness[] }>('/api/subagents/acp-harnesses')
const detectedHarnesses = computed(() => harnessData.value?.harnesses ?? [])

// ACP-support badge per chip: shows whether the harness speaks ACP natively,
// via a detected adapter, via an adapter that still needs installing, or not at
// all (stdin/stdout fallback). Mirrors CodingRunMonitor's KIND_META pattern.
const STDIO_BADGE = { label: 'stdin/stdout', cls: 'text-fg-muted border-border' }
const ACP_BADGE: Record<string, { label: string, cls: string }> = {
  'native': { label: 'ACP native', cls: 'text-emerald-700 dark:text-emerald-400 border-emerald-500/40 bg-emerald-500/10' },
  'adapter': { label: 'ACP · adapter', cls: 'text-sky-700 dark:text-sky-400 border-sky-500/40 bg-sky-500/10' },
  'adapter-missing': { label: 'ACP · adapter', cls: 'text-amber-700 dark:text-amber-400 border-amber-500/40 bg-amber-500/10' },
  'none': STDIO_BADGE,
}
function acpBadge(h: DetectedHarness) {
  return ACP_BADGE[h.acpSupport] ?? STDIO_BADGE
}

async function saveField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingField.value = null
    refresh()
    await refreshPreview()
  }
  finally {
    saving.value = false
  }
}

// One click fills both the command and the adapter id so runtime="acp" is ready.
async function useHarness(h: DetectedHarness) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'subagent.acp.command', value: h.command } })
    await $fetch('/api/config', { method: 'POST', body: { key: 'subagent.acp.harness', value: h.harness } })
    editingField.value = null
    refresh()
    await refreshPreview()
  }
  finally {
    saving.value = false
  }
}

// Border/text styling for a chip: dim when unavailable, emerald when it's the
// currently-active command, neutral-with-hover otherwise.
function chipClass(h: DetectedHarness): string {
  if (!h.available) return 'border-border text-fg-muted opacity-60'
  if (subagentAcpCommand.value === h.command) {
    return 'border-emerald-500 text-emerald-700 dark:text-emerald-400 bg-emerald-500/10'
  }
  return 'border-input text-fg-primary hover:border-emerald-500'
}

// Add a custom harness: submit the command, JClaw probes whether its binary
// resolves on PATH, and on success it's persisted + shown as a new chip.
const customCommandInput = ref('')
const customError = ref('')
const addingCustom = ref(false)

async function addCustomHarness() {
  const cmd = customCommandInput.value.trim()
  if (!cmd) return
  addingCustom.value = true
  customError.value = ''
  try {
    const res = await $fetch<DetectedHarness>('/api/subagents/acp-harnesses', {
      method: 'POST', body: { command: cmd },
    })
    if (res.available) {
      customCommandInput.value = ''
      await refreshHarnesses()
    }
    else {
      customError.value = res.reason || 'That command’s binary was not found on PATH.'
    }
  }
  finally {
    addingCustom.value = false
  }
}

async function removeCustomHarness(command: string) {
  await $fetch('/api/subagents/acp-harnesses', { method: 'DELETE', query: { command } })
  await refreshHarnesses()
}

// Model the acp coding harness runs with instead of its own default. Unset (the
// default) leaves the harness on its own model and login; a specific value is
// bound per harness on the backend (claude/codex: endpoint + model; pi/gemini:
// model only). A per-spawn modelProvider/modelId still overrides it.
const acpModelValue = computed(() => {
  const entries = configData.value?.entries ?? []
  const p = entries.find(e => e.key === 'subagent.acp.modelProvider')?.value
  const m = entries.find(e => e.key === 'subagent.acp.modelId')?.value
  return p && m ? `${p}::${m}` : ''
})

async function saveAcpModel(value: string) {
  saving.value = true
  try {
    if (value) {
      const sep = value.indexOf('::')
      await $fetch('/api/config', { method: 'POST', body: { key: 'subagent.acp.modelProvider', value: value.slice(0, sep) } })
      await $fetch('/api/config', { method: 'POST', body: { key: 'subagent.acp.modelId', value: value.slice(sep + 2) } })
    }
    else {
      await $fetch('/api/config/subagent.acp.modelProvider', { method: 'DELETE' })
      await $fetch('/api/config/subagent.acp.modelId', { method: 'DELETE' })
    }
    refresh()
    await refreshPreview()
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Coding
    </h2>
    <p class="text-xs text-fg-muted">
      The external coding harness <span class="font-mono">subagent_spawn</span>
      drives for <span class="font-mono">runtime="acp"</span> children — a CLI
      such as Claude Code, Codex or Gemini CLI running on this host.
      <span class="font-mono">acp.command</span> is the harness to launch (empty
      refuses every <span class="font-mono">runtime="acp"</span> spawn),
      <span class="font-mono">detected</span> fills it from what's installed, and
      <span class="font-mono">acp.model</span> points the harness at a JClaw
      provider instead of its own login. Recursion caps and the native subagent
      model live in Subagents. Changes apply live; no restart needed.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <!-- JCLAW-499: external-harness (ACP) runtime command. Empty disables
             runtime="acp" subagents; the harness is operator-set, never model-supplied. -->
        <div class="px-4 py-2.5 flex items-start gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5 pt-1">
            acp.command
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-xs text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                External agent harness for runtime="acp" subagents — e.g. "claude -p" or "codex exec". The task is sent on stdin and stdout becomes the reply. Empty disables ACP (runtime="acp" spawns are refused). Operator-set only; never model-supplied.
              </span>
            </span>
          </span>
          <template v-if="editingField === 'acpCommand'">
            <input
              v-model="fieldEdit"
              type="text"
              placeholder="(disabled)"
              aria-label="ACP harness command"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveField('subagent.acp.command', fieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <div class="flex-1 min-w-0 space-y-1">
              <span
                class="block text-sm font-mono"
                :class="subagentAcpCommand ? 'text-fg-primary' : 'text-fg-muted'"
              >{{ subagentAcpCommand || '(disabled)' }}</span>
              <!-- Derived, not stored: the flags are appended per launch path
                   (AcpCommandPreview), so writing them back would pass them twice. -->
              <div
                v-if="showsLaunch"
                class="space-y-0.5"
                data-testid="acp-launch-preview"
              >
                <!-- codex's override is a six-flag inline TOML block; let it wrap. -->
                <p class="text-xs text-fg-muted break-words">
                  launches
                  <span class="font-mono text-fg-primary">{{ previewData!.effective }}</span>
                  <!-- Naming the harness explains the flags: acp.harness is what picks them,
                       and a hand-edited command can leave it pointing elsewhere. -->
                  <span class="ml-1 opacity-70">(harness {{ previewData!.harness }})</span>
                  <span
                    v-if="previewData!.acpAdapter"
                    class="ml-1"
                  >— real ACP over stdio replaces the command above</span>
                </p>
                <p
                  v-if="previewData!.env.length"
                  class="text-xs text-fg-muted"
                >
                  env <span class="font-mono">{{ previewData!.env.join(', ') }}</span>
                </p>
                <p
                  v-if="previewData!.rejection"
                  class="text-xs text-rose-600 dark:text-rose-400"
                >
                  {{ previewData!.rejection }}
                </p>
              </div>
            </div>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingField = 'acpCommand'; fieldEdit = subagentAcpCommand"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- Auto-detected harnesses on this host. Click an available one to fill
             acp.command + acp.harness so runtime="acp" is ready with no typing. -->
        <div class="px-4 py-2.5 flex items-start gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5 pt-1">
            detected
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-xs text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Coding-harness CLIs found on this host's PATH. Click an available one to fill acp.command + acp.harness. Re-open Settings to re-probe.
              </span>
            </span>
          </span>
          <div class="flex-1 flex flex-wrap items-center gap-2 pt-0.5">
            <span
              v-if="harnessPending"
              class="text-xs text-fg-muted"
            >Probing host…</span>
            <template v-else>
              <span
                v-for="h in detectedHarnesses"
                :key="h.id"
                class="inline-flex items-center border transition-colors"
                :class="chipClass(h)"
              >
                <button
                  type="button"
                  :disabled="!h.available || saving"
                  :title="h.available ? `Use ${h.command}` : h.reason"
                  class="inline-flex items-center gap-1.5 px-2 py-1 text-xs"
                  :class="h.available ? 'cursor-pointer' : 'cursor-not-allowed'"
                  @click="h.available && useHarness(h)"
                >
                  <span
                    class="w-1.5 h-1.5 rounded-full"
                    :class="h.available ? 'bg-emerald-500' : 'bg-fg-muted'"
                    aria-hidden="true"
                  />
                  {{ h.name }}
                  <span class="font-mono opacity-70">{{ h.command }}</span>
                  <span
                    class="px-1 py-px text-xs leading-none border rounded"
                    :class="acpBadge(h).cls"
                    :title="h.acpDetail"
                    :data-testid="`acp-badge-${h.id}`"
                  >{{ acpBadge(h).label }}</span>
                  <CheckIcon
                    v-if="h.available && subagentAcpCommand === h.command"
                    class="w-3 h-3"
                    aria-hidden="true"
                  />
                </button>
                <button
                  v-if="h.custom"
                  type="button"
                  :disabled="saving"
                  title="Remove custom harness"
                  aria-label="Remove custom harness"
                  class="px-1.5 py-1 self-stretch flex items-center text-fg-muted hover:text-rose-600 dark:hover:text-rose-400 border-l border-inherit"
                  @click="removeCustomHarness(h.command)"
                >
                  <XMarkIcon
                    class="w-3 h-3"
                    aria-hidden="true"
                  />
                </button>
              </span>
            </template>
            <!-- Add a custom harness: probe on submit; a resolvable binary becomes a chip. -->
            <div class="flex items-center gap-1.5 w-full mt-1">
              <input
                v-model="customCommandInput"
                type="text"
                placeholder="Add a harness command (e.g. aider --message)"
                aria-label="Custom harness command"
                class="flex-1 min-w-0 px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                @keydown.enter.prevent="addCustomHarness"
              >
              <button
                type="button"
                :disabled="addingCustom || saving || !customCommandInput.trim()"
                class="px-2 py-1 text-xs border border-input text-fg-primary hover:border-emerald-500 hover:text-emerald-700 dark:hover:text-emerald-400 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                @click="addCustomHarness"
              >
                {{ addingCustom ? 'Checking…' : 'Add' }}
              </button>
            </div>
            <p
              v-if="customError"
              class="text-xs text-rose-600 dark:text-rose-400 w-full"
            >
              {{ customError }}
            </p>
          </div>
        </div>
        <!-- Model the acp coding harness runs with. Default = the harness's own
             model; a specific provider/model is bound per harness on the backend. -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            acp.model
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-xs text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Provider/model the acp coding harness runs with instead of its own default. Claude Code and Codex are pointed at the provider's endpoint and model; Pi and Gemini CLI take the model only; opencode and custom harnesses take neither and refuse the spawn. A per-spawn modelProvider/modelId on subagent_spawn overrides this.
              </span>
            </span>
          </span>
          <select
            :value="acpModelValue"
            aria-label="ACP harness model"
            class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            @change="saveAcpModel(($event.target as HTMLSelectElement).value)"
          >
            <option value="">
              Harness default (the CLI's own model and login)
            </option>
            <option
              v-for="o in allModelOptions"
              :key="o.value"
              :value="o.value"
            >
              {{ o.label }}
            </option>
          </select>
        </div>
      </div>
    </div>
  </div>
</template>
