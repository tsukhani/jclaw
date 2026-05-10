<script setup lang="ts">
import type { McpServer, McpTestResult } from '~/types/api'
import { ArrowPathIcon, BeakerIcon, ChevronDownIcon, PlusIcon, TrashIcon, XMarkIcon } from '@heroicons/vue/24/outline'

const { data: servers, refresh } = await useFetch<McpServer[]>('/api/mcp-servers')
const { mutate, error: mutationError } = useApiMutation()
const { confirm } = useConfirm()

type TransportKind = 'STDIO' | 'HTTP'

interface KeyValueRow { key: string, value: string }
interface FormState {
  id: number | null
  name: string
  enabled: boolean
  transport: TransportKind
  command: string
  argsRaw: string
  envRows: KeyValueRow[]
  url: string
  headerRows: KeyValueRow[]
}

const editing = ref<FormState | null>(null)
const expandedRowId = ref<number | null>(null)
const testResult = ref<McpTestResult | null>(null)
const testResultForId = ref<number | null>(null)
const testing = ref(false)
const saveError = ref<string | null>(null)

// Stable ID prefix used to scope every form input — both add and edit
// flows share one editing form so reusing one prefix across both is
// fine. The IDs satisfy `vuejs-accessibility/label-has-for` which wants
// labels to both nest a control AND carry a matching `for=`.
const formId = useId()

function blankForm(): FormState {
  return {
    id: null,
    name: '',
    enabled: true,
    transport: 'STDIO',
    command: '',
    argsRaw: '',
    envRows: [{ key: '', value: '' }],
    url: '',
    headerRows: [{ key: '', value: '' }],
  }
}

function formFromServer(s: McpServer): FormState {
  const envRows = Object.entries(s.env || {}).map(([key, value]) => ({ key, value }))
  if (envRows.length === 0) envRows.push({ key: '', value: '' })
  const headerRows = Object.entries(s.headers || {}).map(([key, value]) => ({ key, value }))
  if (headerRows.length === 0) headerRows.push({ key: '', value: '' })
  return {
    id: s.id,
    name: s.name,
    enabled: s.enabled,
    transport: s.transport,
    command: s.command || '',
    argsRaw: (s.args || []).join('\n'),
    envRows,
    url: s.url || '',
    headerRows,
  }
}

function openAddForm() {
  editing.value = blankForm()
  expandedRowId.value = null
  testResult.value = null
  testResultForId.value = null
  saveError.value = null
}

function openEditForm(s: McpServer) {
  editing.value = formFromServer(s)
  expandedRowId.value = s.id
  testResult.value = null
  testResultForId.value = null
  saveError.value = null
}

function cancelEdit() {
  editing.value = null
  expandedRowId.value = null
  testResult.value = null
  testResultForId.value = null
  saveError.value = null
}

function rowsToMap(rows: KeyValueRow[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const r of rows) {
    if (r.key.trim()) out[r.key.trim()] = r.value
  }
  return out
}

function buildPayload(form: FormState) {
  const base: Record<string, unknown> = {
    name: form.name.trim(),
    enabled: form.enabled,
    transport: form.transport,
  }
  if (form.transport === 'STDIO') {
    base.command = form.command.trim()
    base.args = form.argsRaw.split('\n').map(s => s.trim()).filter(s => s.length > 0)
    base.env = rowsToMap(form.envRows)
  }
  else {
    base.url = form.url.trim()
    base.headers = rowsToMap(form.headerRows)
  }
  return base
}

async function saveForm() {
  if (!editing.value) return
  saveError.value = null
  const form = editing.value
  if (!form.name.trim()) {
    saveError.value = 'Name is required'
    return
  }
  const payload = buildPayload(form)
  const result = form.id == null
    ? await mutate<McpServer>('/api/mcp-servers', { method: 'POST', body: payload })
    : await mutate<McpServer>(`/api/mcp-servers/${form.id}`, { method: 'PUT', body: payload })
  if (result == null) {
    saveError.value = mutationError.value || 'Save failed'
    return
  }
  await refresh()
  editing.value = null
  expandedRowId.value = null
  // A newly-created or edited server may be in CONNECTING for a few
  // seconds while the connector handshakes. Poll so the badge ticks
  // over to CONNECTED/ERROR without requiring a manual refresh.
  await pollUntilStable()
}

async function toggleEnabled(s: McpServer) {
  await mutate<McpServer>(`/api/mcp-servers/${s.id}`, {
    method: 'PUT',
    body: { enabled: !s.enabled },
  })
  await refresh()
  await pollUntilStable()
}

/**
 * Poll the list while any row is in CONNECTING state. The connect handshake
 * takes a few seconds (Docker spawn for STDIO, network handshake for HTTP),
 * during which the row would otherwise display "CONNECTING" indefinitely
 * because the page only re-fetches on user actions. Bounded by maxAttempts
 * to avoid an infinite loop on a server stuck in CONNECTING.
 */
async function pollUntilStable() {
  const intervalMs = 600
  const maxAttempts = 60 // ~36s ceiling, well past the 30s request timeout
  for (let i = 0; i < maxAttempts; i++) {
    if (!servers.value?.some(row => row.status === 'CONNECTING')) return
    await new Promise(r => setTimeout(r, intervalMs))
    await refresh()
  }
}

async function deleteServer(s: McpServer) {
  const ok = await confirm({
    title: `Delete MCP server "${s.name}"?`,
    message: 'Disconnects the server, removes it from the registry, and clears all per-agent allowlist entries. The action is audited as MCP_TOOL_UNREGISTER and cannot be undone.',
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  await mutate(`/api/mcp-servers/${s.id}`, { method: 'DELETE' })
  await refresh()
}

async function testServer(s: McpServer) {
  testResult.value = null
  testResultForId.value = s.id
  testing.value = true
  try {
    const result = await mutate<McpTestResult>(`/api/mcp-servers/${s.id}/test`, {
      method: 'POST',
      body: {},
    })
    testResult.value = result
  }
  finally {
    testing.value = false
  }
}

async function testFromForm() {
  if (editing.value?.id == null) {
    saveError.value = 'Save first, then test connection.'
    return
  }
  await testServer({ id: editing.value.id } as McpServer)
}

const statusBadgeClass: Record<McpServer['status'], string> = {
  CONNECTED: 'text-green-400 border-green-400/30',
  CONNECTING: 'text-yellow-400 border-yellow-400/30',
  DISCONNECTED: 'text-neutral-500 border-neutral-500/30',
  ERROR: 'text-red-400 border-red-400/30',
}

function addEnvRow() {
  editing.value?.envRows.push({ key: '', value: '' })
}
function removeEnvRow(i: number) {
  if (!editing.value) return
  editing.value.envRows.splice(i, 1)
  if (editing.value.envRows.length === 0) editing.value.envRows.push({ key: '', value: '' })
}
function addHeaderRow() {
  editing.value?.headerRows.push({ key: '', value: '' })
}
function removeHeaderRow(i: number) {
  if (!editing.value) return
  editing.value.headerRows.splice(i, 1)
  if (editing.value.headerRows.length === 0) editing.value.headerRows.push({ key: '', value: '' })
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        MCP Servers
      </h1>
      <button
        type="button"
        class="inline-flex items-center gap-1.5 text-xs px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white"
        @click="openAddForm"
      >
        <PlusIcon class="w-4 h-4" />
        Add server
      </button>
    </div>

    <p class="text-sm text-fg-muted mb-4">
      Configured MCP servers JClaw will connect to at startup. Discovered tools register as
      <code class="text-xs">mcp_&lt;server&gt;_&lt;tool&gt;</code> and gate through the per-agent allowlist (JCLAW-32).
      Deleting a server disconnects it and clears every per-agent grant.
    </p>

    <!-- Add form (when no row is being edited) -->
    <div
      v-if="editing && editing.id == null"
      class="mb-6 border border-emerald-600/40 bg-surface-elevated p-4"
    >
      <div class="flex items-center justify-between mb-3">
        <h2 class="text-sm font-semibold text-fg-strong">
          New MCP server
        </h2>
        <button
          type="button"
          class="text-fg-muted hover:text-fg-strong"
          aria-label="Cancel"
          @click="cancelEdit"
        >
          <XMarkIcon class="w-5 h-5" />
        </button>
      </div>

      <form
        class="space-y-4"
        @submit.prevent="saveForm"
      >
        <div class="flex items-end gap-4">
          <label
            :for="`${formId}-name`"
            class="flex-1 block"
          >
            <span class="block text-xs text-fg-muted mb-1">Name</span>
            <input
              :id="`${formId}-name`"
              v-model="editing.name"
              type="text"
              required
              pattern="^[a-zA-Z0-9_][a-zA-Z0-9_-]{0,63}$"
              placeholder="github"
              class="w-full bg-surface border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden"
            >
          </label>
          <label
            :for="`${formId}-enabled`"
            class="flex items-center gap-2"
          >
            <input
              :id="`${formId}-enabled`"
              v-model="editing.enabled"
              type="checkbox"
            >
            <span class="text-xs text-fg-muted">Enabled</span>
          </label>
        </div>

        <fieldset>
          <legend class="block text-xs text-fg-muted mb-1">
            Transport
          </legend>
          <div class="flex gap-4">
            <label
              :for="`${formId}-stdio`"
              class="flex items-center gap-2 text-sm"
            >
              <input
                :id="`${formId}-stdio`"
                v-model="editing.transport"
                type="radio"
                value="STDIO"
              >
              <span>STDIO</span>
            </label>
            <label
              :for="`${formId}-http`"
              class="flex items-center gap-2 text-sm"
            >
              <input
                :id="`${formId}-http`"
                v-model="editing.transport"
                type="radio"
                value="HTTP"
              >
              <span>HTTP (Streamable)</span>
            </label>
          </div>
        </fieldset>

        <div
          v-if="editing.transport === 'STDIO'"
          class="space-y-3 border-l-2 border-emerald-600/40 pl-3"
        >
          <label
            :for="`${formId}-command`"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">Command</span>
            <input
              :id="`${formId}-command`"
              v-model="editing.command"
              type="text"
              required
              placeholder="npx"
              class="w-full bg-surface border border-input text-sm text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
            >
          </label>
          <label
            :for="`${formId}-args`"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">Arguments (one per line)</span>
            <textarea
              :id="`${formId}-args`"
              v-model="editing.argsRaw"
              rows="3"
              placeholder="-y&#10;@modelcontextprotocol/server-github"
              class="w-full bg-surface border border-input text-sm text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
            />
          </label>
          <fieldset>
            <legend class="block text-xs text-fg-muted mb-1">
              Environment variables
            </legend>
            <div class="space-y-1.5">
              <div
                v-for="(row, i) in editing.envRows"
                :key="`add-env-${i}`"
                class="flex gap-2 items-center"
              >
                <input
                  v-model="row.key"
                  type="text"
                  placeholder="KEY"
                  aria-label="Environment variable name"
                  class="flex-1 bg-surface border border-input text-xs text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                >
                <input
                  v-model="row.value"
                  type="text"
                  placeholder="value"
                  aria-label="Environment variable value"
                  class="flex-1 bg-surface border border-input text-xs text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                >
                <button
                  type="button"
                  class="text-fg-muted hover:text-red-400 p-1"
                  aria-label="Remove env var"
                  @click="removeEnvRow(i)"
                >
                  <TrashIcon class="w-4 h-4" />
                </button>
              </div>
            </div>
            <button
              type="button"
              class="mt-1.5 text-xs text-fg-muted hover:text-fg-strong inline-flex items-center gap-1"
              @click="addEnvRow"
            >
              <PlusIcon class="w-3.5 h-3.5" />
              Add env var
            </button>
          </fieldset>
        </div>

        <div
          v-else
          class="space-y-3 border-l-2 border-emerald-600/40 pl-3"
        >
          <label
            :for="`${formId}-url`"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">Endpoint URL</span>
            <input
              :id="`${formId}-url`"
              v-model="editing.url"
              type="url"
              required
              placeholder="https://mcp.example.com/v1/mcp"
              class="w-full bg-surface border border-input text-sm text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
            >
          </label>
          <fieldset>
            <legend class="block text-xs text-fg-muted mb-1">
              Headers
            </legend>
            <div class="space-y-1.5">
              <div
                v-for="(row, i) in editing.headerRows"
                :key="`add-hdr-${i}`"
                class="flex gap-2 items-center"
              >
                <input
                  v-model="row.key"
                  type="text"
                  placeholder="Header-Name"
                  aria-label="Header name"
                  class="flex-1 bg-surface border border-input text-xs text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                >
                <input
                  v-model="row.value"
                  type="text"
                  placeholder="value"
                  aria-label="Header value"
                  class="flex-1 bg-surface border border-input text-xs text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                >
                <button
                  type="button"
                  class="text-fg-muted hover:text-red-400 p-1"
                  aria-label="Remove header"
                  @click="removeHeaderRow(i)"
                >
                  <TrashIcon class="w-4 h-4" />
                </button>
              </div>
            </div>
            <button
              type="button"
              class="mt-1.5 text-xs text-fg-muted hover:text-fg-strong inline-flex items-center gap-1"
              @click="addHeaderRow"
            >
              <PlusIcon class="w-3.5 h-3.5" />
              Add header
            </button>
          </fieldset>
        </div>

        <div
          v-if="saveError"
          class="text-xs text-red-400 border border-red-400/30 bg-red-400/5 p-2"
        >
          {{ saveError }}
        </div>

        <div class="flex items-center gap-2 pt-2">
          <button
            type="submit"
            class="text-xs px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white"
          >
            Create
          </button>
          <button
            type="button"
            class="text-xs px-3 py-1.5 text-fg-muted hover:text-fg-strong ml-auto"
            @click="cancelEdit"
          >
            Cancel
          </button>
        </div>
      </form>
    </div>

    <div class="bg-surface-elevated border border-border">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th class="px-4 py-2.5 font-medium">
              Name
            </th>
            <th class="px-4 py-2.5 font-medium">
              Transport
            </th>
            <th class="px-4 py-2.5 font-medium">
              Endpoint
            </th>
            <th class="px-4 py-2.5 font-medium">
              Status
            </th>
            <th class="px-4 py-2.5 font-medium">
              Tools
            </th>
            <th class="px-4 py-2.5 font-medium text-right">
              Actions
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <template
            v-for="server in servers"
            :key="server.id"
          >
            <tr>
              <td class="px-4 py-2.5 text-fg-primary font-medium">
                {{ server.name }}
              </td>
              <td class="px-4 py-2.5 text-fg-muted font-mono text-xs">
                {{ server.transport }}
              </td>
              <td class="px-4 py-2.5 text-fg-muted font-mono text-xs truncate max-w-xs">
                {{ server.transport === 'HTTP' ? server.url : `${server.command || ''} ${(server.args || []).join(' ')}`.trim() }}
              </td>
              <td class="px-4 py-2.5">
                <span
                  class="text-[10px] font-mono px-1.5 py-px rounded-sm border"
                  :class="statusBadgeClass[server.status]"
                  :title="server.lastError || ''"
                >{{ server.status }}</span>
              </td>
              <td class="px-4 py-2.5 text-fg-muted text-xs">
                {{ server.toolCount }}
              </td>
              <td class="px-4 py-2.5 text-right">
                <div class="flex items-center justify-end gap-3">
                  <button
                    type="button"
                    class="relative w-9 h-5 rounded-full transition-colors"
                    :class="server.enabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-neutral-300'"
                    :title="server.enabled ? 'Disable' : 'Enable'"
                    :aria-label="server.enabled ? `Disable ${server.name}` : `Enable ${server.name}`"
                    @click="toggleEnabled(server)"
                  >
                    <span
                      class="block w-4 h-4 rounded-full bg-white transition-transform"
                      :class="server.enabled ? 'translate-x-4' : 'translate-x-0.5'"
                    />
                  </button>
                  <button
                    type="button"
                    class="text-xs text-fg-muted hover:text-fg-strong transition-colors flex items-center gap-1"
                    @click="expandedRowId === server.id ? cancelEdit() : openEditForm(server)"
                  >
                    Edit
                    <ChevronDownIcon
                      class="w-3 h-3 transition-transform"
                      :class="expandedRowId === server.id ? 'rotate-180' : ''"
                    />
                  </button>
                  <button
                    type="button"
                    class="text-fg-muted hover:text-fg-strong transition-colors disabled:opacity-50 disabled:cursor-wait"
                    :title="testing && testResultForId === server.id ? 'Testing…' : 'Test connection'"
                    :aria-label="`Test connection to ${server.name}`"
                    :disabled="testing && testResultForId === server.id"
                    @click="testServer(server)"
                  >
                    <ArrowPathIcon
                      v-if="testing && testResultForId === server.id"
                      class="w-4 h-4 animate-spin"
                    />
                    <BeakerIcon
                      v-else
                      class="w-4 h-4"
                    />
                  </button>
                  <button
                    type="button"
                    class="text-fg-muted hover:text-red-400 transition-colors"
                    :title="`Delete ${server.name}`"
                    :aria-label="`Delete ${server.name}`"
                    @click="deleteServer(server)"
                  >
                    <TrashIcon class="w-4 h-4" />
                  </button>
                </div>
              </td>
            </tr>

            <!-- Inline edit panel — same input structure as the add form -->
            <tr v-if="expandedRowId === server.id && editing && editing.id === server.id">
              <td
                colspan="6"
                class="bg-muted/30 p-4"
              >
                <form
                  class="space-y-4"
                  @submit.prevent="saveForm"
                >
                  <div class="flex items-end gap-4">
                    <label
                      :for="`edit-${server.id}-name`"
                      class="flex-1 block"
                    >
                      <span class="block text-xs text-fg-muted mb-1">Name</span>
                      <input
                        :id="`edit-${server.id}-name`"
                        v-model="editing.name"
                        type="text"
                        required
                        pattern="^[a-zA-Z0-9_][a-zA-Z0-9_-]{0,63}$"
                        class="w-full bg-surface border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden"
                      >
                    </label>
                    <label
                      :for="`edit-${server.id}-enabled`"
                      class="flex items-center gap-2"
                    >
                      <input
                        :id="`edit-${server.id}-enabled`"
                        v-model="editing.enabled"
                        type="checkbox"
                      >
                      <span class="text-xs text-fg-muted">Enabled</span>
                    </label>
                  </div>

                  <fieldset>
                    <legend class="block text-xs text-fg-muted mb-1">
                      Transport
                    </legend>
                    <div class="flex gap-4">
                      <label
                        :for="`edit-${server.id}-stdio`"
                        class="flex items-center gap-2 text-sm"
                      >
                        <input
                          :id="`edit-${server.id}-stdio`"
                          v-model="editing.transport"
                          type="radio"
                          value="STDIO"
                        >
                        <span>STDIO</span>
                      </label>
                      <label
                        :for="`edit-${server.id}-http`"
                        class="flex items-center gap-2 text-sm"
                      >
                        <input
                          :id="`edit-${server.id}-http`"
                          v-model="editing.transport"
                          type="radio"
                          value="HTTP"
                        >
                        <span>HTTP (Streamable)</span>
                      </label>
                    </div>
                  </fieldset>

                  <div
                    v-if="editing.transport === 'STDIO'"
                    class="space-y-3 border-l-2 border-emerald-600/40 pl-3"
                  >
                    <label
                      :for="`edit-${server.id}-command`"
                      class="block"
                    >
                      <span class="block text-xs text-fg-muted mb-1">Command</span>
                      <input
                        :id="`edit-${server.id}-command`"
                        v-model="editing.command"
                        type="text"
                        required
                        class="w-full bg-surface border border-input text-sm text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                      >
                    </label>
                    <label
                      :for="`edit-${server.id}-args`"
                      class="block"
                    >
                      <span class="block text-xs text-fg-muted mb-1">Arguments (one per line)</span>
                      <textarea
                        :id="`edit-${server.id}-args`"
                        v-model="editing.argsRaw"
                        rows="3"
                        class="w-full bg-surface border border-input text-sm text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                      />
                    </label>
                    <fieldset>
                      <legend class="block text-xs text-fg-muted mb-1">
                        Environment variables
                      </legend>
                      <div class="space-y-1.5">
                        <div
                          v-for="(row, i) in editing.envRows"
                          :key="`edit-env-${i}`"
                          class="flex gap-2 items-center"
                        >
                          <input
                            v-model="row.key"
                            type="text"
                            placeholder="KEY"
                            aria-label="Environment variable name"
                            class="flex-1 bg-surface border border-input text-xs text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                          >
                          <input
                            v-model="row.value"
                            type="text"
                            placeholder="value"
                            aria-label="Environment variable value"
                            class="flex-1 bg-surface border border-input text-xs text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                          >
                          <button
                            type="button"
                            class="text-fg-muted hover:text-red-400 p-1"
                            aria-label="Remove env var"
                            @click="removeEnvRow(i)"
                          >
                            <TrashIcon class="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                      <button
                        type="button"
                        class="mt-1.5 text-xs text-fg-muted hover:text-fg-strong inline-flex items-center gap-1"
                        @click="addEnvRow"
                      >
                        <PlusIcon class="w-3.5 h-3.5" />
                        Add env var
                      </button>
                    </fieldset>
                  </div>

                  <div
                    v-else
                    class="space-y-3 border-l-2 border-emerald-600/40 pl-3"
                  >
                    <label
                      :for="`edit-${server.id}-url`"
                      class="block"
                    >
                      <span class="block text-xs text-fg-muted mb-1">Endpoint URL</span>
                      <input
                        :id="`edit-${server.id}-url`"
                        v-model="editing.url"
                        type="url"
                        required
                        class="w-full bg-surface border border-input text-sm text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                      >
                    </label>
                    <fieldset>
                      <legend class="block text-xs text-fg-muted mb-1">
                        Headers
                      </legend>
                      <div class="space-y-1.5">
                        <div
                          v-for="(row, i) in editing.headerRows"
                          :key="`edit-hdr-${i}`"
                          class="flex gap-2 items-center"
                        >
                          <input
                            v-model="row.key"
                            type="text"
                            placeholder="Header-Name"
                            aria-label="Header name"
                            class="flex-1 bg-surface border border-input text-xs text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                          >
                          <input
                            v-model="row.value"
                            type="text"
                            placeholder="value"
                            aria-label="Header value"
                            class="flex-1 bg-surface border border-input text-xs text-fg-strong px-2 py-1 font-mono focus:outline-hidden"
                          >
                          <button
                            type="button"
                            class="text-fg-muted hover:text-red-400 p-1"
                            aria-label="Remove header"
                            @click="removeHeaderRow(i)"
                          >
                            <TrashIcon class="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                      <button
                        type="button"
                        class="mt-1.5 text-xs text-fg-muted hover:text-fg-strong inline-flex items-center gap-1"
                        @click="addHeaderRow"
                      >
                        <PlusIcon class="w-3.5 h-3.5" />
                        Add header
                      </button>
                    </fieldset>
                  </div>

                  <div
                    v-if="testResult && testResultForId === server.id"
                    class="text-xs p-2 border"
                    :class="testResult.success ? 'text-green-400 border-green-400/30 bg-green-400/5' : 'text-red-400 border-red-400/30 bg-red-400/5'"
                  >
                    <div class="font-medium">
                      {{ testResult.success ? '✓ Connection successful' : '✗ Connection failed' }}
                    </div>
                    <div class="font-mono mt-1">
                      {{ testResult.message }}
                    </div>
                  </div>

                  <div
                    v-if="saveError"
                    class="text-xs text-red-400 border border-red-400/30 bg-red-400/5 p-2"
                  >
                    {{ saveError }}
                  </div>

                  <div class="flex items-center gap-2 pt-2">
                    <button
                      type="submit"
                      class="text-xs px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white"
                    >
                      Save changes
                    </button>
                    <button
                      type="button"
                      class="text-xs px-3 py-1.5 border border-input text-fg-muted hover:text-fg-strong"
                      :disabled="testing"
                      @click="testFromForm"
                    >
                      {{ testing ? 'Testing…' : 'Test connection' }}
                    </button>
                    <button
                      type="button"
                      class="text-xs px-3 py-1.5 text-fg-muted hover:text-fg-strong ml-auto"
                      @click="cancelEdit"
                    >
                      Cancel
                    </button>
                  </div>
                </form>
              </td>
            </tr>

            <!-- Test result banner for collapsed rows -->
            <tr v-else-if="testResult && testResultForId === server.id">
              <td
                colspan="6"
                class="bg-muted/30 px-4 py-2 text-xs"
                :class="testResult.success ? 'text-green-400' : 'text-red-400'"
              >
                {{ testResult.success ? '✓ ' : '✗ ' }}{{ testResult.message }}
              </td>
            </tr>
          </template>
        </tbody>
      </table>
      <div
        v-if="!servers?.length"
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No MCP servers configured. Use "Add server" to connect one.
      </div>
    </div>
  </div>
</template>
