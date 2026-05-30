/**
 * JCLAW-260 / JCLAW-22: parse a Task's `description` into its ordered steps —
 * the frontend twin of the backend `services.TaskSteps.parse`. A value that
 * is a JSON array of strings yields those steps in order; anything else
 * (plain text, malformed JSON, a non-string or empty array) yields a single
 * step holding the raw text. `null`, non-strings, and blank input yield an
 * empty list.
 *
 * Keep this in lockstep with the backend parser so the admin UI shows exactly
 * the steps the agent will receive at fire time.
 */
export function parseTaskSteps(description: unknown): string[] {
  if (typeof description !== 'string') return []
  const trimmed = description.trim()
  if (!trimmed) return []
  if (trimmed.startsWith('[')) {
    try {
      const parsed: unknown = JSON.parse(trimmed)
      if (
        Array.isArray(parsed)
        && parsed.length > 0
        && parsed.every(s => typeof s === 'string')
      ) {
        return parsed as string[]
      }
    }
    catch {
      // Looked like an array but wasn't valid JSON — fall through to plain text.
    }
  }
  return [description]
}

/**
 * JCLAW-22 (slice E): inverse of {@link parseTaskSteps}. Serialise an edited
 * step list back into the `description` string that gets PATCHed to the API.
 * Steps are trimmed and empties dropped; a single step is stored as plain
 * text and multiple steps as a JSON array — the canonical shapes the backend
 * `TaskSteps.parse` and the frontend `parseTaskSteps` both understand. An
 * all-empty edit collapses to "" (no instructions).
 *
 * Round-trips with {@link parseTaskSteps}: parse(serialize(steps)) === steps
 * for any list of non-blank steps.
 */
export function serializeTaskSteps(steps: string[]): string {
  const cleaned = steps.map(s => s.trim()).filter(s => s.length > 0)
  if (cleaned.length === 0) return ''
  if (cleaned.length === 1) return cleaned[0] ?? ''
  return JSON.stringify(cleaned)
}
