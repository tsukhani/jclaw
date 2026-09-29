/** Mirrors ConfigService.isSensitive: the API masks these keys on every read. */
export function isSensitiveKey(key: string): boolean {
  const lower = key.toLowerCase()
  return ['key', 'secret', 'password', 'token'].some(s => lower.includes(s))
}
