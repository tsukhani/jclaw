import { describe, it, expect } from 'vitest'
import type { ChecklistItem } from '~/types/api'
import { checklistFromCall, hasOpenItem } from '~/utils/checklist'

const ITEMS: ChecklistItem[] = [
  { content: 'Run tests', status: 'completed', activeForm: 'Running tests' },
  { content: 'Fix the parser', status: 'in_progress', activeForm: 'Fixing the parser' },
]
const OK = 'Checklist updated successfully (2 items).'

describe('checklistFromCall', () => {
  it('returns the list a successful call set', () => {
    expect(checklistFromCall('checklist', JSON.stringify({ items: ITEMS }), OK)).toEqual(ITEMS)
  })

  it('ignores a refused call, another tool, and arguments that do not parse', () => {
    const args = JSON.stringify({ items: ITEMS })
    expect(checklistFromCall('checklist', args, 'Error: At most one item may be in_progress. Found 2.')).toBeNull()
    expect(checklistFromCall('checklist', args, 'Tool checklist is not enabled for this agent')).toBeNull()
    expect(checklistFromCall('web_search', args, OK)).toBeNull()
    expect(checklistFromCall('checklist', '{not json', OK)).toBeNull()
  })

  it('drops entries that are not steps', () => {
    const args = JSON.stringify({ items: [ITEMS[0], { content: 'x', status: 'later', activeForm: 'x' }, 'text'] })
    expect(checklistFromCall('checklist', args, OK)).toEqual([ITEMS[0]])
  })
})

describe('hasOpenItem', () => {
  it('is true while a step is unfinished', () => {
    expect(hasOpenItem(ITEMS)).toBe(true)
    expect(hasOpenItem([ITEMS[0]!])).toBe(false)
    expect(hasOpenItem([])).toBe(false)
    expect(hasOpenItem(null)).toBe(false)
  })
})
