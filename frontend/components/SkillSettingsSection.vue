<script setup lang="ts">
/**
 * JF-357: the settings one agent's skill uses, inside its Secrets panel. A setting is a value that is not secret, such
 * as an address, a user name or a path, so its value is shown and can be edited. Only the operator writes one.
 *
 * JF-352: a value can come from a file an update kept; the server deletes that file once each name it assigns that the
 * skill declares has a value.
 */
import type { KeptSkillFile } from '~/types/api'

const props = withDefaults(defineProps<{
  base: string
  settings: Setting[]
  expected: ExpectedSetting[]
  kept?: KeptSkillFile[]
}>(), { kept: () => [] })
/** After a setting is added, changed or deleted, so the panel reloads and the page redraws the skill's flag. */
const emit = defineEmits<{ changed: [] }>()

export interface Setting { name: string, value: string, setAt: string }
export interface ExpectedSetting {
  name: string
  description: string
  example: string
  optional: boolean
  set: boolean
  setIn?: string | null
}

const editing = ref<string | null>(null)
const editName = ref('')
const editValue = ref('')
const freeForm = ref(false)
/** The kept file whose NAME=value line becomes the value; '' for a typed value. */
const editFromKept = ref('')

const { mutate, loading: saving, errorDetails: writeError } = useApiMutation()
const { confirm } = useConfirm()
const nameId = useId()
const valueId = useId()
const sourceId = useId()

interface Row { name: string, stored?: Setting, declared?: ExpectedSetting }

/** Declared settings in the block's order, then stored ones the block does not declare. */
const rows = computed<Row[]>(() => {
  const out: Row[] = props.expected.map(d => ({ name: d.name, declared: d, stored: props.settings.find(s => s.name === d.name) }))
  for (const s of props.settings) {
    if (!out.some(r => r.name === s.name)) out.push({ name: s.name, stored: s })
  }
  return out
})

const editingRow = computed(() => rows.value.find(r => r.name === editing.value) ?? null)

function open(row: Row) {
  editing.value = row.name
  editName.value = row.name
  editValue.value = row.stored?.value ?? ''
  editFromKept.value = ''
  freeForm.value = false
}

function openFreeForm() {
  editing.value = ''
  editName.value = ''
  editValue.value = ''
  editFromKept.value = ''
  freeForm.value = true
}

function close() {
  editing.value = null
}

async function save() {
  const name = editName.value.trim()
  const body = editFromKept.value ? { fromKept: editFromKept.value } : { value: editValue.value }
  if (await mutate(`${props.base}/${encodeURIComponent(name)}`, { method: 'PUT', body }) === null) return
  editing.value = null
  emit('changed')
}

async function remove(row: Row) {
  const ok = await confirm({
    title: 'Delete setting',
    message: `Delete ${row.name}? The skill can no longer use it.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  if (await mutate(`${props.base}/${encodeURIComponent(row.name)}`, { method: 'DELETE' }) !== null) emit('changed')
}

function setAt(iso: string) {
  return new Date(iso).toLocaleString()
}
</script>

<template>
  <div
    class="border-t border-border"
    data-testid="skill-settings"
  >
    <div class="px-4 py-2.5 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="text-xs font-medium text-fg-muted uppercase tracking-wider">Settings</span>
        <span class="text-xs text-fg-muted">{{ settings.length }}</span>
      </div>
      <button
        v-if="editing === null"
        type="button"
        class="text-xs px-2 py-1 border border-border text-fg-strong hover:bg-muted"
        data-testid="setting-add"
        @click="openFreeForm"
      >
        Add setting
      </button>
    </div>
    <p class="px-4 pb-2 text-xs text-fg-muted">
      Values this skill needs that are not secret, such as an address or a user name. They are shown as they are, and
      the skill's commands receive them as they are.
    </p>
    <p
      v-if="!rows.length && editing === null"
      class="px-4 pb-3 text-xs text-fg-muted"
    >
      None.
    </p>

    <div
      v-if="rows.length"
      class="divide-y divide-border border-t border-border"
    >
      <div
        v-for="row in rows"
        :key="row.name"
        class="px-4 py-2.5 flex items-center justify-between gap-3"
        :data-testid="`setting-${row.name}`"
      >
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <span class="font-mono text-xs text-fg-strong">{{ row.name }}</span>
            <span
              v-if="!row.stored && row.declared?.set"
              class="text-xs text-fg-muted border border-border px-1"
            >Set in {{ row.declared.setIn }}</span>
            <span
              v-else-if="!row.stored && row.declared?.setIn"
              class="text-xs text-amber-800 dark:text-amber-300 border border-amber-300 dark:border-amber-700/40 bg-amber-50 dark:bg-amber-900/20 px-1"
            >Set in {{ row.declared.setIn }}, which is switched off</span>
            <span
              v-else-if="!row.stored && row.declared?.optional"
              class="text-xs text-fg-muted border border-border px-1"
            >Optional, not set</span>
            <span
              v-else-if="!row.stored"
              class="text-xs text-amber-800 dark:text-amber-300 border border-amber-300 dark:border-amber-700/40 bg-amber-50 dark:bg-amber-900/20 px-1"
            >Needs a value</span>
          </div>
          <div
            v-if="row.declared?.description"
            class="text-xs text-fg-muted"
          >
            {{ row.declared.description }}
          </div>
          <div
            v-if="row.stored"
            class="text-xs text-fg-muted break-all"
          >
            <span class="font-mono text-fg-strong">{{ row.stored.value }}</span> · set {{ setAt(row.stored.setAt) }}
          </div>
        </div>
        <div
          v-if="editing === null && (row.stored || !row.declared?.setIn)"
          class="flex items-center gap-2 shrink-0"
        >
          <button
            type="button"
            class="text-xs px-2 py-1 border border-border text-fg-strong hover:bg-muted"
            :aria-label="`${row.stored ? 'Edit' : 'Add'} ${row.name}`"
            @click="open(row)"
          >
            {{ row.stored ? 'Edit' : 'Add' }}
          </button>
          <button
            v-if="row.stored"
            type="button"
            class="text-xs px-2 py-1 border border-border text-red-700 dark:text-red-400 hover:bg-muted"
            :aria-label="`Delete ${row.name}`"
            @click="remove(row)"
          >
            Delete
          </button>
        </div>
      </div>
    </div>

    <form
      v-if="editing !== null"
      class="px-4 py-3 border-t border-border space-y-2"
      autocomplete="off"
      data-testid="setting-form"
      @submit.prevent="save"
    >
      <p
        v-if="editingRow?.declared?.description"
        class="text-xs text-fg-muted"
      >
        {{ editingRow.declared.description }}
      </p>
      <label
        :for="nameId"
        class="block"
      >
        <span class="block text-xs text-fg-muted">Name</span>
        <input
          :id="nameId"
          v-model="editName"
          type="text"
          name="setting-name"
          :readonly="!freeForm"
          spellcheck="false"
          placeholder="SITE_URL"
          class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
        >
      </label>
      <label
        v-if="kept.length"
        :for="sourceId"
        class="block"
      >
        <span class="block text-xs text-fg-muted">Value from</span>
        <select
          :id="sourceId"
          v-model="editFromKept"
          name="setting-source"
          class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
        >
          <option value="">
            What I type below
          </option>
          <option
            v-for="file in kept"
            :key="file.id"
            :value="file.id"
          >
            Kept file {{ file.path }}, kept {{ setAt(file.keptAt) }}
          </option>
        </select>
      </label>
      <p
        v-if="editFromKept"
        class="text-xs text-fg-muted"
        data-testid="setting-form-kept-note"
      >
        JClaw takes the value from the file's plain assignment for {{ editName.trim() || 'this name' }}, a line such as
        NAME=value. JClaw deletes the kept file once each name in it that this skill asks for has a value.
      </p>
      <label
        v-else
        :for="valueId"
        class="block"
      >
        <span class="block text-xs text-fg-muted">Value</span>
        <input
          :id="valueId"
          v-model="editValue"
          type="text"
          name="setting-value"
          spellcheck="false"
          :placeholder="editingRow?.declared?.example ? `for example ${editingRow.declared.example}` : ''"
          class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
        >
      </label>
      <p
        class="text-xs text-amber-800 dark:text-amber-300"
        data-testid="setting-form-caution"
      >
        Shown in clear and given to the skill's commands as it is. Never type a password, key or token here: add it
        under Secrets.
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
          @click="close"
        >
          Cancel
        </button>
      </div>
    </form>
  </div>
</template>
