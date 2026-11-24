// The plugin pin gate: only the exact reviewed commit of the exact reviewed repository passes.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { readCatalog, approvedPairs, isApproved } from '../src/pins.mjs'

const SHA = '9c739a9ea5985e0ba86c20d7160af06382c2198e'
const REPO = 'https://github.com/rriggs/hermes-plugin-ollama-cloud-search'
const reviewed = approvedPairs([`# comment\n${SHA} ${REPO} 2026-11-13 reviewed\n`])
const catalog = (sha, repo = REPO) => `name: web-ollama-cloud\nrepo: ${repo}\nsha: ${sha}\ntier: community\n`

test('reads the commit and repository from a catalog entry, quoted or not', () => {
  assert.deepEqual(readCatalog(catalog(SHA)), { commit: SHA, repo: REPO })
  assert.deepEqual(readCatalog(`repo: "${REPO}"\nsha: '${SHA}'\n`), { commit: SHA, repo: REPO })
  assert.deepEqual(readCatalog('name: x\n'), { commit: '', repo: '' })
})

test('reads no commit from an entry with a second or a nested sha, and no repository from one with a second repo', () => {
  assert.equal(readCatalog(catalog(SHA) + `sha: ${'a'.repeat(40)}\n`).commit, '')
  assert.equal(readCatalog(`repo: ${REPO}\nsource:\n  sha: ${SHA}\n`).commit, '')
  assert.equal(readCatalog(catalog(SHA) + 'repo: https://github.com/someone/fork\n').repo, '')
  assert.equal(readCatalog(catalog(SHA) + `sha  : ${SHA}\n`).commit, '')
})

test('approves only the exact reviewed pair', () => {
  const cases = [
    ['the reviewed commit and repository', SHA, REPO, true],
    ['an empty commit', '', REPO, false],
    ['a prefix of the reviewed commit', SHA.slice(0, 12), REPO, false],
    ['a pattern that matches any commit', '.'.repeat(40), REPO, false],
    ['an uppercase commit', SHA.toUpperCase(), REPO, false],
    ['the reviewed commit from another repository', SHA, 'https://github.com/someone/fork', false],
    ['another commit of the reviewed repository', 'a'.repeat(40), REPO, false],
  ]
  for (const [name, commit, repo, expected] of cases) assert.equal(isApproved({ commit, repo }, reviewed), expected, name)
})

test('the command refuses an unapproved pin with exit 3 and records an approval only for the pinned commit', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-pins-'))
  const cat = path.join(dir, 'web-ollama-cloud.yaml')
  const tracked = path.join(dir, 'tracked.txt')
  const local = path.join(dir, 'local.txt')
  const next = 'b'.repeat(40)
  fs.writeFileSync(tracked, `${SHA} ${REPO} 2026-11-13 reviewed\n`)
  fs.writeFileSync(cat, catalog(next))
  const run = (...args) => {
    try { return { code: 0, out: execFileSync(process.execPath, [new URL('../src/pins.mjs', import.meta.url).pathname, ...args], { stdio: 'pipe' }).toString().trim() } }
    catch (e) { return { code: e.status, err: e.stderr.toString() } }
  }
  const refused = run('check', cat, tracked, local)
  assert.equal(refused.code, 3)
  assert.match(refused.err, new RegExp(`${REPO}/compare/${SHA}\\.\\.\\.${next}`))
  assert.equal(run('approve', cat, SHA, local).code, 2)
  assert.equal(fs.existsSync(local), false)
  assert.equal(run('approve', cat, next, local).code, 0)
  assert.deepEqual(run('check', cat, tracked, local), { code: 0, out: `${next} ${REPO}` })
  fs.writeFileSync(cat, catalog(''))
  assert.equal(run('check', cat, tracked, local).code, 2)
})
