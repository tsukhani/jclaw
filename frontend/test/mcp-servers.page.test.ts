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
    return { success: true, toolCount: 2, message: 'Connected', toolNames: ['create_issue', 'list_repos'] }
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

  it('adds no column: the breaker sits beside the connection status', async () => {
    const c = await mountWith([breaker('mcp:github', 'CLOSED')])
    expect(c.findAll('thead th').map(th => th.text())).toEqual(['Name', 'Transport', 'Endpoint', 'Status', 'Tools', 'Actions'])
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
