import type { ChecklistItem } from '~/types/api'

const STATUSES: ReadonlySet<string> = new Set(['pending', 'in_progress', 'completed'])

function isItem(value: unknown): value is ChecklistItem {
  if (typeof value !== 'object' || value === null) return false
  const item = value as Record<string, unknown>
  return typeof item.content === 'string' && typeof item.activeForm === 'string'
    && typeof item.status === 'string' && STATUSES.has(item.status)
}

/**
 * The list a `checklist` call set, or null for any other tool or a call the tool refused. Success is
 * matched on CheckListTool's own reply, since a refusal from the registry or the approval gate need not
 * start with "Error".
 */
export function checklistFromCall(name: string, args: string | undefined, resultText: string | null | undefined): ChecklistItem[] | null {
  if (name !== 'checklist' || !resultText?.startsWith('Checklist updated')) return null
  try {
    const items: unknown = JSON.parse(args ?? '').items
    return Array.isArray(items) ? items.filter(isItem) : null
  }
  catch {
    return null
  }
}

/** True while at least one step is unfinished, which is when the chat shows the list. */
export function hasOpenItem(items: readonly ChecklistItem[] | null | undefined): items is readonly ChecklistItem[] {
  return !!items?.some(item => item.status !== 'completed')
}
