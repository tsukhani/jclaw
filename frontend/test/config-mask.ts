import { isSensitiveKey } from '~/utils/secrets'

/**
 * `value` as GET /api/config serves it, masked as ConfigService.maskValue masks it: a secret that is set reads as
 * `****` alone, whatever its length, and an unset one as blank.
 */
export function maskedAsRead(key: string, value: string): string {
  return isSensitiveKey(key) && value !== '' ? '****' : value
}
