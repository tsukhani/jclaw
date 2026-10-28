import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import Skills from '~/pages/skills/[[name]].vue'
import type { SkillFile } from '~/types/api'

/** The skill detail view shows what SKILL.md and each reference file cost a conversation once read. */

function setupApi(files: SkillFile[]) {
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/skills', () => [
    { name: 'big-skill', folderName: 'big-skill', description: 'A large skill', version: '1.0.0' },
  ])
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
  ])
  registerEndpoint('/api/agents/1/skills', () => [])
  registerEndpoint('/api/skills/big-skill/files', () => ({ files, tools: [], commands: [], author: '' }))
  registerEndpoint('/api/skills/big-skill/files/SKILL.md', () => ({ content: '# big-skill' }))
}

enableAutoUnmount(afterEach)

beforeEach(async () => {
  clearNuxtData()
  await useRouter().replace('/skills')
})

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
const contextRows = (component: any): string[] =>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: test-utils wrapper.
  component.findAll('tbody tr').map((row: any) => row.findAll('td')
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: test-utils wrapper.
    .map((cell: any) => cell.text().replace(/\s+/g, ' ').trim()).join(' '))

describe('Skills page — context size of a skill\'s files', () => {
  it('lists SKILL.md first, then each reference, with characters and estimated tokens', async () => {
    setupApi([
      { path: 'SKILL.md', isText: true, size: 21_100, chars: 21_000, estimatedTokens: 5_200 },
      { path: 'references/zeta.md', isText: true, size: 4_000, chars: 4_000, estimatedTokens: 950 },
      { path: 'references/alpha.md', isText: true, size: 1_000, chars: 1_000, estimatedTokens: 250 },
      { path: 'tools/README.md', isText: true, size: 20 },
    ])

    const component = await mountSuspended(Skills, { route: '/skills/big-skill' })
    await flushPromises()

    expect(component.text()).toContain('Context size')
    expect(contextRows(component)).toEqual([
      'SKILL.md over the 20,000-character limit 21,000 ~5,200',
      'references/alpha.md 1,000 ~250',
      'references/zeta.md 4,000 ~950',
    ])
  })

  it('shows no limit note for a SKILL.md within it, and no panel for a listing without counts', async () => {
    setupApi([{ path: 'SKILL.md', isText: true, size: 9_000, chars: 9_000, estimatedTokens: 2_100 }])
    const within = await mountSuspended(Skills, { route: '/skills/big-skill' })
    await flushPromises()

    expect(contextRows(within)).toEqual(['SKILL.md 9,000 ~2,100'])
    within.unmount()

    clearNuxtData()
    await useRouter().replace('/skills')
    setupApi([{ path: 'SKILL.md', isText: true, size: 9_000 }])
    const uncounted = await mountSuspended(Skills, { route: '/skills/big-skill' })
    await flushPromises()

    expect(uncounted.text()).not.toContain('Context size')
  })
})
