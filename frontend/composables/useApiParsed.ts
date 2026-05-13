/**
 * JCLAW-287 Phase 1: schema-validated wrapper around Nuxt's auto-imported
 * {@code $fetch}. Use this at call sites that consume the high-risk shapes
 * defined in {@code types/schemas.ts}.
 *
 * Semantics:
 *  - Network/HTTP errors propagate unchanged from {@code $fetch}, so callers
 *    can keep their existing error handling (status codes, retry logic).
 *  - Parse failures throw a wrapped {@link SchemaParseError} carrying the
 *    request URL and the zod issue list, and are also logged via
 *    {@code console.error} for debugging in the dev tools console.
 */
import type { z } from 'zod'

/**
 * Thrown when {@link fetchParsed} successfully receives a response but the
 * payload fails schema validation. Distinct from network errors so callers
 * can branch ({@code instanceof}) — a schema break is a backend-contract
 * issue, not a transient failure to retry.
 */
export class SchemaParseError extends Error {
  readonly url: string
  readonly issues: z.core.$ZodIssue[]

  constructor(url: string, issues: z.core.$ZodIssue[]) {
    const detail = issues.map(i => `${i.path.join('.')}: ${i.message}`).join('; ')
    super(`Schema validation failed for ${url}: ${detail}`)
    this.name = 'SchemaParseError'
    this.url = url
    this.issues = issues
  }
}

/**
 * GET {@code url} with {@code $fetch}, then validate the response against
 * {@code schema}. On success, returns the parsed (and narrowed) value. On
 * schema failure, logs the zod issues to the console and throws a
 * {@link SchemaParseError}. Network and HTTP errors bubble through unchanged.
 */
export async function fetchParsed<T extends z.ZodTypeAny>(
  url: string,
  schema: T,
  opts?: Parameters<typeof $fetch>[1],
): Promise<z.infer<T>> {
  const raw = await $fetch(url, opts)
  const result = schema.safeParse(raw)
  if (!result.success) {
    console.error(`Schema validation failed for ${url}:`, result.error.issues)
    throw new SchemaParseError(url, result.error.issues)
  }
  return result.data
}
