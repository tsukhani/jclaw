import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import { MANAGED_PREFIXES } from '~/components/settings/managed-prefixes'

// The unmanaged-config banner reports any Config DB row outside MANAGED_PREFIXES as a
// stale key. A key the UI itself saves must therefore sit under one of them, or the
// banner fires on every install where an operator has used that setting.

// Vitest runs from frontend/; under the nuxt environment import.meta.url is not a file URL.
const ROOT = process.cwd()
const SOURCE_DIRS = ['components', 'composables', 'layouts', 'pages']
const TOUCHES_CONFIG = /\/api\/config|useSettingsConfig|configKey|config-key/

// A template literal contributes its text before the first `${`, so `search.${id}.enabled` checks as `search.`.
const KEY_SHAPE = /^[a-z][a-zA-Z0-9_]*\.(?:[a-zA-Z_][\w.-]*)?$/

// Key-shaped literals in those files that are not config keys.
const NOT_CONFIG_KEYS = new Set<string>([
  // Provider signup-link labels
  'exa.ai', 'metadefender.opswat.com', 'openapi.felo.ai', 'tavily.com', 'virustotal.com',
  'llama.cpp', // a provider's display name
  'recording.wav', // the file name of an uploaded reference-voice clip
])

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap(e =>
    e.isDirectory() ? sourceFiles(join(dir, e.name)) : /\.(?:vue|ts)$/.test(e.name) ? [join(dir, e.name)] : [])
}

function literals(code: string): string[] {
  const out: string[] = []
  const visit = (node: ts.Node): void => {
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) out.push(node.text)
    else if (ts.isTemplateExpression(node)) out.push(node.head.text)
    ts.forEachChild(node, visit)
  }
  visit(ts.createSourceFile('source.ts', code, ts.ScriptTarget.Latest, false, ts.ScriptKind.TS))
  return out
}

function vueLiterals(sfc: string): string[] {
  const out = [...sfc.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)].flatMap(m => literals(m[1]!))
  const template = sfc.replace(/<script\b[\s\S]*?<\/script>|<style\b[\s\S]*?<\/style>|<!--[\s\S]*?-->/g, '')
  for (const [, name, value] of template.matchAll(/\s([^\s=<>"'/]+)="([^"]*)"/g)) {
    // A bound attribute holds an expression, where `otel.enabled` is a property read, not a key.
    out.push(...(/^(?:[:@#]|v-)/.test(name!) ? literals(value!) : [value!]))
  }
  for (const [, expression] of template.matchAll(/\{\{([\s\S]*?)\}\}/g)) out.push(...literals(expression!))
  return out
}

/** Key-shaped literal → the files naming it. */
function configKeyLiterals(): Map<string, Set<string>> {
  const found = new Map<string, Set<string>>()
  for (const file of SOURCE_DIRS.flatMap(d => sourceFiles(join(ROOT, d)))) {
    const text = readFileSync(file, 'utf8')
    if (!TOUCHES_CONFIG.test(text)) continue
    const keys = (file.endsWith('.vue') ? vueLiterals(text) : literals(text)).filter(s => KEY_SHAPE.test(s))
    // A relative name such as `actions.delete`, saved as `telegram.actions.delete`, is checked through the full key.
    const full = keys.filter(k => !keys.some(other => other.endsWith(`.${k}`)))
    for (const key of full) (found.get(key) ?? found.set(key, new Set()).get(key)!).add(relative(ROOT, file))
  }
  return found
}

describe('unmanaged-config banner — every key the UI saves is under a managed prefix', () => {
  const found = configKeyLiterals()

  it('finds the keys it is meant to check', () => {
    // A floor, so an extractor that silently stops matching fails here rather than passing on nothing.
    expect(found.size).toBeGreaterThan(100)
  })

  it('claims every key-shaped literal in a config-touching file', () => {
    const uncovered = [...found]
      .filter(([key]) => !NOT_CONFIG_KEYS.has(key) && !MANAGED_PREFIXES.some(p => key.startsWith(p)))
      .map(([key, files]) => `${key}  (${[...files].join(', ')})`)
    expect(uncovered, 'add the owning prefix to MANAGED_PREFIXES in components/settings/managed-prefixes.ts, '
    + 'or, if the literal is not a config key, to NOT_CONFIG_KEYS in this test').toEqual([])
  })

  it('lists no NOT_CONFIG_KEYS entry the source no longer contains', () => {
    expect([...NOT_CONFIG_KEYS].filter(k => !found.has(k))).toEqual([])
  })
})
