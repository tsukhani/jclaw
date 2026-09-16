/**
 * Composable for mutating API calls ($fetch POST/PUT/DELETE) with consistent
 * error handling and loading state. Replaces the scattered, inconsistent
 * try/catch patterns across pages.
 *
 * Usage:
 *   const { mutate, loading, error, errorDetails } = useApiMutation()
 *   await mutate('/api/agents/1', { method: 'DELETE' })
 */
import type { ApiErrorBody, ApiErrorDetails } from '~/types/api'

/**
 * Normalize a thrown `$fetch` error into the canonical envelope (JCLAW-1131). `$fetch`
 * surfaces the parsed body as `error.data`; a failure that never reached the server has none,
 * which is what leaves `code` and `template` null.
 *
 * @param fallback stands in for the raw HTTP status text `$fetch` throws when there is no
 *   envelope. Omit it to keep that text, which is all a transport failure has to say.
 */
export function apiErrorDetails(e: unknown, fallback?: string): ApiErrorDetails {
  const data = (e as { data?: Partial<ApiErrorBody> } | undefined)?.data
  return {
    code: data?.code ?? null,
    message: data?.message ?? fallback ?? (e instanceof Error ? e.message : 'Request failed'),
    template: data?.template ?? null,
  }
}

export function useApiMutation() {
  const loading = ref(false)
  const error = ref<string | null>(null)
  // A sibling of `error`, not a replacement: every consumer that destructures the string keeps
  // compiling, and only the surfaces that render the three parts read this one.
  const errorDetails = ref<ApiErrorDetails | null>(null)

  async function mutate<T = unknown>(
    url: string,
    opts: Parameters<typeof $fetch>[1] = {},
  ): Promise<T | null> {
    loading.value = true
    error.value = null
    errorDetails.value = null
    try {
      const result = await $fetch<T>(url, opts)
      return result
    }
    catch (e: unknown) {
      const details = apiErrorDetails(e)
      error.value = details.message
      errorDetails.value = details
      console.error(`API mutation failed [${opts.method ?? 'GET'} ${url}]:`, details.message)
      return null
    }
    finally {
      loading.value = false
    }
  }

  return {
    mutate,
    loading: readonly(loading),
    error: readonly(error),
    errorDetails: readonly(errorDetails),
  }
}
