import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { sections } from '~/components/guide/sections'

// Vitest runs from frontend/; under the nuxt environment import.meta.url is not a file URL.
const GUIDE_DIR = join(process.cwd(), '..', 'docs', 'user-guide')

function listedPages(): { title: string, url: string }[] {
  const text = readFileSync(join(GUIDE_DIR, 'llms.txt'), 'utf8')
  return [...text.matchAll(/^- \[([^\]]+)\]\(([^)]+)\)/gm)].map(m => ({ title: m[1]!, url: m[2]! }))
}

describe('llms.txt', () => {
  it('lists every guide section by its title, in the guide\'s order', () => {
    expect(listedPages()).toEqual(sections.map(s => ({ title: s.title, url: `${s.id}.md` })))
  })

  // llms.txt links a section by its id, so a section whose id is not its file name is a dead link.
  it('links each section to the file holding that section\'s text', () => {
    for (const s of sections) {
      expect(readFileSync(join(GUIDE_DIR, `${s.id}.md`), 'utf8')).toBe(s.content)
    }
  })
})
