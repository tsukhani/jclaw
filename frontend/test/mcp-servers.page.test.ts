import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import McpServers from '~/pages/mcp-servers.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * The MCP Servers page: listing, the two transports' distinct payload shapes, and the
 * row actions.
 *
 * <p>The payload assertions are the point of most of these. A STDIO server and an HTTP
 * server are edited through the same form but must reach the API as different documents —
 * command/args/env versus url/headers — and the form carries fields for both at once, so
 * "which half was sent" is a real branch rather than a rendering detail.
 */

function server(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: 1,
    name: 'github',
    enabled: true,
    transport: 'STDIO',
    command: 'npx',
    args: ['-y', '@modelcontextprotocol/server-github'],
    env: { GITHUB_TOKEN: 'abc' },
    url: null,
    headers: {},
    status: 'CONNECTED',
    lastError: null,
    lastConnectedAt: null,
    lastDisconnectedAt: null,
    toolCount: 2,
    tools: [
      { name: 'create_issue', description: 'Open an issue' },
      { name: 'list_repos', description: 'List repositories' },
    ],
    createdAt: null,
    updatedAt: null,
    duplicateOf: null,
    protocol: 'AUTOMATIC',
    protocolVersion: null,
    protocolGeneration: null,
    protocolSwitchedAt: null,
    protocolSwitchReason: null,
    ...over,
  }
}

/** Mounted alongside ConfirmDialog so confirm() actually resolves — useConfirm holds
 *  module-singleton state and ConfirmDialog is what renders it. */
const Harness = defineComponent({
  setup() {
    return () => h('div', [h(McpServers), h(ConfirmDialog)])
  },
})

let posted: Record<string, unknown> | null = null
let putBody: Record<string, unknown> | null = null
let deleted = false
let testedId: number | null = null

function setupApi(rows: unknown[]) {
  posted = null
  putBody = null
  deleted = false
  testedId = null
  registerEndpoint('/api/mcp-servers', {
    handler: async (event) => {
      if (event.method === 'POST') {
        const { readBody } = await import('h3')
        posted = await readBody(event) as Record<string, unknown>
        return { id: 99 }
      }
      return rows
    },
  })
  registerEndpoint('/api/mcp-servers/1', {
    handler: async (event) => {
      if (event.method === 'DELETE') {
        deleted = true
        return { ok: true }
      }
      const { readBody } = await import('h3')
      putBody = await readBody(event) as Record<string, unknown>
      return { ok: true }
    },
  })
  registerEndpoint('/api/mcp-servers/1/test', () => {
    testedId = 1
    return { success: true, toolCount: 2, message: 'Connected over MCP v1. 2 tool(s) advertised. The server offers MCP v2.', toolNames: ['create_issue', 'list_repos'], protocolVersion: '2025-06-18', offersModern: true }
  })
}

async function clickLabel(c: VueWrapper, label: string) {
  const btn = c.findAll('button').find(b => b.attributes('aria-label') === label)
  expect(btn, `no button labelled "${label}"`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

async function clickText(c: VueWrapper, text: string) {
  const btn = c.findAll('button').find(b => b.text().trim() === text)
  expect(btn, `no button reading "${text}"`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

describe('MCP Servers page', () => {
  beforeEach(() => clearNuxtData())
  afterEach(() => {
    document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
  })

  it('tells the operator how to start when nothing is configured', async () => {
    setupApi([])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    expect(c.text()).toContain('No MCP servers configured')
  })

  it('lists a configured server with its transport and advertised tool count', async () => {
    setupApi([server()])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    expect(c.text()).toContain('github')
    expect(c.text()).toContain('STDIO')
    // The tool list itself stays collapsed until asked for.
    expect(c.text()).not.toContain('create_issue')
  })

  it('expands one server\'s tool list and collapses it again', async () => {
    setupApi([server()])
    const c = await mountSuspended(McpServers)
    await flushPromises()

    await clickLabel(c, 'Show 2 tools for github')
    expect(c.text()).toContain('create_issue')
    expect(c.text()).toContain('list_repos')

    await clickLabel(c, 'Hide 2 tools for github')
    expect(c.text()).not.toContain('create_issue')
  })

  it('opens an add form that defaults to the STDIO transport', async () => {
    setupApi([])
    const c = await mountSuspended(McpServers)
    await flushPromises()

    await clickText(c, 'Add server')
    expect(c.text()).toContain('New MCP server')
    // STDIO is the default, so the command field is the one on offer.
    expect(c.find('input[placeholder="npx"]').exists()).toBe(true)
  })

  it('sends a STDIO server as command, newline-split args and non-blank env only', async () => {
    setupApi([])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    await clickText(c, 'Add server')

    await c.find('input[placeholder="github"]').setValue('gh')
    await c.find('input[placeholder="npx"]').setValue('  npx  ')
    await c.find('textarea').setValue('-y\n\n  @scope/server  \n')
    await c.find('input[aria-label="Environment variable name"]').setValue(' TOKEN ')
    await c.find('input[aria-label="Environment variable value"]').setValue('secret')
    await c.find('form').trigger('submit')
    await vi.waitFor(() => expect(posted).toBeTruthy())

    expect(posted!.name).toBe('gh')
    expect(posted!.transport).toBe('STDIO')
    expect(posted!.command).toBe('npx')
    // Blank lines are dropped and each arg is trimmed — a stray newline must not become
    // an empty argv entry, which the subprocess would receive as a real argument.
    expect(posted!.args).toEqual(['-y', '@scope/server'])
    expect(posted!.env).toEqual({ TOKEN: 'secret' })
    // The HTTP half of the form is not sent for a STDIO server.
    expect(posted).not.toHaveProperty('url')
    expect(posted).not.toHaveProperty('headers')
  })

  it('sends an HTTP server as url and headers, with no command or args', async () => {
    setupApi([])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    await clickText(c, 'Add server')

    await c.find('input[placeholder="github"]').setValue('remote')
    // Transport is a radio pair, and picking HTTP swaps the whole lower half of the form.
    await c.find('input[type="radio"][value="HTTP"]').setValue()
    await flushPromises()

    await c.find('input[placeholder="https://mcp.example.com/v1/mcp"]').setValue(' https://x.test/mcp ')
    await c.find('input[aria-label="Header name"]').setValue('Authorization')
    await c.find('input[aria-label="Header value"]').setValue('Bearer t')
    await c.find('form').trigger('submit')
    await vi.waitFor(() => expect(posted).toBeTruthy())

    expect(posted!.transport).toBe('HTTP')
    expect(posted!.url).toBe('https://x.test/mcp')
    expect(posted!.headers).toEqual({ Authorization: 'Bearer t' })
    expect(posted).not.toHaveProperty('command')
    expect(posted).not.toHaveProperty('args')
  })

  it('populates the edit form from the stored server, one arg per line', async () => {
    setupApi([server()])
    const c = await mountSuspended(McpServers)
    await flushPromises()

    await clickText(c, 'Edit')
    // The inline edit row identifies its fields by id rather than placeholder.
    expect((c.find('#edit-1-name').element as HTMLInputElement).value).toBe('github')
    expect((c.find('#edit-1-command').element as HTMLInputElement).value).toBe('npx')
    expect((c.find('#edit-1-args').element as HTMLTextAreaElement).value)
      .toBe('-y\n@modelcontextprotocol/server-github')
    expect((c.find('input[aria-label="Environment variable name"]').element as HTMLInputElement).value)
      .toBe('GITHUB_TOKEN')
  })

  it('shows a saved secret as dots and sends its mask back untouched, which keeps it (JCLAW-1331)', async () => {
    setupApi([server({ env: { GITHUB_TOKEN: 'ghp_****', NODE_ENV: 'production' } })])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    await clickText(c, 'Edit')

    expect(c.find('form').html()).not.toContain('ghp_')
    const values = c.findAll('input[aria-label="Environment variable value"]')
    expect(values.map(v => (v.element as HTMLInputElement).value)).toEqual(['production'])

    await c.find('form').trigger('submit')
    await vi.waitFor(() => expect(putBody).toBeTruthy())
    expect(putBody!.env).toEqual({ GITHUB_TOKEN: 'ghp_****', NODE_ENV: 'production' })
  })

  it('sends a changed secret as typed, and asks for a value once a saved one is renamed', async () => {
    setupApi([server({ transport: 'HTTP', command: null, args: [], env: {}, url: 'https://x.test/mcp',
      headers: { 'Authorization': 'Bear****', 'X-Tier': 'gold****' } })])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    await clickText(c, 'Edit')

    expect(c.findAll('input[aria-label="Header value"]')).toHaveLength(0)
    await clickLabel(c, 'Edit Header value')
    await c.find('input[aria-label="Header value"]').setValue('Bearer new')
    const names = c.findAll('input[aria-label="Header name"]')
    await names[1]!.setValue('X-Plan')
    expect(c.findAll('input[aria-label="Header value"]')).toHaveLength(2)

    await c.find('form').trigger('submit')
    await vi.waitFor(() => expect(putBody).toBeTruthy())
    expect(putBody!.headers).toEqual({ 'Authorization': 'Bearer new', 'X-Plan': '' })
  })

  it('offers view tools on every channel only when the add form\'s box is ticked', async () => {
    setupApi([])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    await clickText(c, 'Add server')

    const box = c.find('input[data-testid="mcp-view-tools-everywhere"]')
    expect(box.element.closest('label')!.textContent!.trim()).toBe('Offer view tools on every channel')
    expect((box.element as HTMLInputElement).checked).toBe(false)
    await c.find('input[placeholder="github"]').setValue('n8n')
    await c.find('input[placeholder="npx"]').setValue('npx')
    await c.find('form').trigger('submit')
    await vi.waitFor(() => expect(posted).toBeTruthy())
    expect(posted!.offerViewToolsEverywhere).toBe(false)

    posted = null
    await clickText(c, 'Add server')
    await c.find('input[placeholder="github"]').setValue('n8n')
    await c.find('input[placeholder="npx"]').setValue('npx')
    await c.find('input[data-testid="mcp-view-tools-everywhere"]').setValue(true)
    await c.find('form').trigger('submit')
    await vi.waitFor(() => expect(posted).toBeTruthy())
    expect(posted!.offerViewToolsEverywhere).toBe(true)
  })

  it('shows a server\'s stored view-tools setting in its edit form and sends it changed', async () => {
    setupApi([server({ offerViewToolsEverywhere: true })])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    await clickText(c, 'Edit')

    const box = c.find('#edit-1-view-tools')
    expect((box.element as HTMLInputElement).checked).toBe(true)
    await box.setValue(false)
    await c.find('form').trigger('submit')
    await vi.waitFor(() => expect(putBody).toBeTruthy())
    expect(putBody!.offerViewToolsEverywhere).toBe(false)
  })

  it('flips a server\'s enabled flag without sending the rest of the form', async () => {
    setupApi([server()])
    const c = await mountSuspended(McpServers)
    await flushPromises()

    expect(c.find('button[role="switch"][aria-label="github server"]').attributes('aria-checked')).toBe('true')
    await clickLabel(c, 'github server')
    await vi.waitFor(() => expect(putBody).toBeTruthy())
    expect(putBody!.enabled).toBe(false)
    // The row toggle is a partial update — sending the whole server would let a stale
    // row overwrite fields the operator never touched.
    expect(putBody).not.toHaveProperty('command')
  })

  it('reports a connection test against the row it was run for', async () => {
    setupApi([server()])
    const c = await mountSuspended(McpServers)
    await flushPromises()

    await clickLabel(c, 'Test connection to github')
    expect(testedId).toBe(1)
    expect(c.text()).toContain('Connected')
  })

  it('shows the protocol generation each server last connected with in its own column, and its revision on hover (JCLAW-1443)', async () => {
    setupApi([
      server({ protocolVersion: '2026-07-28', protocolGeneration: 2 }),
      server({ id: 2, name: 'older', protocolVersion: '2025-06-18', protocolGeneration: 1 }),
      server({ id: 3, name: 'fresh' }),
    ])
    const c = await mountSuspended(McpServers)
    await flushPromises()

    const headers = c.findAll('thead th').map(th => th.text())
    const column = headers.indexOf('Version')
    expect(column).toBe(headers.indexOf('Status') + 1)
    const cells = c.findAll('tbody > tr').map(tr => tr.findAll('td')[column]!)
    expect(cells.map(td => td.text())).toEqual(['v2', 'v1', '—'])

    const [v2, v1] = c.findAll('[data-testid="mcp-protocol-version"]')
    expect(v2!.attributes('title')).toContain('2026-07-28')
    expect(v1!.attributes('title')).toContain('2025-06-18')
    expect(v2!.classes()).not.toEqual(v1!.classes())
  })

  it('edits the protocol setting beside the version JClaw last connected with', async () => {
    setupApi([server({ protocolVersion: '2026-07-28', protocolGeneration: 2 })])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    await clickText(c, 'Edit')

    const select = c.find('#edit-1-protocol')
    expect((select.element as HTMLSelectElement).value).toBe('AUTOMATIC')
    expect(c.find('[data-testid="mcp-protocol-last-version"]').text()).toBe('Last connected with MCP v2')
    expect(c.find('[data-testid="mcp-protocol-switched"]').exists()).toBe(false)

    await select.setValue('HANDSHAKE')
    await c.find('form').trigger('submit')
    await vi.waitFor(() => expect(putBody).toBeTruthy())
    expect(putBody!.protocol).toBe('HANDSHAKE')
  })

  it('says when and why JClaw switched a server to Handshake', async () => {
    setupApi([server({ protocol: 'HANDSHAKE', protocolVersion: '2025-06-18', protocolGeneration: 1,
      protocolSwitchedAt: '2026-10-11T08:00:00Z', protocolSwitchReason: 'the first tools/list over MCP v2 failed' })])
    const c = await mountSuspended(McpServers)
    await flushPromises()
    await clickText(c, 'Edit')

    expect((c.find('#edit-1-protocol').element as HTMLSelectElement).value).toBe('HANDSHAKE')
    const note = c.find('[data-testid="mcp-protocol-switched"]')
    expect(note.text()).toContain('JClaw switched this server to Handshake')
    expect(note.text()).toContain('the first tools/list over MCP v2 failed')
  })

  it('reports the protocol a connection test found', async () => {
    setupApi([server()])
    const c = await mountSuspended(McpServers)
    await flushPromises()

    await clickLabel(c, 'Test connection to github')
    expect(c.text()).toContain('Connected over MCP v1')
    expect(c.text()).toContain('The server offers MCP v2.')
  })

  it('deletes only after the operator confirms', async () => {
    setupApi([server()])
    const c = await mountSuspended(Harness)
    await flushPromises()

    await clickLabel(c, 'Delete github')
    // The dialog is open and nothing has been sent yet — confirmation gates the request.
    expect(deleted).toBe(false)

    // The dialog teleports to body, so it is not inside the wrapper's tree.
    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete')
    expect(confirmBtn, 'Delete confirm button should exist on the dialog').toBeTruthy()
    confirmBtn!.click()
    await vi.waitFor(() => expect(deleted).toBe(true))
  })
})

describe('MCP servers page — each server shows its own breaker (JCLAW-1301)', () => {
  beforeEach(() => clearNuxtData())

  function breaker(name: string, state: string, over: Record<string, unknown> = {}) {
    return { name, subsystem: name.split(':')[0], target: name.split(':')[1], state, samples: 8, failures: 5, slowCalls: 0, reason: state === 'CLOSED' ? null : 'FAILURE_RATE', manual: false, ...over }
  }

  async function mountWith(breakers: unknown[]) {
    setupApi([
      server(),
      server({ id: 2, name: 'fetch', toolCount: 0, tools: [] }),
      server({ id: 3, name: 'files', toolCount: 0, tools: [] }),
    ])
    registerEndpoint('/api/breakers', () => breakers)
    const c = await mountSuspended(McpServers)
    await flushPromises()
    // The breaker read is lazy, so the rows render before their breakers do.
    await vi.waitFor(() => expect(c.find('[data-testid^="mcp-breaker-toggle-"]').exists()).toBe(true))
    return c
  }

  it('adds no column: the breaker sits among the row actions', async () => {
    const c = await mountWith([breaker('mcp:github', 'CLOSED')])
    expect(c.findAll('thead th').map(th => th.text())).toEqual(['Name', 'Transport', 'Endpoint', 'Status', 'Version', 'Tools', 'Actions'])
    const actions = c.findAll('tbody tr')[0]!.findAll('td').at(-1)!
    expect(actions.find('[data-testid="mcp-breaker-toggle-github"]').exists()).toBe(true)
  })

  it('opens the row beneath a server whose breaker is not serving, without being asked', async () => {
    const c = await mountWith([breaker('mcp:github', 'OPEN')])

    const toggle = c.find('[data-testid="mcp-breaker-toggle-github"]')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    const row = c.find('[data-testid="mcp-breaker-row-github"]')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('Circuit breaker')
    expect(row.text()).toContain('OPEN')
    expect(row.text()).toContain('tripped on failure rate')
    expect(row.find('button[aria-label="Restore github"]').exists()).toBe(true)
  })

  it('keeps a serving breaker behind its icon until the operator opens it', async () => {
    const c = await mountWith([breaker('mcp:fetch', 'CLOSED')])

    const toggle = c.find('[data-testid="mcp-breaker-toggle-fetch"]')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(c.find('[data-testid="mcp-breaker-row-fetch"]').exists()).toBe(false)

    await toggle.trigger('click')
    const row = c.find('[data-testid="mcp-breaker-row-fetch"]')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('8 recent calls')
    expect(row.find('button[aria-label="Isolate fetch"]').exists()).toBe(true)

    await toggle.trigger('click')
    expect(c.find('[data-testid="mcp-breaker-row-fetch"]').exists()).toBe(false)
  })

  it('shows nothing for a server that was never called, nor for a provider breaker of the same name', async () => {
    const c = await mountWith([breaker('mcp:github', 'CLOSED'), breaker('llm:files', 'OPEN')])
    expect(c.find('[data-testid="mcp-breaker-toggle-fetch"]').exists()).toBe(false)
    expect(c.find('[data-testid="mcp-breaker-toggle-files"]').exists()).toBe(false)
    expect(c.find('[data-testid="mcp-breaker-row-files"]').exists()).toBe(false)
  })
})

describe('MCP servers page — a failed row action (JCLAW-1221)', () => {
  it('says why a server switch did not take', async () => {
    setupApi([server()])
    const off = registerEndpoint('/api/mcp-servers/1', {
      method: 'PUT',
      handler: async (event) => {
        const { setResponseStatus } = await import('h3')
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    try {
      const c = await mountSuspended(McpServers)
      await flushPromises()
      await clickLabel(c, 'github server')
      await vi.waitFor(() => expect(c.find('[data-testid="api-error"]').exists()).toBe(true))
      expect(c.find('[data-testid="api-error"]').text()).toContain('/api/mcp-servers/1')
    }
    finally {
      off()
    }
  })
})
