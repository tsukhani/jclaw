// Settings every part of the benchmark reads. run.sh exports the environment; the defaults here are the ones
// JF-399 measured with.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const env = process.env
const num = (name, fallback) => (env[name] === undefined || env[name] === '' ? fallback : Number(env[name]))

export const HARNESSES = ['jclaw', 'openclaw', 'hermes']

export const config = {
  runDir: env.RUN_DIR ?? path.join(process.cwd(), 'run'),
  model: env.BENCH_MODEL ?? 'deepseek-v4.1-flash',
  contextWindow: num('BENCH_CONTEXT_WINDOW', 1048576),
  maxTokens: num('BENCH_MAX_TOKENS', 65535),
  upstream: env.UPSTREAM ?? 'https://ollama.com',
  portBase: num('PORT_BASE', 18100),
  jclawPort: num('JCLAW_PORT', 19100),
  openclawPort: num('OPENCLAW_PORT', 18789),
  hermesPort: num('HERMES_PORT', 8642),
  searchTool: env.BENCH_SEARCH_TOOL ?? 'web_search',
  quietMs: num('BENCH_QUIET_MS', 3000),
  bar: num('BENCH_BAR', 1.10),
}

export const proxyPort = (harness) => config.portBase + 1 + HARNESSES.indexOf(harness)
export const statusUrl = () => `http://127.0.0.1:${config.portBase}/`
export const secret = (name) => path.join(config.runDir, 'secrets', name)
export const now = () => performance.timeOrigin + performance.now()

// True when the module was started as a script, not imported (a symlinked path still compares equal).
export const isMain = (metaUrl) => !!process.argv[1] && fs.realpathSync(process.argv[1]) === fs.realpathSync(fileURLToPath(metaUrl))
