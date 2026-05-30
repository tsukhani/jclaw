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
