import type { Ref } from 'vue'
import { ROUTER_MODEL_ID, ROUTER_PROVIDER } from '~/utils/model-route'

export interface ProviderModel {
  id: string
  name?: string
  supportsThinking?: boolean
  /** Reasoning-effort levels this model accepts, e.g. ["low","medium","high"]. */
  thinkingLevels?: string[]
  /**
   * Pure reasoning models (OpenAI o1/o3, DeepSeek-R1, Qwen QwQ) that have no
   * non-thinking mode. The provider API accepts a "reasoning off" value but
   * the model thinks anyway, so the toggle is rendered locked-on rather than
   * misleadingly lying to the operator. Implies {@link supportsThinking}.
   */
  alwaysThinks?: boolean
  /** True when the model accepts image inputs (vision). */
  supportsVision?: boolean
  /** True when the model accepts audio inputs. */
  supportsAudio?: boolean
  /** True when the model accepts native video inputs (Qwen-VL family). */
  supportsVideo?: boolean
  /**
   * Whether the model can call tools. Absent means unknown, which counts as
   * supported — only an authoritative negative is recorded (JCLAW-1074), so
   * read this through {@link modelSupportsTools} rather than truthiness.
   */
  supportsTools?: boolean
  /** Total context window size in tokens. 0/undefined when the provider did not advertise it. */
  contextWindow?: number
  [key: string]: unknown
}

/** Fallback thinking levels used when a thinking-capable model doesn't declare its own. Mirrors backend LlmTypes.DEFAULT_THINKING_LEVELS. */
export const DEFAULT_THINKING_LEVELS = ['low', 'medium', 'high'] as const

/** Resolve the effective thinking levels for a model, applying the default fallback. */
export function effectiveThinkingLevels(model: ProviderModel | null | undefined): string[] {
  if (!model) return []
  if (model.thinkingLevels?.length) return model.thinkingLevels
  return model.supportsThinking ? [...DEFAULT_THINKING_LEVELS] : []
}

/**
 * Whether a provider runs on the operator's own hardware — the "Local" subsection of
 * Settings → LLM Providers (JCLAW-182). Unknown providers default to remote.
 *
 * Declared identity, not something inferred from {@code baseUrl}: a remote provider can
 * perfectly well sit behind a loopback or LAN address (a local proxy or gateway fronting a
 * cloud API), so the host tells you where the socket goes, not where the model runs.
 *
 * Reads {@code provider.<name>.local}, the same key the backend's ProviderLocality reads to
 * decide whether memory embeddings and reranking may use a provider (JCLAW-1102). One key,
 * because it is one fact: hardware the operator controls both shows a real prefill window
 * and is somewhere memory text may go. A separate hardcoded list here would drift the moment
 * an operator reclassified a provider, leaving chat and Settings disagreeing silently.
 *
 * Gates the "Prefilling…" status label: prompt prefill / model load is only a long, visible
 * window on a local model — on a remote provider the pre-first-token gap is a sub-second
 * network/queue hop, not a prefill worth surfacing.
 */
export function isLocalProvider(
  name: string | null | undefined,
  configData: ConfigData | null | undefined,
): boolean {
  if (!name) return false
  const entry = (configData?.entries ?? []).find(e => e.key === `provider.${name}.local`)
  return (entry?.value ?? '').toLowerCase() === 'true'
}

/**
 * Whether a model can call tools. Unknown counts as supported, mirroring the
 * backend's {@code toolCallingSupported()}: a model discovered before the
 * capability existed carries no flag, and treating that as "no tools" would
 * wrongly tell the operator their agent is disarmed.
 */
export function modelSupportsTools(model: ProviderModel | null | undefined): boolean {
  return model?.supportsTools !== false
}

/**
 * True only when discovery carried an authoritative "this model cannot call
 * tools" — the sole case worth persisting.
 *
 * Split out of the Settings save mapping because the capabilities beside it all
 * follow `detected ? value : false`, and copying that shape here would write
 * `supportsTools: false` for every model on a provider that reports no
 * capabilities at all, disarming its agents (JCLAW-1074). Absence has to stay
 * absence.
 */
export function isDeclaredToolIncapable(
  discovered: Record<string, unknown> | null | undefined,
): boolean {
  return discovered?.toolsDetectedFromProvider === true && discovered.supportsTools === false
}

/** Find a model's metadata by provider name + model id, or null if not found. */
export function findProviderModel(
  providers: Provider[],
  providerName: string | null | undefined,
  modelId: string | null | undefined,
): ProviderModel | null {
  if (!providerName || !modelId) return null
  const provider = providers.find(p => p.name === providerName)
  return provider?.models.find(m => m.id === modelId) ?? null
}

export interface Provider {
  name: string
  models: ProviderModel[]
}

export interface ConfigData {
  entries: { key: string, value: string }[]
}

/**
 * Extract configured providers and their models from config entries.
 * Filters out:
 * - providers without a non-empty API key (unconfigured)
 * - providers explicitly marked {@code provider.NAME.enabled=false} (JCLAW-110)
 *
 * The enabled-flag filter matches the backend {@code TelegramModelSelector}'s
 * rule, so the web chat dropdown and the Telegram {@code /model} keyboard
 * show the same set of providers. Providers without the {@code .enabled}
 * key remain visible — the key is opt-in, written only when the user
 * toggles a provider off in Settings (or the load-test harness flips its
 * own reserved key).
 */
interface ConfigEntry { key: string, value: string }

/** Pull the provider name from a "provider.NAME.suffix" config key. Returns null when the key isn't a provider key. */
function providerNameFromKey(key: string): string | null {
  if (!key.startsWith('provider.')) return null
  return key.split('.')[1] ?? null
}

function seedProviders(entries: ConfigEntry[], providerMap: Map<string, Provider>): void {
  for (const e of entries) {
    const name = providerNameFromKey(e.key)
    if (name && !providerMap.has(name)) providerMap.set(name, { name, models: [] })
  }
}

function dropProvidersWithoutApiKey(entries: ConfigEntry[], providerMap: Map<string, Provider>): void {
  for (const e of entries) {
    if (!e.key.endsWith('.apiKey')) continue
    const name = providerNameFromKey(e.key)
    if (name && (!e.value || e.value === '(empty)')) providerMap.delete(name)
  }
}

// JCLAW-110: drop providers the user has explicitly disabled. Case-
// insensitive "false" match matches the backend filter exactly.
function dropDisabledProviders(entries: ConfigEntry[], providerMap: Map<string, Provider>): void {
  for (const e of entries) {
    if (!e.key.endsWith('.enabled')) continue
    const name = providerNameFromKey(e.key)
    if (name && e.value?.toLowerCase() === 'false') providerMap.delete(name)
  }
}

function attachProviderModels(entries: ConfigEntry[], providerMap: Map<string, Provider>): void {
  for (const e of entries) {
    if (!e.key.endsWith('.models')) continue
    const name = providerNameFromKey(e.key)
    if (!name) continue
    const provider = providerMap.get(name)
    if (!provider) continue
    try {
      provider.models = JSON.parse(e.value)
    }
    catch { provider.models = [] }
  }
}

const THINKING_LADDER = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max']

function ladderRank(level: string): number {
  const i = THINKING_LADDER.indexOf(level)
  return i >= 0 ? i : THINKING_LADDER.length
}

/**
 * The model router (JCLAW-1222) as one more provider with one virtual model, offered while
 * `router.chat.models` lists a model — the same rule the backend's RouterPolicy.available applies.
 * A capability is advertised when any listed model has it, mirroring ModelRouter.catalogModel:
 * the router steers a turn that needs it to a model that does.
 */
export function routerProvider(entries: ConfigEntry[], providers: Map<string, Provider>): Provider | null {
  const listed: { provider: string, model: string }[] = []
  let hasChat = false
  for (const e of entries) {
    const taskClass = /^router\.([a-z]+)\.models$/.exec(e.key)?.[1]
    if (!taskClass) continue
    let parsed: unknown
    try {
      parsed = JSON.parse(e.value)
    }
    catch {
      continue
    }
    if (!Array.isArray(parsed)) continue
    for (const c of parsed as { provider?: unknown, model?: unknown }[]) {
      if (typeof c?.provider !== 'string' || typeof c.model !== 'string') continue
      listed.push({ provider: c.provider, model: c.model })
      if (taskClass === 'chat') hasChat = true
    }
  }
  if (!hasChat) return null
  const models = listed
    .map(c => providers.get(c.provider)?.models.find(m => m.id === c.model))
    .filter((m): m is ProviderModel => !!m)
  const levels = new Set(models.flatMap(m => effectiveThinkingLevels(m)))
  return {
    name: ROUTER_PROVIDER,
    models: [{
      id: ROUTER_MODEL_ID,
      name: 'Auto (best value)',
      contextWindow: Math.max(0, ...models.map(m => m.contextWindow ?? 0)),
      supportsThinking: models.some(m => m.supportsThinking),
      thinkingLevels: [...levels].sort((a, b) => ladderRank(a) - ladderRank(b)),
      supportsVision: models.some(m => m.supportsVision),
      supportsAudio: models.some(m => m.supportsAudio),
      supportsVideo: models.some(m => m.supportsVideo),
    }],
  }
}

export function useProviders(configData: Ref<ConfigData | null>) {
  const providers = computed<Provider[]>(() => {
    const entries = configData.value?.entries ?? []
    const providerMap = new Map<string, Provider>()

    seedProviders(entries, providerMap)
    dropProvidersWithoutApiKey(entries, providerMap)
    dropDisabledProviders(entries, providerMap)
    attachProviderModels(entries, providerMap)

    const router = routerProvider(entries, providerMap)
    return router ? [...providerMap.values(), router] : Array.from(providerMap.values())
  })

  return { providers }
}
