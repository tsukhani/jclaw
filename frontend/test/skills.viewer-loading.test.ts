import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import Skills from '~/pages/skills/[[name]].vue'
import type { SkillFilesResponse } from '~/types/api'

/**
 * The skill viewer paints its body in one go, once the skill's file listing has arrived. The Required Tools,
 * Commands and Context size cards sit above the Files panel and come with that listing, so a body painted
 * ahead of it shows the panel first and then pushes it down when the cards land: on prod this shifted the
 * panel 314 px on 4 of 15 loads of /skills/whatsapp-wacli-mac (CLS 0.1243).
 */

const LISTING: SkillFilesResponse = {
  files: [{ path: 'SKILL.md', isText: true, size: 900, chars: 900, estimatedTokens: 220 }],
  tools: [{ name: 'web_search' }],
  commands: ['wacli'],
  author: 'main',
}

function held() {
  let release: (value: SkillFilesResponse) => void = () => {}
  const promise = new Promise<SkillFilesResponse>((resolve) => {
    release = resolve
  })
  return { promise, release: (value: SkillFilesResponse) => release(value) }
}

function setupApi(files: Record<string, () => unknown>) {
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/skills', () => [
    { name: 'wa-skill', folderName: 'wa-skill', description: 'Messages', version: '1.0.0' },
    { name: 'other-skill', folderName: 'other-skill', description: 'Other', version: '1.0.0' },
  ])
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
  ])
  registerEndpoint('/api/agents/1/skills', () => [])
  for (const [name, handler] of Object.entries(files)) {
    registerEndpoint(`/api/skills/${name}/files`, handler)
    registerEndpoint(`/api/skills/${name}/files/SKILL.md`, () => ({ content: `# ${name}` }))
  }
}

enableAutoUnmount(afterEach)

// A released response needs macrotask turns of its own beyond the one that dispatched it.
async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

beforeEach(async () => {
  clearNuxtData()
  await useRouter().replace('/skills')
})

/** The viewer's section headings in page order: the cards, then the Files panel. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
const sections = (component: any): string[] =>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: test-utils wrapper.
  component.findAll('span.uppercase.tracking-wider').map((s: any) => s.text())

const ALL_SECTIONS = ['Required Tools', 'Commands', 'Context size', 'Files']

describe('Skills page — the viewer paints once its listing has arrived', () => {
  it('paints no part of the body, the Files panel included, until the listing lands, then all of it', async () => {
    const listing = held()
    setupApi({ 'wa-skill': () => listing.promise })

    const component = await mountSuspended(Skills, { route: '/skills/wa-skill' })
    await flushPromises()

    expect(component.text()).toContain('Back to skills')
    expect(sections(component)).toEqual([])
    expect(component.text()).not.toContain('0 files')

    listing.release(LISTING)
    await settle()

    expect(sections(component)).toEqual(ALL_SECTIONS)
    expect(component.text()).toContain('1 file')
  })

  it('opens an empty viewer, not a blank page, when the listing request fails', async () => {
    setupApi({
      'wa-skill': () => {
        throw createError({ statusCode: 500 })
      },
    })

    const component = await mountSuspended(Skills, { route: '/skills/wa-skill' })
    await flushPromises()

    expect(sections(component)).toEqual(['Files'])
    expect(component.text()).toContain('0 files')
  })

  it('never shows the previous skill\'s cards under the next skill\'s name while its listing loads', async () => {
    const next = held()
    setupApi({ 'wa-skill': () => LISTING, 'other-skill': () => next.promise })

    const component = await mountSuspended(Skills, { route: '/skills/wa-skill' })
    await flushPromises()
    expect(sections(component)).toEqual(ALL_SECTIONS)

    await useRouter().push('/skills/other-skill')
    await flushPromises()

    expect(sections(component)).toEqual([])

    next.release({ files: [{ path: 'SKILL.md', isText: true, size: 10 }], tools: [], commands: [], author: '' })
    await settle()

    expect(sections(component)).toEqual(['Files'])
  })
})
