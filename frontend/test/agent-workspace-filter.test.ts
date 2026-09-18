import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import AgentWorkspaceManager from '~/components/agents/AgentWorkspaceManager.vue'
import type { WorkspaceEntry, WorkspaceListing } from '~/types/api'
import { isTextFile, workspaceEntryStyle } from '~/utils/workspace-files'

/**
 * The filter bar narrows the tree to matching names with their ancestors kept and opened, and
 * every row is coloured by kind: folders, text files, binaries.
 */
function file(path: string, size = 10, isProtected = false): WorkspaceEntry {
  return { path, name: path.split('/').pop()!, kind: 'file', size, protected: isProtected, children: null }
}

function dir(path: string, children: WorkspaceEntry[]): WorkspaceEntry {
  return { path, name: path.split('/').pop()!, kind: 'dir', size: children.reduce((n, c) => n + c.size, 0), protected: false, children }
}

const LISTING: WorkspaceListing = {
  total: 60,
  entries: [
    dir('docs', [file('docs/guide.md'), file('docs/diagram.png'), dir('docs/deep', [file('docs/deep/guide-notes.txt')])]),
    file('archive.zip'),
    file('notes.md'),
    file('SOUL.md', 10, true),
  ],
}

async function settle() {
  for (let i = 0; i < 3; i++) await flushPromises()
}

function rowPaths(component: VueWrapper) {
  return component.findAll('[data-testid^="ws-row-"]').map(r => r.attributes('data-testid')!.replace('ws-row-', ''))
}

describe('workspace entry style', () => {
  it('reads text files by extension or well-known name, case-insensitively', () => {
    for (const n of ['notes.md', 'data.JSON', 'run.sh', '.gitignore', 'Makefile', 'README', 'app.vue']) {
      expect(isTextFile(n), n).toBe(true)
    }
    for (const n of ['photo.png', 'archive.zip', 'clip.mp4', 'noext', '.DS_Store']) {
      expect(isTextFile(n), n).toBe(false)
    }
    expect(workspaceEntryStyle('dir', 'anything.md')).toBe('dir')
    expect(workspaceEntryStyle('file', 'a.md')).toBe('text')
    expect(workspaceEntryStyle('file', 'a.bin')).toBe('binary')
  })
})

describe('AgentWorkspaceManager filter and colours', () => {
  beforeEach(() => {
    clearNuxtData()
    registerEndpoint('/api/agents/31/workspace-tree', () => LISTING)
  })

  it('colours folders, text files and binaries differently, with a distinct icon per kind', async () => {
    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 31 } })
    await settle()

    const folder = component.find('[data-testid="ws-row-docs"] [data-kind="dir"]')
    const text = component.find('[data-testid="ws-row-notes.md"] [data-kind="text"]')
    const binary = component.find('[data-testid="ws-row-archive.zip"] [data-kind="binary"]')
    expect(folder.classes().some(c => c.startsWith('text-sky-'))).toBe(true)
    expect(text.classes().some(c => c.startsWith('text-emerald-'))).toBe(true)
    expect(binary.classes().some(c => c.startsWith('text-amber-'))).toBe(true)
    // Protected files are still coloured by kind; the badge carries the protection.
    expect(component.find('[data-testid="ws-row-SOUL.md"] [data-kind="text"]').exists()).toBe(true)
    component.unmount()
  })

  it('narrows the tree to matching names and keeps their folders visible and open', async () => {
    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 31 } })
    await settle()
    expect(rowPaths(component)).toEqual(['docs', 'archive.zip', 'notes.md', 'SOUL.md'])

    await component.find('[data-testid="workspace-filter"]').setValue('guide')
    await settle()
    expect(rowPaths(component)).toEqual(['docs', 'docs/guide.md', 'docs/deep', 'docs/deep/guide-notes.txt'])
    expect(component.find('[data-testid="ws-row-docs"] button').attributes('aria-expanded')).toBe('true')

    await component.find('[data-testid="workspace-filter"]').setValue('ZIP')
    await settle()
    expect(rowPaths(component)).toEqual(['archive.zip'])

    await component.find('[data-testid="workspace-filter"]').setValue('nothing-here')
    await settle()
    expect(rowPaths(component)).toEqual([])
    expect(component.find('[data-testid="workspace-empty"]').text()).toBe('Nothing matches the filter.')

    await component.find('[data-testid="workspace-filter-clear"]').trigger('click')
    await settle()
    expect(rowPaths(component)).toEqual(['docs', 'archive.zip', 'notes.md', 'SOUL.md'])
    component.unmount()
  })
})
