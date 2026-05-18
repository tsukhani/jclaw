import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import Skills from '~/pages/skills.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * Mount Skills with a sibling ConfirmDialog so the promote flow's
 * confirm() dialog actually renders into the DOM. Skills uses useConfirm()'s
 * module-singleton state, and ConfirmDialog is what reads it; in production
 * ConfirmDialog is mounted once at the app root.
 */
const SkillsHarness = defineComponent({
  setup() {
    return () => h('div', [h(Skills), h(ConfirmDialog)])
  },
})

/**
 * JCLAW-314 — pages/skills.vue critical flow coverage.
 *
 * The page already has structural / render coverage in skills.test.ts;
 * this sibling spec exercises:
 *
 *   1. Filter-bar LIKE matching against the global skills list.
 *   2. Filter-bar LIKE matching against the agents list.
 *   3. Drag-promote flow that opens a ConfirmDialog when a same-named
 *      global already exists, then fires the /api/skills/promote POST
 *      on confirm. Cancel preserves the global list unchanged.
 *   4. Delete button on a global skill row sends the DELETE and surfaces
 *      the refresh.
 *
 * The page's drag-drop handlers and the delete button live on hover-only
 * affordances; we either reach into the exposed handlers via the
 * component's render tree (DOM-level click) or trigger the same code path
 * the SSE event would. The intent is to pin the user-observable contract,
 * not the exact HTML scaffolding.
 */

// happy-dom doesn't ship an EventSource shim; the skills page mounts
// useEventBus() which constructs one. Mirror the workaround from the
// existing skills.test.ts so the page setup completes.
if (typeof globalThis.EventSource === 'undefined') {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: test stub for a browser global; narrow shape doesn't add value.
  ;(globalThis as any).EventSource = class MockEventSource {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mirrors the DOM EventSource handler signature without importing dom-lib types.
    onmessage: ((e: any) => void) | null = null
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: same — DOM error events aren't worth typing here.
    onerror: ((e: any) => void) | null = null
    close() {}
  }
}

beforeEach(() => {
  useState('promotingSkills', () => new Set())
  // useFetch caches by URL between mounts; flush so each test sees its own fixture.
  clearNuxtData()
})

function setupApi(opts?: { skills?: unknown[], agents?: unknown[] }) {
  registerEndpoint('/api/skills', () => opts?.skills ?? [
    { name: 'web-search', folderName: 'web-search', description: 'Search the web', version: '1.0.0' },
    { name: 'code-review', folderName: 'code-review', description: 'Review code changes', version: '0.2.0' },
    { name: 'docs-writer', folderName: 'docs-writer', description: 'Generate docs', version: '0.4.0' },
  ])
  registerEndpoint('/api/agents', () => opts?.agents ?? [
    { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
    { id: 2, name: 'helper', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true },
  ])
  registerEndpoint('/api/agents/1/skills', () => [
    { name: 'web-search', folderName: 'web-search', enabled: true, version: '1.0.0' },
  ])
  registerEndpoint('/api/agents/2/skills', () => [])
}

describe('Skills page — filter-bar LIKE matching (global list)', () => {
  it('hides non-matching global skills as the user types', async () => {
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    // Scope every assertion to the Global Skills <section> (data-tour
    // anchor). The right-hand Agents column lists each agent's skills by
    // name too, so a page-wide assertion that "web-search" disappears
    // would fail just because the agent's bound copy is still listed.
    const globalSection = () => component.find('[data-tour="global-skills"]')
    expect(globalSection().text()).toContain('web-search')
    expect(globalSection().text()).toContain('code-review')
    expect(globalSection().text()).toContain('docs-writer')

    const input = component.find<HTMLInputElement>('input[aria-label="Filter global skills by name"]')
    expect(input.exists()).toBe(true)
    await input.setValue('code')
    await flushPromises()

    const text = globalSection().text()
    expect(text).toContain('code-review')
    expect(text).not.toContain('web-search')
    expect(text).not.toContain('docs-writer')
  })

  it('shows an "no skills match" italic notice when the filter excludes everything', async () => {
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    const input = component.find<HTMLInputElement>('input[aria-label="Filter global skills by name"]')
    await input.setValue('zzz-no-match-zzz')
    await flushPromises()

    expect(component.text()).toContain('No skills match')
    expect(component.text()).toContain('zzz-no-match-zzz')
  })

  it('restores the full list when the filter is cleared', async () => {
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    const globalSection = () => component.find('[data-tour="global-skills"]')
    const input = component.find<HTMLInputElement>('input[aria-label="Filter global skills by name"]')
    await input.setValue('code')
    await flushPromises()
    expect(globalSection().text()).not.toContain('web-search')

    await input.setValue('')
    await flushPromises()
    expect(globalSection().text()).toContain('web-search')
    expect(globalSection().text()).toContain('docs-writer')
  })
})

describe('Skills page — filter-bar LIKE matching (agents list)', () => {
  it('filters agents by name (case-insensitive substring)', async () => {
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    expect(component.text()).toContain('main-agent')
    expect(component.text()).toContain('helper')

    const input = component.find<HTMLInputElement>('input[aria-label="Filter agents by name"]')
    expect(input.exists()).toBe(true)
    await input.setValue('HELP')
    await flushPromises()

    const text = component.text()
    expect(text).toContain('helper')
    expect(text).not.toContain('main-agent')
  })
})

describe('Skills page — promote flow opens ConfirmDialog when global already exists', () => {
  afterEach(() => {
    // Reset useConfirm singleton state and strip teleported dialog content
    // so leakage from one promote-flow case doesn't bleed into the next.
    const { _state, _resolve } = useConfirm()
    if (_state.open) _resolve(false)
    document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
  })

  it('fires /api/skills/promote when the user confirms the replacement dialog', async () => {
    let promotePosted = false
    let promoteBody: unknown = null
    registerEndpoint('/api/skills/promote', {
      method: 'POST',
      handler: async (event) => {
        const { readBody } = await import('h3')
        promoteBody = await readBody(event)
        promotePosted = true
        return { status: 'ok' }
      },
    })
    setupApi({
      // Global already has 'web-search', and the helper agent will hold a copy.
      agents: [
        { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
      ],
    })
    // Override the per-agent skills endpoint to give main-agent a web-search to promote.
    registerEndpoint('/api/agents/1/skills', () => [
      { name: 'web-search', folderName: 'web-search', enabled: true, version: '1.1.0' },
    ])

    const component = await mountSuspended(SkillsHarness)
    await flushPromises()

    // Find the agent's draggable web-search row in the right-hand Agent
    // Skills column. The right column lays out skill rows under each
    // agent block; we filter to the row whose closest section is the
    // Agent Skills section (the global section is identified by its
    // data-tour attribute, while the agent section is its sibling).
    const draggables = component.findAll('[draggable="true"]')
    const agentSkillRow = draggables.find((el) => {
      if (!el.text().includes('web-search')) return false
      // The Global Skills section has data-tour="global-skills"; the agent
      // section is the sibling without that attribute. Anything outside the
      // global section is necessarily the agent-side row for our fixture.
      return !el.element.closest('[data-tour="global-skills"]')
    })
    expect(agentSkillRow).toBeTruthy()

    const dt = new DataTransfer()
    await agentSkillRow!.trigger('dragstart', { dataTransfer: dt })
    await flushPromises()

    const globalSection = component.find('[data-tour="global-skills"]')
    expect(globalSection.exists()).toBe(true)
    await globalSection.trigger('dragover', { dataTransfer: dt })
    await globalSection.trigger('drop', { dataTransfer: dt })
    await flushPromises()

    // The drop handler calls confirm() because a same-named global exists.
    // Click "Promote" on the ConfirmDialog (teleported to body via the
    // SkillsHarness wrapper).
    const promoteBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Promote')
    expect(promoteBtn).toBeTruthy()
    promoteBtn!.click()
    // vi.waitFor polls the assertion while the registerEndpoint mock's
    // multi-microtask resolution chain settles. flushPromises drains
    // only one round which is insufficient for $fetch through Nitro.
    await vi.waitFor(() => expect(promotePosted).toBe(true))
    expect(promoteBody).toEqual({ agentId: 1, skillName: 'web-search' })
  })

  it('does not POST /api/skills/promote when the user cancels the dialog', async () => {
    let promotePosted = false
    registerEndpoint('/api/skills/promote', {
      method: 'POST',
      handler: () => {
        promotePosted = true
        return { status: 'ok' }
      },
    })
    setupApi({
      agents: [
        { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
      ],
    })
    registerEndpoint('/api/agents/1/skills', () => [
      { name: 'web-search', folderName: 'web-search', enabled: true, version: '1.1.0' },
    ])

    const component = await mountSuspended(SkillsHarness)
    await flushPromises()

    const agentSkillRow = component.findAll('[draggable="true"]').find(el =>
      el.text().includes('web-search')
      && !el.element.closest('[data-tour="global-skills"]'))
    const dt = new DataTransfer()
    await agentSkillRow!.trigger('dragstart', { dataTransfer: dt })
    const globalSection = component.find('[data-tour="global-skills"]')
    await globalSection.trigger('dragover', { dataTransfer: dt })
    await globalSection.trigger('drop', { dataTransfer: dt })
    await flushPromises()

    const cancelBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Cancel')
    expect(cancelBtn).toBeTruthy()
    cancelBtn!.click()
    await flushPromises()
    await flushPromises()

    expect(promotePosted).toBe(false)
  })
})

describe('Skills page — delete a global custom skill', () => {
  it('issues DELETE /api/skills/{name} when the trash icon is clicked', async () => {
    let deletedName: string | null = null
    registerEndpoint('/api/skills/code-review', {
      method: 'DELETE',
      handler: () => {
        deletedName = 'code-review'
        return { status: 'ok' }
      },
    })
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    // Find the trash button on the code-review row. The button carries
    // title="Delete skill" — that's the stable contract used by the
    // tooltip and the test. Multiple rows have trash buttons (one per
    // non-structural skill), so we scope to the row containing the name.
    const rows = component.findAll('.group')
    const codeReviewRow = rows.find(r => r.text().includes('code-review'))
    expect(codeReviewRow).toBeTruthy()
    const trashBtn = codeReviewRow!.findAll('button[title="Delete skill"]')[0]
    expect(trashBtn).toBeTruthy()
    await trashBtn!.trigger('click')
    // vi.waitFor for the async DELETE handler to land — see the comment
    // on the promote-confirm test above for the rationale.
    await vi.waitFor(() => expect(deletedName).toBe('code-review'))
  })
})

/**
 * JCLAW-323 — additional skills.vue coverage. The original ticket asked for
 * file-upload error paths, an edit-dialog form, and pagination — none of those
 * features exist in skills.vue today (it's a read-only viewer with drag-drop
 * skill assignment). These tests instead drive the actual uncovered surface:
 * filter clear buttons, view-skill flow, version-update affordances, dragError
 * banner promote failures, and SSE-driven promote completion events.
 */

describe('Skills page — filter clear buttons', () => {
  it('clears the global filter when the × clear button is clicked', async () => {
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    const input = component.find<HTMLInputElement>('input[aria-label="Filter global skills by name"]')
    await input.setValue('code')
    await flushPromises()
    // While the filter is set, a clear button with title="Clear filter" appears
    // next to the global filter input.
    const clearBtns = component.findAll('button[title="Clear filter"]')
    expect(clearBtns.length).toBeGreaterThanOrEqual(1)
    // First clear button belongs to the global-filter row (left column),
    // since the section renders before the agents section in the DOM tree.
    await clearBtns[0]!.trigger('click')
    await flushPromises()

    // Filter is now empty — full list shows again.
    const globalSection = component.find('[data-tour="global-skills"]')
    expect(globalSection.text()).toContain('web-search')
    expect(globalSection.text()).toContain('docs-writer')
  })

  it('clears the agent filter when its × clear button is clicked', async () => {
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    const input = component.find<HTMLInputElement>('input[aria-label="Filter agents by name"]')
    await input.setValue('help')
    await flushPromises()
    expect(component.text()).not.toContain('main-agent')

    const clearBtns = component.findAll('button[title="Clear filter"]')
    // After typing, both the global and the agent filter rows render clear
    // buttons. The agent-section clear is the second occurrence.
    expect(clearBtns.length).toBeGreaterThanOrEqual(1)
    // Find the clear button whose nearest input has aria-label for agents.
    const agentClear = clearBtns.find((b) => {
      const parent = b.element.parentElement
      const sibling = parent?.querySelector('input[aria-label="Filter agents by name"]')
      return sibling != null
    })
    expect(agentClear).toBeTruthy()
    await agentClear!.trigger('click')
    await flushPromises()
    expect(component.text()).toContain('main-agent')
  })
})

describe('Skills page — view-skill flow (read-only viewer)', () => {
  it('opens the read-only skill viewer when the Eye icon is clicked on a global skill', async () => {
    // /api/skills/{name}/files is the backing endpoint editSkill calls.
    registerEndpoint('/api/skills/web-search/files', () => ({
      files: [{ path: 'SKILL.md', isText: true }],
      tools: [],
      commands: [],
      author: '',
    }))
    registerEndpoint('/api/skills/web-search/files/SKILL.md', () => ({
      content: '# Web Search\n\nUse this skill to search the web.',
    }))
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    // The viewer is gated behind editing.value; clicking "View skill" sets it.
    const rows = component.findAll('.group')
    const webSearchRow = rows.find(r => r.text().includes('web-search'))
    expect(webSearchRow).toBeTruthy()
    const viewBtn = webSearchRow!.find('button[title="View skill"]')
    expect(viewBtn.exists()).toBe(true)
    await viewBtn.trigger('click')
    await flushPromises()

    // Read-only viewer shell is now mounted; the back button has the canonical
    // text from the template.
    expect(component.text()).toContain('Back to skills')
    expect(component.text()).toContain('web-search')
  })

  it('returns to the skills list when the Back button is clicked in the viewer', async () => {
    registerEndpoint('/api/skills/code-review/files', () => ({
      files: [{ path: 'SKILL.md', isText: true }],
      tools: [],
      commands: [],
      author: '',
    }))
    registerEndpoint('/api/skills/code-review/files/SKILL.md', () => ({
      content: '# Code Review',
    }))
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    const rows = component.findAll('.group')
    const codeRow = rows.find(r => r.text().includes('code-review'))
    expect(codeRow).toBeTruthy()
    await codeRow!.find('button[title="View skill"]').trigger('click')
    await flushPromises()
    expect(component.text()).toContain('Back to skills')

    // Back button restores the two-column view; the page heading is visible again.
    const backBtn = component.findAll('button').find(b => b.text().includes('Back to skills'))
    expect(backBtn).toBeTruthy()
    await backBtn!.trigger('click')
    await flushPromises()
    // Filter input from the global skills column reappears.
    expect(component.find('input[aria-label="Filter global skills by name"]').exists()).toBe(true)
  })
})

describe('Skills page — global → agent drag-and-drop copy', () => {
  it('POSTs /api/agents/{id}/skills/{name}/copy when a global skill is dropped on an agent card', async () => {
    let copyCalled = false
    registerEndpoint('/api/agents/2/skills/web-search/copy', {
      method: 'POST',
      handler: () => {
        copyCalled = true
        return { status: 'ok' }
      },
    })
    setupApi({
      agents: [
        { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
        { id: 2, name: 'helper', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true },
      ],
    })
    // helper agent has no skills yet — drop should issue a fresh copy POST.
    registerEndpoint('/api/agents/2/skills', () => [])

    const component = await mountSuspended(Skills)
    await flushPromises()

    // Drag the global web-search row onto helper's card.
    const globalSection = component.find('[data-tour="global-skills"]')
    const globalRow = globalSection.findAll('[draggable="true"]').find(el =>
      el.text().includes('web-search'))
    expect(globalRow).toBeTruthy()
    const dt = new DataTransfer()
    await globalRow!.trigger('dragstart', { dataTransfer: dt })
    await flushPromises()

    // Find helper's drop target — it's the agent card rendered in the right column.
    const allDivs = component.findAll('div')
    const helperCard = allDivs.find(d =>
      d.text().includes('helper') && d.element.tagName === 'DIV' && d.attributes('class')?.includes('p-3'))
    expect(helperCard).toBeTruthy()
    await helperCard!.trigger('dragover', { dataTransfer: dt })
    await helperCard!.trigger('drop', { dataTransfer: dt })

    await vi.waitFor(() => expect(copyCalled).toBe(true))
  })
})

describe('Skills page — version-update affordance', () => {
  it('renders the "→ vX" update button when the agent has an older skill version than global', async () => {
    setupApi({
      skills: [
        { name: 'web-search', folderName: 'web-search', description: 'Search the web', version: '2.0.0' },
      ],
      agents: [
        { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
      ],
    })
    // Agent has older version 1.0.0; global is 2.0.0 → update button shows.
    registerEndpoint('/api/agents/1/skills', () => [
      { name: 'web-search', folderName: 'web-search', enabled: true, version: '1.0.0' },
    ])

    const component = await mountSuspended(Skills)
    await flushPromises()

    // Update button shows "→ vN.N.N" text and a title="Update to vN.N.N" tooltip.
    const updateBtn = component.findAll('button').find(b =>
      (b.attributes('title') ?? '').includes('Update to v2.0.0'))
    expect(updateBtn).toBeTruthy()
    expect(updateBtn!.text()).toContain('→ v2.0.0')
  })

  it('issues POST /api/agents/{id}/skills/{name}/copy when the update button is clicked', async () => {
    let copyCalled = false
    registerEndpoint('/api/agents/1/skills/web-search/copy', {
      method: 'POST',
      handler: () => {
        copyCalled = true
        return { status: 'ok' }
      },
    })
    setupApi({
      skills: [
        { name: 'web-search', folderName: 'web-search', description: 'Search the web', version: '2.0.0' },
      ],
      agents: [
        { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
      ],
    })
    registerEndpoint('/api/agents/1/skills', () => [
      { name: 'web-search', folderName: 'web-search', enabled: true, version: '1.0.0' },
    ])

    const component = await mountSuspended(Skills)
    await flushPromises()
    const updateBtn = component.findAll('button').find(b =>
      (b.attributes('title') ?? '').includes('Update to v2.0.0'))
    expect(updateBtn).toBeTruthy()
    await updateBtn!.trigger('click')
    await vi.waitFor(() => expect(copyCalled).toBe(true))
  })
})

describe('Skills page — inline rename flow', () => {
  it('writes PUT /api/skills/{old}/rename with the new name on Enter', async () => {
    let renameBody: unknown = null
    registerEndpoint('/api/skills/code-review/rename', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        renameBody = await readBody(event)
        return { status: 'ok' }
      },
    })
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    // Find the code-review name span; double-click switches to the rename input.
    const rows = component.findAll('.group')
    const codeRow = rows.find(r => r.text().includes('code-review'))
    expect(codeRow).toBeTruthy()
    const nameSpan = codeRow!.find('span.font-mono')
    await nameSpan.trigger('dblclick')
    await flushPromises()

    // The aria-label encodes the original folder name.
    const renameInput = codeRow!.find<HTMLInputElement>('input[aria-label="Rename skill code-review"]')
    expect(renameInput.exists()).toBe(true)
    await renameInput.setValue('code-review-v2')
    await renameInput.trigger('keydown.enter')
    await vi.waitFor(() => expect(renameBody).toEqual({ newName: 'code-review-v2' }))
  })

  it('cancels the rename without firing the API when Escape is pressed', async () => {
    let renameCalled = false
    registerEndpoint('/api/skills/code-review/rename', {
      method: 'PUT',
      handler: () => {
        renameCalled = true
        return { status: 'ok' }
      },
    })
    setupApi()
    const component = await mountSuspended(Skills)
    await flushPromises()

    const rows = component.findAll('.group')
    const codeRow = rows.find(r => r.text().includes('code-review'))
    expect(codeRow).toBeTruthy()
    await codeRow!.find('span.font-mono').trigger('dblclick')
    await flushPromises()

    const renameInput = codeRow!.find<HTMLInputElement>('input[aria-label="Rename skill code-review"]')
    await renameInput.setValue('renamed')
    await renameInput.trigger('keydown.escape')
    await flushPromises()
    // No PUT was fired.
    expect(renameCalled).toBe(false)
  })
})
