/**
 * Guards a read whose inputs can change while a response is in flight, so a slow older response
 * cannot overwrite a newer one. `begin()` marks a request; `isCurrent(token)` is false once a later
 * `begin()` ran, and the caller returns instead of assigning. Nuxt's `useFetch` already cancels a
 * superseded refresh, so this is for reads issued with a bare `$fetch`.
 */
export function useLatestRequest() {
  let latest = 0
  return {
    begin: (): number => ++latest,
    isCurrent: (token: number): boolean => token === latest,
  }
}
