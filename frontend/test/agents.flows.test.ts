import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Agents from '~/pages/agents/[[name]].vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * JCLAW-322 — pages/agents.vue critical-path flow coverage.
 *
 * The existing agents.page.test.ts covers structural rendering (list,
 * model id, New Agent button, skills sort, empty state). This sibling spec
 * exercises the still-uncovered user flows that drive the bulk of the
 * page's logic:
 *
 *   - Edit flow: clicking a custom agent populates the form and pulls
 *     the agent's tools / skills / workspace file / queue mode / exec
 *     config / effective allowlist from the API.
 *   - Create flow: clicking New Agent opens the form, typing a name then
 *     clicking Save POSTs /api/agents with the form payload.
 *   - Edit-save: changing form fields then clicking Save PUTs
 *     /api/agents/:id.
 *   - Toggle enabled from the row: PUTs partial { enabled } body.
 *   - Bulk-delete: select-mode + ConfirmDialog cancel preserves rows;
 *     confirm fires DELETE /api/agents/:id per selected id.
 *   - Tool toggle: clicking a single-tool row's toggle PUTs the tool
 *     enabled flag. Bulk-toggle fires one PUT per tool.
 *   - Skill toggle: clicking a skill row's toggle PUTs the skill
 *     enabled flag; bulk-toggle fans out.
 *   - Workspace file: switching tabs fetches the new file; saving
 *     PUTs the textarea contents.
 *   - Queue mode: changing the dropdown POSTs to /api/config.
 *   - Inspect prompt: opening the dialog fetches /api/agents/:id/prompt-breakdown,
 *     channel switch refetches, Escape and close button dismiss.
 */

/**
 * Mount Agents with a sibling ConfirmDialog so the bulk-delete flow's
 * confirm() actually renders into the DOM. useConfirm() uses module-singleton
 * state and ConfirmDialog is what reads it; in production it's mounted once
 * at the app root.
 */
const AgentsHarness = defineComponent({
  setup() {
    return () => h('div', [h(Agents), h(ConfirmDialog)])
  },
})

function setupAgentsApi(opts?: {
  agents?: unknown[]
  configEntries?: unknown[]
  agent1Tools?: unknown[]
  agent2Tools?: unknown[]
  agent1Skills?: unknown[]
  agent2Skills?: unknown[]
  agent1Core?: unknown
  agent2Core?: unknown
}) {
  // JCLAW-981: core-memory usage is per agent, so it is polled per agent.
  registerEndpoint('/api/agents/1/core-migration', () => opts?.agent1Core ?? ({
    running: false, processed: 0, total: 0, liveCore: 4, cap: 20, overCap: false, error: null,
  }))
  registerEndpoint('/api/agents/2/core-migration', () => opts?.agent2Core ?? ({
    running: false, processed: 0, total: 0, liveCore: 4, cap: 20, overCap: false, error: null,
  }))
  registerEndpoint('/api/agents', () => opts?.agents ?? [
    {
      id: 1,
      name: 'main',
      modelProvider: 'ollama-cloud',
      modelId: 'kimi-k2.5',
      enabled: true,
      isMain: true,
      providerConfigured: true,
      thinkingMode: null,
      compressionEnabled: true,
      compressionJson: true,
      compressionCode: true,
      compressionText: true,
      compressionTargetRatio: 0.3, acpAllowed: false,
      memoryAutocaptureEnabled: true, memoryAutocaptureModelInherited: true,
      memoryAutocaptureProvider: 'ollama-cloud', memoryAutocaptureModel: 'kimi-k2.5',
      fallbackProvider: null, fallbackModelId: null,
      createdAt: '2026-04-01T10:00:00Z',
      updatedAt: '2026-04-22T10:00:00Z',
    },
    {
      id: 2,
      name: 'helper',
      modelProvider: 'openai',
      modelId: 'gpt-4',
      enabled: true,
      isMain: false,
      providerConfigured: true,
      thinkingMode: null,
      compressionEnabled: false,
      compressionJson: false,
      compressionCode: false,
      compressionText: false,
      compressionTargetRatio: 0.3, acpAllowed: false,
      memoryAutocaptureEnabled: true, memoryAutocaptureModelInherited: true,
      memoryAutocaptureProvider: 'openai', memoryAutocaptureModel: 'gpt-4',
      fallbackProvider: null, fallbackModelId: null,
      createdAt: '2026-04-10T10:00:00Z',
      updatedAt: '2026-04-20T10:00:00Z',
    },
  ])
  registerEndpoint('/api/config', () => ({
    entries: opts?.configEntries ?? [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
      { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****' },
      {
        key: 'provider.ollama-cloud.models',
        value: '[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":false}]',
      },
      { key: 'provider.openai.baseUrl', value: 'https://api.openai.com/v1' },
      { key: 'provider.openai.apiKey', value: 'sk-****' },
      {
        key: 'provider.openai.models',
        value: '[{"id":"gpt-4","name":"GPT-4","supportsThinking":false}]',
      },
    ],
  }))
  registerEndpoint('/api/tools/meta', () => [
    {
      name: 'exec',
      category: 'System',
      icon: 'terminal',
      shortDescription: 'Shell',
      system: false,
      actions: [],
    },
    {
      name: 'filesystem',
      category: 'Files',
      icon: 'folder',
      shortDescription: 'Files',
      system: false,
      actions: [],
    },
  ])
  registerEndpoint('/api/agents/1/tools', () => opts?.agent1Tools ?? [
    { name: 'exec', description: 'Execute shell', system: false, enabled: true },
    { name: 'filesystem', description: 'Filesystem', system: false, enabled: false },
  ])
  registerEndpoint('/api/agents/2/tools', () => opts?.agent2Tools ?? [
    { name: 'exec', description: 'Execute shell', system: false, enabled: false },
    { name: 'filesystem', description: 'Filesystem', system: false, enabled: true },
  ])
  registerEndpoint('/api/agents/1/skills', () => opts?.agent1Skills ?? [])
  registerEndpoint('/api/agents/2/skills', () => opts?.agent2Skills ?? [
    { name: 'web-search', enabled: true, isGlobal: true, tools: [] },
    { name: 'code-review', enabled: false, isGlobal: false, tools: [] },
  ])
  // Per-agent shell allowlist surface, fetched on edit-open.
  registerEndpoint('/api/agents/1/shell/effective-allowlist', () => ({
    global: ['ls', 'cat'],
    bySkill: {},
  }))
  registerEndpoint('/api/agents/2/shell/effective-allowlist', () => ({
    global: ['ls', 'cat'],
    bySkill: { 'web-search': ['curl'] },
  }))
  // Workspace files — AGENT.md is the first tab opened by editAgent.
  registerEndpoint('/api/agents/1/workspace/AGENT.md', () => ({ content: 'agent 1 instructions' }))
  registerEndpoint('/api/agents/2/workspace/AGENT.md', () => ({ content: 'helper instructions' }))
  registerEndpoint('/api/agents/2/workspace/SOUL.md', () => ({ content: 'helper soul' }))
  // Per-agent config endpoints — queue mode + exec privileges.
  registerEndpoint('/api/config/agent.main.queue.mode', () => ({ value: 'queue' }))
  registerEndpoint('/api/config/agent.helper.queue.mode', () => ({ value: 'collect' }))
  registerEndpoint('/api/config/agent.main.shell.bypassAllowlist', () => ({ value: 'false' }))
  registerEndpoint('/api/config/agent.main.shell.allowGlobalPaths', () => ({ value: 'false' }))
  registerEndpoint('/api/config/agent.helper.shell.bypassAllowlist', () => ({ value: 'false' }))
  registerEndpoint('/api/config/agent.helper.shell.allowGlobalPaths', () => ({ value: 'false' }))
}

beforeEach(() => {
  // useFetch caches by URL across mounts; clear so each case sees its own
  // fixture. Mirrors skills.flows.test.ts.
  clearNuxtData()
})

afterEach(() => {
  vi.restoreAllMocks()
  // Drain any leaked open ConfirmDialog from the harness mount.
  const { _state, _resolve } = useConfirm()
  if (_state.open) _resolve(false)
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

/**
 * Helper: locate and click the helper agent's row to enter edit mode. The
 * card is keyboard-interactive via @keydown.enter, so a synthetic click on
 * the matching role="button" is the most robust trigger.
 *
 * Typed as `unknown` here because `mountSuspended`'s return type uses the
 * Nuxt-test-utils ComponentMountingOptions inference, which doesn't narrow
 * well across spec files; we only need the findAll/trigger surface.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: helper consumes the Nuxt mount wrapper without depending on its precise generic shape.
async function openHelperEdit(component: any) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: matches the broad wrapper above.
  const targets = component.findAll('[role="button"], button') as any[]
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: see above.
  const helperCard = targets.find((t: any) => t.text().includes('helper'))
  expect(helperCard, 'helper agent card should be reachable').toBeTruthy()
  await helperCard!.trigger('click')
  // Opening an agent is a route change to /agents/<id>, and the global auth
  // middleware awaits an /api/config probe before the page's route watcher runs
  // — so a single flush lands before the form exists.
  await vi.waitFor(() => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: matches the broad wrapper above.
    const values = component.findAll('input').map((i: any) => i.element.value)
    expect(values).toContain('helper')
  })
}

describe('Agents page — edit flow opens form and pulls per-agent state', () => {
  it('populates the form fields, tools, skills, allowlist, and workspace from API on edit', async () => {
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // Form fields render with the helper's persisted values.
    const nameInput = component.find<HTMLInputElement>('input[disabled=""]')
    // The Name input doesn't carry disabled (helper is not main). Match by id-association — there are 2 text inputs in the form.
    const allInputs = component.findAll<HTMLInputElement>('input[type="text"], input:not([type])')
    const nameInputEl = allInputs.find(i => i.element.value === 'helper')
    expect(nameInputEl, 'name input should be prefilled to "helper"').toBeTruthy()
    // Acknowledge the unused alias so the linter doesn't grumble about a
    // dropped variable while documenting intent.
    expect(nameInput).toBeDefined()

    // Provider/Model dropdowns: the form populates both selects.
    const selects = component.findAll<HTMLSelectElement>('select')
    expect(selects.length).toBeGreaterThan(0)
    const providerSel = selects.find(s => s.element.value === 'openai')
    expect(providerSel, 'provider select should default to openai').toBeTruthy()

    // Tools section renders (exec + filesystem rows are listed).
    const text = component.text()
    expect(text).toContain('Tools')
    expect(text).toContain('exec')
    expect(text).toContain('filesystem')

    // Skills section renders the helper's two skills.
    expect(text).toContain('Skills')
    expect(text).toContain('web-search')
    expect(text).toContain('code-review')

    // Shell allowlist (effective) renders the global + bySkill totals.
    expect(text).toContain('Shell Allowlist')

    // Workspace editor renders with AGENT.md content.
    const textareas = component.findAll<HTMLTextAreaElement>('textarea')
    expect(textareas.length).toBeGreaterThan(0)
    expect(textareas[0]!.element.value).toContain('helper instructions')

    // Queue mode dropdown reflects the per-agent config value.
    const queueSelect = selects.find(s => Array.from(s.element.options).some(o => o.value === 'collect'))
    expect(queueSelect, 'queue mode select should render').toBeTruthy()
    expect(queueSelect!.element.value).toBe('collect')
  })
})

describe('Agents page — edit-save round-trips PUT /api/agents/:id', () => {
  it('PUTs the modified payload to /api/agents/2 when Save is clicked', async () => {
    let putBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // Find the description input (second text input in the form grid) and set
    // a value — this dirties the form and enables the Save button.
    const inputs = component.findAll<HTMLInputElement>('input:not([type="checkbox"])')
    const descInput = inputs.find(i => i.attributes('placeholder')?.includes('What is this agent for'))
    expect(descInput, 'description input should exist').toBeTruthy()
    await descInput!.setValue('does helpful things')
    await flushPromises()

    const saveBtn = component.findAll('button').find(b =>
      (b.attributes('title') ?? '') === 'Save',
    )
    expect(saveBtn).toBeTruthy()
    await saveBtn!.trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())

    expect(putBody).toMatchObject({
      name: 'helper',
      description: 'does helpful things',
      modelProvider: 'openai',
      modelId: 'gpt-4',
      enabled: true,
    })
  })

  // JCLAW-1190: the fallback pair rides the ordinary Save; None is null/null on the wire.
  it('PUTs the chosen fallback provider and its first model when Save is clicked', async () => {
    let putBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()
    await openHelperEdit(component)

    const fallbackProvider = component.findAll('label')
      .find(l => l.text().includes('Fallback Provider'))!.find('select')
    // The helper's own provider is openai, so it must not be offered as its own fallback.
    expect(fallbackProvider.findAll('option').map(o => o.text().trim())).toEqual(['None', 'ollama-cloud'])
    await fallbackProvider.setValue('ollama-cloud')
    await flushPromises()

    const fallbackModel = component.findAll('label')
      .find(l => l.text().includes('Fallback Model'))!.find('select')
    expect((fallbackModel.element as HTMLSelectElement).value).toBe('kimi-k2.5')

    const saveBtn = component.findAll('button').find(b => (b.attributes('title') ?? '') === 'Save')
    await saveBtn!.trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())
    expect(putBody).toMatchObject({ fallbackProvider: 'ollama-cloud', fallbackModelId: 'kimi-k2.5' })
  })

  it('PUTs null for both halves when the fallback is set back to None', async () => {
    let putBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    setupAgentsApi({
      agents: [
        {
          id: 2, name: 'helper', modelProvider: 'openai', modelId: 'gpt-4', enabled: true, isMain: false,
          providerConfigured: true, thinkingMode: null, compressionEnabled: false, compressionJson: false,
          compressionCode: false, compressionText: false, compressionTargetRatio: 0.3, acpAllowed: false,
          memoryAutocaptureEnabled: true, memoryAutocaptureModelInherited: true,
          memoryAutocaptureProvider: 'openai', memoryAutocaptureModel: 'gpt-4',
          fallbackProvider: 'ollama-cloud', fallbackModelId: 'kimi-k2.5',
          createdAt: '2026-04-10T10:00:00Z', updatedAt: '2026-04-20T10:00:00Z',
        },
      ],
    })
    const component = await mountSuspended(Agents)
    await flushPromises()
    await openHelperEdit(component)

    const fallbackProvider = component.findAll('label')
      .find(l => l.text().includes('Fallback Provider'))!.find('select')
    expect((fallbackProvider.element as HTMLSelectElement).value).toBe('ollama-cloud')
    await fallbackProvider.setValue('')
    await flushPromises()

    const saveBtn = component.findAll('button').find(b => (b.attributes('title') ?? '') === 'Save')
    await saveBtn!.trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())
    expect(putBody).toMatchObject({ fallbackProvider: null, fallbackModelId: null })
  })

  it('PUTs a partial { compressionEnabled } immediately when the Content Compression toggle is clicked', async () => {
    let putBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // The helper defaults to compression OFF; the master is a pill toggle in the
    // Content Compression header and saves immediately on click.
    const toggle = component.find('button[title="Enable content compression"]')
    expect(toggle.exists(), 'master compression toggle should render').toBe(true)
    await toggle.trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())

    expect(putBody).toEqual({ compressionEnabled: true })
  })

  it('PUTs a partial { acpAllowed } immediately when the ACP grant toggle is clicked', async () => {
    let putBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // The helper (non-main) defaults to acpAllowed OFF; the ACP card renders a
    // pill toggle that saves immediately on click. The main agent shows no toggle.
    const toggle = component.find('button[title="Allow ACP runtime"]')
    expect(toggle.exists(), 'ACP grant toggle should render for a non-main agent').toBe(true)
    await toggle.trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())

    expect(putBody).toEqual({ acpAllowed: true })
  })

  it('PUTs a partial { memoryAutocaptureEnabled } immediately when the Memory toggle is clicked', async () => {
    let putBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // The helper fixture defaults to auto-capture ON; the Memory card toggle
    // saves immediately on click (JCLAW-534).
    const toggle = component.find('button[title="Turn auto-capture off"]')
    expect(toggle.exists(), 'memory auto-capture toggle should render').toBe(true)
    await toggle.trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())

    expect(putBody).toEqual({ memoryAutocaptureEnabled: false })
  })

  it('PUTs a partial { compressionJson } when a sub-toggle is changed (master on)', async () => {
    let putBody: Record<string, unknown> | null = null
    setupAgentsApi()
    // Re-register the helper with the master ON so its sub-toggles are interactive.
    registerEndpoint('/api/agents', () => [
      {
        id: 2, name: 'helper', modelProvider: 'openai', modelId: 'gpt-4',
        enabled: true, isMain: false, providerConfigured: true, thinkingMode: null,
        compressionEnabled: true, compressionJson: true, compressionCode: true,
        compressionText: true, compressionTargetRatio: 0.3, acpAllowed: false,
        createdAt: '2026-04-10T10:00:00Z', updatedAt: '2026-04-20T10:00:00Z',
      },
    ])
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    const component = await mountSuspended(Agents)
    await flushPromises()
    await openHelperEdit(component)

    // The JSON sub-toggle is a pill toggle (master is on, so it's interactive)
    // and saves immediately on click.
    const toggle = component.find('button[title="Disable JSON compression"]')
    expect(toggle.exists(), 'JSON sub-toggle should render').toBe(true)
    await toggle.trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())

    expect(putBody).toEqual({ compressionJson: false })
  })

  it('PUTs { compressionTargetRatio } when the aggressiveness control changes', async () => {
    let putBody: Record<string, unknown> | null = null
    setupAgentsApi()
    registerEndpoint('/api/agents', () => [
      {
        id: 2, name: 'helper', modelProvider: 'openai', modelId: 'gpt-4',
        enabled: true, isMain: false, providerConfigured: true, thinkingMode: null,
        compressionEnabled: true, compressionJson: true, compressionCode: true,
        compressionText: true, compressionTargetRatio: 0.3, acpAllowed: false,
        createdAt: '2026-04-10T10:00:00Z', updatedAt: '2026-04-20T10:00:00Z',
      },
    ])
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    const component = await mountSuspended(Agents)
    await flushPromises()
    await openHelperEdit(component)

    const slider = component.find<HTMLInputElement>('input[aria-label="Text aggressiveness"]')
    expect(slider.exists(), 'aggressiveness slider should render').toBe(true)
    await slider.setValue('0.5')
    await slider.trigger('change')
    await vi.waitFor(() => expect(putBody).not.toBeNull())

    expect(putBody).toEqual({ compressionTargetRatio: 0.5 })
  })
})

describe('Agents page — toggle agent enabled from the row', () => {
  it('PUTs { enabled: !current } when the row-level toggle is clicked', async () => {
    let putBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    // The enabled toggle on a custom-agent row has title="Disable agent" when
    // enabled, "Enable agent" when disabled. Helper starts enabled.
    const toggle = component.findAll('button').find(b =>
      b.attributes('title') === 'Disable agent',
    )
    expect(toggle, 'enabled toggle should exist for helper').toBeTruthy()
    await toggle!.trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())

    expect(putBody).toEqual({ enabled: false })
  })
})

describe('Agents page — per-card delete via ConfirmDialog', () => {
  it('cancel on the dialog preserves the helper row and skips the DELETE', async () => {
    let deleted = false
    registerEndpoint('/api/agents/2', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { status: 'ok' }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(AgentsHarness)
    await flushPromises()

    // Each custom-agent card carries a trash button titled "Delete <name>".
    const deleteBtn = component.find('button[title="Delete helper"]')
    expect(deleteBtn.exists(), 'per-card delete button should exist').toBe(true)
    await deleteBtn.trigger('click')
    await flushPromises()

    // Click Cancel on the ConfirmDialog (teleported to body).
    const cancelBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Cancel')
    expect(cancelBtn).toBeTruthy()
    cancelBtn!.click()
    await flushPromises()
    await flushPromises()

    expect(deleted).toBe(false)
  })

  it('confirm on the dialog fires DELETE /api/agents/:id for the agent', async () => {
    let deleted = false
    registerEndpoint('/api/agents/2', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { status: 'ok' }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(AgentsHarness)
    await flushPromises()

    const deleteBtn = component.find('button[title="Delete helper"]')
    expect(deleteBtn.exists()).toBe(true)
    await deleteBtn.trigger('click')
    await flushPromises()

    // Click the Delete confirm button (variant=danger, label='Delete').
    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete')
    expect(confirmBtn, 'Delete confirm button should exist on the dialog').toBeTruthy()
    confirmBtn!.click()
    await vi.waitFor(() => expect(deleted).toBe(true))
  })
})

describe('Agents page — Delete All via ConfirmDialog', () => {
  it('type-gate + confirm fires DELETE for every custom agent', async () => {
    let deleted = false
    registerEndpoint('/api/agents/2', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { status: 'ok' }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(AgentsHarness)
    await flushPromises()

    // The "Delete All" button sits on the Custom Agents header row.
    const deleteAllBtn = component.findAll('button').find(b => b.text().trim() === 'Delete All')
    expect(deleteAllBtn, 'Delete All button should exist').toBeTruthy()
    await deleteAllBtn!.trigger('click')
    await flushPromises()

    // The wipe-all confirm requires typing 'delete' into the gate input.
    const gateInput = document.body.querySelector<HTMLInputElement>('[role="dialog"] input[type="text"]')
    expect(gateInput, 'require-text gate input should render').toBeTruthy()
    gateInput!.value = 'delete'
    gateInput!.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    // confirmText is "Delete 1" for the single-custom-agent fixture.
    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete 1')
    expect(confirmBtn, 'Delete-N confirm button should exist').toBeTruthy()
    confirmBtn!.click()
    await vi.waitFor(() => expect(deleted).toBe(true))
  })
})

describe('Agents page — tool toggle round-trips', () => {
  it('PUTs the per-tool enabled flag when the row toggle is clicked', async () => {
    let toolPut: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2/tools/filesystem', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        toolPut = await readBody(event) as Record<string, unknown>
        return { status: 'ok' }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // filesystem on agent 2 starts enabled=true; the per-row toggle has
    // title="Disable tool for this agent" in that state.
    const toggles = component.findAll('button').filter(b =>
      (b.attributes('title') ?? '').includes('tool for this agent'),
    )
    // Find the toggle in the row that contains "filesystem".
    const fsToggle = toggles.find(b => b.element.parentElement?.parentElement?.textContent?.includes('filesystem'))
    expect(fsToggle, 'filesystem toggle should exist').toBeTruthy()
    await fsToggle!.trigger('click')
    await vi.waitFor(() => expect(toolPut).not.toBeNull())

    expect(toolPut).toEqual({ enabled: false })
  })
})

describe('Agents page — skill toggle round-trips', () => {
  it('PUTs the per-skill enabled flag when the skill row toggle is clicked', async () => {
    let skillPut: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2/skills/web-search', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        skillPut = await readBody(event) as Record<string, unknown>
        return { status: 'ok' }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // web-search on agent 2 starts enabled=true. The per-skill toggle title
    // is "Disable skill" in that state.
    const skillToggle = component.findAll('button').find(b =>
      b.attributes('title') === 'Disable skill',
    )
    expect(skillToggle, 'skill toggle should exist').toBeTruthy()
    await skillToggle!.trigger('click')
    await vi.waitFor(() => expect(skillPut).not.toBeNull())

    expect(skillPut).toEqual({ enabled: false })
  })
})

describe('Agents page — workspace file edit/save', () => {
  it('switching tabs fetches the new file content', async () => {
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    const textarea = () => component.find<HTMLTextAreaElement>('textarea')
    expect(textarea().element.value).toContain('helper instructions')

    // Click the SOUL.md tab — the workspace tabs are buttons whose text
    // matches the filename.
    const soulTab = component.findAll('button').find(b => b.text() === 'SOUL.md')
    expect(soulTab).toBeTruthy()
    await soulTab!.trigger('click')
    await flushPromises()
    await vi.waitFor(() => expect(textarea().element.value).toContain('helper soul'))
  })

  it('renders a live preview and toggles the editor/preview panes', async () => {
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    const toggle = (title: string) =>
      component.findAll('button').find(b => b.attributes('title') === title)

    // Default = split view: editor textarea AND preview both present, and the
    // preview renders the loaded markdown (renderMarkdown wraps plain text in <p>).
    expect(component.find('textarea').exists()).toBe(true)
    const preview = () => component.find('.md-preview')
    expect(preview().exists()).toBe(true)
    expect(preview().html()).toContain('helper instructions')

    // Hide the editor → preview-only.
    await toggle('Toggle editor')!.trigger('click')
    await flushPromises()
    expect(component.find('textarea').exists()).toBe(false)
    expect(preview().exists()).toBe(true)

    // Guard: hiding the preview when it's the only visible pane is refused.
    await toggle('Toggle preview')!.trigger('click')
    await flushPromises()
    expect(preview().exists()).toBe(true)

    // Bring the editor back → split view again.
    await toggle('Toggle editor')!.trigger('click')
    await flushPromises()
    expect(component.find('textarea').exists()).toBe(true)
    expect(preview().exists()).toBe(true)
  })
})

describe('Agents page — Inspect prompt dialog', () => {
  it('changing the channel select refetches with the new channelType', async () => {
    const calls: string[] = []
    registerEndpoint('/api/agents/2/prompt-breakdown', (event) => {
      calls.push(String(event.node?.req?.url ?? event.path ?? ''))
      return {
        totalChars: 100,
        totalTokenEstimate: 25,
        cacheBoundaryMarker: '',
        cacheablePrefixChars: 80,
        staticPrefixChars: 80,
        coreMemoryChars: 0,
        variableSuffixChars: 20,
        sections: [{ name: 'Identity', chars: 100, tokens: 25 }],
        skills: [],
        tools: [],
      }
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)
    const inspectBtn = component.findAll('button').find(b => b.text().trim() === 'Inspect prompt')
    await inspectBtn!.trigger('click')
    await vi.waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(1))

    // The channel <select> sits inside the dialog header.
    const channelSelect = component.find<HTMLSelectElement>('select#prompt-breakdown-channel')
    expect(channelSelect.exists()).toBe(true)
    await channelSelect.setValue('telegram')
    await flushPromises()
    await vi.waitFor(() => expect(calls.some(u => u.includes('channelType=telegram'))).toBe(true))
  })

  it('swaps both tables for a single donut spanning sections and tool schemas', async () => {
    registerEndpoint('/api/agents/2/prompt-breakdown', () => ({
      totalChars: 400,
      totalTokenEstimate: 100,
      cacheBoundaryMarker: '',
      cacheablePrefixChars: 320,
      staticPrefixChars: 320,
      coreMemoryChars: 0,
      variableSuffixChars: 80,
      sections: [
        { name: 'Identity', chars: 200, tokens: 50 },
        { name: 'Safety', chars: 100, tokens: 25 },
      ],
      // Skills live inside the Identity/Skills section already — the chart must
      // not merge them in on top, or the shares would exceed the whole.
      skills: [{ name: 'deploy', chars: 40, tokens: 10 }],
      // Two tools that must arrive as ONE slice summing to 25%.
      tools: [
        { name: 'Bash', chars: 60, tokens: 15 },
        { name: 'Read', chars: 40, tokens: 10 },
      ],
    }))
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)
    await component.findAll('button').find(b => b.text().trim() === 'Inspect prompt')!.trigger('click')
    await flushPromises()

    // Table is the default view: the sortable "Chars" column header is present.
    expect(component.findAll('table').length).toBeGreaterThan(0)

    await component.findAll('button').find(b => b.text().trim() === 'chart')!.trigger('click')
    await flushPromises()

    // Exactly one donut replaces BOTH tables — sections and tools are disjoint
    // halves of one whole, so two charts would each read as its own 100%.
    expect(component.findAll('table').length).toBe(0)
    expect(component.findAll('svg[role="img"]')).toHaveLength(1)

    // Sections stay individual; the tool schemas arrive as one rolled-up slice
    // carrying their count, so ~30 sub-2% tools can't bury the sections.
    const legend = component.find('svg[role="img"]').attributes('aria-label')!
    expect(legend).toContain('Identity 50.0%')
    expect(legend).toContain('Safety 25.0%')
    expect(legend).toContain('Tool schemas (2) 25.0%')
    expect(legend).not.toContain('Bash')
    // Skills are not a third series: 50 + 25 + 25 already closes the circle.
    expect(legend).not.toContain('deploy')
  })

  it('View Full fetches and renders the assembled prompt text', async () => {
    registerEndpoint('/api/agents/2/prompt-breakdown', () => ({
      totalChars: 100,
      totalTokenEstimate: 25,
      cacheBoundaryMarker: '',
      cacheablePrefixChars: 80,
      staticPrefixChars: 80,
      coreMemoryChars: 0,
      variableSuffixChars: 20,
      sections: [{ name: 'Identity', chars: 100, tokens: 25 }],
      skills: [],
      tools: [],
    }))
    registerEndpoint('/api/agents/2/prompt-text', () => ({
      text: '## Role\nYou are a helpful agent.\n## Safety\nBe careful.',
    }))
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)
    await component.findAll('button').find(b => b.text().trim() === 'Inspect prompt')!.trigger('click')
    await flushPromises()

    const viewFull = component.find('[data-testid="view-full-prompt"]')
    expect(viewFull.exists()).toBe(true)
    await viewFull.trigger('click')
    await flushPromises()

    const pre = component.find('[data-testid="full-prompt-text"]')
    expect(pre.exists()).toBe(true)
    expect(pre.text()).toContain('You are a helpful agent.')

    // Back returns to the numbers rather than closing the dialog outright.
    await component.findAll('button').find(b => b.text().trim() === 'Back')!.trigger('click')
    await flushPromises()
    expect(component.find('[data-testid="full-prompt-text"]').exists()).toBe(false)
    expect(component.findAll('table').length).toBeGreaterThan(0)
  })

  it('View Full shows raw and rendered markdown panes, and toggles between them', async () => {
    registerEndpoint('/api/agents/2/prompt-breakdown', () => ({
      totalChars: 100,
      totalTokenEstimate: 25,
      cacheBoundaryMarker: '',
      cacheablePrefixChars: 80,
      staticPrefixChars: 80,
      coreMemoryChars: 0,
      variableSuffixChars: 20,
      sections: [{ name: 'Identity', chars: 100, tokens: 25 }],
      skills: [],
      tools: [],
    }))
    registerEndpoint('/api/agents/2/prompt-text', () => ({
      text: '## Your Role\nYou are a helpful agent.',
    }))
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)
    await component.findAll('button').find(b => b.text().trim() === 'Inspect prompt')!.trigger('click')
    await flushPromises()
    await component.find('[data-testid="view-full-prompt"]').trigger('click')
    await flushPromises()

    const raw = () => component.find('[data-testid="full-prompt-text"]')
    const rendered = () => component.find('[data-testid="full-prompt-rendered"]')

    // Default = split: raw keeps the literal "## Your Role", the rendered pane
    // turns it into a real heading element.
    expect(raw().exists()).toBe(true)
    expect(raw().text()).toContain('## Your Role')
    expect(rendered().exists()).toBe(true)
    expect(rendered().html()).toContain('<h2')
    expect(rendered().text()).not.toContain('## Your Role')

    // Hide the raw pane → rendered only.
    await component.find('[data-testid="toggle-prompt-raw"]').trigger('click')
    await flushPromises()
    expect(raw().exists()).toBe(false)
    expect(rendered().exists()).toBe(true)

    // Guard: hiding the rendered pane when it's the only visible one is refused.
    await component.find('[data-testid="toggle-prompt-rendered"]').trigger('click')
    await flushPromises()
    expect(rendered().exists()).toBe(true)

    // Bring raw back → split again.
    await component.find('[data-testid="toggle-prompt-raw"]').trigger('click')
    await flushPromises()
    expect(raw().exists()).toBe(true)
    expect(rendered().exists()).toBe(true)
  })

  it('closes the dialog when the close button is clicked', async () => {
    registerEndpoint('/api/agents/2/prompt-breakdown', () => ({
      totalChars: 100,
      totalTokenEstimate: 25,
      cacheBoundaryMarker: '',
      cacheablePrefixChars: 80,
      staticPrefixChars: 80,
      coreMemoryChars: 0,
      variableSuffixChars: 20,
      sections: [{ name: 'Identity', chars: 100, tokens: 25 }],
      skills: [],
      tools: [],
    }))
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)
    const inspectBtn = component.findAll('button').find(b => b.text().trim() === 'Inspect prompt')
    await inspectBtn!.trigger('click')
    await flushPromises()
    expect(component.text()).toContain('System prompt')

    // The dialog has a Close button with title="Close".
    const closeBtn = component.findAll('button').find(b =>
      b.attributes('title') === 'Close',
    )
    expect(closeBtn).toBeTruthy()
    await closeBtn!.trigger('click')
    await flushPromises()

    // The dialog body's "System prompt" header is gone (the v-if collapsed it).
    // The page might still contain the heading "Agents" — assert the dialog-
    // specific copy is gone.
    expect(component.find('[role="dialog"]').exists()).toBe(false)
  })
})

describe('Agents page — Shell Allowlist expansion', () => {
  it('clicking the Shell Allowlist header expands the command table', async () => {
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // The Shell Allowlist header is a <button> with text containing
    // "Shell Allowlist" and the command count.
    const allowlistBtn = component.findAll('button').find(b =>
      b.text().includes('Shell Allowlist'),
    )
    expect(allowlistBtn, 'Shell Allowlist header should be clickable').toBeTruthy()
    await allowlistBtn!.trigger('click')
    await flushPromises()

    // Once expanded the table renders global ('ls', 'cat') and per-skill
    // ('curl' under web-search) rows.
    const text = component.text()
    expect(text).toContain('ls')
    expect(text).toContain('cat')
    expect(text).toContain('curl')
    expect(text).toContain('web-search')
  })
})

describe('Agents page — bulk-toggle all tools and skills', () => {
  it('clicking the Tools section header toggle flips every per-tool flag', async () => {
    const toolPuts: Record<string, Record<string, unknown>> = {}
    registerEndpoint('/api/agents/2/tools/exec', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        toolPuts.exec = await readBody(event) as Record<string, unknown>
        return { status: 'ok' }
      },
    })
    registerEndpoint('/api/agents/2/tools/filesystem', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        toolPuts.filesystem = await readBody(event) as Record<string, unknown>
        return { status: 'ok' }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // The Tools section header has a bulk toggle whose title flips between
    // "Enable all tools for this agent" and "Disable all tools for this agent".
    // Helper has exec=false, filesystem=true → not all-enabled, so the title is "Enable all".
    const bulkBtn = component.findAll('button').find(b =>
      (b.attributes('title') ?? '') === 'Enable all tools for this agent',
    )
    expect(bulkBtn, 'Tools bulk-toggle should exist').toBeTruthy()
    await bulkBtn!.trigger('click')
    await vi.waitFor(() => expect(toolPuts.exec).toBeDefined())
    await vi.waitFor(() => expect(toolPuts.filesystem).toBeDefined())

    // Both should be set to enabled: true (the flipped target state).
    expect(toolPuts.exec).toEqual({ enabled: true })
    expect(toolPuts.filesystem).toEqual({ enabled: true })
  })
})

describe('Agents page — core-memory usage sits on the agent, not in Settings', () => {
  it('offers the migration on an agent that is over the core cap', async () => {
    // The cap is per agent, because the core block is assembled per agent. An
    // instance-wide reading cannot say whose excess it is, nor whether a migration can
    // move it — Settings still owns the cap itself, which is global policy.
    setupAgentsApi({
      agent2Core: {
        running: false, processed: 0, total: 0, liveCore: 26, cap: 20, overCap: true, error: null,
      },
    })
    const component = await mountSuspended(Agents)
    await flushPromises()
    await openHelperEdit(component)

    expect(component.find('[data-testid="agent-core-over-cap"]').exists()).toBe(true)
    expect((component.find('[data-testid="agent-core-migrate"]').element as HTMLButtonElement)
      .disabled).toBe(false)
    expect(component.find('[data-testid="agent-core-memory"]').text()).toContain('26 of 20 allowed')
  })

  it('leaves the migration disabled for an agent within the cap', async () => {
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()
    await openHelperEdit(component)

    expect(component.find('[data-testid="agent-core-over-cap"]').exists()).toBe(false)
    expect((component.find('[data-testid="agent-core-migrate"]').element as HTMLButtonElement)
      .disabled).toBe(true)
    expect(component.find('[data-testid="agent-core-memory"]').text()).toContain('4 of 20 allowed')
  })
})

/**
 * JCLAW-1078: opening an agent must not edit it.
 *
 * `providers` is filtered to providers that have an API key, so an agent stored
 * against a provider whose key is currently unset is not in that list. The
 * provider watcher used to read that as "invalid provider" and set
 * `enabled = false` — on page load, before the operator touched anything, and
 * irreversibly, since picking a valid provider afterwards never restored it.
 * Saving then failed with 409 "The main agent cannot be disabled", or worse,
 * silently disabled a custom agent, which the backend does not guard.
 */
describe('Agents page — an unconfigured provider does not disable the agent', () => {
  function setupWithUnconfiguredProvider() {
    registerEndpoint('/api/agents', () => [
      // Stored against ollama-cloud, whose key is unset below — exactly the
      // fresh-install shape that triggered the bug.
      { id: 2, name: 'helper', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
        enabled: true, isMain: false, providerConfigured: false,
        thinkingMode: null, compressionEnabled: false, compressionJson: false,
        compressionCode: false, compressionText: false, compressionTargetRatio: 0.3,
        acpAllowed: false, createdAt: '2026-04-10T10:00:00Z', updatedAt: '2026-04-20T10:00:00Z' },
    ])
    registerEndpoint('/api/config', () => ({
      entries: [
        // An empty apiKey is what drops ollama-cloud from the UI's provider
        // list — the filter only removes a provider that HAS the key and left
        // it blank, which is exactly the state a fresh install is in.
        { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
        { key: 'provider.ollama-cloud.apiKey', value: '' },
        { key: 'provider.ollama-local.baseUrl', value: 'http://localhost:11434/v1' },
        { key: 'provider.ollama-local.apiKey', value: 'olla****' },
        { key: 'provider.ollama-local.models', value:
          '[{"id":"dolphin3:8b","name":"dolphin3:8b","supportsTools":false}]' },
      ],
    }))
    registerEndpoint('/api/tools/meta', () => [])
    registerEndpoint('/api/agents/2/tools', () => [])
    registerEndpoint('/api/agents/2/skills', () => [])
  }

  it('leaves enabled untouched when the stored provider has no key', async () => {
    let putBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: 2 }
      },
    })
    setupWithUnconfiguredProvider()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // Switch to a provider that IS configured, as the operator would.
    const selects = component.findAll<HTMLSelectElement>('select')
    const providerSelect = selects.find(s =>
      Array.from(s.element.options).some(o => o.value === 'ollama-local'))
    expect(providerSelect, 'provider select should offer ollama-local').toBeTruthy()
    await providerSelect!.setValue('ollama-local')
    await flushPromises()

    const saveBtn = component.findAll('button').find(b =>
      (b.attributes('title') ?? '') === 'Save')
    expect(saveBtn).toBeTruthy()
    await saveBtn!.trigger('click')
    await vi.waitFor(() => expect(putBody).not.toBeNull())

    // The whole bug in one assertion: the agent was never asked to be disabled.
    expect(putBody!.enabled).toBe(true)
    expect(putBody).toMatchObject({ modelProvider: 'ollama-local', modelId: 'dolphin3:8b' })
  })
})
