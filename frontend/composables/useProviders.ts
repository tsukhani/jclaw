import type { Ref } from 'vue'

export interface ProviderModel {
  id: string
  name?: string
  supportsThinking?: boolean
  /** Reasoning-effort levels this model accepts, e.g. ["low","medium","high"]. */
  thinkingLevels?: string[]
  /** True when the model accepts image inputs (vision). */
  supportsVision?: boolean
  /** True when the model accepts audio inputs. */
  supportsAudio?: boolean
  [key: string]: unknown
}

/** Fallback thinking levels used when a thinking-capable model doesn't declare its own. Mirrors backend LlmTypes.DEFAULT_THINKING_LEVELS. */
export const DEFAULT_THINKING_LEVELS = ['low', 'medium', 'high'] as const

/** Resolve the effective thinking levels for a model, applying the default fallback. */
export function effectiveThinkingLevels(model: ProviderModel | null | undefined): string[] {
  if (!model) return []
  if (model.thinkingLevels && model.thinkingLevels.length) return model.thinkingLevels
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
export function useProviders(configData: Ref<ConfigData | null>) {
  const providers = computed<Provider[]>(() => {
    const entries = configData.value?.entries ?? []
    const providerMap = new Map<string, Provider>()

    for (const e of entries) {
      if (!e.key.startsWith('provider.')) continue
      const name = e.key.split('.')[1]!
      if (!providerMap.has(name)) providerMap.set(name, { name, models: [] })
    }

    for (const e of entries) {
      if (e.key.endsWith('.apiKey') && e.key.startsWith('provider.')) {
        const name = e.key.split('.')[1]!
        if (!e.value || e.value === '(empty)') providerMap.delete(name)
      }
    }

    // JCLAW-110: drop providers the user has explicitly disabled. Case-
    // insensitive "false" match matches the backend filter exactly.
    for (const e of entries) {
      if (e.key.endsWith('.enabled') && e.key.startsWith('provider.')) {
        const name = e.key.split('.')[1]!
        if (e.value && e.value.toLowerCase() === 'false') providerMap.delete(name)
      }
    }

    for (const e of entries) {
      if (e.key.endsWith('.models') && e.key.startsWith('provider.')) {
        const name = e.key.split('.')[1]!
        const provider = providerMap.get(name)
        if (provider) {
          try {
            provider.models = JSON.parse(e.value)
          }
          catch { provider.models = [] }
        }
      }
    }

    return Array.from(providerMap.values())
  })

  return { providers }
}
