import type { Ref } from 'vue'

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
    if (name && e.value && e.value.toLowerCase() === 'false') providerMap.delete(name)
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

export function useProviders(configData: Ref<ConfigData | null>) {
  const providers = computed<Provider[]>(() => {
    const entries = configData.value?.entries ?? []
    const providerMap = new Map<string, Provider>()

    seedProviders(entries, providerMap)
    dropProvidersWithoutApiKey(entries, providerMap)
    dropDisabledProviders(entries, providerMap)
    attachProviderModels(entries, providerMap)

    return Array.from(providerMap.values())
  })

  return { providers }
}
