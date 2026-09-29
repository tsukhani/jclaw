/**
 * Mirrors ConfigService.isSensitive, which masks these keys on every read: the words of the last segment,
 * split on camelCase, _ and -, name a credential when the last word ends in key, keys or token, or any
 * word contains secret or password.
 */
export function isSensitiveKey(key: string): boolean {
  const words = key.slice(key.lastIndexOf('.') + 1)
    .split(/(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map(w => w.toLowerCase())
  const last = words.at(-1)
  if (!last) return false
  return last.endsWith('key') || last.endsWith('keys') || last.endsWith('token')
    || words.some(w => w.includes('secret') || w.includes('password'))
}
