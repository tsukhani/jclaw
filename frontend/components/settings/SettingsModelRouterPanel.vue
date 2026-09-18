<script setup lang="ts">
// Model Router settings panel (JCLAW-1222): the ordered model list per task class that the
// router/auto virtual model picks from, the two budget thresholds, and each listed provider's
// live quota usage.
import {
  ArrowDownIcon,
  ArrowUpIcon,
  CheckIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import { ROUTE_CLASS_LABELS } from '~/utils/model-route'

interface RouterCandidate {
  provider: string
  model: string
}

interface ProviderUsage {
  provider: string
  prepaid: boolean
  usageSource: boolean
  windows: Record<string, number>
  fetchedAt: string | null
}

interface RouterStatus {
  available: boolean
  downshiftAt: number
  exhaustedAt: number
  providers: ProviderUsage[]
  unavailable: Record<string, string>
}

const CLASS_HELP: Record<string, string> = {
  chat: 'Quick conversation, and the list every other class uses until you give it its own. Heavy classes also drop here when a subscription passes the downshift threshold, so put light, cheap models first.',
  summarize: 'Summaries, recaps and key points.',
  agentic: 'Multi-step work with tools: several actions, numbered steps, or a follow-up to a tool-heavy turn.',
  reasoning: 'Proofs, trade-offs, root causes and math.',
  coding: 'Code blocks, stack traces and code-heavy requests.',
}

// Mirrors RouterPolicy's defaults, used while the key is unset.
const DEFAULT_DOWNSHIFT_AT = 0.75
const DEFAULT_EXHAUSTED_AT = 0.95

const IMAGE_ONLY_PROVIDERS = new Set(['bfl', 'replicate'])

const { configData, saving, refresh, configValue, getProviderModels, providersData } = useSettingsConfig()
const { saveError, attempt } = useSaveAttempt()

// Lazy: the status call reads each Ollama Cloud provider's usage over the network, and must not
// hold the panel open while it does.
const { data: status, refresh: refreshStatus } = useLazyFetch<RouterStatus>('/api/router/status')

function modelsKey(taskClass: string): string {
  return `router.${taskClass}.models`
}

function listFor(taskClass: string): RouterCandidate[] {
  const raw = configValue(modelsKey(taskClass))
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed)
      ? parsed.filter((c): c is RouterCandidate => typeof c?.provider === 'string' && typeof c?.model === 'string')
      : []
  }
  catch {
    return []
  }
}

const modelOptions = computed(() => {
  const names = new Set<string>()
  for (const e of configData.value?.entries ?? []) {
    if (!e.key.startsWith('provider.')) continue
    const name = e.key.split('.')[1]!
    if (!IMAGE_ONLY_PROVIDERS.has(name)) names.add(name)
  }
  const options: { value: string, label: string }[] = []
  for (const provider of names) {
    for (const m of getProviderModels(provider)) {
      options.push({ value: `${provider}::${m.id}`, label: `${provider} / ${m.name || m.id}` })
    }
  }
  return options
})

const chatConfigured = computed(() => listFor('chat').length > 0)

// Unset means on: an operator who has set nothing gets credit protection.
const preferPrepaid = computed(() => configValue('router.preferPrepaid', 'true').toLowerCase() !== 'false')

/**
 * Prepaid — a subscription, or a self-hosted provider that bills nothing per call. Mirrors the
 * backend's ModelRouter.isPrepaid so the badge below says what the router will actually do.
 */
function isPrepaid(providerName: string): boolean {
  const p = (providersData.value ?? []).find(x => x.name === providerName)
  if (!p) return false
  return p.paymentModality === 'SUBSCRIPTION' || (p.supportedModalities.length === 0 && p.local)
}

/** True when prepaid-first ordering will pass this row over in favour of a prepaid one below it. */
function demoted(taskClass: string, candidate: RouterCandidate): boolean {
  return preferPrepaid.value && !isPrepaid(candidate.provider)
    && listFor(taskClass).some(c => isPrepaid(c.provider))
}

async function savePreferPrepaid(prefer: boolean) {
  saving.value = true
  await attempt(async () => {
    // Checked is the default, so it clears the key rather than storing what absence already means.
    if (prefer) await $fetch('/api/config/router.preferPrepaid', { method: 'DELETE' })
    else await $fetch('/api/config', { method: 'POST', body: { key: 'router.preferPrepaid', value: 'false' } })
  })
  await refresh()
  saving.value = false
}

// JCLAW-1222: the optional classifier model. Unset means the local keyword rules label every prompt.
const classifierValue = computed(() => {
  const p = configValue('router.classifier.provider')
  const m = configValue('router.classifier.model')
  return p && m ? `${p}::${m}` : ''
})

async function saveClassifier(value: string) {
  saving.value = true
  await attempt(async () => {
    if (value) {
      const sep = value.indexOf('::')
      await $fetch('/api/config', { method: 'POST', body: { key: 'router.classifier.provider', value: value.slice(0, sep) } })
      await $fetch('/api/config', { method: 'POST', body: { key: 'router.classifier.model', value: value.slice(sep + 2) } })
    }
    else {
      await $fetch('/api/config/router.classifier.provider', { method: 'DELETE' })
      await $fetch('/api/config/router.classifier.model', { method: 'DELETE' })
    }
  })
  await refresh()
  saving.value = false
}

async function saveList(taskClass: string, list: RouterCandidate[]) {
  saving.value = true
  const saved = await attempt(async () => {
    if (list.length) {
      await $fetch('/api/config', { method: 'POST', body: { key: modelsKey(taskClass), value: JSON.stringify(list) } })
    }
    else {
      await $fetch(`/api/config/${modelsKey(taskClass)}`, { method: 'DELETE' })
    }
  })
  await refresh()
  if (saved) refreshStatus()
  saving.value = false
}

function addModel(taskClass: string, value: string) {
  if (!value) return
  const sep = value.indexOf('::')
  const candidate = { provider: value.slice(0, sep), model: value.slice(sep + 2) }
  const list = listFor(taskClass)
  if (list.some(c => c.provider === candidate.provider && c.model === candidate.model)) return
  saveList(taskClass, [...list, candidate])
}

function removeModel(taskClass: string, index: number) {
  saveList(taskClass, listFor(taskClass).filter((_, i) => i !== index))
}

function moveModel(taskClass: string, index: number, delta: number) {
  const list = listFor(taskClass)
  const target = index + delta
  if (target < 0 || target >= list.length) return
  const moved = list[index]!
  list[index] = list[target]!
  list[target] = moved
  saveList(taskClass, list)
}

function thresholdPercent(key: string, fallback: number): string {
  const raw = configValue(key)
  const fraction = raw ? Number(raw) : fallback
  return Number.isFinite(fraction) ? String(Math.round(fraction * 100)) : String(Math.round(fallback * 100))
}

const editingThreshold = ref<string | null>(null)
const thresholdEdit = ref('')

async function saveThreshold(key: string) {
  saving.value = true
  const value = String(Number(thresholdEdit.value) / 100)
  const saved = await attempt(async () => {
    await $fetch('/api/config', { method: 'POST', body: { key, value } })
  })
  if (saved) editingThreshold.value = null
  await refresh()
  if (saved) refreshStatus()
  saving.value = false
}

function percent(fraction: number): string {
  return `${Math.round(fraction * 100)}%`
}

function usageTone(fraction: number): string {
  const s = status.value
  if (!s) return ''
  if (fraction >= s.exhaustedAt) return 'text-red-700 dark:text-red-400'
  if (fraction >= s.downshiftAt) return 'text-amber-700 dark:text-amber-400'
  return ''
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Model Router
    </h2>
    <p class="text-xs text-fg-muted">
      <span class="font-mono">router/auto</span> appears in every model picker once the Chat list has a
      model. For each prompt it picks a task class, then the first usable model on that class's list.
      The chat window shows which model answered each reply.
    </p>
    <label
      for="router-prefer-prepaid"
      class="flex items-start gap-2 text-xs text-fg-muted"
    >
      <input
        id="router-prefer-prepaid"
        type="checkbox"
        :checked="preferPrepaid"
        :disabled="saving"
        class="mt-0.5 accent-white"
        @change="savePreferPrepaid(($event.target as HTMLInputElement).checked)"
      >
      <span>
        Prefer subscription and self-hosted models over per-token ones, whatever the order below. On (the
        default), included credit is spent before money is, and a per-token model only serves when no
        prepaid one can. Off, the lists are followed exactly as written — the budget guard still applies.
      </span>
    </label>
    <p
      class="text-xs"
      :class="chatConfigured ? 'text-fg-muted' : 'text-amber-700 dark:text-amber-400'"
      data-testid="router-availability"
    >
      {{ chatConfigured ? 'Auto is offered in the model pickers.' : 'Add at least one Chat model to offer Auto in the model pickers.' }}
    </p>

    <div class="bg-surface-elevated border border-border divide-y divide-border">
      <div
        v-for="(label, taskClass) in ROUTE_CLASS_LABELS"
        :key="taskClass"
        class="px-4 py-3 space-y-2"
        :data-testid="`router-class-${taskClass}`"
      >
        <div class="flex items-center gap-1.5">
          <span class="text-sm text-fg-strong">{{ label }}</span>
          <InfoTip
            :label="`About the ${label} list`"
            content-class="w-72"
          >
            {{ CLASS_HELP[taskClass] }}
          </InfoTip>
          <span
            v-if="taskClass !== 'chat' && !listFor(taskClass).length"
            class="text-xs text-fg-muted"
          >uses the Chat list</span>
        </div>
        <ol
          v-if="listFor(taskClass).length"
          class="space-y-1"
        >
          <li
            v-for="(c, i) in listFor(taskClass)"
            :key="`${c.provider}::${c.model}`"
            class="flex items-center gap-2 text-sm font-mono text-fg-primary"
          >
            <span class="w-5 text-right text-xs text-fg-muted">{{ i + 1 }}.</span>
            <span class="min-w-0 truncate">{{ c.provider }} / {{ c.model }}</span>
            <span
              v-if="demoted(taskClass, c)"
              class="shrink-0 px-1.5 py-0.5 text-[10px] uppercase tracking-wide rounded text-fg-muted border border-border"
              title="Per-token: with the preference above on, the prepaid models in this list are tried first, whatever the order here."
            >fallback only</span>
            <span class="flex-1" />
            <button
              type="button"
              class="p-1 text-fg-muted hover:text-fg-strong disabled:opacity-40 transition-colors"
              :disabled="saving || i === 0"
              :aria-label="`Move ${c.provider} / ${c.model} up`"
              @click="moveModel(taskClass, i, -1)"
            >
              <ArrowUpIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              type="button"
              class="p-1 text-fg-muted hover:text-fg-strong disabled:opacity-40 transition-colors"
              :disabled="saving || i === listFor(taskClass).length - 1"
              :aria-label="`Move ${c.provider} / ${c.model} down`"
              @click="moveModel(taskClass, i, 1)"
            >
              <ArrowDownIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              type="button"
              class="p-1 text-fg-muted hover:text-red-600 dark:hover:text-red-400 disabled:opacity-40 transition-colors"
              :disabled="saving"
              :aria-label="`Remove ${c.provider} / ${c.model}`"
              @click="removeModel(taskClass, i)"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </li>
        </ol>
        <select
          value=""
          :aria-label="`Add a model to the ${label} list`"
          :disabled="saving"
          class="w-full max-w-md min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
          @change="addModel(taskClass, ($event.target as HTMLSelectElement).value); ($event.target as HTMLSelectElement).value = ''"
        >
          <option value="">
            Add a model…
          </option>
          <option
            v-for="o in modelOptions"
            :key="o.value"
            :value="o.value"
          >
            {{ o.label }}
          </option>
        </select>
      </div>
    </div>

    <h3 class="text-sm font-medium text-fg-muted">
      Classifier
    </h3>
    <p class="text-xs text-fg-muted">
      How each prompt gets its task class. The built-in keyword rules are free and instant, but they read
      words rather than intent, so a demanding prompt phrased in ordinary language can stay on the chat
      model. Naming a model here replaces them: it is asked which class fits and answers with one word.
      That costs one extra call before the reply starts, and the model sees the first 4000 characters of
      the prompt — so a remote classifier is one more place your prompts go. If it is unreachable, slow or
      answers with something else, the keyword rules decide instead and the turn carries on.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3">
        <span class="text-xs font-mono text-fg-muted w-56 max-sm:w-full shrink-0">classifier model</span>
        <select
          :value="classifierValue"
          aria-label="Prompt classifier model"
          :disabled="saving"
          class="flex-1 min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
          @change="saveClassifier(($event.target as HTMLSelectElement).value)"
        >
          <option value="">
            Keyword rules (no model call)
          </option>
          <option
            v-for="o in modelOptions"
            :key="o.value"
            :value="o.value"
          >
            {{ o.label }}
          </option>
        </select>
      </div>
    </div>

    <h3 class="text-sm font-medium text-fg-muted">
      Budget guard
    </h3>
    <p class="text-xs text-fg-muted">
      Usage is read from each Ollama Cloud provider's own quota windows. Past the downshift threshold, the
      Summarize, Agent work, Reasoning and Coding classes stop using that provider's models and fall back to
      the Chat list; past the exhausted threshold, no class uses it. A provider that answers a call with
      "out of credit" is also benched for a while.
    </p>
    <div class="bg-surface-elevated border border-border divide-y divide-border">
      <div
        v-for="row in [
          { key: 'router.budget.downshiftAt', label: 'Downshift at', fallback: DEFAULT_DOWNSHIFT_AT },
          { key: 'router.budget.exhaustedAt', label: 'Exhausted at', fallback: DEFAULT_EXHAUSTED_AT },
        ]"
        :key="row.key"
        class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3"
      >
        <span class="text-xs font-mono text-fg-muted w-56 max-sm:w-full shrink-0">{{ row.label }}</span>
        <template v-if="editingThreshold === row.key">
          <input
            v-model="thresholdEdit"
            type="number"
            min="1"
            max="100"
            :aria-label="`${row.label} (percent)`"
            class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
          >
          <span class="text-sm text-fg-muted">%</span>
          <button
            class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
            title="Save"
            @click="saveThreshold(row.key)"
          >
            <CheckIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
          <button
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            title="Cancel"
            @click="editingThreshold = null"
          >
            <XMarkIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </template>
        <template v-else>
          <span class="flex-1 text-sm text-fg-primary font-mono">{{ thresholdPercent(row.key, row.fallback) }}%</span>
          <button
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            title="Edit"
            @click="editingThreshold = row.key; thresholdEdit = thresholdPercent(row.key, row.fallback)"
          >
            <PencilIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </template>
      </div>
      <ApiErrorAlert
        :error="saveError"
        class="px-4 py-2.5"
      />
    </div>

    <div
      v-if="status?.providers.length"
      class="bg-surface-elevated border border-border divide-y divide-border"
      data-testid="router-usage"
    >
      <div
        v-for="p in status.providers"
        :key="p.provider"
        class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3 text-sm"
      >
        <span class="w-56 max-sm:w-full shrink-0 font-mono text-fg-strong">{{ p.provider }}</span>
        <span class="text-xs text-fg-muted w-28 shrink-0">{{ p.prepaid ? 'Prepaid' : 'Per-token' }}</span>
        <span class="flex-1 min-w-0 text-xs">
          <template v-if="Object.keys(p.windows).length">
            <span
              v-for="(fraction, name) in p.windows"
              :key="name"
              class="mr-3 font-mono"
              :class="usageTone(fraction)"
            >{{ name }} {{ percent(fraction) }}</span>
          </template>
          <span
            v-else
            class="text-fg-muted"
          >{{ p.usageSource ? 'Usage not read yet' : 'No usage API: benched only after an out-of-credit reply' }}</span>
        </span>
      </div>
      <div
        v-for="(until, key) in status.unavailable"
        :key="key"
        class="px-4 py-2 text-xs text-amber-700 dark:text-amber-400"
      >
        {{ key }} is benched for running out of credit until {{ new Date(until).toLocaleTimeString() }}
      </div>
    </div>
  </div>
</template>
