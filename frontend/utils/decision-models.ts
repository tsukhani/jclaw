/** The model names in `decision.ollama.models`, or [] when it is unset or not a JSON array of strings. */
export function parseDecisionModels(raw: string): string[] {
  if (!raw.trim()) return []
  try {
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? parsed.filter((m): m is string => typeof m === 'string' && m.trim() !== '') : []
  }
  catch {
    return []
  }
}
