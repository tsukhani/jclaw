<script setup lang="ts">
/**
 * JF-323: the secrets one agent's skill uses. The operator adds, replaces and deletes them here; the server never
 * returns a value, so this panel only ever shows names, hosts and when each was set. A typed value is cleared from
 * the form as soon as it is saved.
 *
 * JF-348: the skill's SKILL.md declares what it needs, in its secrets block. Each declared secret is listed with its state, and
 * while a required one has no value the skill is not runnable, which the panel says at its top.
 */
import type { ApiErrorDetails } from '~/types/api'

const props = defineProps<{ agentId: number, skillName: string }>()
/** After a secret is added, replaced or deleted, so the page can redraw the skill's not-runnable flag. */
const emit = defineEmits<{ changed: [] }>()

interface Secret { name: string, hosts: string[], setAt: string }
interface Expected {
  name: string
  description: string
  exampleHosts: string[]
  optional: boolean
  set: boolean
  setIn?: string | null
}

const secrets = ref<Secret[]>([])
const expected = ref<Expected[]>([])
const missing = ref<string[]>([])
const loading = ref(false)
const loadError = ref<ApiErrorDetails | null>(null)

const formMode = ref<'add' | 'replace' | null>(null)
const formName = ref('')
const formValue = ref('')
const formHosts = ref('')
/** The declared secret being added, for its description and example host; null for a free-form add. */
const formDeclared = ref<Expected | null>(null)

const { mutate, loading: saving, errorDetails: writeError } = useApiMutation()
const { confirm } = useConfirm()
const nameId = useId()
const valueId = useId()
const hostsId = useId()

const base = computed(() =>
  `/api/agents/${props.agentId}/skills/${encodeURIComponent(props.skillName)}/secrets`)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const res = await $fetch<{ secrets: Secret[], expected?: Expected[], missing?: string[] }>(base.value)
    secrets.value = res.secrets
    expected.value = res.expected ?? []
    missing.value = res.missing ?? []
  }
  catch (e) {
    loadError.value = apiErrorDetails(e)
  }
  finally {
    loading.value = false
  }
}

function openAdd(declared: Expected | null = null) {
  formMode.value = 'add'
  formDeclared.value = declared
  formName.value = declared?.name ?? ''
  formValue.value = ''
  formHosts.value = ''
}

function openReplace(secret: Secret) {
  formMode.value = 'replace'
  formDeclared.value = expected.value.find(e => e.name === secret.name) ?? null
  formName.value = secret.name
  formValue.value = ''
  formHosts.value = secret.hosts.join(', ')
}

function closeForm() {
  formMode.value = null
  formValue.value = ''
}

function hostList(text: string): string[] {
  return text.split(/[\s,]+/).filter(Boolean)
}

async function save() {
  const body: { value?: string, hosts: string[] } = { hosts: hostList(formHosts.value) }
  if (formValue.value !== '') body.value = formValue.value
  const saved = await mutate(`${base.value}/${encodeURIComponent(formName.value.trim())}`, { method: 'PUT', body })
  formValue.value = ''
  if (saved === null) return
  formMode.value = null
  await load()
  emit('changed')
}

async function remove(secret: Secret) {
  const ok = await confirm({
    title: 'Delete secret',
    message: `Delete ${secret.name}? The skill can no longer use it, and its value cannot be recovered.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  if (await mutate(`${base.value}/${encodeURIComponent(secret.name)}`, { method: 'DELETE' }) !== null) {
    await load()
    emit('changed')
  }
}

/**
 * Declared secrets this skill does not hold itself: not set at all, or held by another skill of the agent. A name
 * is used once on an agent, so one held elsewhere has no Add; held by a skill that is switched off, it is still
 * missing here, because a command gets secrets only from skills that are on.
 */
const declaredElsewhere = computed(() =>
  expected.value.filter(e => !secrets.value.some(s => s.name === e.name)))

function describe(name: string) {
  return expected.value.find(e => e.name === name)?.description ?? ''
}

const hostsPlaceholder = computed(() =>
  formDeclared.value?.exampleHosts.length
    ? `for example ${formDeclared.value.exampleHosts.join(', ')}`
    : 'api.example.com, nas.example.com:5001')

function setAt(iso: string) {
  return new Date(iso).toLocaleString()
}

watch(() => [props.agentId, props.skillName], () => {
  closeForm()
  load()
}, { immediate: true })
</script>

<template>
  <div
    class="bg-surface-elevated border border-border"
    data-testid="skill-secrets"
  >
    <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="text-xs font-medium text-fg-muted uppercase tracking-wider">Secrets</span>
        <span class="text-xs text-fg-muted">{{ secrets.length }}</span>
      </div>
      <button
        v-if="formMode === null"
        type="button"
        class="text-xs px-2 py-1 border border-border text-fg-strong hover:bg-muted"
        data-testid="secret-add"
        @click="openAdd()"
      >
        Add secret
      </button>
    </div>

    <p class="px-4 py-2 text-xs text-fg-muted">
      Keys and passwords this skill uses on this agent. JClaw stores them encrypted, and no one can read a value
      back, you included: you can only replace or delete it. Each secret names the hosts it may be sent to.
    </p>

    <p
      v-if="missing.length"
      role="status"
      class="mx-4 mb-2 px-3 py-2 text-xs border border-amber-300 dark:border-amber-700/40 bg-amber-50 dark:bg-amber-900/20 text-amber-800 dark:text-amber-300"
      data-testid="secrets-not-runnable"
    >
      Not runnable: {{ missing.length === 1 ? 'this secret needs' : 'these secrets need' }} a value before the agent can use this skill:
      <span class="font-mono">{{ missing.join(', ') }}</span>
    </p>

    <ApiErrorAlert
      v-if="loadError"
      :error="loadError"
      class="px-4 pb-3"
    />
    <p
      v-else-if="loading && !secrets.length"
      class="px-4 pb-3 text-xs text-fg-muted"
    >
      Loading…
    </p>
    <p
      v-else-if="!secrets.length && !declaredElsewhere.length && formMode === null"
      class="px-4 pb-3 text-xs text-fg-muted"
    >
      None.
    </p>

    <div
      v-if="declaredElsewhere.length"
      class="divide-y divide-border border-t border-border"
    >
      <div
        v-for="item in declaredElsewhere"
        :key="item.name"
        class="px-4 py-2.5 flex items-center justify-between gap-3"
        :data-testid="`declared-${item.name}`"
      >
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <span class="font-mono text-xs text-fg-strong">{{ item.name }}</span>
            <span
              v-if="item.set"
              class="text-xs text-fg-muted border border-border px-1"
            >Set in {{ item.setIn }}</span>
            <span
              v-else-if="item.setIn"
              class="text-xs px-1 border"
              :class="item.optional
                ? 'text-fg-muted border-border'
                : 'text-amber-800 dark:text-amber-300 border-amber-300 dark:border-amber-700/40 bg-amber-50 dark:bg-amber-900/20'"
            >Set in {{ item.setIn }}, which is switched off</span>
            <span
              v-else-if="item.optional"
              class="text-xs text-fg-muted border border-border px-1"
            >Optional, not set</span>
            <span
              v-else
              class="text-xs text-amber-800 dark:text-amber-300 border border-amber-300 dark:border-amber-700/40 bg-amber-50 dark:bg-amber-900/20 px-1"
            >Needs a value</span>
          </div>
          <div
            v-if="item.description"
            class="text-xs text-fg-muted"
          >
            {{ item.description }}
          </div>
        </div>
        <button
          v-if="!item.set && !item.setIn"
          type="button"
          class="text-xs px-2 py-1 border border-border text-fg-strong hover:bg-muted shrink-0"
          :aria-label="`Add ${item.name}`"
          @click="openAdd(item)"
        >
          Add
        </button>
      </div>
    </div>

    <div
      v-if="secrets.length"
      class="divide-y divide-border border-t border-border"
    >
      <div
        v-for="secret in secrets"
        :key="secret.name"
        class="px-4 py-2.5 flex items-center justify-between gap-3"
        :data-testid="`secret-${secret.name}`"
      >
        <div class="min-w-0">
          <div class="font-mono text-xs text-fg-strong">
            {{ secret.name }}
          </div>
          <div
            v-if="describe(secret.name)"
            class="text-xs text-fg-muted"
          >
            {{ describe(secret.name) }}
          </div>
          <div class="text-xs text-fg-muted break-all">
            Sent only to {{ secret.hosts.join(', ') }} · set {{ setAt(secret.setAt) }}
          </div>
        </div>
        <div class="flex items-center gap-2 shrink-0">
          <button
            type="button"
            class="text-xs px-2 py-1 border border-border text-fg-strong hover:bg-muted"
            :aria-label="`Replace ${secret.name}`"
            @click="openReplace(secret)"
          >
            Replace
          </button>
          <button
            type="button"
            class="text-xs px-2 py-1 border border-border text-red-700 dark:text-red-400 hover:bg-muted"
            :aria-label="`Delete ${secret.name}`"
            @click="remove(secret)"
          >
            Delete
          </button>
        </div>
      </div>
    </div>

    <form
      v-if="formMode !== null"
      class="px-4 py-3 border-t border-border space-y-2"
      autocomplete="off"
      data-testid="secret-form"
      @submit.prevent="save"
    >
      <p
        v-if="formDeclared?.description"
        class="text-xs text-fg-muted"
        data-testid="secret-form-hint"
      >
        {{ formDeclared.description }}
      </p>
      <label
        :for="nameId"
        class="block"
      >
        <span class="block text-xs text-fg-muted">Name</span>
        <input
          :id="nameId"
          v-model="formName"
          type="text"
          name="secret-name"
          :readonly="formMode === 'replace' || formDeclared !== null"
          spellcheck="false"
          placeholder="NAS_PASSWORD"
          class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
        >
      </label>
      <label
        :for="valueId"
        class="block"
      >
        <span class="block text-xs text-fg-muted">Value</span>
        <input
          :id="valueId"
          v-model="formValue"
          type="password"
          name="secret-value"
          autocomplete="new-password"
          spellcheck="false"
          :placeholder="formMode === 'replace' ? 'Leave empty to keep the current value' : ''"
          class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
        >
      </label>
      <label
        :for="hostsId"
        class="block"
      >
        <span class="block text-xs text-fg-muted">Hosts it may be sent to</span>
        <input
          :id="hostsId"
          v-model="formHosts"
          type="text"
          name="secret-hosts"
          spellcheck="false"
          :placeholder="hostsPlaceholder"
          class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
        >
      </label>
      <!-- A skill's SKILL.md sits in the agent's workspace, which the agent can write: its text is a hint, never a fact. -->
      <p
        v-if="formDeclared !== null"
        class="text-xs text-amber-800 dark:text-amber-300"
        data-testid="secret-form-caution"
      >
        The description and the example host come from the skill's own files, which the agent can change. Enter the
        host of the real service: the secret is sent to every host you type here.
      </p>
      <ApiErrorAlert :error="writeError" />
      <div class="flex items-center gap-2">
        <button
          type="submit"
          class="text-xs px-2 py-1 border border-border text-fg-strong hover:bg-muted disabled:opacity-50"
          :disabled="saving"
        >
          {{ saving ? 'Saving…' : 'Save' }}
        </button>
        <button
          type="button"
          class="text-xs px-2 py-1 text-fg-muted hover:text-fg-strong"
          @click="closeForm"
        >
          Cancel
        </button>
      </div>
    </form>
  </div>
</template>
