<script setup lang="ts">
// Printers settings panel (JCLAW-911). Browse the network, pick a default
// printer, and set the job options that default carries.
//
// Discovery is not run on mount. An mDNS browse takes seconds and blocks nothing
// useful — opening Settings to change the timezone should not pay for it — so the
// scan is an explicit action. The saved default loads immediately, because that
// is the state the operator came here to see.

interface PrinterEntry {
  name: string
  host: string
  port: number
  protocol: string
  formats: string | null
  isDefault: boolean
}

interface PrinterDefaults {
  name: string | null
  host: string | null
  port: number
  protocol: string | null
  /** IPP attribute → chosen value, for whatever this printer offers. */
  options: Record<string, string>
}

interface PrinterReachability {
  /** False when no default is saved — absence, not a fault. */
  configured: boolean
  reachable: boolean
  host: string | null
  port: number
}

/** One job option the printer announced — the UI has no list of its own. */
/** A selectable value: what to send, and what to show. They differ for enums —
 *  print-quality is carried as '4' but reads as 'normal'. */
interface OptionValue {
  value: string
  label: string
}

interface JobOption {
  /** IPP attribute name, e.g. 'sides' or 'media-source'. */
  name: string
  label: string
  /** Selectable values; empty when this is a numeric range. */
  values: OptionValue[]
  /** Set together for range attributes — copies is "1-99", not a list. */
  min: number | null
  max: number | null
  defaultValue: string | null
}

interface PrinterOptions {
  options: JobOption[]
  protocols: string[]
  mediaReady: string | null
  /** True when options came from the device rather than there being none. */
  fromPrinter: boolean
}

const { mutate } = useApiMutation()

const { data: saved, refresh: refreshSaved } = useLazyFetch<PrinterDefaults>('/api/printers/default')
// Separate lazy call so the saved address paints immediately and the badge fills in
// after the probe: a default outlives the DHCP lease it was saved under, and the
// only symptom until now was a print that timed out with no hint the address moved.
const { data: reach, refresh: refreshReach }
  = useLazyFetch<PrinterReachability>('/api/printers/default/status')
// Options are per-printer, not global: this Canon reports sides-supported =
// one-sided only, so offering duplex would let an operator save a default the
// printer must reject. Re-queried whenever the selected printer changes.
const options = ref<PrinterOptions | null>(null)

async function loadOptions(host?: string | null, port?: number | null, protocol?: string | null) {
  const query = host
    ? `?host=${encodeURIComponent(host)}&port=${port || 0}&protocol=${protocol || ''}`
    : ''
  options.value = await $fetch<PrinterOptions>(`/api/printers/options${query}`).catch(() => null)
}

const found = ref<PrinterEntry[] | null>(null)
const scanning = ref(false)
const savingState = ref(false)
const notice = ref<string | null>(null)
const problem = ref<string | null>(null)

// Draft option values keyed by IPP attribute name. '' means "printer's default".
// A map rather than named refs: the set of controls is the printer's to decide,
// so the component cannot know them ahead of time.
const draft = ref<Record<string, string>>({})

// Manual entry. Not a convenience: mDNS is link-local, so it is blocked on most
// VPNs and in any container without a multicast route — the exact networks where
// an operator is most likely to know the address and least likely to discover it.
// Without this the panel is unusable there, which UAT caught the hard way.
const manualHost = ref('')
const manualPort = ref('')
const manualProtocol = ref('')

watch(saved, (s) => {
  loadOptions(s?.host, s?.port, s?.protocol)
}, { immediate: true })

/**
 * Rebuild the draft whenever either the saved values or the printer's option
 * list changes, seeding every offered option with '' when it has no saved value.
 *
 * The empty string matters: it is the value of the "Printer default" <option>.
 * Leaving a key undefined matches no option at all — not even the placeholder —
 * so the browser renders a blank select, which reads as the control being broken
 * rather than as "not set". Both sources are watched because they arrive
 * independently: options come from the printer, values from the Config DB.
 */
watch([saved, options], ([s, o]) => {
  const next: Record<string, string> = {}
  for (const opt of o?.options ?? []) {
    next[opt.name] = s?.options?.[opt.name] ?? ''
  }
  draft.value = next
}, { immediate: true })

/** Only the options this printer actually offers, dropping any stale leftovers. */
function draftForPrinter(): Record<string, string> {
  const offered = new Set((options.value?.options ?? []).map(o => o.name))
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(draft.value)) {
    if (v && offered.has(k)) out[k] = v
  }
  return out
}

const hasDefault = computed(() => !!saved.value?.host)

/** "Office LaserJet — 10.0.0.5:631 (IPP)", collapsing the name when it IS the host. */
const defaultLabel = computed(() => {
  const s = saved.value
  if (!s?.host) return ''
  const address = s.port ? `${s.host}:${s.port}` : s.host
  const suffix = s.protocol ? ` (${s.protocol})` : ''
  return s.name && s.name !== s.host
    ? `${s.name} — ${address}${suffix}`
    : `${address}${suffix}`
})

async function scan() {
  scanning.value = true
  problem.value = null
  notice.value = null
  const list = await mutate<PrinterEntry[]>('/api/printers', { method: 'GET' })
  scanning.value = false
  if (list === null) {
    problem.value = 'Discovery failed. See the application log for details.'
    return
  }
  found.value = list
  if (list.length === 0) {
    notice.value = 'No printers answered. mDNS is link-local, so this is also what '
      + 'a blocked multicast route looks like — on a VPN or in a container it will '
      + 'always be empty. Enter the address by hand below if you know it.'
  }
}

/** Save a discovered printer as the default, carrying the current job options. */
async function chooseDefault(p: PrinterEntry) {
  await persist({
    name: p.name, host: p.host, port: p.port, protocol: p.protocol,
    options: draftForPrinter(),
  })
}

/** Save a hand-entered address as the default. */
async function useManual() {
  const host = manualHost.value.trim()
  if (!host) return
  const port = Number.parseInt(manualPort.value, 10)
  await persist({
    name: host, host,
    port: Number.isFinite(port) && port > 0 ? port : 0,
    protocol: manualProtocol.value || null,
    options: draftForPrinter(),
  })
  manualHost.value = ''
  manualPort.value = ''
}

/** Save job-option changes against the printer already chosen. */
async function saveOptions() {
  if (!saved.value?.host) return
  await persist({
    name: saved.value.name, host: saved.value.host, port: saved.value.port,
    protocol: saved.value.protocol,
    options: draftForPrinter(),
  })
}

async function clearDefault() {
  await persist({ host: null })
}

async function persist(body: Record<string, unknown>) {
  savingState.value = true
  problem.value = null
  notice.value = null
  const res = await mutate('/api/printers/default', { method: 'PUT', body })
  savingState.value = false
  if (res === null) {
    problem.value = 'Could not save. The value may be invalid — check the job options.'
    return
  }
  await refreshSaved()
  // Re-probe: saving is exactly when the address changes, so a badge left over from
  // the previous default would be reporting on something that is no longer set.
  await refreshReach()
  // Re-scan silently so the "Default" badge moves without another click.
  if (found.value) await scan()
  notice.value = body.host ? 'Default printer saved.' : 'Default printer cleared.'
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Printers
    </h2>
    <p class="text-xs text-fg-muted">
      Choose the printer the <code>printer</code> tool uses when an agent doesn't name one, and the
      job options it should carry. Everything here is optional — without a default, agents must pass
      a printer or host on every call.
    </p>

    <!-- Current default -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Default printer</span>
          <div class="text-xs text-fg-muted mt-0.5">
            <!-- One string, built in script: interpolating the parts inline put a
                 stray space before ":9100" and printed the name twice when it is
                 just the host (which manual entry makes it). -->
            <template v-if="hasDefault">
              {{ defaultLabel }}
              <span
                v-if="reach?.configured"
                class="ml-2 inline-flex items-center gap-1.5 align-middle"
                data-testid="printer-reachability"
              >
                <span
                  class="w-1.5 h-1.5 rounded-full shrink-0"
                  :class="reach.reachable ? 'bg-emerald-500' : 'bg-amber-500'"
                  aria-hidden="true"
                />
                <span :class="reach.reachable ? 'text-fg-muted' : 'text-amber-700 dark:text-amber-400'">
                  {{ reach.reachable ? 'Online' : 'Not answering' }}
                </span>
              </span>
            </template>
            <template v-else>
              None set. Agents must name a printer on every call.
            </template>
            <!-- Named rather than implied: the operator cannot act on "offline" until
                 they know the address is the suspect, and a moved DHCP lease is the
                 common cause. -->
            <div
              v-if="reach?.configured && !reach.reachable"
              class="mt-1 text-amber-700 dark:text-amber-400"
            >
              Nothing answered at this address. If the printer moved to a new IP, scan
              and save it again.
            </div>
          </div>
        </div>
        <button
          v-if="hasDefault"
          :disabled="savingState"
          class="shrink-0 px-3 py-1.5 text-xs font-medium text-fg-muted hover:text-fg-strong
                 border border-border rounded-full transition-colors disabled:opacity-40"
          @click="clearDefault"
        >
          Clear
        </button>
      </div>
    </div>

    <!-- Discovery -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Find printers</span>
          <div class="text-xs text-fg-muted mt-0.5">
            Browses the local network over mDNS/Bonjour. Takes a couple of seconds.
          </div>
        </div>
        <button
          :disabled="scanning"
          class="shrink-0 px-3 py-1.5 text-xs font-medium text-white bg-emerald-700
                 hover:bg-emerald-800 disabled:opacity-40 rounded-full transition-colors"
          @click="scan"
        >
          {{ scanning ? 'Scanning…' : 'Scan' }}
        </button>
      </div>

      <ul
        v-if="found && found.length"
        class="border-t border-border divide-y divide-border"
      >
        <li
          v-for="p in found"
          :key="`${p.host}:${p.port}`"
          class="px-4 py-2.5 flex items-center justify-between gap-4"
        >
          <div class="min-w-0">
            <span class="text-sm text-fg-strong">{{ p.name }}</span>
            <span
              v-if="p.isDefault"
              class="ml-2 px-1.5 py-0.5 text-[10px] uppercase tracking-wide
                     bg-secondary text-secondary-foreground rounded"
            >Default</span>
            <div class="text-xs text-fg-muted mt-0.5">
              {{ p.host }}:{{ p.port }} ({{ p.protocol }})<template v-if="p.formats">
                — {{ p.formats }}
              </template>
            </div>
          </div>
          <button
            :disabled="savingState || p.isDefault"
            class="shrink-0 px-3 py-1.5 text-xs font-medium border border-border
                   rounded-full transition-colors disabled:opacity-40
                   hover:text-fg-strong text-fg-muted"
            @click="chooseDefault(p)"
          >
            {{ p.isDefault ? 'Current' : 'Set default' }}
          </button>
        </li>
      </ul>

      <!-- Manual entry. The only route to a default on a network that blocks
           mDNS, which is most VPNs and any container without a multicast route. -->
      <div class="border-t border-border px-4 py-3">
        <div class="text-xs text-fg-muted mb-2">
          Or enter an address directly — needed when the network blocks mDNS.
        </div>
        <div class="grid gap-2 sm:grid-cols-[2fr_1fr_1fr_auto] sm:items-end">
          <label
            for="printer-manual-host"
            class="block"
          >
            <span class="block text-[11px] text-fg-muted mb-1">Host or IP</span>
            <input
              id="printer-manual-host"
              v-model="manualHost"
              type="text"
              placeholder="10.0.0.5"
              class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
            >
          </label>
          <label
            for="printer-manual-port"
            class="block"
          >
            <span class="block text-[11px] text-fg-muted mb-1">Port</span>
            <input
              id="printer-manual-port"
              v-model="manualPort"
              type="number"
              placeholder="auto"
              class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
            >
          </label>
          <label
            for="printer-manual-protocol"
            class="block"
          >
            <span class="block text-[11px] text-fg-muted mb-1">Protocol</span>
            <select
              id="printer-manual-protocol"
              v-model="manualProtocol"
              class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
            >
              <option value="">Auto</option>
              <option
                v-for="v in options?.protocols ?? []"
                :key="v"
                :value="v"
              >{{ v }}</option>
            </select>
          </label>
          <button
            :disabled="savingState || !manualHost.trim()"
            class="px-3 py-1.5 text-xs font-medium border border-border rounded-full
                   transition-colors disabled:opacity-40 hover:text-fg-strong text-fg-muted"
            @click="useManual"
          >
            Set default
          </button>
        </div>
      </div>
    </div>

    <!-- Job options. One select per attribute the printer announced; the panel
         has no list of its own, so a device offering trays or output bins gets
         those controls without a frontend change. -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5">
        <span class="text-sm font-medium text-fg-strong">Job options</span>
        <div class="text-xs text-fg-muted mt-0.5">
          Applied to jobs that don't specify their own. Leave any on <em>Printer default</em> to
          let the printer decide. These travel over IPP only — a job that falls back to a raw
          socket or LPD prints with the printer's own settings, and the tool says so.
        </div>
      </div>

      <div
        v-if="options?.fromPrinter"
        class="px-4 pb-3 grid gap-3 sm:grid-cols-3"
      >
        <label
          v-for="opt in options.options"
          :key="opt.name"
          :for="`printer-opt-${opt.name}`"
          class="block"
        >
          <span class="block text-[11px] text-fg-muted mb-1">{{ opt.label }}</span>
          <!-- Ranges are number inputs: copies-supported is 1-99, and a
               ninety-nine item dropdown is not a control. -->
          <input
            v-if="opt.min !== null && opt.max !== null"
            :id="`printer-opt-${opt.name}`"
            v-model="draft[opt.name]"
            type="number"
            :min="opt.min"
            :max="opt.max"
            :placeholder="opt.defaultValue ?? `${opt.min}–${opt.max}`"
            class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
          >
          <select
            v-else
            :id="`printer-opt-${opt.name}`"
            v-model="draft[opt.name]"
            class="w-full px-2 py-1.5 text-xs bg-surface border border-border"
          >
            <option value="">
              Printer default<template v-if="opt.defaultValue"> ({{ opt.defaultValue }})</template>
            </option>
            <option
              v-for="v in opt.values"
              :key="v.value"
              :value="v.value"
            >{{ v.label }}{{ opt.name === 'media' && v.value === options.mediaReady ? ' — loaded' : '' }}</option>
          </select>
        </label>
      </div>

      <div
        v-else
        class="px-4 pb-3 text-xs text-fg-muted"
      >
        <template v-if="hasDefault">
          This printer reported no job options — it may not speak IPP, or may be unreachable.
          Jobs will use its own settings.
        </template>
        <template v-else>
          Set a default printer above and its options appear here, read from the device.
        </template>
      </div>

      <div
        v-if="options?.fromPrinter"
        class="px-4 pb-3 flex justify-end"
      >
        <button
          :disabled="savingState || !hasDefault"
          class="px-3 py-1.5 text-xs font-medium text-white bg-emerald-700 hover:bg-emerald-800
                 disabled:opacity-40 rounded-full transition-colors"
          @click="saveOptions"
        >
          {{ savingState ? 'Saving…' : 'Save options' }}
        </button>
      </div>
    </div>

    <p
      v-if="notice || problem"
      class="text-xs"
      role="status"
      aria-live="polite"
    >
      <span
        v-if="problem"
        class="text-red-600 dark:text-red-400"
      >{{ problem }}</span>
      <span
        v-else
        class="text-fg-muted"
      >{{ notice }}</span>
    </p>
  </div>
</template>
