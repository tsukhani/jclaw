<script setup lang="ts">
/**
 * JF-323: the secrets one agent's skill uses. The operator adds, replaces and deletes them here; the server never
 * returns a value, so this panel only ever shows names, hosts and when each was set. A typed value is cleared from
 * the form as soon as it is saved.
 */
import type { ApiErrorDetails } from '~/types/api'

const props = defineProps<{ agentId: number, skillName: string }>()

interface Secret { name: string, hosts: string[], setAt: string }

const secrets = ref<Secret[]>([])
const loading = ref(false)
const loadError = ref<ApiErrorDetails | null>(null)

const formMode = ref<'add' | 'replace' | null>(null)
const formName = ref('')
const formValue = ref('')
const formHosts = ref('')

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
    secrets.value = (await $fetch<{ secrets: Secret[] }>(base.value)).secrets
  }
  catch (e) {
    loadError.value = apiErrorDetails(e)
  }
  finally {
    loading.value = false
  }
}

function openAdd() {
  formMode.value = 'add'
  formName.value = ''
  formValue.value = ''
  formHosts.value = ''
}

function openReplace(secret: Secret) {
  formMode.value = 'replace'
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
}

async function remove(secret: Secret) {
  const ok = await confirm({
    title: 'Delete secret',
    message: `Delete ${secret.name}? The skill can no longer use it, and its value cannot be recovered.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  if (await mutate(`${base.value}/${encodeURIComponent(secret.name)}`, { method: 'DELETE' }) !== null) await load()
}

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
        @click="openAdd"
      >
        Add secret
      </button>
    </div>

    <p class="px-4 py-2 text-xs text-fg-muted">
      Keys and passwords this skill uses on this agent. JClaw stores them encrypted, and no one can read a value
      back, you included: you can only replace or delete it. Each secret names the hosts it may be sent to.
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
      v-else-if="!secrets.length && formMode === null"
      class="px-4 pb-3 text-xs text-fg-muted"
    >
      None.
    </p>

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
          :readonly="formMode === 'replace'"
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
          placeholder="api.example.com, nas.example.com:5001"
          class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
        >
      </label>
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
