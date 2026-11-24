import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { describe, it, expect } from 'vitest'
import { BUILT_HTML_REPORT_FILE, checkBuiltHtml, failureReason, findHtmlProblems, sharedFiles } from '../tools/built-html-check'

const HEAD = '<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>JClaw</title>'
const BODY = '</head><body><div id="__nuxt"></div><div id="teleports"></div>'
  + '<script type="application/json" id="__NUXT_DATA__">[]</script></body></html>'
const SHELL = HEAD + BODY
const PAGE = HEAD + '<link rel="modulepreload" as="script" crossorigin href="/_nuxt/DM7Abcrf.js">'.repeat(3) + BODY
// What a lost prerender race leaves: the shell written over the start of the longer page, whose tail survives.
const SPLICED = SHELL + PAGE.slice(SHELL.length)

function output(files: Record<string, string>): string {
  const dir = mkdtempSync(join(tmpdir(), 'built-html-'))
  for (const [name, text] of Object.entries(files)) {
    mkdirSync(dirname(join(dir, name)), { recursive: true })
    writeFileSync(join(dir, name), text)
  }
  return dir
}

/** Line 1 and the 1-based column of `needle` in the spliced tail; html-validate points at an end tag's `/` and at an id's value. */
function inTail(needle: string) {
  return { line: 1, column: SPLICED.indexOf(needle, SHELL.length) + 1 }
}

const RACE = 'Prerendered routes that share a file are written at the same moment, and the shorter write lands over the start '
  + 'of the longer one, whose tail survives. Skip all but one of them with nitro.prerender.ignore in nuxt.config.ts, '
  + 'as /index.html is skipped beside /.'

const SPLICED_PROBLEMS = [
  { file: 'index.html', line: 1, column: SHELL.length + 1, rule: 'one-document', message: 'holds 2 </html> end tags, so a second HTML document follows the first' },
  { file: 'index.html', ...inTail('/head>'), rule: 'close-order', message: 'Stray end tag \'</head>\'' },
  { file: 'index.html', ...inTail('__nuxt"'), rule: 'no-dup-id', message: 'Duplicate ID "__nuxt"' },
  { file: 'index.html', ...inTail('teleports"'), rule: 'no-dup-id', message: 'Duplicate ID "teleports"' },
  { file: 'index.html', ...inTail('__NUXT_DATA__"'), rule: 'no-dup-id', message: 'Duplicate ID "__NUXT_DATA__"' },
  { file: 'index.html', ...inTail('/html>'), rule: 'close-order', message: 'Stray end tag \'</html>\'' },
]

describe('findHtmlProblems', () => {
  it('finds nothing in an empty output', async () => {
    expect(await findHtmlProblems(output({}))).toEqual([])
  })

  it('passes pages that each hold one valid document, and reads no file that is not HTML', async () => {
    const dir = output({
      'index.html': SHELL,
      '200.html': SHELL,
      'settings/index.html': PAGE,
      '_nuxt/entry.js': `const page = '${SHELL}${SHELL}'`,
    })
    expect(await findHtmlProblems(dir)).toEqual([])
  })

  it('reports a page holding a second document after its first </html>, where the second begins and how it breaks the markup', async () => {
    expect(await findHtmlProblems(output({ 'index.html': SPLICED, '200.html': SHELL }))).toEqual(SPLICED_PROBLEMS)
  })

  it('counts every </html> past the first, on whatever line it falls', async () => {
    const three = `${SHELL}\n${SHELL}\n${SHELL}\n`
    const problems = await findHtmlProblems(output({ 'index.html': three }))
    expect(problems[0]).toEqual({
      file: 'index.html', line: 1, column: SHELL.length + 1, rule: 'one-document',
      message: 'holds 3 </html> end tags, so a second HTML document follows the first',
    })
  })

  it('reports a page that breaks the standard rules though it holds one document, naming it by its path in the output', async () => {
    const page = HEAD + BODY.replace('<div id="teleports">', '<div id="__nuxt">')
    expect(await findHtmlProblems(output({ 'index.html': SHELL, 'settings/index.html': page }))).toEqual([
      { file: 'settings/index.html', line: 1, column: page.lastIndexOf('__nuxt"') + 1, rule: 'no-dup-id', message: 'Duplicate ID "__nuxt"' },
    ])
  })
})

describe('sharedFiles', () => {
  it('reports each file two routes write, naming the routes', () => {
    expect(sharedFiles([
      { route: '/index.html', fileName: '/index.html' },
      { route: '/settings', fileName: '/settings/index.html' },
      { route: '/', fileName: '/index.html' },
      { route: '/200.html', fileName: '/200.html' },
    ])).toEqual([
      { file: 'index.html', rule: 'one-route-per-file', message: 'written by 2 prerendered routes at once: /, /index.html' },
    ])
  })

  it('passes routes that each write their own file, and a route that wrote none', () => {
    expect(sharedFiles([])).toEqual([])
    expect(sharedFiles([{ route: '/', fileName: '/index.html' }, { route: '/skipped' }, { route: '/also-skipped' }])).toEqual([])
  })
})

describe('failureReason', () => {
  const problem = (file: string, rule: string) => ({ file, line: 1, column: 1, rule, message: '' })

  it('names the race when two routes share a file, whether or not this build lost it', () => {
    expect(failureReason([problem('index.html', 'one-route-per-file'), problem('index.html', 'one-document')]))
      .toBe('the built web app failed its HTML check: index.html is written by two prerendered routes at once, which race each other')
    expect(failureReason([problem('index.html', 'one-route-per-file')]))
      .toBe('the built web app failed its HTML check: index.html is written by two prerendered routes at once, which race each other')
  })

  it('names the splice when a page holds two documents and no route list is known', () => {
    expect(failureReason([problem('index.html', 'one-document'), problem('index.html', 'close-order')]))
      .toBe('the built web app failed its HTML check: index.html holds more than one HTML document, from two writes landing at once')
  })

  it('names the rules a page breaks when no page holds two documents', () => {
    expect(failureReason([problem('settings/index.html', 'no-dup-id'), problem('settings/index.html', 'close-order'), problem('settings/index.html', 'no-dup-id')]))
      .toBe('the built web app failed its HTML check: settings/index.html breaks the HTML standard (no-dup-id, close-order)')
  })

  it('names two files, and counts the rest past the first', () => {
    expect(failureReason([problem('a.html', 'one-document'), problem('b.html', 'one-document')]))
      .toBe('the built web app failed its HTML check: a.html and b.html hold more than one HTML document, from two writes landing at once')
    expect(failureReason([problem('a.html', 'no-dup-id'), problem('b.html', 'no-dup-id'), problem('c.html', 'no-dup-id')]))
      .toBe('the built web app failed its HTML check: a.html and 2 other files break the HTML standard (no-dup-id)')
  })
})

describe('checkBuiltHtml', () => {
  it('fails the build naming each problem and why it happens, and leaves its reason for jclaw.sh', async () => {
    const dir = output({ 'index.html': SPLICED })
    const report = join(mkdtempSync(join(tmpdir(), 'report-')), 'cache', 'built-html-check.txt')

    await expect(checkBuiltHtml(dir, report)).rejects.toThrow(new Error([
      'The built web app failed its HTML check, so the build stopped before anything could serve it:',
      ...SPLICED_PROBLEMS.map(p => `  ${p.file}:${p.line}:${p.column}: ${p.message} [${p.rule}]`),
      RACE,
      'html-validate explains each of its rules at https://html-validate.org/rules/.',
    ].join('\n')))
    expect(readFileSync(report, 'utf8')).toBe(`${failureReason(SPLICED_PROBLEMS)}\n`)
  })

  it('fails a build whose routes share a file even when the last write left it whole', async () => {
    const dir = output({ 'index.html': PAGE })
    const report = join(dir, 'report.txt')

    await expect(checkBuiltHtml(dir, report, [{ route: '/', fileName: '/index.html' }, { route: '/index.html', fileName: '/index.html' }]))
      .rejects.toThrow(new Error([
        'The built web app failed its HTML check, so the build stopped before anything could serve it:',
        '  index.html: written by 2 prerendered routes at once: /, /index.html [one-route-per-file]',
        RACE,
      ].join('\n')))
    expect(readFileSync(report, 'utf8'))
      .toBe('the built web app failed its HTML check: index.html is written by two prerendered routes at once, which race each other\n')
  })

  it('leaves out the race when no page holds two documents', async () => {
    const page = HEAD + BODY.replace('<div id="teleports">', '<div id="__nuxt">')
    const dir = output({ 'index.html': page })
    const column = page.lastIndexOf('__nuxt"') + 1

    await expect(checkBuiltHtml(dir, join(dir, 'report.txt'))).rejects.toThrow(new Error([
      'The built web app failed its HTML check, so the build stopped before anything could serve it:',
      `  index.html:1:${column}: Duplicate ID "__nuxt" [no-dup-id]`,
      'html-validate explains each of its rules at https://html-validate.org/rules/.',
    ].join('\n')))
  })

  it('passes a clean build and removes the reason an earlier failed check left', async () => {
    const dir = output({ 'index.html': PAGE, 'settings/index.html': PAGE })
    const report = join(dir, 'report.txt')
    writeFileSync(report, 'an earlier build\'s reason\n')

    await expect(checkBuiltHtml(dir, report, [{ route: '/', fileName: '/index.html' }, { route: '/settings', fileName: '/settings/index.html' }]))
      .resolves.toBeUndefined()
    expect(existsSync(report)).toBe(false)
  })

  it('leaves its reason where jclaw.sh reads it', () => {
    const script = readFileSync(join(import.meta.dirname, '..', '..', 'jclaw.sh'), 'utf8')
    expect(script).toContain(`"$SCRIPT_DIR/frontend/${BUILT_HTML_REPORT_FILE}"`)
  })
})
