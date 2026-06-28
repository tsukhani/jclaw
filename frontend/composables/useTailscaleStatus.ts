export interface TailscaleStatus {
  enabled: boolean
  available: boolean
  publicUrl: string | null
  error: string | null
}

/**
 * Lazily fetch the Tailscale Funnel status (GET /api/tailscale). The probe shells
 * out to `tailscale status` (~400ms), so it's loaded lazily — never blocking the
 * page render — and the channel pages (index, slack, telegram) share this one
 * definition instead of each repeating the fetch + interface.
 */
export function useTailscaleStatus() {
  return useFetch<TailscaleStatus>('/api/tailscale', { lazy: true })
}
