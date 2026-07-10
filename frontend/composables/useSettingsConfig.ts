import type { InjectionKey, Ref } from 'vue'
import type { ConfigResponse, ProviderModelDef } from '~/types/api'

/**
 * Shared /api/config store for the Settings page and its extracted panels
 * (JCLAW-680). The page owns the single awaited {@code useFetch('/api/config')}
 * — Nuxt dedupes by key, but rather than have each panel re-await it (which
 * would spawn a Suspense boundary per child), the page provides one reactive
 * context that panels {@link useSettingsConfig inject} synchronously.
 *
 * Exposes the two primitives every panel needs: {@code configValue(key)} to
 * read a stored row (with a fallback) and {@code saveField(key, value)} to
 * POST-then-refresh. {@code saving} is shared so the whole page still greys out
 * its inputs during a write, matching the pre-extraction monolith.
 */
export interface SettingsConfigContext {
  configData: Ref<ConfigResponse | null>
  refresh: () => Promise<void>
  saving: Ref<boolean>
  /** Value of the config row {@code key}, or {@code fallback} when absent. */
  configValue: (key: string, fallback?: string) => string
  /** POST {@code {key, value}} to /api/config then refresh the store. */
  saveField: (key: string, value: string) => Promise<void>
  /**
   * Parsed {@code provider.<name>.models} JSON, or [] when unset/invalid.
   * The LLM-provider model catalog is a pure read-projection of the config
   * store, so media panels (transcription diarization, captioning, video)
   * that need a provider's audio/vision/video models inject it from here
   * rather than each re-deriving it (JCLAW-680 second pass).
   */
  getProviderModels: (providerName: string) => ProviderModelDef[]
  /** True when {@code provider.<name>.apiKey} is set and non-blank. */
  apiKeyConfigured: (providerName: string) => boolean
}

export const settingsConfigKey: InjectionKey<SettingsConfigContext>
  = Symbol('settingsConfig')

/**
 * Page-level: create the shared config store and {@code provide} it to child
 * panels. Returns the context plus the {@code AsyncData} handle so the page can
 * {@code await} it (keeping the original single-Suspense load), and the raw
 * {@code configData}/{@code refresh}/{@code saving} refs the page still uses
 * directly for the panels not yet extracted.
 */
export function useProvideSettingsConfig() {
  const asyncConfig = useFetch<ConfigResponse>('/api/config')
  const saving = ref(false)

  function configValue(key: string, fallback = ''): string {
    return asyncConfig.data.value?.entries?.find(e => e.key === key)?.value ?? fallback
  }

  async function saveField(key: string, value: string): Promise<void> {
    saving.value = true
    try {
      await $fetch('/api/config', { method: 'POST', body: { key, value } })
      asyncConfig.refresh()
    }
    finally {
      saving.value = false
    }
  }

  function getProviderModels(providerName: string): ProviderModelDef[] {
    const modelsEntry = asyncConfig.data.value?.entries?.find(e => e.key === `provider.${providerName}.models`)
    if (!modelsEntry?.value) return []
    try {
      return JSON.parse(modelsEntry.value) as ProviderModelDef[]
    }
    catch { return [] }
  }

  function apiKeyConfigured(providerName: string): boolean {
    const v = asyncConfig.data.value?.entries?.find(e => e.key === `provider.${providerName}.apiKey`)?.value
    return !!v && v.trim().length > 0
  }

  const context: SettingsConfigContext = {
    configData: asyncConfig.data as Ref<ConfigResponse | null>,
    refresh: asyncConfig.refresh,
    saving,
    configValue,
    saveField,
    getProviderModels,
    apiKeyConfigured,
  }
  provide(settingsConfigKey, context)
  return { ...context, asyncConfig }
}

/** Panel-level: inject the shared config context provided by the page. */
export function useSettingsConfig(): SettingsConfigContext {
  const context = inject(settingsConfigKey)
  if (!context) {
    throw new Error('useSettingsConfig() must be used within a component that provides the settings config context')
  }
  return context
}
