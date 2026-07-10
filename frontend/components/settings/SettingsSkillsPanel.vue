<script setup lang="ts">
// Skills Promotion settings panel (JCLAW-680). LLM sanitization during skill
// promotion; uses the main agent's model by default if not configured. Moved
// verbatim from pages/settings.vue. Reads the shared config store + provider-
// model catalog; resolves defaults from the main agent via its own (Nuxt-
// deduped) /api/agents fetch.
import {
  CheckIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import type { Agent } from '~/types/api'

const { configData, saving, refresh, getProviderModels } = useSettingsConfig()

// Skills Promotion config
const { data: agentsList } = await useFetch<Agent[]>('/api/agents')
const mainAgent = computed(() => agentsList.value?.find(a => a.name === 'main') ?? null)

// JCLAW-229: image-generation-only providers are NOT chat LLM providers — their
// keys are set in the Image Generation section, so skip them when listing the
// providers the skills-promotion picker can choose from.
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

const spProviderRaw = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'skillsPromotion.provider')?.value ?? ''
})
const spModelRaw = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'skillsPromotion.model')?.value ?? ''
})

// Effective provider/model — resolve defaults from main agent
const spEffectiveProvider = computed(() => spProviderRaw.value || mainAgent.value?.modelProvider || '')
const spEffectiveModel = computed(() => spModelRaw.value || mainAgent.value?.modelId || '')

const spTimeout = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'skillsPromotion.timeoutSeconds')?.value ?? '300'
})
const spBatchKb = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'skillsPromotion.batchSizeKb')?.value ?? '100'
})

const editingSPField = ref<string | null>(null)
const spFieldEdit = ref('')

const spAvailableModels = computed(() => {
  // When editing provider, use the edit value; otherwise use the effective provider
  const name = editingSPField.value === 'provider' ? spFieldEdit.value : spEffectiveProvider.value
  if (!name) return []
  return getProviderModels(name)
})

// Whether an explicit (non-default) provider is selected
const spHasExplicitProvider = computed(() => !!spProviderRaw.value)

async function saveSPField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    // When provider changes, also clear the saved model — the old model likely
    // belongs to the previous provider and would be invalid for the new one.
    if (configKey === 'skillsPromotion.provider') {
      await $fetch('/api/config', { method: 'POST', body: { key: 'skillsPromotion.model', value: '' } })
    }
    editingSPField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <!-- Skills Promotion -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Skills Promotion
    </h2>
    <p class="text-xs text-fg-muted">
      LLM sanitization during skill promotion. Uses the main agent's model by default if not configured.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <!-- Provider -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0">provider</span>
          <template v-if="editingSPField === 'provider'">
            <select
              v-model="spFieldEdit"
              aria-label="Skills promotion provider"
              class="w-48 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
              <option value="">
                Default (main agent)
              </option>
              <option
                v-for="name in availableProviderNames"
                :key="name"
                :value="name"
              >
                {{ name }}
              </option>
            </select>
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveSPField('skillsPromotion.provider', spFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingSPField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">
              {{ spProviderRaw ? spProviderRaw : spEffectiveProvider ? spEffectiveProvider + ' (from main agent)' : 'Not configured' }}
            </span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingSPField = 'provider'; spFieldEdit = spProviderRaw"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- Model -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0">model</span>
          <template v-if="editingSPField === 'model'">
            <select
              v-if="spAvailableModels.length"
              v-model="spFieldEdit"
              aria-label="Skills promotion model"
              class="w-64 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
              <option
                v-if="!spHasExplicitProvider"
                value=""
              >
                Default (main agent)
              </option>
              <option
                v-for="m in spAvailableModels"
                :key="m.id"
                :value="m.id"
              >
                {{ m.name || m.id }}
              </option>
            </select>
            <input
              v-else
              v-model="spFieldEdit"
              type="text"
              placeholder="model-id"
              aria-label="Skills promotion model id"
              class="w-64 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveSPField('skillsPromotion.model', spFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingSPField = null"
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
              :class="spModelRaw || !spHasExplicitProvider ? 'text-fg-primary' : 'text-amber-700 dark:text-amber-400'"
            >
              {{ spModelRaw ? spModelRaw : spHasExplicitProvider ? 'Select a model' : spEffectiveModel ? spEffectiveModel + ' (from main agent)' : 'Not configured' }}
            </span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingSPField = 'model'; spFieldEdit = spModelRaw || (spHasExplicitProvider && spAvailableModels.length ? (spAvailableModels[0]?.id ?? '') : '')"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- Timeout -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0">timeoutSeconds</span>
          <template v-if="editingSPField === 'timeout'">
            <input
              v-model="spFieldEdit"
              type="number"
              min="30"
              max="900"
              aria-label="Skills promotion timeout seconds"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveSPField('skillsPromotion.timeoutSeconds', spFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingSPField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ spTimeout }}s</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingSPField = 'timeout'; spFieldEdit = spTimeout"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- Batch Size KB -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0">batchSizeKb</span>
          <template v-if="editingSPField === 'batchKb'">
            <input
              v-model="spFieldEdit"
              type="number"
              min="10"
              max="1000"
              aria-label="Batch size KB"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveSPField('skillsPromotion.batchSizeKb', spFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingSPField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ spBatchKb }} KB</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingSPField = 'batchKb'; spFieldEdit = spBatchKb"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
