import type { Ref } from 'vue'

export interface ProviderModel {
  id: string
  name?: string
  supportsThinking?: boolean
  [key: string]: unknown
}

export interface Provider {
  name: string
  models: ProviderModel[]
}

export interface ConfigData {
  entries: { key: string; value: string }[]
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
          try { provider.models = JSON.parse(e.value) } catch { provider.models = [] }
        }
      }
    }

    return Array.from(providerMap.values())
  })

  return { providers }
}
