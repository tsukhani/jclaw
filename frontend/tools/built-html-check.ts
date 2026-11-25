import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join, relative } from 'node:path'
import { HtmlValidate, StaticConfigLoader } from 'html-validate'

/** Where a failed check leaves its one-line reason; jclaw.sh shows it as why a restart stopped. */
export const BUILT_HTML_REPORT_FILE = 'node_modules/.cache/jclaw/built-html-check.txt'

export interface HtmlProblem {
  /** The file's path under the build output, with `/` separators. */
  file: string
  line?: number
  column?: number
  rule: string
  message: string
}

/** As much of Nitro's prerendered route as the check reads. */
export interface PrerenderedRoute {
  route: string
  fileName?: string
}

const ONE_ROUTE_PER_FILE = 'one-route-per-file'
const ONE_DOCUMENT = 'one-document'
const ERROR = 2

// Spec conformance only: the recommended and document presets add style and SRI rules Nuxt's own markup breaks.
const validator = new HtmlValidate(new StaticConfigLoader({ extends: ['html-validate:standard'] }))

// Code-unit order, not localeCompare: the report must list files the same way whatever the machine's locale.
const byCodeUnit = (a: string, b: string): number => Number(a > b) - Number(a < b)

function locate(text: string, offset: number): { line: number, column: number } {
  const before = text.slice(0, offset)
  return { line: before.split('\n').length, column: offset - before.lastIndexOf('\n') }
}

/** Each file more than one route wrote: Nitro writes them at the same moment, so whichever lands last decides its bytes. */
export function sharedFiles(routes: PrerenderedRoute[]): HtmlProblem[] {
  const writers = new Map<string, string[]>()
  for (const { route, fileName } of routes) {
    if (!fileName) continue
    const file = fileName.replace(/^\/+/, '')
    writers.set(file, [...writers.get(file) ?? [], route])
  }
  return [...writers]
    .filter(([, by]) => by.length > 1)
    .sort(([a], [b]) => byCodeUnit(a, b))
    .map(([file, by]) => ({
      file,
      rule: ONE_ROUTE_PER_FILE,
      message: `written by ${by.length} prerendered routes at once: ${by.toSorted(byCodeUnit).join(', ')}`,
    }))
}

/** Every page in `publicDir` that holds more than one document, or breaks html-validate's standard rules. */
export async function findHtmlProblems(publicDir: string): Promise<HtmlProblem[]> {
  const files = (await readdir(publicDir, { recursive: true, withFileTypes: true }))
    .filter(entry => entry.isFile() && entry.name.endsWith('.html'))
    .map(entry => relative(publicDir, join(entry.parentPath, entry.name)).replaceAll('\\', '/'))
    .sort(byCodeUnit)
  const problems: HtmlProblem[] = []
  // One page at a time: validation is CPU work on this thread, so checking the 24 pages together saved 4 ms of 50 (2026-11-25).
  for (const file of files) {
    const html = await readFile(join(publicDir, file), 'utf8')
    // HTML parsing folds content after </html> back into the body, so the validator alone passes a tail with no duplicate id.
    const ends = [...html.matchAll(/<\/html\s*>/gi)]
    if (ends.length > 1) {
      const first = ends[0]!
      problems.push({
        file,
        ...locate(html, first.index + first[0].length),
        rule: ONE_DOCUMENT,
        message: `holds ${ends.length} </html> end tags, so a second HTML document follows the first`,
      })
    }
    const report = await validator.validateString(html, file)
    for (const m of report.results.flatMap(result => result.messages)) {
      if (m.severity === ERROR) problems.push({ file, line: m.line, column: m.column, rule: m.ruleId, message: m.message })
    }
  }
  return problems
}

function unique(values: string[]): string[] {
  return [...new Set(values)]
}

function namesOf(files: string[]): string {
  if (files.length === 1) return files[0]!
  if (files.length === 2) return `${files[0]} and ${files[1]}`
  return `${files[0]} and ${files.length - 1} other files`
}

function filesWith(problems: HtmlProblem[], rule: string): string[] {
  return unique(problems.filter(p => p.rule === rule).map(p => p.file))
}

/** One clause, to complete jclaw.sh's "The restart failed: …". */
export function failureReason(problems: HtmlProblem[]): string {
  const prefix = 'the built web app failed its HTML check: '
  const shared = filesWith(problems, ONE_ROUTE_PER_FILE)
  if (shared.length > 0) {
    return `${prefix}${namesOf(shared)} ${shared.length === 1 ? 'is' : 'are'} written by two prerendered routes at once, which race each other`
  }
  const spliced = filesWith(problems, ONE_DOCUMENT)
  if (spliced.length > 0) {
    return `${prefix}${namesOf(spliced)} ${spliced.length === 1 ? 'holds' : 'hold'} more than one HTML document, from two writes landing at once`
  }
  const broken = unique(problems.map(p => p.file))
  return `${prefix}${namesOf(broken)} ${broken.length === 1 ? 'breaks' : 'break'} the HTML standard (${unique(problems.map(p => p.rule)).join(', ')})`
}

function failureMessage(problems: HtmlProblem[]): string {
  const lines = [
    'The built web app failed its HTML check, so the build stopped before anything could serve it:',
    ...problems.map((p) => {
      const at = p.line ? `:${p.line}:${p.column}` : ''
      return `  ${p.file}${at}: ${p.message} [${p.rule}]`
    }),
  ]
  if (problems.some(p => p.rule === ONE_ROUTE_PER_FILE || p.rule === ONE_DOCUMENT)) {
    lines.push('Prerendered routes that share a file are written at the same moment, and the shorter write lands over the start '
      + 'of the longer one, whose tail survives. Skip all but one of them with nitro.prerender.ignore in nuxt.config.ts, '
      + 'as /index.html is skipped beside /.')
  }
  if (problems.some(p => p.rule !== ONE_ROUTE_PER_FILE && p.rule !== ONE_DOCUMENT)) {
    lines.push('html-validate explains each of its rules at https://html-validate.org/rules/.')
  }
  return lines.join('\n')
}

/**
 * Throws, naming every problem, when two `routes` wrote one file or a page in `publicDir` fails the check, and
 * leaves the reason in `reportFile`.
 */
export async function checkBuiltHtml(publicDir: string, reportFile: string, routes: PrerenderedRoute[] = []): Promise<void> {
  await rm(reportFile, { force: true })
  const problems = [...sharedFiles(routes), ...await findHtmlProblems(publicDir)]
  if (problems.length === 0) return
  await mkdir(dirname(reportFile), { recursive: true })
  await writeFile(reportFile, `${failureReason(problems)}\n`)
  throw new Error(failureMessage(problems))
}
