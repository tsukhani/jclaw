// Resolves the version each harness runs at: its latest stable GitHub release unless one is given. A prerelease
// (OpenClaw's betas, Hermes's canaries) is never "latest" on GitHub, so it is never picked by default.
// Usage: node versions.mjs [--jclaw tag] [--openclaw tag|main] [--hermes tag|main]   prints JSON
import { parseArgs } from 'node:util'
import { isMain } from './config.mjs'

export const REPOS = { jclaw: 'tsukhani/jclaw', openclaw: 'openclaw/openclaw', hermes: 'NousResearch/hermes-agent' }

export async function latestRelease(repo) {
  const res = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
    headers: { accept: 'application/vnd.github+json', 'user-agent': 'harness-bench' }, signal: AbortSignal.timeout(20000),
  })
  if (!res.ok) throw new Error(`${repo}: latest release lookup -> ${res.status}`)
  const body = await res.json()
  return { tag: body.tag_name, published: body.published_at }
}

if (isMain(import.meta.url)) {
  const { values } = parseArgs({ options: { jclaw: { type: 'string' }, openclaw: { type: 'string' }, hermes: { type: 'string' } } })
  const out = {}
  for (const [h, repo] of Object.entries(REPOS)) out[h] = values[h] ? { tag: values[h] } : await latestRelease(repo)
  process.stdout.write(JSON.stringify(out) + '\n')
}
