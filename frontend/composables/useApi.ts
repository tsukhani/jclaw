/**
 * Composable for making API calls to the Play backend.
 * All requests go through the /api/ proxy configured in nuxt.config.ts.
 */
export function useApi<T>(path: string) {
  return useFetch<T>(`/api${path}`)
}
