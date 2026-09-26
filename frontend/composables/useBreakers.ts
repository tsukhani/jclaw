import type { Breaker } from '~/types/api'

const REFRESH_MS = 10_000

/**
 * The circuit-breaker registry, re-read every 10 s while the caller is mounted. Breakers are
 * minted on first use, so a provider or MCP server that was never called has no entry in `byName`.
 */
export function useBreakers() {
  const { data, refresh } = useLazyFetch<Breaker[]>('/api/breakers')

  let timer: ReturnType<typeof setInterval> | null = null
  onMounted(() => {
    timer = setInterval(() => refresh(), REFRESH_MS)
  })
  onBeforeUnmount(() => {
    if (timer) clearInterval(timer)
    timer = null
  })

  const breakers = computed(() => data.value ?? [])
  const byName = computed(() => new Map(breakers.value.map(b => [b.name, b])))
  return { breakers, byName, refresh }
}
