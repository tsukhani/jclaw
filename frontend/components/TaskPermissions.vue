<script setup lang="ts">
/**
 * JCLAW-1062/1068: what a task fire is permitted to do — its tool allow-list, the
 * origin that decides fire-time trust, and the model it fires on.
 *
 * The allow-list and model pin are set through the task API. Origin is recorded at
 * creation and no edit raises it (JCLAW-1021); the one way up is the operator's own
 * Trust, which this block offers and the page performs.
 */
const props = defineProps<{
  enabledToolNames?: string | null
  originChannel?: string | null
  modelProvider?: string | null
  modelId?: string | null
}>()

const emit = defineEmits<{ trust: [] }>()

/**
 * Mirror of services.TaskToolPolicy.parse. It has to agree exactly: showing pills for
 * a list the backend reads as unrestricted would assert a fence that is not there.
 * Accepts a JSON array, a comma-separated list, or a bare name; anything carrying JSON
 * punctuation that fails to parse is malformed and reads as unrestricted, as it does
 * server-side.
 */
const TOOL_NAME = /^[A-Za-z0-9_.-]+$/

const tools = computed<string[] | null>(() => {
  const raw = props.enabledToolNames
  if (!raw || !raw.trim()) return null
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed) && parsed.every(n => typeof n === 'string')) {
      const names = parsed.map(n => n.trim()).filter(Boolean)
      return names.length ? names : null
    }
    return null
  }
  catch {
    if (/[[\]{}"]/.test(raw)) return null
    const names = raw.split(',').map(n => n.trim()).filter(Boolean)
    return names.length && names.every(n => TOOL_NAME.test(n)) ? names : null
  }
})

/** web is the only operator origin; absent reads as UNKNOWN and fails closed. */
const origin = computed(() => {
  const o = props.originChannel
  if (!o) {
    return {
      label: 'unrecorded',
      tone: 'border-amber-500/40 text-amber-700 dark:text-amber-400',
      title: 'No recorded origin — classifies as UNKNOWN, so dangerous tools fail closed at fire time.',
    }
  }
  if (o === 'web') {
    return {
      label: 'web',
      tone: 'border-emerald-500/40 text-emerald-700 dark:text-emerald-400',
      title: 'Operator origin — dangerous tools resolve by the Tool Approvals policy instead of failing closed.',
    }
  }
  return {
    label: o,
    tone: 'border-border text-fg-muted',
    title: `Inbound channel origin — untrusted, so dangerous tools fail closed unless the policy is "ask".`,
  }
})

/** Unpinned fires on the agent's current model, so an agent edit silently re-points
 *  the task — the reason the absent pin is shown rather than left blank. */
const model = computed(() => {
  const id = props.modelId?.trim()
  const provider = props.modelProvider?.trim()
  if (!id) return null
  return provider ? `${provider} / ${id}` : id
})
</script>

<template>
  <section data-testid="task-permissions">
    <div class="text-[10px] uppercase tracking-wider font-medium text-fg-muted mb-1.5">
      Permissions
    </div>

    <div class="flex flex-wrap items-center gap-1.5 mb-2">
      <span class="text-[11px] text-fg-muted">Origin</span>
      <span
        class="text-[11px] px-1.5 py-0.5 border font-mono"
        :class="origin.tone"
        :title="origin.title"
        data-testid="task-origin-pill"
      >{{ origin.label }}</span>
      <button
        v-if="originChannel !== 'web'"
        type="button"
        class="inline-flex items-center text-xs text-fg-muted hover:text-fg-strong transition-colors bg-transparent border-0 cursor-pointer"
        title="Vouch for this task: record the operator origin, so its fires run dangerous tools under the Tool Approvals policy instead of failing closed."
        data-testid="task-origin-trust"
        @click="emit('trust')"
      >
        Trust
      </button>
    </div>

    <div class="flex flex-wrap items-center gap-1.5 mb-2">
      <span class="text-[11px] text-fg-muted">Model</span>
      <span
        v-if="model"
        class="text-[11px] px-1.5 py-0.5 border border-border text-fg-strong font-mono"
        title="Pinned — this task always fires on this model, whatever its agent is set to."
        data-testid="task-model-pill"
      >{{ model }}</span>
      <span
        v-else
        class="text-[11px] px-1.5 py-0.5 border border-border text-fg-muted"
        title="Not pinned — this task fires on its agent's current model, and follows any change to it."
        data-testid="task-model-unpinned"
      >follows the agent</span>
    </div>

    <div class="flex flex-wrap items-center gap-1.5">
      <span class="text-[11px] text-fg-muted">Tools</span>
      <span
        v-if="!tools"
        class="text-[11px] px-1.5 py-0.5 border border-border text-fg-muted"
        title="No allow-list — this task may use every tool its agent has."
        data-testid="task-tools-unrestricted"
      >all the agent&rsquo;s tools</span>
      <span
        v-for="tool in tools"
        v-else
        :key="tool"
        class="text-[11px] px-1.5 py-0.5 border border-border text-fg-strong font-mono"
        data-testid="task-tool-pill"
      >{{ tool }}</span>
    </div>
  </section>
</template>
