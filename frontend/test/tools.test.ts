import { describe, it, expect, beforeEach } from 'vitest'
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
  { name: 'shell_exec', category: 'System', icon: 'terminal', shortDescription: 'Run shell commands', actions: [] },
  { name: 'message', category: 'System', icon: 'send', shortDescription: 'Send a chat message', actions: [] },
  { name: 'web_search', category: 'Web', icon: 'search', shortDescription: 'Search the web', actions: [] },
  { name: 'filesystem', category: 'Files', icon: 'folder', shortDescription: 'Read and write files', actions: [] },
  { name: 'datetime', category: 'Utilities', icon: 'clock', shortDescription: 'Date and time', actions: [] },
  { name: 'jclaw_docs', category: 'Utilities', icon: 'document', shortDescription: 'Search the user guide', actions: [] },
  // MCP-grouped: lives on /mcp-servers, must be excluded from this page.
  { name: 'mcp_acme_doThing', category: 'MCP', icon: 'plug', shortDescription: 'external', actions: [], group: 'acme' },
]

beforeEach(() => {
  registerEndpoint('/api/tools/meta', () => META_FIXTURE)
})

function norm(s: string): string {
  return s.replace(/\s+/g, ' ').trim()
}

describe('tools page category counts', () => {
  it('renders a per-category tool count on each filter chip', async () => {
    const wrapper = await mountSuspended(Tools)
    await flushPromises()
    const text = norm(wrapper.text())

    // 6 native tools (mcp_acme_doThing excluded via its group), summing the
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
    expect(wrapper.findAll('h2').length).toBe(0)
  })
})
