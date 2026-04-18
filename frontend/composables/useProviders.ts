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

export interface Provider {
  name: string
  models: ProviderModel[]
}

export interface ConfigData {
  entries: { key: string, value: string }[]
}

/**
 * Extract configured providers and their models from config entries.
 * Filters out providers without a non-empty API key.
 */
export function useProviders(configData: Ref<ConfigData | null>) {
  const providers = computed<Provider[]>(() => {
    const entries = configData.value?.entries ?? []
    const providerMap = new Map<string, Provider>()

    for (const e of entries) {
      if (!e.key.startsWith('provider.')) continue
      const name = e.key.split('.')[1]
      if (!providerMap.has(name)) providerMap.set(name, { name, models: [] })
    }

    for (const e of entries) {
      if (e.key.endsWith('.apiKey') && e.key.startsWith('provider.')) {
        const name = e.key.split('.')[1]
        if (!e.value || e.value === '(empty)') providerMap.delete(name)
      }
    }

    for (const e of entries) {
      if (e.key.endsWith('.models') && e.key.startsWith('provider.')) {
        const name = e.key.split('.')[1]
        const provider = providerMap.get(name)
        if (provider) {
          try { provider.models = JSON.parse(e.value) }
          catch { provider.models = [] }
        }
      }
    }

    return Array.from(providerMap.values())
  })

  return { providers }
}
