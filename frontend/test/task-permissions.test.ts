import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import TaskPermissions from '~/components/TaskPermissions.vue'

/**
 * JCLAW-1062/1068 — the Tasks page permissions block.
 *
 * The parsing assertions matter more than the rendering ones: this component mirrors
 * services.TaskToolPolicy.parse, and if the two disagree the page shows a fence the
 * gate will not enforce. Each case here has a counterpart in TaskToolPolicyTest.
 */
async function mount(
  enabledToolNames: string | null,
  originChannel: string | null = 'web',
  modelProvider: string | null = null,
  modelId: string | null = null,
) {
  return mountSuspended(TaskPermissions, {
    props: { enabledToolNames, originChannel, modelProvider, modelId },
  })
}

describe('TaskPermissions', () => {
  it('renders one pill per tool for the JSON array form', async () => {
    const c = await mount('["web_search","filesystem"]')
    const pills = c.findAll('[data-testid="task-tool-pill"]').map(p => p.text())
    expect(pills).toEqual(['web_search', 'filesystem'])
  })

  it('renders pills for the comma-separated form the column already holds', async () => {
    const c = await mount('datetime,web_search,mcp_google-workspace-mcp')
    const pills = c.findAll('[data-testid="task-tool-pill"]').map(p => p.text())
    expect(pills).toEqual(['datetime', 'web_search', 'mcp_google-workspace-mcp'])
  })

  it('renders a pill per MCP action entry in both forms, as TaskToolPolicy parses them', async () => {
    // JF-373: before the separator joined the name pattern, the comma form read as unrestricted.
    const json = await mount('["mcp_gw/search_gmail_messages","web_search"]')
    expect(json.findAll('[data-testid="task-tool-pill"]').map(p => p.text()))
      .toEqual(['mcp_gw/search_gmail_messages', 'web_search'])
    const comma = await mount('mcp_gw/search_gmail_messages,web_search')
    expect(comma.findAll('[data-testid="task-tool-pill"]').map(p => p.text()))
      .toEqual(['mcp_gw/search_gmail_messages', 'web_search'])
  })

  it('renders a pill for a bare single tool name', async () => {
    const c = await mount('mcp_google-workspace-mcp')
    expect(c.findAll('[data-testid="task-tool-pill"]').map(p => p.text()))
      .toEqual(['mcp_google-workspace-mcp'])
  })

  it('says unrestricted when there is no allow-list', async () => {
    const c = await mount(null)
    expect(c.find('[data-testid="task-tools-unrestricted"]').exists()).toBe(true)
    expect(c.findAll('[data-testid="task-tool-pill"]')).toHaveLength(0)
  })

  it('shows truncated JSON as unrestricted, matching the backend', async () => {
    // Read as a name list this would render one pill named `["exec` — a fence the gate
    // does not apply. TaskToolPolicy treats it as malformed and runs unrestricted.
    const c = await mount('["exec",')
    expect(c.find('[data-testid="task-tools-unrestricted"]').exists()).toBe(true)
  })

  it('flags an unrecorded origin as failing closed', async () => {
    const c = await mount(null, null)
    const pill = c.find('[data-testid="task-origin-pill"]')
    expect(pill.text()).toBe('unrecorded')
    expect(pill.attributes('title')).toMatch(/fail closed/i)
  })

  it('marks a web origin as the operator origin', async () => {
    const c = await mount(null, 'web')
    const pill = c.find('[data-testid="task-origin-pill"]')
    expect(pill.text()).toBe('web')
    expect(pill.attributes('title')).toMatch(/operator origin/i)
  })

  it('marks a voice origin as the operator origin, as the backend classifies it', async () => {
    const c = await mount(null, 'voice')
    const pill = c.find('[data-testid="task-origin-pill"]')
    expect(pill.text()).toBe('voice')
    expect(pill.attributes('title')).toBe(
      'Operator origin — dangerous tools resolve by the Tool Approvals policy instead of failing closed.')
  })

  it('shows an inbound channel origin as untrusted', async () => {
    const c = await mount(null, 'telegram')
    const pill = c.find('[data-testid="task-origin-pill"]')
    expect(pill.text()).toBe('telegram')
    expect(pill.attributes('title')).toMatch(/untrusted/i)
  })

  it('offers Trust for an unrecorded or untrusted origin, and emits it on click', async () => {
    for (const origin of [null, 'telegram']) {
      const c = await mount(null, origin)
      const button = c.find('[data-testid="task-origin-trust"]')
      expect(button.exists()).toBe(true)
      await button.trigger('click')
      expect(c.emitted('trust')).toHaveLength(1)
    }
  })

  it('offers no Trust once the task already has the operator origin', async () => {
    for (const origin of ['web', 'voice']) {
      const c = await mount(null, origin)
      expect(c.find('[data-testid="task-origin-trust"]').exists(), origin).toBe(false)
    }
  })

  it('shows a pinned model as provider and id', async () => {
    const c = await mount(null, 'web', 'ollama-cloud', 'kimi-k2.6')
    expect(c.find('[data-testid="task-model-pill"]').text()).toBe('ollama-cloud / kimi-k2.6')
    expect(c.find('[data-testid="task-model-unpinned"]').exists()).toBe(false)
  })

  it('says the model follows the agent when nothing is pinned', async () => {
    const c = await mount(null)
    const pill = c.find('[data-testid="task-model-unpinned"]')
    expect(pill.text()).toBe('follows the agent')
    expect(pill.attributes('title')).toMatch(/follows any change/i)
    expect(c.find('[data-testid="task-model-pill"]').exists()).toBe(false)
  })

  it('treats a provider without a model id as unpinned', async () => {
    // A provider alone names no model, so the fire still resolves through the
    // agent — reporting it as pinned would assert a stability that is not there.
    const c = await mount(null, 'web', 'ollama-cloud', null)
    expect(c.find('[data-testid="task-model-unpinned"]').exists()).toBe(true)
  })

  it('shows a bare model id when no provider is recorded', async () => {
    const c = await mount(null, 'web', null, 'kimi-k2.6')
    expect(c.find('[data-testid="task-model-pill"]').text()).toBe('kimi-k2.6')
  })

  // JF-386: a tool for the task's subagents only shows its plain name and says so.
  it('labels an entry marked for subagents only, in both forms', async () => {
    for (const raw of ['["subagents:exec","web_search"]', 'subagents:exec,web_search']) {
      const c = await mount(raw)
      const pills = c.findAll('[data-testid="task-tool-pill"]')
      expect(pills.map(p => [p.element.firstChild?.textContent, p.attributes('data-subagents-only') ?? null])).toEqual([
        ['exec', 'true'],
        ['web_search', null],
      ])
      expect(pills[0]!.find('[data-testid="task-tool-subagents-only"]').text()).toBe('subagents only')
      expect(pills[0]!.attributes('title')).toBe('For this task\'s subagents only: the task\'s own run is not offered it.')
      expect(pills[1]!.find('[data-testid="task-tool-subagents-only"]').exists()).toBe(false)
    }
  })

  // JF-378: a narrowed entry naming an action its connected server does not offer is marked.
  describe('an action its server does not offer', () => {
    beforeEach(() => clearNuxtData())

    function marks(c: Awaited<ReturnType<typeof mount>>) {
      return c.findAll('[data-testid="task-tool-pill"]').map(p => [p.text(), p.attributes('data-unoffered') ?? null, p.attributes('title') ?? null])
    }

    it('marks the missing action, and neither an offered action nor a native tool', async () => {
      registerEndpoint('/api/tools/mcp_gw378', () => ({ name: 'mcp_gw378', actions: [{ name: 'search_gmail_messages', description: '' }] }))
      const c = await mount('["mcp_gw378/search_gmail_messages","mcp_gw378/gmail_list_messages","web_search"]')
      await flushPromises()
      expect(marks(c)).toEqual([
        ['mcp_gw378/search_gmail_messages', null, null],
        ['mcp_gw378/gmail_list_messages', 'true', 'mcp_gw378 does not offer this action, so the task\'s runs cannot use it.'],
        ['web_search', null, null],
      ])
    })

    it('marks nothing when the server lists no actions, as a disconnected one does', async () => {
      registerEndpoint('/api/tools/mcp_down378', () => ({ name: 'mcp_down378', actions: [] }))
      const c = await mount('["mcp_down378/search_gmail_messages"]')
      await flushPromises()
      expect(marks(c)).toEqual([['mcp_down378/search_gmail_messages', null, null]])
    })

    it('marks nothing when the server cannot be read', async () => {
      registerEndpoint('/api/tools/mcp_gone378', (event) => {
        setResponseStatus(event, 404)
        return { code: 'not_found' }
      })
      const c = await mount('["mcp_gone378/search_gmail_messages"]')
      await flushPromises()
      expect(marks(c)).toEqual([['mcp_gone378/search_gmail_messages', null, null]])
    })
  })
})
