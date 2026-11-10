import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { H3Event } from 'h3'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Tools from '~/pages/tools.vue'

/**
 * pages/tools.vue — category-chip tool counts.
 *
 * The filter chips show a per-category tool count (e.g. "All (6)",
 * "Utilities (2)"). Counts are absolute per-category totals derived from the
 * rendered card set, so MCP-grouped tools (which the grid folds onto the
 * /mcp-servers page and excludes here) must NOT be counted.
 */

// One native tool per relevant category, plus a second Utilities tool, plus
// an MCP-grouped tool that the page must exclude from both grid and counts.
const META_FIXTURE = [
  { name: 'shell_exec', group: null, enabled: true, category: 'System', icon: 'terminal', shortDescription: 'Run shell commands', requiresConfig: null, actionCount: 1 },
  { name: 'message', group: null, enabled: true, category: 'System', icon: 'send', shortDescription: 'Send a chat message', requiresConfig: null, actionCount: 1 },
  { name: 'web_search', group: null, enabled: true, category: 'Web', icon: 'search', shortDescription: 'Search the web', requiresConfig: null, actionCount: 2 },
  { name: 'filesystem', group: null, enabled: true, category: 'Files', icon: 'folder', shortDescription: 'Read and write files', requiresConfig: null, actionCount: 3 },
  { name: 'datetime', group: null, enabled: true, category: 'Utilities', icon: 'clock', shortDescription: 'Date and time', requiresConfig: null, actionCount: 1 },
  { name: 'jclaw_docs', group: null, enabled: true, category: 'Utilities', icon: 'document', shortDescription: 'Search the user guide', requiresConfig: null, actionCount: 1 },
  // MCP-grouped: lives on /mcp-servers, must be excluded from this page.
  { name: 'mcp_acme', group: 'acme', enabled: true, category: 'MCP', icon: 'plug', shortDescription: 'external', requiresConfig: null, actionCount: 40 },
]

const FILESYSTEM_DETAIL = {
  ...META_FIXTURE[3],
  actionCount: undefined,
  description: 'Read, write and list files in the agent workspace.',
  actions: [
    { name: 'readFile', description: 'Read a file.' },
    { name: 'writeFile', description: 'Write a file.' },
    { name: 'listFiles', description: 'List a directory.' },
  ],
}

/** Each tool's own read, counted per name, answering with its detail unless a test swaps the handler. */
let detailReads: Record<string, number> = {}
let unregister: Array<() => void> = []

function stubDetail(name: string, handler: (event: H3Event) => unknown) {
  unregister.push(registerEndpoint(`/api/tools/${name}`, (event: H3Event) => {
    detailReads[name] = (detailReads[name] ?? 0) + 1
    return handler(event)
  }))
}

beforeEach(() => {
  registerEndpoint('/api/tools/meta', () => META_FIXTURE)
  detailReads = {}
  for (const meta of META_FIXTURE) {
    stubDetail(meta.name, () => meta.name === 'filesystem'
      ? FILESYSTEM_DETAIL
      : { ...meta, description: `${meta.name} in full.`, actions: [{ name: `${meta.name}_run`, description: 'Runs.' }] })
  }
})

afterEach(() => {
  unregister.forEach(off => off())
  unregister = []
})

function norm(s: string): string {
  return s.replace(/\s+/g, ' ').trim()
}

describe('tools page category counts', () => {
  it('renders a per-category tool count on each filter chip', async () => {
    const wrapper = await mountSuspended(Tools)
    await flushPromises()
    const text = norm(wrapper.text())

    // 6 native tools (mcp_acme excluded via its group), summing the
    // per-category counts: 2 System + 1 Web + 1 Files + 2 Utilities.
    expect(text).toContain('All (6)')
    expect(text).toContain('System (2)')
    expect(text).toContain('Web (1)')
    expect(text).toContain('Files (1)')
    expect(text).toContain('Utilities (2)')
  })

  it('does not count MCP-grouped tools (no MCP chip, total excludes them)', async () => {
    const wrapper = await mountSuspended(Tools)
    await flushPromises()
    const text = norm(wrapper.text())

    // The MCP-grouped tool is folded out, so All stays 6 (not 7) and there is
    // no MCP filter chip on this page.
    expect(text).not.toContain('All (7)')
    expect(text).not.toContain('MCP (')
  })
})

describe('tools page All-view grouping', () => {
  it('groups by category (System, Utilities, Files, Web) with alphabetical order within each', async () => {
    const wrapper = await mountSuspended(Tools)
    await flushPromises()

    // Subheadings render in the fixed category order (MCP excluded).
    const headings = wrapper.findAll('h2').map(h => norm(h.text()))
    expect(headings).toEqual(['System (2)', 'Utilities (2)', 'Files (1)', 'Web (1)'])

    // Card names flow alphabetically within each category section, in category
    // order. Fixture insertion order (shell_exec, message, web_search, filesystem,
    // datetime, jclaw_docs) differs, so this proves the grouping + alpha sort.
    const names = wrapper.findAll('span.font-mono.font-semibold').map(s => s.text().trim())
    expect(names).toEqual([
      'message', 'shell_exec', // System, alphabetical
      'datetime', 'jclaw_docs', // Utilities, alphabetical
      'filesystem', // Files
      'web_search', // Web
    ])
  })

  it('drops the subheadings in a single-category view (the chip labels it)', async () => {
    const wrapper = await mountSuspended(Tools)
    await flushPromises()
    await wrapper.findAll('button').find(b => norm(b.text()).startsWith('System'))!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('h2')).toHaveLength(0)
  })
})

describe('tools page functions (JF-142: the catalog counts them, the tool\'s own read lists them)', () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
  function functionsButton(wrapper: any, name: string) {
    return wrapper.find(`[data-testid="tool-card-${name}"] button[aria-expanded]`)
  }

  it('shows each collapsed card\'s Functions count from the catalog and reads no tool', async () => {
    const wrapper = await mountSuspended(Tools)
    await flushPromises()

    expect(norm(functionsButton(wrapper, 'filesystem').text())).toBe('Functions 3')
    expect(norm(functionsButton(wrapper, 'web_search').text())).toBe('Functions 2')
    expect(norm(functionsButton(wrapper, 'datetime').text())).toBe('Functions 1')
    expect(functionsButton(wrapper, 'filesystem').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-testid^="tool-functions-"]').exists()).toBe(false)
    expect(detailReads).toEqual({})
  })

  it('reads a card\'s functions from its tool when the card expands, and only that card\'s', async () => {
    const wrapper = await mountSuspended(Tools)
    await flushPromises()

    await functionsButton(wrapper, 'filesystem').trigger('click')
    await flushPromises()

    const panel = wrapper.find('[data-testid="tool-functions-filesystem"]')
    expect(panel.findAll('code').map((c: { text: () => string }) => c.text())).toEqual(['readFile', 'writeFile', 'listFiles'])
    expect(panel.findAll('span').map((c: { text: () => string }) => c.text())).toEqual(['Read a file.', 'Write a file.', 'List a directory.'])
    expect(functionsButton(wrapper, 'filesystem').attributes('aria-expanded')).toBe('true')
    expect(detailReads).toEqual({ filesystem: 1 })
  })

  it('keeps a card\'s functions once read, so collapsing and expanding again reads nothing more', async () => {
    const wrapper = await mountSuspended(Tools)
    await flushPromises()

    await functionsButton(wrapper, 'filesystem').trigger('click')
    await flushPromises()
    await functionsButton(wrapper, 'filesystem').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="tool-functions-filesystem"]').exists()).toBe(false)
    await functionsButton(wrapper, 'filesystem').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="tool-functions-filesystem"]').findAll('code')).toHaveLength(3)
    expect(detailReads).toEqual({ filesystem: 1 })
  })

  it('announces the load while the read is in flight', async () => {
    let release: (v: unknown) => void = () => {}
    unregister.forEach(off => off())
    unregister = []
    stubDetail('filesystem', () => new Promise((resolve) => {
      release = resolve
    }))
    const wrapper = await mountSuspended(Tools)
    await flushPromises()

    await functionsButton(wrapper, 'filesystem').trigger('click')
    await flushPromises()
    const panel = () => wrapper.find('[data-testid="tool-functions-filesystem"]')
    expect(norm(panel().find('[role="status"]').text())).toBe('Loading functions…')

    release(FILESYSTEM_DETAIL)
    await vi.waitFor(() => expect(panel().findAll('code')).toHaveLength(3))
    expect(panel().find('[role="status"]').exists()).toBe(false)
  })

  it('shows the error when a card\'s read is refused, and reads again on the next expand', async () => {
    let refuse = true
    unregister.forEach(off => off())
    unregister = []
    stubDetail('filesystem', async (event) => {
      if (!refuse) return FILESYSTEM_DETAIL
      const { setResponseStatus } = await import('h3')
      setResponseStatus(event, 404)
      return { type: 'error', code: 'not_found', message: 'No tool named \'filesystem\' is registered.' }
    })
    const wrapper = await mountSuspended(Tools)
    await flushPromises()

    await functionsButton(wrapper, 'filesystem').trigger('click')
    const panel = () => wrapper.find('[data-testid="tool-functions-filesystem"]')
    await vi.waitFor(() => expect(panel().find('[data-testid="api-error"]').exists()).toBe(true))
    expect(panel().find('[data-testid="api-error"]').text()).toContain('No tool named \'filesystem\' is registered.')
    expect(panel().findAll('code')).toHaveLength(0)
    expect(detailReads).toEqual({ filesystem: 1 })

    refuse = false
    await functionsButton(wrapper, 'filesystem').trigger('click')
    await functionsButton(wrapper, 'filesystem').trigger('click')
    await vi.waitFor(() => expect(panel().findAll('code')).toHaveLength(3))
    expect(panel().find('[data-testid="api-error"]').exists()).toBe(false)
    expect(detailReads).toEqual({ filesystem: 2 })
  })

  it('reads every shown card\'s functions on Expand all, and no MCP tool\'s', async () => {
    const wrapper = await mountSuspended(Tools)
    await flushPromises()

    await wrapper.findAll('button').find((b: { text: () => string }) => norm(b.text()) === 'Expand all')!.trigger('click')
    await flushPromises()

    expect(detailReads).toEqual({ shell_exec: 1, message: 1, web_search: 1, filesystem: 1, datetime: 1, jclaw_docs: 1 })
    expect(wrapper.find('[data-testid="tool-functions-datetime"]').findAll('code').map((c: { text: () => string }) => c.text()))
      .toEqual(['datetime_run'])
  })
})
