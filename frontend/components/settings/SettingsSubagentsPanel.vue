<script setup lang="ts">
// Subagents settings panel (JCLAW-266, moved in JCLAW-680). Recursion caps for
// the subagent_spawn tool plus the ACP harness command and the pinned subagent
// model. Moved verbatim from pages/settings.vue. Reads the shared config store +
// provider-model catalog; derives agent routing from its own (Nuxt-deduped)
// /api/agents fetch.
import {
  CheckIcon,
  InformationCircleIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import type { Agent } from '~/types/api'

const { configData, saving, refresh, getProviderModels } = useSettingsConfig()

const { data: agentsList } = await useFetch<Agent[]>('/api/agents')

// JCLAW-229: image-generation-only providers are NOT chat LLM providers — their
// keys are set in the Image Generation section, so skip them when listing the
// providers the subagent-model picker can choose from.
const IMAGE_ONLY_PROVIDERS = new Set(['bfl', 'replicate'])

// Distinct LLM-provider names configured in the Config DB (provider.<name>.*),
// minus the image-only providers. Built directly from the config store — same
// list the picker consumed before providerEntries moved to the Unmanaged panel.
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

// JCLAW-266: subagent recursion caps. DB-backed via ConfigService so the
// Settings page can edit them at runtime without a restart. Defaults
// mirror the Java-side fallbacks in SubagentSpawnTool.
const subagentMaxDepth = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'subagent.maxDepth')?.value ?? '1'
})

const subagentMaxChildrenPerParent = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'subagent.maxChildrenPerParent')?.value ?? '5'
})

// JCLAW-499: external-harness (ACP) runtime command. Empty = disabled
// (runtime="acp" spawns are refused until set). Operator-set only.
const subagentAcpCommand = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'subagent.acp.command')?.value ?? ''
})

const editingSubagentField = ref<string | null>(null)
const subagentFieldEdit = ref('')

async function saveSubagentField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingSubagentField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}

// JCLAW-422: subagent model. Unset (the default) = inherit the conversation's
// model — your agent's default unless you switch mid-chat. A specific value
// pins ALL fan-outs to that model (e.g. a cheaper one for large evaluations).
const subagentModelValue = computed(() => {
  const entries = configData.value?.entries ?? []
  const p = entries.find(e => e.key === 'subagent.modelProvider')?.value
  const m = entries.find(e => e.key === 'subagent.modelId')?.value
  return p && m ? `${p}::${m}` : ''
})

const subagentInheritLabel = computed(() => {
  const a = agentsList.value?.find(x => x.isMain) ?? agentsList.value?.[0]
  return a ? `${a.modelProvider} / ${a.modelId}` : 'the agent default'
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

async function saveSubagentModel(value: string) {
  saving.value = true
  try {
    if (!value) {
      await $fetch('/api/config/subagent.modelProvider', { method: 'DELETE' })
      await $fetch('/api/config/subagent.modelId', { method: 'DELETE' })
    }
    else {
      const sep = value.indexOf('::')
      await $fetch('/api/config', { method: 'POST', body: { key: 'subagent.modelProvider', value: value.slice(0, sep) } })
      await $fetch('/api/config', { method: 'POST', body: { key: 'subagent.modelId', value: value.slice(sep + 2) } })
    }
    refresh()
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <!-- Subagents (JCLAW-266) -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Subagents
    </h2>
    <p class="text-xs text-fg-muted">
      Recursion caps for the <span class="font-mono">subagent_spawn</span> tool.
      <span class="font-mono">maxDepth</span> bounds how deep the parent-child
      chain may go (1 = top-level agents may spawn, grandchildren refused);
      <span class="font-mono">maxChildrenPerParent</span> bounds how many
      concurrent <span class="font-mono">RUNNING</span> children a single parent
      may have in flight. On violation the tool emits
      <span class="font-mono">SUBAGENT_LIMIT_EXCEEDED</span> and returns a
      plain-text refusal to the model. Changes apply live; no restart needed.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            maxDepth
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Max recursion depth for subagent_spawn. 1 = only top-level agents may spawn; a child trying to spawn its own subagent is refused.
              </span>
            </span>
          </span>
          <template v-if="editingSubagentField === 'maxDepth'">
            <input
              v-model="subagentFieldEdit"
              type="number"
              min="1"
              max="32"
              aria-label="Max recursion depth"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveSubagentField('subagent.maxDepth', subagentFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingSubagentField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ subagentMaxDepth }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingSubagentField = 'maxDepth'; subagentFieldEdit = subagentMaxDepth"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            maxChildrenPerParent
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Max concurrent RUNNING children per parent. Counted per direct parent, not across the whole subtree. The (N+1)th spawn from the same parent is refused while N children are in flight.
              </span>
            </span>
          </span>
          <template v-if="editingSubagentField === 'maxChildrenPerParent'">
            <input
              v-model="subagentFieldEdit"
              type="number"
              min="1"
              max="64"
              aria-label="Max concurrent children per parent"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveSubagentField('subagent.maxChildrenPerParent', subagentFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingSubagentField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ subagentMaxChildrenPerParent }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingSubagentField = 'maxChildrenPerParent'; subagentFieldEdit = subagentMaxChildrenPerParent"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- JCLAW-499: external-harness (ACP) runtime command. Empty disables
             runtime="acp" subagents; the harness is operator-set, never model-supplied. -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            acp.command
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                External agent harness for runtime="acp" subagents — e.g. "claude -p" or "codex exec". The task is sent on stdin and stdout becomes the reply. Empty disables ACP (runtime="acp" spawns are refused). Operator-set only; never model-supplied.
              </span>
            </span>
          </span>
          <template v-if="editingSubagentField === 'acpCommand'">
            <input
              v-model="subagentFieldEdit"
              type="text"
              placeholder="(disabled)"
              aria-label="ACP harness command"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveSubagentField('subagent.acp.command', subagentFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingSubagentField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span
              class="flex-1 text-sm font-mono"
              :class="subagentAcpCommand ? 'text-fg-primary' : 'text-fg-muted'"
            >{{ subagentAcpCommand || '(disabled)' }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingSubagentField = 'acpCommand'; subagentFieldEdit = subagentAcpCommand"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- JCLAW-422: model subagents run on. Default (inherit) tracks the
             conversation's model; a specific value pins all fan-outs. -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            model
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Model subagents run on. "Conversation default" inherits the model your chat is using (your agent default unless you switch mid-chat). Pick a specific model to pin every fan-out to it — e.g. a cheaper model for large evaluations.
              </span>
            </span>
          </span>
          <select
            :value="subagentModelValue"
            aria-label="Subagent model"
            class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            @change="saveSubagentModel(($event.target as HTMLSelectElement).value)"
          >
            <option value="">
              Conversation default (inherit — {{ subagentInheritLabel }})
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
