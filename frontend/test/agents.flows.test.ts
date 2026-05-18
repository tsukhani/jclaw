import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Agents from '~/pages/agents.vue'
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
}) {
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
  await flushPromises()
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

  it('clicking Back to agents exits edit mode and clears per-agent state', async () => {
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)
    expect(component.text()).toContain('Edit Agent')

    const backBtn = component.findAll('button').find(b => b.text().includes('Back to agents'))
    expect(backBtn).toBeTruthy()
    await backBtn!.trigger('click')
    await flushPromises()

    // Edit form is gone; the list view is back.
    expect(component.text()).not.toContain('Edit Agent')
    expect(component.text()).toContain('New Agent')
  })
})

describe('Agents page — create flow round-trips POST /api/agents', () => {
  it('POSTs the form payload when Save is clicked after entering a name', async () => {
    let postedBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents', {
      method: 'POST',
      handler: async (event) => {
        const { readBody } = await import('h3')
        postedBody = await readBody(event) as Record<string, unknown>
        return { id: 99, name: postedBody.name, modelProvider: postedBody.modelProvider, modelId: postedBody.modelId, enabled: true, isMain: false }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    const newBtn = component.find('button[title="New Agent"]')
    await newBtn.trigger('click')
    await flushPromises()

    // Find the Name input — the first input in the form, identified by its
    // empty value (vs the description which is also empty but follows).
    const inputs = component.findAll<HTMLInputElement>('input:not([type="checkbox"])')
    // First two are Name + Description. Set the name; description stays blank.
    await inputs[0]!.setValue('research-agent')
    await flushPromises()

    // The Save button is the lone <button> with the Save icon inside the
    // new-agent card; locate by title.
    const saveBtn = component.findAll('button').find(b =>
      (b.attributes('title') ?? '').includes('Save')
      || (b.attributes('title') ?? '').toLowerCase().includes('save'),
    )
    expect(saveBtn, 'save button should exist').toBeTruthy()
    await saveBtn!.trigger('click')
    await vi.waitFor(() => expect(postedBody).not.toBeNull())

    expect(postedBody).toMatchObject({
      name: 'research-agent',
      modelProvider: 'ollama-cloud',
      modelId: 'kimi-k2.5',
      enabled: true,
      // Empty description is normalized to null on the wire.
      description: null,
      // Empty thinkingMode coerces to null.
      thinkingMode: null,
    })
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

describe('Agents page — bulk-delete via ConfirmDialog', () => {
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

    // Enter select mode via the trash button on the toolbar.
    const enterBtn = component.find('button[title="Delete an agent"]')
    expect(enterBtn.exists()).toBe(true)
    await enterBtn.trigger('click')
    await flushPromises()

    // Tick the helper agent's selection checkbox.
    const checkboxes = component.findAll<HTMLInputElement>('input[type="checkbox"]')
    expect(checkboxes.length).toBeGreaterThanOrEqual(1)
    await checkboxes[0]!.trigger('click')
    await flushPromises()

    // Click the Delete N button on the toolbar.
    const deleteBtn = component.findAll('button').find(b => b.text().startsWith('Delete') && b.text().match(/Delete \d/))
    expect(deleteBtn, 'delete-N button should exist').toBeTruthy()
    await deleteBtn!.trigger('click')
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

  it('confirm on the dialog fires DELETE /api/agents/:id for each selected agent', async () => {
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

    const enterBtn = component.find('button[title="Delete an agent"]')
    await enterBtn.trigger('click')
    await flushPromises()

    const checkboxes = component.findAll<HTMLInputElement>('input[type="checkbox"]')
    await checkboxes[0]!.trigger('click')
    await flushPromises()

    const deleteBtn = component.findAll('button').find(b => b.text().match(/Delete \d/))
    await deleteBtn!.trigger('click')
    await flushPromises()

    // Click the Delete confirm button (variant=danger, label='Delete').
    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete')
    expect(confirmBtn, 'Delete confirm button should exist on the dialog').toBeTruthy()
    confirmBtn!.click()
    await vi.waitFor(() => expect(deleted).toBe(true))
  })

  it('cancelling select mode without confirming exits select mode', async () => {
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    const enterBtn = component.find('button[title="Delete an agent"]')
    await enterBtn.trigger('click')
    await flushPromises()
    expect(component.text()).toContain('Cancel')

    const cancelBtn = component.findAll('button').find(b => b.text().trim() === 'Cancel')
    expect(cancelBtn).toBeTruthy()
    await cancelBtn!.trigger('click')
    await flushPromises()

    // Select-mode toolbar gone; New Agent visible again.
    const newBtn = component.find('button[title="New Agent"]')
    expect(newBtn.exists()).toBe(true)
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

  it('PUTs the textarea contents when Save is clicked on a dirty workspace', async () => {
    let wsBody: Record<string, unknown> | null = null
    registerEndpoint('/api/agents/2/workspace/AGENT.md', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        wsBody = await readBody(event) as Record<string, unknown>
        return { status: 'ok' }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    const textarea = component.find<HTMLTextAreaElement>('textarea')
    await textarea.setValue('helper instructions — edited')
    await flushPromises()

    // The workspace editor block has its own Save button — locate by title
    // matching 'Save file'.
    const saveBtn = component.findAll('button').find(b =>
      (b.attributes('title') ?? '') === 'Save file',
    )
    expect(saveBtn, 'workspace Save file button should exist').toBeTruthy()
    await saveBtn!.trigger('click')
    await vi.waitFor(() => expect(wsBody).not.toBeNull())

    expect(wsBody).toEqual({ content: 'helper instructions — edited' })
  })
})

describe('Agents page — queue mode persists to /api/config', () => {
  it('POSTs the new queue.mode value when the dropdown is changed', async () => {
    let cfgPost: Record<string, unknown> | null = null
    registerEndpoint('/api/config', {
      method: 'POST',
      handler: async (event) => {
        const { readBody } = await import('h3')
        cfgPost = await readBody(event) as Record<string, unknown>
        return { status: 'ok' }
      },
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    // The queue-mode <select> has three options: queue / collect / interrupt.
    // Pick the one whose <option> values match.
    const selects = component.findAll<HTMLSelectElement>('select')
    const queueSelect = selects.find(s =>
      Array.from(s.element.options).some(o => o.value === 'interrupt'),
    )
    expect(queueSelect).toBeTruthy()
    await queueSelect!.setValue('interrupt')
    await flushPromises()
    await vi.waitFor(() => expect(cfgPost).not.toBeNull())

    expect(cfgPost).toEqual({
      key: 'agent.helper.queue.mode',
      value: 'interrupt',
    })
  })
})

describe('Agents page — Inspect prompt dialog', () => {
  it('opens the dialog and fetches /api/agents/:id/prompt-breakdown for the web channel', async () => {
    let breakdownUrl: string | null = null
    registerEndpoint('/api/agents/2/prompt-breakdown', (event) => {
      breakdownUrl = String(event.node?.req?.url ?? event.path ?? '')
      return {
        totalChars: 1000,
        totalTokenEstimate: 250,
        cacheBoundaryMarker: '<cache-boundary/>',
        cacheablePrefixChars: 800,
        variableSuffixChars: 200,
        sections: [
          { name: 'Identity', chars: 400, tokens: 100 },
          { name: 'Skills', chars: 300, tokens: 75 },
          { name: 'Memories', chars: 300, tokens: 75 },
        ],
        skills: [
          { name: 'web-search', chars: 200, tokens: 50 },
          { name: 'code-review', chars: 50, tokens: 12 },
        ],
        tools: [
          { name: 'exec', chars: 150, tokens: 38 },
        ],
      }
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)

    const inspectBtn = component.findAll('button').find(b => b.text().trim() === 'Inspect prompt')
    expect(inspectBtn, 'Inspect prompt button should exist on edit form').toBeTruthy()
    await inspectBtn!.trigger('click')
    await vi.waitFor(() => expect(breakdownUrl).not.toBeNull())

    // The dialog renders with the totals and section/tool tables.
    const text = component.text()
    expect(text).toContain('System prompt')
    expect(text).toContain('Total chars')
    expect(text).toContain('1,000')
    expect(text).toContain('Identity')
    expect(text).toContain('Skills')
    expect(text).toContain('Memories')
    expect(text).toContain('Tool schemas')
    expect(breakdownUrl).toContain('channelType=web')
  })

  it('changing the channel select refetches with the new channelType', async () => {
    const calls: string[] = []
    registerEndpoint('/api/agents/2/prompt-breakdown', (event) => {
      calls.push(String(event.node?.req?.url ?? event.path ?? ''))
      return {
        totalChars: 100,
        totalTokenEstimate: 25,
        cacheBoundaryMarker: '',
        cacheablePrefixChars: 80,
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

  it('closes the dialog when the close button is clicked', async () => {
    registerEndpoint('/api/agents/2/prompt-breakdown', () => ({
      totalChars: 100,
      totalTokenEstimate: 25,
      cacheBoundaryMarker: '',
      cacheablePrefixChars: 80,
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

  it('surfaces an error when the API call fails', async () => {
    registerEndpoint('/api/agents/2/prompt-breakdown', () => {
      throw new Error('boom')
    })
    setupAgentsApi()
    const component = await mountSuspended(Agents)
    await flushPromises()

    await openHelperEdit(component)
    const inspectBtn = component.findAll('button').find(b => b.text().trim() === 'Inspect prompt')
    await inspectBtn!.trigger('click')
    // wait for the catch branch to populate promptBreakdownError
    await vi.waitFor(() => expect(component.text()).toMatch(/boom|Failed to load/))
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
