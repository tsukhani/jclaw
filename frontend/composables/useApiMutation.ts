/**
 * Composable for mutating API calls ($fetch POST/PUT/DELETE) with consistent
 * error handling and loading state. Replaces the scattered, inconsistent
 * try/catch patterns across pages.
 *
 * Usage:
 *   const { mutate, loading, error } = useApiMutation()
 *   await mutate('/api/agents/1', { method: 'DELETE' })
 */
export function useApiMutation() {
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function mutate<T = unknown>(
    url: string,
    opts: Parameters<typeof $fetch>[1] = {},
  ): Promise<T | null> {
    loading.value = true
    error.value = null
    try {
      const result = await $fetch<T>(url, opts)
      return result
    }
    catch (e: unknown) {
      const message = e instanceof Error ? e.message : 'Request failed'
      error.value = message
      console.error(`API mutation failed [${opts.method ?? 'GET'} ${url}]:`, message)
      return null
    }
    finally {
      loading.value = false
    }
  }

  return { mutate, loading: readonly(loading), error: readonly(error) }
}
