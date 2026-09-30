<script setup lang="ts">
// Proxy Providers settings panel (JCLAW-1323). The web_scrape.proxy.* keys stay the only stored state: a
// preset composes them from its own fields and parses them back, and DataImpulse keeps a login and password
// per plan (JCLAW-1334). WebScrapeSettingsTest requires each fixed key's literal in this file.
import { ArrowTopRightOnSquareIcon } from '@heroicons/vue/24/outline'
import {
  composeDataImpulse,
  composeProxy,
  credentialError,
  DATAIMPULSE,
  DATAIMPULSE_PLANS,
  emptyDataImpulse,
  isPlanId,
  keepsStoredPassword,
  parseProxy,
  planProxyWrites,
  planRecord,
  planUsable,
  proxyHost,
  proxyKey,
  validateDataImpulse,
  validateManual,
  type DataImpulseErrors,
  type DataImpulseFields,
  type ManualErrors,
  type ProxyProviderId,
  type StoredPlans,
  type StoredProxy,
} from '~/utils/proxy-providers'

const { configData, saving, refresh } = useSettingsConfig()

const KEYS = {
  url: 'web_scrape.proxy.url',
  username: 'web_scrape.proxy.username',
  password: 'web_scrape.proxy.password',
  enabled: 'web_scrape.proxy.enabled',
  plan: 'web_scrape.proxy.dataimpulse.plan',
  targeting: 'web_scrape.proxy.dataimpulse.targeting',
} as const

function stored(key: string): string {
  return configData.value?.entries?.find(e => e.key === key)?.value?.trim() ?? ''
}
const storedUrl = computed(() => stored(KEYS.url))
const storedUsername = computed(() => stored(KEYS.username))
// The password reads back masked, so all the panel knows is whether one is set.
const storedHasPassword = computed(() => stored(KEYS.password) !== '')
// The backend treats anything but "false" as on.
const enabled = computed(() => stored(KEYS.enabled).toLowerCase() !== 'false')
const storedPlans = computed<StoredPlans>(() => {
  const plan = stored(KEYS.plan)
  return {
    plan: isPlanId(plan) ? plan : '',
    targeting: stored(KEYS.targeting),
    logins: planRecord(p => stored(proxyKey(`${p}.login`))),
    hasPassword: planRecord(p => stored(proxyKey(`${p}.password`)) !== ''),
  }
})
// A string, so the watch below sees a change of value rather than every recomputed object.
const storedPlansSnapshot = computed(() => JSON.stringify(storedPlans.value))
const saved = computed(() => parseProxy(storedUrl.value, storedUsername.value, storedPlans.value))
const storedProxy = computed<StoredProxy>(() => ({
  url: storedUrl.value, username: storedUsername.value, hasPassword: storedHasPassword.value, enabled: enabled.value,
  dataimpulse: storedPlans.value,
}))

const provider = ref<ProxyProviderId>('none')
const dataimpulse = reactive<DataImpulseFields>(emptyDataImpulse())
const manual = reactive({ url: '', username: '' })
const planPasswords = reactive(planRecord(() => ''))
const planPasswordEditing = reactive(planRecord(() => false))
const manualPassword = ref('')
const manualPasswordEditing = ref(false)
const errors = ref<DataImpulseErrors>({})
const manualErrors = ref<ManualErrors>({})

function resetPlanPasswords() {
  for (const { id } of DATAIMPULSE_PLANS) {
    planPasswords[id] = ''
    planPasswordEditing[id] = false
  }
}

// Keyed on strings, so the enabled toggle's refresh does not wipe a half-typed card.
watch([storedUrl, storedUsername, storedPlansSnapshot], () => {
  const parsed = saved.value
  provider.value = parsed.provider
  Object.assign(dataimpulse, parsed.dataimpulse)
  Object.assign(manual, parsed.manual)
  errors.value = {}
  manualErrors.value = {}
  resetPlanPasswords()
  manualPasswordEditing.value = false
}, { immediate: true })

const manualIsSocks = computed(() => /^socks5:/i.test(manual.url.trim()))
// Plan passwords only ever go to the DataImpulse gateway, so a saved one is kept whatever the host.
const planHasPassword = computed(() => planRecord(p => storedPlans.value.hasPassword[p] || planPasswords[p].trim() !== ''))
const preview = computed(() => Object.keys(validateDataImpulse(dataimpulse, planHasPassword.value)).length ? null : composeDataImpulse(dataimpulse))
// A card shows the saved password only while a save would keep it: the same host, and not SOCKS5.
const manualKeepsPassword = computed(() => !manualIsSocks.value && keepsStoredPassword(manual.url, storedProxy.value))
const passwordHint = computed(() => storedHasPassword.value
  ? `The saved password belongs to ${proxyHost(storedUrl.value) || 'the proxy it was saved with'}; saving another host clears it.`
  : '')

const { saveError, attempt } = useSaveAttempt()

// Checks everything the backend would refuse first, so a refusal cannot land halfway through the writes.
function validate(): boolean {
  errors.value = {}
  manualErrors.value = {}
  if (provider.value === 'dataimpulse') {
    const found: DataImpulseErrors = validateDataImpulse(dataimpulse, planHasPassword.value)
    for (const { id, label } of DATAIMPULSE_PLANS) {
      const password = credentialError(planPasswords[id], false, `DataImpulse ${label} password`)
      if (password) found[`${id}.password`] = password
    }
    errors.value = found
  }
  if (provider.value === 'manual') {
    manualErrors.value = manualIsSocks.value
      ? validateManual({ url: manual.url, username: '' }, '')
      : validateManual(manual, manualPassword.value)
  }
  return !Object.keys(errors.value).length && !Object.keys(manualErrors.value).length
}

async function save() {
  if (!validate()) return
  const password = provider.value === 'manual' ? manualPassword.value : ''
  const writes = planProxyWrites(composeProxy(provider.value, { dataimpulse, manual }), password, storedProxy.value,
    provider.value === 'dataimpulse' ? planPasswords : {})
  saving.value = true
  testResult.value = null
  testError.value = null
  if (await attempt(async () => {
    for (const write of writes) {
      await $fetch('/api/config', { method: 'POST', body: { key: proxyKey(write.field), value: write.value } })
    }
  })) {
    resetPlanPasswords()
    manualPassword.value = ''
    manualPasswordEditing.value = false
  }
  // After a refusal too: the writes before it landed, and the cards should show them.
  await refresh()
  saving.value = false
}

async function toggleEnabled() {
  saving.value = true
  testResult.value = null
  testError.value = null
  await attempt(() => $fetch('/api/config', { method: 'POST', body: { key: KEYS.enabled, value: enabled.value ? 'false' : 'true' } }))
  await refresh()
  saving.value = false
}

interface ProxyTestResult {
  ok: boolean
  ip?: string | null
  ms: number
  status?: number | null
  reason?: string | null
  error?: string | null
  proxy?: { host: string, address?: string | null, reverseName?: string | null } | null
}

const testRun = useSaveAttempt()
const testError = testRun.saveError
const testing = ref(false)
const testResult = ref<ProxyTestResult | null>(null)
const canTest = computed(() => storedUrl.value !== '' && enabled.value)
// A literal host is its own address, so only a name has a resolution worth showing.
const resolution = computed(() => {
  const proxy = testResult.value?.proxy
  return proxy && proxy.address !== proxy.host ? proxy : null
})

async function testConnection() {
  testing.value = true
  testResult.value = null
  await testRun.attempt(async () => {
    testResult.value = await $fetch<ProxyTestResult>('/api/scrape/proxy/test', { method: 'POST' })
  })
  testing.value = false
}

function isSuccess(status: number | null | undefined): boolean {
  return status != null && status >= 200 && status < 300
}

function duration(ms: number): string {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

const PROVIDERS: { id: ProxyProviderId, label: string, detail: string }[] = [
  { id: 'none', label: 'None', detail: 'connect directly' },
  { id: 'dataimpulse', label: 'DataImpulse', detail: 'residential, mobile and datacenter proxies' },
  { id: 'manual', label: 'Manual', detail: 'any HTTP or SOCKS5 proxy' },
]

const INPUT = 'flex-1 min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden'
const FIELD_LABEL = 'text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0'
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Proxy Providers
    </h2>
    <p class="text-xs text-fg-muted">
      The proxy <span class="font-mono">web_fetch</span> and <span class="font-mono">web_scrape</span>
      go through, for every way they fetch a page: the plain fetch, the browser-impersonating fetch and
      the full browser render, as well as robots.txt and sitemaps. Nothing else uses it. Choose a
      provider and JClaw fills in its address and login syntax, or enter any proxy by hand.
    </p>

    <fieldset class="min-w-0 space-y-3">
      <legend class="sr-only">
        Proxy provider
      </legend>
      <div
        v-for="p in PROVIDERS"
        :key="p.id"
        class="bg-surface-elevated border border-border"
        :data-testid="`proxy-card-${p.id}`"
      >
        <label
          :for="`proxy-provider-${p.id}`"
          class="px-4 py-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 cursor-pointer"
        >
          <input
            :id="`proxy-provider-${p.id}`"
            v-model="provider"
            type="radio"
            name="proxy-provider"
            :value="p.id"
            class="accent-emerald-600"
          >
          <span class="text-sm text-fg-primary">{{ p.label }}</span>
          <span class="flex-1 text-xs text-fg-muted">{{ p.detail }}</span>
          <span
            v-if="saved.provider === p.id"
            class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
            :data-testid="`proxy-saved-${p.id}`"
          >saved</span>
          <span
            v-if="saved.provider === p.id && p.id !== 'none' && !enabled"
            class="text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/30 px-1"
            :data-testid="`proxy-switched-off-${p.id}`"
          >switched off</span>
        </label>

        <div
          v-if="p.id === 'dataimpulse' && provider === 'dataimpulse'"
          class="border-t border-border divide-y divide-border"
        >
          <p class="px-4 py-2.5 text-xs text-fg-muted">
            Each plan's login and password are under Proxy Access in the
            <a
              :href="DATAIMPULSE.dashboardUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="text-fg-strong underline inline-flex items-center gap-0.5"
              data-testid="proxy-dataimpulse-dashboard"
            >DataImpulse dashboard<ArrowTopRightOnSquareIcon
              class="w-3 h-3"
              aria-hidden="true"
            /></a>.
            JClaw connects to DataImpulse's gateway over HTTP, which every fetch can use, and puts the
            country and session after the chosen plan's login as DataImpulse expects. Every plan shares
            the gateway, country, rotation and session below.
          </p>
          <div
            role="radiogroup"
            aria-labelledby="proxy-dataimpulse-plan-label"
            :aria-describedby="errors.plan ? 'proxy-dataimpulse-plan-error' : undefined"
            class="px-4 py-2 space-y-2"
            data-testid="proxy-dataimpulse-plans"
          >
            <p
              id="proxy-dataimpulse-plan-label"
              class="text-xs text-fg-muted"
            >
              Each plan has its own login and password. Choose the plan the proxy uses; the others stay saved.
            </p>
            <div
              v-for="plan in DATAIMPULSE_PLANS"
              :key="plan.id"
              class="space-y-1"
              :data-testid="`proxy-dataimpulse-plan-${plan.id}`"
            >
              <div class="flex max-sm:flex-wrap items-center gap-3">
                <label
                  :for="`proxy-dataimpulse-use-${plan.id}`"
                  :class="FIELD_LABEL"
                  class="flex items-center gap-2 cursor-pointer"
                >
                  <input
                    :id="`proxy-dataimpulse-use-${plan.id}`"
                    v-model="dataimpulse.plan"
                    type="radio"
                    name="proxy-dataimpulse-plan"
                    :value="plan.id"
                    :disabled="!planUsable(dataimpulse, plan.id, planHasPassword)"
                    class="accent-emerald-600"
                  >
                  {{ plan.label }}
                </label>
                <input
                  :id="`proxy-dataimpulse-${plan.id}-login`"
                  v-model="dataimpulse.logins[plan.id]"
                  type="text"
                  autocomplete="off"
                  spellcheck="false"
                  placeholder="login"
                  :aria-label="`${plan.label} login`"
                  :aria-invalid="!!errors[`${plan.id}.login`]"
                  :aria-describedby="errors[`${plan.id}.login`] ? `proxy-dataimpulse-${plan.id}-login-error` : undefined"
                  :class="INPUT"
                >
                <SecretField
                  v-model="planPasswords[plan.id]"
                  v-model:editing="planPasswordEditing[plan.id]"
                  form
                  :saved="storedPlans.hasPassword[plan.id]"
                  :input-id="`proxy-dataimpulse-${plan.id}-password`"
                  :label="`DataImpulse ${plan.label} password`"
                  placeholder="password"
                  :input-class="INPUT"
                  :input-attrs="{
                    'aria-invalid': !!errors[`${plan.id}.password`],
                    'aria-describedby': errors[`${plan.id}.password`] ? `proxy-dataimpulse-${plan.id}-password-error` : undefined,
                  }"
                  :data-testid="`proxy-dataimpulse-${plan.id}-password-field`"
                />
              </div>
              <p
                v-if="errors[`${plan.id}.login`]"
                :id="`proxy-dataimpulse-${plan.id}-login-error`"
                class="text-xs text-danger sm:ml-51"
              >
                {{ errors[`${plan.id}.login`] }}
              </p>
              <p
                v-if="errors[`${plan.id}.password`]"
                :id="`proxy-dataimpulse-${plan.id}-password-error`"
                class="text-xs text-danger sm:ml-51"
              >
                {{ errors[`${plan.id}.password`] }}
              </p>
            </div>
            <p
              v-if="errors.plan"
              id="proxy-dataimpulse-plan-error"
              class="text-xs text-danger"
            >
              {{ errors.plan }}
            </p>
          </div>
          <div class="px-4 py-2 space-y-1">
            <label
              for="proxy-dataimpulse-countries"
              class="flex max-sm:flex-wrap items-center gap-3"
            >
              <span :class="FIELD_LABEL">country</span>
              <input
                id="proxy-dataimpulse-countries"
                v-model="dataimpulse.countries"
                type="text"
                autocomplete="off"
                spellcheck="false"
                placeholder="any, or two-letter codes such as de or de,au"
                :aria-invalid="!!errors.countries"
                :aria-describedby="errors.countries ? 'proxy-dataimpulse-countries-error' : undefined"
                :class="INPUT"
              >
            </label>
            <p
              v-if="errors.countries"
              id="proxy-dataimpulse-countries-error"
              class="text-xs text-danger sm:ml-51"
            >
              {{ errors.countries }}
            </p>
          </div>
          <div
            role="radiogroup"
            aria-labelledby="proxy-dataimpulse-rotation-label"
            class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3"
          >
            <span
              id="proxy-dataimpulse-rotation-label"
              :class="FIELD_LABEL"
            >rotation</span>
            <div class="flex-1 min-w-0 flex flex-wrap gap-x-4 gap-y-1">
              <label
                for="proxy-dataimpulse-rotating"
                class="flex items-center gap-2 text-sm text-fg-primary cursor-pointer"
              >
                <input
                  id="proxy-dataimpulse-rotating"
                  v-model="dataimpulse.rotation"
                  type="radio"
                  name="proxy-dataimpulse-rotation"
                  value="rotating"
                  class="accent-emerald-600"
                >
                Rotating: a new IP for every request
              </label>
              <label
                for="proxy-dataimpulse-sticky"
                class="flex items-center gap-2 text-sm text-fg-primary cursor-pointer"
              >
                <input
                  id="proxy-dataimpulse-sticky"
                  v-model="dataimpulse.rotation"
                  type="radio"
                  name="proxy-dataimpulse-rotation"
                  value="sticky"
                  class="accent-emerald-600"
                >
                Sticky: one IP for a session
              </label>
            </div>
          </div>
          <div
            v-if="dataimpulse.rotation === 'sticky'"
            class="px-4 py-2 space-y-1"
          >
            <label
              for="proxy-dataimpulse-minutes"
              class="flex max-sm:flex-wrap items-center gap-3"
            >
              <span :class="FIELD_LABEL">session minutes</span>
              <input
                id="proxy-dataimpulse-minutes"
                v-model="dataimpulse.sessionMinutes"
                type="text"
                inputmode="numeric"
                autocomplete="off"
                :placeholder="`${DATAIMPULSE.sessionMinutesDefault}, or ${DATAIMPULSE.sessionMinutesMin} to ${DATAIMPULSE.sessionMinutesMax}`"
                :aria-invalid="!!errors.sessionMinutes"
                :aria-describedby="errors.sessionMinutes ? 'proxy-dataimpulse-minutes-error' : undefined"
                :class="INPUT"
              >
            </label>
            <p
              v-if="errors.sessionMinutes"
              id="proxy-dataimpulse-minutes-error"
              class="text-xs text-danger sm:ml-51"
            >
              {{ errors.sessionMinutes }}
            </p>
          </div>
          <div
            role="radiogroup"
            aria-labelledby="proxy-dataimpulse-gateway-label"
            class="px-4 py-2 space-y-1"
          >
            <div class="flex max-sm:flex-wrap items-center gap-3">
              <span
                id="proxy-dataimpulse-gateway-label"
                :class="FIELD_LABEL"
              >gateway</span>
              <div class="flex-1 min-w-0 flex flex-wrap gap-x-4 gap-y-1">
                <label
                  for="proxy-dataimpulse-gateway-hostname"
                  class="flex items-center gap-2 text-sm text-fg-primary cursor-pointer"
                >
                  <input
                    id="proxy-dataimpulse-gateway-hostname"
                    v-model="dataimpulse.gateway"
                    type="radio"
                    name="proxy-dataimpulse-gateway"
                    value="hostname"
                    class="accent-emerald-600"
                  >
                  <span class="font-mono">{{ DATAIMPULSE.host }}</span>
                </label>
                <label
                  for="proxy-dataimpulse-gateway-ip"
                  class="flex items-center gap-2 text-sm text-fg-primary cursor-pointer"
                >
                  <input
                    id="proxy-dataimpulse-gateway-ip"
                    v-model="dataimpulse.gateway"
                    type="radio"
                    name="proxy-dataimpulse-gateway"
                    value="ip"
                    class="accent-emerald-600"
                  >
                  <span class="font-mono">{{ DATAIMPULSE.ipHost }}</span>
                </label>
              </div>
            </div>
            <p class="text-xs text-fg-muted sm:ml-51">
              The same gateway either way. DataImpulse recommends the name, and publishes the address for
              networks whose DNS blocks it; the address may change.
            </p>
          </div>
          <p
            v-if="preview"
            class="px-4 py-2 text-xs text-fg-muted break-all"
            data-testid="proxy-dataimpulse-preview"
          >
            Connects to <span class="font-mono">{{ preview.url }}</span> with the username
            <span class="font-mono">{{ preview.username }}</span>.
          </p>
        </div>

        <div
          v-if="p.id === 'manual' && provider === 'manual'"
          class="border-t border-border divide-y divide-border"
        >
          <p class="px-4 py-2.5 text-xs text-fg-muted">
            Any proxy, as <span class="font-mono">http://host:port</span> or
            <span class="font-mono">socks5://host:port</span>. Credentials go in the username and password,
            never in the URL, and a SOCKS5 proxy is used without them. An HTTP proxy must allow CONNECT
            to any port, 80 included, because rendered pages tunnel every connection through it.
          </p>
          <div class="px-4 py-2 space-y-1">
            <label
              for="proxy-manual-url"
              class="flex max-sm:flex-wrap items-center gap-3"
            >
              <span :class="FIELD_LABEL">url</span>
              <input
                id="proxy-manual-url"
                v-model="manual.url"
                type="text"
                autocomplete="off"
                spellcheck="false"
                placeholder="http://proxy.example:8080"
                :aria-invalid="!!manualErrors.url"
                :aria-describedby="manualErrors.url ? 'proxy-manual-url-error' : undefined"
                :class="INPUT"
              >
            </label>
            <p
              v-if="manualErrors.url"
              id="proxy-manual-url-error"
              class="text-xs text-danger sm:ml-51"
            >
              {{ manualErrors.url }}
            </p>
          </div>
          <div class="px-4 py-2 space-y-1">
            <label
              for="proxy-manual-username"
              class="flex max-sm:flex-wrap items-center gap-3"
            >
              <span :class="FIELD_LABEL">username</span>
              <input
                id="proxy-manual-username"
                v-model="manual.username"
                type="text"
                autocomplete="off"
                spellcheck="false"
                :disabled="manualIsSocks"
                :aria-invalid="!!manualErrors.username"
                :aria-describedby="manualErrors.username ? 'proxy-manual-username-error' : undefined"
                :class="INPUT"
              >
            </label>
            <p
              v-if="manualErrors.username"
              id="proxy-manual-username-error"
              class="text-xs text-danger sm:ml-51"
            >
              {{ manualErrors.username }}
            </p>
          </div>
          <div class="px-4 py-2 space-y-1">
            <div class="flex max-sm:flex-wrap items-center gap-3">
              <span :class="FIELD_LABEL">password</span>
              <SecretField
                v-model="manualPassword"
                v-model:editing="manualPasswordEditing"
                form
                :saved="manualKeepsPassword"
                input-id="proxy-manual-password"
                label="proxy password"
                :input-class="INPUT"
                :input-attrs="{
                  'disabled': manualIsSocks,
                  'aria-invalid': !!manualErrors.password,
                  'aria-describedby': manualErrors.password ? 'proxy-manual-password-error' : passwordHint && !manualIsSocks && !manualKeepsPassword ? 'proxy-manual-password-hint' : undefined,
                }"
                data-testid="proxy-manual-password-field"
              />
            </div>
            <p
              v-if="manualErrors.password"
              id="proxy-manual-password-error"
              class="text-xs text-danger sm:ml-51"
            >
              {{ manualErrors.password }}
            </p>
            <p
              v-else-if="passwordHint && !manualIsSocks && !manualKeepsPassword"
              id="proxy-manual-password-hint"
              class="text-xs text-fg-muted sm:ml-51"
            >
              {{ passwordHint }}
            </p>
          </div>
          <p
            v-if="manualIsSocks"
            class="px-4 py-2 text-xs text-fg-muted"
          >
            Saving a SOCKS5 proxy clears the stored username and password.
          </p>
        </div>
      </div>
    </fieldset>

    <div class="flex items-center gap-3">
      <button
        type="button"
        class="px-3 py-1.5 text-sm border border-border bg-surface-elevated text-fg-primary hover:bg-muted transition-colors disabled:opacity-50"
        :disabled="saving || testing || !configData"
        data-testid="proxy-save"
        @click="save"
      >
        Save
      </button>
    </div>
    <ApiErrorAlert :error="saveError" />

    <div class="bg-surface-elevated border border-border divide-y divide-border">
      <div
        v-if="storedUrl"
        class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3"
      >
        <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0 flex items-center gap-1.5">
          enabled
          <InfoTip
            label="About enabled"
            content-class="w-64"
          >
            Turn the saved proxy off without clearing it. While it is off every fetch connects directly.
            Saving a proxy switches it on.
          </InfoTip>
        </span>
        <button
          type="button"
          :aria-pressed="enabled"
          aria-label="enabled"
          :disabled="saving || testing"
          :class="enabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
          class="relative w-9 h-5 rounded-full transition-colors"
          data-testid="proxy-enabled"
          @click="toggleEnabled"
        >
          <span
            :class="enabled ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
        <span class="ml-auto text-[11px] text-fg-muted">
          {{ enabled ? 'on' : 'off' }}
        </span>
      </div>
      <div class="px-4 py-2.5 space-y-2">
        <div class="flex max-sm:flex-wrap items-center gap-3">
          <button
            type="button"
            class="px-3 py-1.5 text-sm border border-border bg-surface-elevated text-fg-primary hover:bg-muted transition-colors disabled:opacity-50"
            :disabled="!canTest || testing || saving"
            data-testid="proxy-test"
            @click="testConnection"
          >
            {{ testing ? 'Testing…' : 'Test connection' }}
          </button>
          <span class="text-xs text-fg-muted">
            <template v-if="canTest">
              Sends one request through the saved proxy to
              <span class="font-mono">api.ipify.org</span>, which reports the address it came from.
            </template>
            <template v-else>
              Save a proxy and switch it on to test it.
            </template>
          </span>
        </div>
        <p class="text-xs text-fg-muted">
          The test sends plain HTTP, which the proxy relays itself. HTTPS pages and rendered pages also
          need the proxy to allow CONNECT, to port 443 at least.
        </p>
        <div
          role="status"
          data-testid="proxy-test-result"
          class="text-sm"
        >
          <template v-if="testResult?.ok">
            <span class="text-green-700 dark:text-green-400">Connected.</span>
            Egress IP <span class="font-mono">{{ testResult.ip }}</span>, in {{ duration(testResult.ms) }}.
          </template>
          <template v-else-if="testResult?.status && !isSuccess(testResult.status)">
            <span class="text-danger">The request got
              <span class="font-mono">{{ testResult.status }}{{ testResult.reason ? ` ${testResult.reason}` : '' }}</span></span>
            after {{ duration(testResult.ms) }}.
          </template>
          <span
            v-else-if="testResult"
            class="text-danger"
          >{{ testResult.error }}</span>
          <p
            v-if="resolution"
            class="mt-1 text-xs text-fg-muted"
            data-testid="proxy-test-resolution"
          >
            <span class="font-mono">{{ resolution.host }}</span>
            <template v-if="resolution.address">
              resolved to <span class="font-mono">{{ resolution.address }}</span> <span v-if="resolution.reverseName">(<span class="font-mono">{{ resolution.reverseName }}</span>)</span>
              on this machine.
            </template>
            <template v-else>
              did not resolve on this machine.
            </template>
          </p>
        </div>
        <ApiErrorAlert :error="testError" />
      </div>
    </div>
  </div>
</template>
