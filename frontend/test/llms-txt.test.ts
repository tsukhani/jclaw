import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { sections } from '~/components/guide/sections'
import { buildLlmsFull } from '../../bin/llms-full.mjs'

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

describe('llms-full.txt', () => {
  let dir: string

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'llms-full-'))
  })

  afterEach(() => {
    rmSync(dir, { recursive: true, force: true })
  })

  function write(files: Record<string, string>) {
    for (const [name, text] of Object.entries(files)) writeFileSync(join(dir, name), text)
  }

  it('is the index, then each page it lists in the index\'s order', () => {
    write({
      'llms.txt': '# Guide\n\n> Two pages.\n\n## Pages\n\n- [Second](b.md): listed first\n- [First](a.md)\n',
      'a.md': '# First\n\nA.\n',
      'b.md': '# Second\n\nB.\n',
    })
    expect(buildLlmsFull(dir)).toBe(
      '# Guide\n\n> Two pages.\n\n## Pages\n\n- [Second](b.md): listed first\n- [First](a.md)\n\n'
      + '---\n\nSource: b.md\n\n# Second\n\nB.\n\n'
      + '---\n\nSource: a.md\n\n# First\n\nA.\n',
    )
  })

  it('is the index alone when it lists no pages', () => {
    write({ 'llms.txt': '# Guide\n\n> Nothing yet.\n' })
    expect(buildLlmsFull(dir)).toBe('# Guide\n\n> Nothing yet.\n')
  })

  it('fails on a page the index lists but the folder lacks', () => {
    write({ 'llms.txt': '# Guide\n\n- [Gone](gone.md)\n' })
    expect(() => buildLlmsFull(dir)).toThrow(`ENOENT: no such file or directory, open '${join(dir, 'gone.md')}'`)
  })

  it('holds every guide section\'s text, in the guide\'s order', () => {
    const full = buildLlmsFull(GUIDE_DIR)
    const at = sections.map(s => full.indexOf(`Source: ${s.id}.md\n\n${s.content.trimEnd()}`))
    expect(sections.filter((_, i) => at[i] === -1).map(s => s.id)).toEqual([])
    expect(at).toEqual([...at].sort((x, y) => x - y))
  })
})
