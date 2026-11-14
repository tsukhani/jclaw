<script setup lang="ts">
/**
 * JF-323: the secrets one agent's skill uses. The operator adds, replaces and deletes them here; the server never
 * returns a value, so this panel only ever shows names, hosts and when each was set. A typed value is cleared from
 * the form as soon as it is saved.
 *
 * JF-348: the skill's SKILL.md declares what it needs, in its secrets block. Each declared secret is listed with its state, and
 * while a required one has no value the skill is not runnable, which the panel says at its top.
 *
 * JF-357: the same block declares settings, values that are not secret; they are listed and edited below the secrets.
 *
 * JF-352: the copies JClaw kept of the old copy's files at an update are listed last, never with their content. A secret or a
 * setting can take its value from one, and the server deletes that file once each name it assigns that the skill
 * declares has a value.
 *
 * JF-374: a renewed secret carries the operator's capture rule, and the secrets proxy fills its value from the answer
 * to a sign-in that sent the rule's credential. Adding a secret a renewed line declares starts with Renewed ticked and
 * the rule the line suggests, flagged until the operator edits it, as the agent can change SKILL.md; it binds nothing
 * until saved. Replace ticks Renewed only for a stored secret with a rule. A name another skill's renewed secret holds
 * with no value yet is that skill's sign-in's to fill, so it gets no Add, which the server would refuse.
 */
import type { ApiErrorDetails, HolderState, KeptSkillFile, SkillSecretCapture } from '~/types/api'
import type { ExpectedSetting, Setting } from '~/components/SkillSettingsSection.vue'
import { formatSize } from '~/utils/format'

const props = defineProps<{ agentId: number, skillName: string }>()
/** After a secret is added, replaced or deleted, so the page can redraw the skill's not-runnable flag. */
const emit = defineEmits<{ changed: [] }>()

interface Secret {
  name: string
  hosts: string[]
  setAt: string
  hasValue?: boolean
  capture?: SkillSecretCapture | null
  renewedAt?: string | null
}
interface Expected {
  name: string
  description: string
  exampleHosts: string[]
  optional: boolean
  set: boolean
  setIn?: string | null
  /** Where `setIn` stands: switched off, or on but not offered, is why this skill cannot be given the name. */
  setInState?: HolderState | null
  /** `setIn` holds it as a renewed secret with no value yet, which that skill's sign-in fills. */
  filledBySignIn?: boolean
  renewed?: boolean
  suggested?: SkillSecretCapture | null
  /** The text after `from` on the skill's line when it is no rule the server can save. */
  unreadableSuggestion?: string | null
}

type CredentialPlace = 'json' | 'form' | 'header' | 'basic'
const CREDENTIAL_PLACES: CredentialPlace[] = ['json', 'form', 'header', 'basic']

const secrets = ref<Secret[]>([])
const expected = ref<Expected[]>([])
const settings = ref<Setting[]>([])
const expectedSettings = ref<ExpectedSetting[]>([])
const missing = ref<string[]>([])
const kept = ref<KeptSkillFile[]>([])
const loading = ref(false)
const loadError = ref<ApiErrorDetails | null>(null)
const keptError = ref<ApiErrorDetails | null>(null)

const formMode = ref<'add' | 'replace' | null>(null)
const formName = ref('')
const formValue = ref('')
const formHosts = ref('')
/** The declared secret being added, for its description and example host; null for a free-form add. */
const formDeclared = ref<Expected | null>(null)
/** The kept file whose content becomes the value; '' for a typed value. */
const formFromKept = ref('')
/** Whether the secret is renewed, with the capture rule below. */
const formRenew = ref(false)
const formMethod = ref('')
const formPath = ref('')
const formFrom = ref<SkillSecretCapture['from']>('json')
const formField = ref('')
/** The secret the sign-in sends, and where: a JSON field, a form field, a header, or the Basic auth password. */
const formCredential = ref('')
const formCredentialPlace = ref<CredentialPlace>('json')
const formCredentialField = ref('')
/** The rule fields came from the skill's SKILL.md, which the agent can change. */
const formRuleSuggested = ref(false)
/** The stored secret has a rule, so unticking Renewed sends its removal. */
const formHadRule = ref(false)
/** The stored secret has a value, so a Replace that leaves Value empty keeps one. */
const formHadValue = ref(false)

const { mutate, loading: saving, errorDetails: writeError } = useApiMutation()
const { mutate: mutateKept, errorDetails: keptWriteError } = useApiMutation()
const { confirm } = useConfirm()
const nameId = useId()
const valueId = useId()
const hostsId = useId()
const sourceId = useId()
const renewId = useId()
const methodId = useId()
const pathId = useId()
const fromId = useId()
const fieldId = useId()
const credentialId = useId()
const credentialChoicesId = useId()
const credentialInId = useId()
const credentialFieldId = useId()

const base = computed(() =>
  `/api/agents/${props.agentId}/skills/${encodeURIComponent(props.skillName)}/secrets`)
const settingsBase = computed(() =>
  `/api/agents/${props.agentId}/skills/${encodeURIComponent(props.skillName)}/settings`)
const keptBase = computed(() =>
  `/api/agents/${props.agentId}/skills/${encodeURIComponent(props.skillName)}/kept`)

async function settingsChanged() {
  await load()
  emit('changed')
}

function load() {
  return Promise.all([loadSecrets(), loadKept()])
}

async function loadSecrets() {
  loading.value = true
  loadError.value = null
  try {
    const res = await $fetch<{
      secrets: Secret[]
      expected?: Expected[]
      settings?: Setting[]
      expectedSettings?: ExpectedSetting[]
      missing?: string[]
    }>(base.value)
    secrets.value = res.secrets
    expected.value = res.expected ?? []
    settings.value = res.settings ?? []
    expectedSettings.value = res.expectedSettings ?? []
    missing.value = res.missing ?? []
  }
  catch (e) {
    loadError.value = apiErrorDetails(e)
  }
  finally {
    loading.value = false
  }
}

async function loadKept() {
  keptError.value = null
  try {
    kept.value = (await $fetch<{ kept?: KeptSkillFile[] }>(keptBase.value)).kept ?? []
  }
  catch (e) {
    kept.value = []
    keptError.value = apiErrorDetails(e)
  }
}

function fillRule(rule: SkillSecretCapture | null | undefined, suggested: boolean) {
  formMethod.value = rule?.method ?? ''
  formPath.value = rule?.path ?? ''
  formFrom.value = rule?.from ?? 'json'
  formField.value = rule?.field ?? ''
  formCredential.value = rule?.credential ?? ''
  const [place = 'json', ...field] = (rule?.credentialIn ?? '').trim().split(/\s+/)
  formCredentialPlace.value = CREDENTIAL_PLACES.includes(place as CredentialPlace) ? place as CredentialPlace : 'json'
  formCredentialField.value = field.join(' ')
  formRuleSuggested.value = suggested && !!rule
}

/**
 * The caution about the skill's suggestion stays while any typed rule field still holds what the skill suggested. The
 * two selects are left out: most rules share their choices, so they would keep the caution on.
 */
function ruleEdited() {
  const rule = formDeclared.value?.suggested
  if (!formRuleSuggested.value || !rule) return
  const [, ...field] = rule.credentialIn.trim().split(/\s+/)
  formRuleSuggested.value = formMethod.value === rule.method || formPath.value === rule.path
    || formField.value === rule.field || formCredential.value === rule.credential
    || (field.length > 0 && formCredentialField.value === field.join(' '))
}

function openAdd(declared: Expected | null = null) {
  formMode.value = 'add'
  formDeclared.value = declared
  formName.value = declared?.name ?? ''
  formValue.value = ''
  formFromKept.value = ''
  formHosts.value = ''
  formRenew.value = !!declared?.renewed
  formHadRule.value = false
  formHadValue.value = false
  fillRule(declared?.renewed ? declared.suggested : null, true)
}

function openReplace(secret: Secret) {
  formMode.value = 'replace'
  formDeclared.value = expected.value.find(e => e.name === secret.name) ?? null
  formName.value = secret.name
  formValue.value = ''
  formFromKept.value = ''
  formHosts.value = secret.hosts.join(', ')
  formHadRule.value = !!secret.capture
  formHadValue.value = secret.hasValue !== false
  formRenew.value = !!secret.capture
  fillRule(secret.capture, false)
}

/** Ticking Renewed with the rule still blank fills it from the skill's suggestion, if it has one. */
function renewToggled() {
  if (!formRenew.value || formMethod.value || formPath.value || formField.value || formCredential.value) return
  fillRule(formDeclared.value?.suggested, true)
}

function closeForm() {
  formMode.value = null
  formValue.value = ''
}

function hostList(text: string): string[] {
  return text.split(/[\s,]+/).filter(Boolean)
}

async function save() {
  const body: { value?: string, fromKept?: string, hosts: string[], capture?: SkillSecretCapture | null } = {
    hosts: hostList(formHosts.value),
  }
  if (formFromKept.value) body.fromKept = formFromKept.value
  else if (formValue.value !== '') body.value = formValue.value
  if (formRenew.value) {
    body.capture = {
      method: formMethod.value.trim(),
      path: formPath.value.trim(),
      from: formFrom.value,
      field: formField.value.trim(),
      credential: formCredential.value.trim(),
      credentialIn: formCredentialPlace.value === 'basic'
        ? 'basic'
        : `${formCredentialPlace.value} ${formCredentialField.value.trim()}`.trim(),
    }
  }
  else if (formHadRule.value) {
    body.capture = null
  }
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

async function removeKept(file: KeptSkillFile) {
  const ok = await confirm({
    title: 'Delete kept file',
    message: `Delete the kept copy of ${file.path}? The skill's own folder is not changed.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  // Reloaded either way: a 404 means the file is already gone, and its row should go with it.
  await mutateKept(`${keptBase.value}/${encodeURIComponent(file.id)}`, { method: 'DELETE' })
  await loadKept()
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

/** A stored secret the skill declares renewed, saved without a rule: its sign-in's key still reaches commands. */
function renewedWithoutRule(secret: Secret) {
  return !secret.capture && !!expected.value.find(e => e.name === secret.name)?.renewed
}

const valuePlaceholder = computed(() => {
  if (formMode.value === 'replace' && formHadValue.value) return 'Leave empty to keep the current value'
  return formRenew.value ? 'Leave empty: JClaw takes it from the next sign-in' : ''
})

const hostsPlaceholder = computed(() =>
  formDeclared.value?.exampleHosts.length
    ? `for example ${formDeclared.value.exampleHosts.join(', ')}`
    : 'api.example.com, nas.example.com:5001')

function setAt(iso: string) {
  return new Date(iso).toLocaleString()
}

const noRuleNote = 'No capture rule yet: until you save one, the key this skill\'s sign-in returns reaches its'
  + ' commands as plain text.'

/** This skill's secrets, and the one being saved, which a refresh-style sign-in sends itself. */
const credentialChoices = computed(() => {
  const names = secrets.value.map(s => s.name)
  const own = formName.value.trim()
  return own && !names.includes(own) ? [...names, own] : names
})

const PLACE_TEXT: Record<CredentialPlace, string> = {
  json: 'JSON field',
  form: 'form field',
  header: 'header',
  basic: 'the Basic auth password',
}

function placeText(credentialIn: string) {
  const [place = '', ...field] = credentialIn.trim().split(/\s+/)
  const named = PLACE_TEXT[place as CredentialPlace]
  return named ? [named, ...field].join(' ') : credentialIn
}

function ruleText(rule: SkillSecretCapture) {
  const answer = `${rule.method} ${rule.path}, ${rule.from === 'header' ? 'header' : 'JSON field'} ${rule.field}`
  return rule.credential && rule.credentialIn
    ? `${answer}, signed in with ${rule.credential} in ${placeText(rule.credentialIn)}`
    : answer
}

function holderNote(item: Expected) {
  if (item.setInState === 'notInstalled') return ', which is no longer installed'
  if (item.setInState === 'notOffered') return ', which is not offered right now'
  if (item.setInState === 'switchedOff') return ', which is switched off'
  // A server that names no state says only that a value holder is not on.
  return item.setInState == null && !item.filledBySignIn ? ', which is switched off' : ''
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
      Not runnable: {{ missing.length === 1 ? 'this needs' : 'these need' }} a value before the agent can use this skill:
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
              v-if="item.filledBySignIn && item.setIn === skillName"
              class="text-xs text-fg-muted border border-border px-1"
            >Renewed at sign-in, not set</span>
            <span
              v-else-if="item.set"
              class="text-xs text-fg-muted border border-border px-1"
            >Set in {{ item.setIn }}</span>
            <span
              v-else-if="item.setIn && item.filledBySignIn"
              class="text-xs px-1 border"
              :class="item.optional || item.renewed
                ? 'text-fg-muted border-border'
                : 'text-amber-800 dark:text-amber-300 border-amber-300 dark:border-amber-700/40 bg-amber-50 dark:bg-amber-900/20'"
            >Filled by {{ item.setIn }}'s sign-in{{ holderNote(item) }}</span>
            <span
              v-else-if="item.setIn"
              class="text-xs px-1 border"
              :class="item.optional || item.renewed
                ? 'text-fg-muted border-border'
                : 'text-amber-800 dark:text-amber-300 border-amber-300 dark:border-amber-700/40 bg-amber-50 dark:bg-amber-900/20'"
            >Set in {{ item.setIn }}{{ holderNote(item) }}</span>
            <span
              v-else-if="item.renewed"
              class="text-xs text-fg-muted border border-border px-1"
            >Renewed at sign-in, not set</span>
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
          <div
            v-if="item.renewed && !item.setIn"
            class="text-xs text-fg-muted"
            :data-testid="`declared-${item.name}-no-rule`"
          >
            {{ noRuleNote }}
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
          <div
            v-if="secret.capture"
            class="text-xs text-fg-muted break-all"
            :data-testid="`secret-${secret.name}-renewal`"
          >
            Taken from the answer to {{ ruleText(secret.capture) }} ·
            <template v-if="secret.renewedAt">
              renewed {{ setAt(secret.renewedAt) }}
            </template>
            <template v-else-if="secret.hasValue === false">
              no value yet: JClaw stores one at the skill's next sign-in
            </template>
            <template v-else>
              not renewed yet
            </template>
          </div>
          <div
            v-else-if="renewedWithoutRule(secret)"
            class="text-xs text-fg-muted"
            :data-testid="`secret-${secret.name}-no-rule`"
          >
            {{ noRuleNote }}
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
        v-if="kept.length"
        :for="sourceId"
        class="block"
      >
        <span class="block text-xs text-fg-muted">Value from</span>
        <select
          :id="sourceId"
          v-model="formFromKept"
          name="secret-source"
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
        v-if="formFromKept"
        class="text-xs text-fg-muted"
        data-testid="secret-form-kept-note"
      >
        If the file has plain assignments such as NAME=value, JClaw takes the value from the line for
        {{ formName.trim() || 'this name' }}. If it has none, the whole file is the value, without spaces or line breaks
        at either end. JClaw deletes the kept file once each name in it that this skill asks for has a value.
      </p>
      <label
        v-else
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
          :placeholder="valuePlaceholder"
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
      <label
        :for="renewId"
        class="flex items-center gap-2 text-xs text-fg-strong"
      >
        <input
          :id="renewId"
          v-model="formRenew"
          type="checkbox"
          name="secret-renew"
          @change="renewToggled"
        >
        Renewed: JClaw takes its value from a sign-in answer
      </label>
      <fieldset
        v-if="formRenew"
        class="space-y-2 border-l-2 border-border pl-3"
        data-testid="secret-form-rule"
      >
        <legend class="sr-only">
          Capture rule
        </legend>
        <p
          class="text-xs text-fg-muted"
          data-testid="secret-form-rule-help"
        >
          When a success or a redirect from one of the hosts above answers this method and path, and the sign-in sent the
          secret named below by its stand-in, once, in the place named, JClaw stores the value it finds in the answer, and
          the command gets its stand-in instead. Such an answer that does not hold the value is withheld, and so is one to
          a sign-in that sent the secret more than once or anywhere else. A sign-in that never sent it passes as before.
        </p>
        <p
          class="text-xs text-fg-muted"
          data-testid="secret-form-rule-help"
        >
          A JSON field, in the answer or in the sign-in, is one at the top level, not inside another object. A form field
          may name a nested one, such as user[password]. A cookie cannot be taken.
        </p>
        <div class="grid grid-cols-[6rem_1fr] gap-2">
          <label
            :for="methodId"
            class="block"
          >
            <span class="block text-xs text-fg-muted">Method</span>
            <input
              :id="methodId"
              v-model="formMethod"
              type="text"
              name="secret-capture-method"
              spellcheck="false"
              placeholder="POST"
              class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
              @input="ruleEdited"
            >
          </label>
          <label
            :for="pathId"
            class="block"
          >
            <span class="block text-xs text-fg-muted">Path</span>
            <input
              :id="pathId"
              v-model="formPath"
              type="text"
              name="secret-capture-path"
              spellcheck="false"
              placeholder="/v1/sessions/current"
              class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
              @input="ruleEdited"
            >
          </label>
        </div>
        <div class="grid grid-cols-[10rem_1fr] gap-2">
          <label
            :for="fromId"
            class="block"
          >
            <span class="block text-xs text-fg-muted">Value is in</span>
            <select
              :id="fromId"
              v-model="formFrom"
              name="secret-capture-from"
              class="mt-1 w-full text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
              @change="ruleEdited"
            >
              <option value="json">
                A JSON field
              </option>
              <option value="header">
                A response header
              </option>
            </select>
          </label>
          <label
            :for="fieldId"
            class="block"
          >
            <span class="block text-xs text-fg-muted">{{ formFrom === 'header' ? 'Header' : 'Field' }}</span>
            <input
              :id="fieldId"
              v-model="formField"
              type="text"
              name="secret-capture-field"
              spellcheck="false"
              :placeholder="formFrom === 'header' ? 'X-Session' : 'session'"
              class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
              @input="ruleEdited"
            >
          </label>
        </div>
        <label
          :for="credentialId"
          class="block"
        >
          <span class="block text-xs text-fg-muted">Secret the sign-in sends</span>
          <input
            :id="credentialId"
            v-model="formCredential"
            type="text"
            name="secret-capture-credential"
            :list="credentialChoicesId"
            spellcheck="false"
            placeholder="BACKOFFICE_PASSWORD"
            class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
            @input="ruleEdited"
          >
          <datalist
            :id="credentialChoicesId"
            data-testid="secret-capture-credential-choices"
          >
            <option
              v-for="choice in credentialChoices"
              :key="choice"
              :value="choice"
            />
          </datalist>
        </label>
        <div class="grid grid-cols-[10rem_1fr] gap-2">
          <label
            :for="credentialInId"
            class="block"
          >
            <span class="block text-xs text-fg-muted">Sent in</span>
            <select
              :id="credentialInId"
              v-model="formCredentialPlace"
              name="secret-capture-credential-in"
              class="mt-1 w-full text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
              @change="ruleEdited"
            >
              <option value="json">
                A JSON field
              </option>
              <option value="form">
                A form field
              </option>
              <option value="header">
                A request header
              </option>
              <option value="basic">
                The Basic auth password
              </option>
            </select>
          </label>
          <label
            v-if="formCredentialPlace !== 'basic'"
            :for="credentialFieldId"
            class="block"
          >
            <span class="block text-xs text-fg-muted">{{ formCredentialPlace === 'header' ? 'Header' : 'Field' }}</span>
            <input
              :id="credentialFieldId"
              v-model="formCredentialField"
              type="text"
              name="secret-capture-credential-field"
              spellcheck="false"
              :placeholder="formCredentialPlace === 'header' ? 'X-Password' : 'password'"
              class="mt-1 w-full font-mono text-xs bg-surface border border-border px-2 py-1 text-fg-strong"
              @input="ruleEdited"
            >
          </label>
        </div>
        <p
          v-if="formDeclared?.unreadableSuggestion"
          class="text-xs text-fg-muted break-all"
          data-testid="secret-form-rule-unreadable"
        >
          The skill's files suggest a rule JClaw cannot read: <span class="font-mono">{{ formDeclared.unreadableSuggestion }}</span>.
          Fill in the method, the path, the field and the secret its sign-in sends yourself.
        </p>
        <p
          v-if="formRuleSuggested"
          class="text-xs text-amber-800 dark:text-amber-300"
          data-testid="secret-form-rule-caution"
        >
          This rule comes from the skill's own files, which the agent can change. Check the method, the path, the field
          and the secret its sign-in sends against the service before you save.
        </p>
      </fieldset>
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

    <SkillSettingsSection
      v-if="!loadError"
      :base="settingsBase"
      :settings="settings"
      :expected="expectedSettings"
      :kept="kept"
      @changed="settingsChanged"
    />

    <div
      v-if="kept.length || keptError || keptWriteError"
      class="border-t border-border"
      data-testid="skill-kept"
    >
      <div class="px-4 py-2.5 flex items-center gap-2">
        <span class="text-xs font-medium text-fg-muted uppercase tracking-wider">Kept files</span>
        <span class="text-xs text-fg-muted">{{ kept.length }}</span>
      </div>
      <p class="px-4 pb-2 text-xs text-fg-muted">
        Copies of the files this skill's old copy held when it was updated, such as a key or a config file you edited.
        The update put the files themselves back in the new copy, unless the new version ships its own file there outside
        credentials/. JClaw
        keeps these copies outside the agent's workspace, so the skill and the agent cannot read them, and never shows
        what they hold. To use one, choose it under Value from when you add or replace a secret or a setting. JClaw
        deletes a copy once each name in it that this skill asks for has a value, and the skill's own file with it while
        that file is unchanged and holds only names this skill asks for, so no plain copy is left in the skill.
      </p>
      <ApiErrorAlert
        v-if="keptError"
        :error="keptError"
        class="px-4 pb-3"
      />
      <ApiErrorAlert
        :error="keptWriteError"
        class="px-4 pb-3"
      />
      <div
        v-if="kept.length"
        class="divide-y divide-border border-t border-border"
      >
        <div
          v-for="file in kept"
          :key="file.id"
          class="px-4 py-2.5 flex items-center justify-between gap-3"
          :data-testid="`kept-${file.path}`"
        >
          <div class="min-w-0">
            <div class="font-mono text-xs text-fg-strong break-all">
              {{ file.path }}
            </div>
            <div class="text-xs text-fg-muted">
              {{ formatSize(file.size) }} · kept {{ setAt(file.keptAt) }}
            </div>
          </div>
          <button
            type="button"
            class="text-xs px-2 py-1 border border-border text-red-700 dark:text-red-400 hover:bg-muted shrink-0"
            :aria-label="`Delete kept file ${file.path}, kept ${setAt(file.keptAt)}`"
            @click="removeKept(file)"
          >
            Delete
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
