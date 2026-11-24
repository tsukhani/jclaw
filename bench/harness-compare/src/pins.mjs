// Hermes's catalog pins its web-ollama-cloud plugin to one commit of one repository, and the run installs it only
// when that exact pair is listed as reviewed. A listed line is `<40-hex commit> <repository URL> [date] [note]`.
//   node pins.mjs check <catalog.yaml> <approved-file>...        prints `<commit> <repository>`; exit 3 if not approved
//   node pins.mjs approve <catalog.yaml> <commit> <local-file>   adds the catalog's pair once it names that commit
// Exit 2 for a catalog entry without a full commit or a GitHub repository URL.
import fs from 'node:fs'
import { isMain } from './config.mjs'

export const COMMIT = /^[0-9a-f]{40}$/
export const REPO = /^https:\/\/github\.com\/[\w.-]+\/[\w.-]+$/

// The check reads the entry with a regex while Hermes reads it as YAML, so the entry must hold exactly one sha and one
// repo, both at the top level: with a duplicated or nested key the two parsers could each pick a different value.
export function readCatalog(text) {
  const field = (name) => {
    const all = [...text.matchAll(new RegExp(`^([ \\t]*)${name}[ \\t]*:[ \\t]*(.*)$`, 'gm'))]
    if (all.length !== 1 || all[0][1] !== '') return ''
    return all[0][2].trim().replace(/^(['"])(.*)\1$/, '$2')
  }
  return { commit: field('sha'), repo: field('repo') }
}

export function wellFormed({ commit, repo }) {
  return COMMIT.test(commit) && REPO.test(repo)
}

export function approvedPairs(texts) {
  return texts.flatMap(t => t.split('\n')).map(l => l.trim()).filter(l => l && !l.startsWith('#'))
    .map(l => { const [commit, repo] = l.split(/\s+/); return { commit, repo } })
}

export function isApproved(pin, pairs) {
  return wellFormed(pin) && pairs.some(p => p.commit === pin.commit && p.repo === pin.repo)
}

const readAll = (files) => files.filter(f => fs.existsSync(f)).map(f => fs.readFileSync(f, 'utf8'))

if (isMain(import.meta.url)) {
  const [cmd, catalogFile, ...rest] = process.argv.slice(2)
  const pin = readCatalog(fs.readFileSync(catalogFile, 'utf8'))
  if (!wellFormed(pin)) {
    console.error('the catalog entry needs exactly one top-level sha (a full commit) and one top-level repo (a GitHub repository URL)')
    process.exit(2)
  }
  if (cmd === 'approve') {
    const [commit, local] = rest
    if (commit !== pin.commit) { console.error(`the catalog pins ${pin.commit}, not ${commit}`); process.exit(2) }
    fs.appendFileSync(local, `${pin.commit} ${pin.repo} ${new Date().toISOString().slice(0, 10)} approved with --approve-hermes-plugin\n`, { mode: 0o600 })
  } else if (cmd === 'check') {
    const pairs = approvedPairs(readAll(rest))
    if (!isApproved(pin, pairs)) {
      const last = pairs.filter(p => p.repo === pin.repo).at(-1)
      console.error(`Hermes pins its web-ollama-cloud plugin to a commit not yet approved:\n  ${pin.repo} at ${pin.commit}`)
      if (last) console.error(`Review the change since the last approved commit:\n  ${pin.repo}/compare/${last.commit}...${pin.commit}`)
      else console.error('No commit of this repository has been approved; review it in full.')
      console.error(`then rerun with --approve-hermes-plugin ${pin.commit}`)
      process.exit(3)
    }
    console.log(`${pin.commit} ${pin.repo}`)
  } else {
    console.error(`unknown command: ${cmd ?? '(none)'}`)
    process.exit(2)
  }
}
