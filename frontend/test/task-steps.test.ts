import { describe, it, expect } from 'vitest'
import { parseTaskSteps, serializeTaskSteps } from '~/utils/task-steps'

describe('parseTaskSteps (JCLAW-260 — mirrors backend TaskSteps.parse)', () => {
  it('plain text → single step', () => {
    expect(parseTaskSteps('Water the plants')).toEqual(['Water the plants'])
  })

  it('JSON array of strings → ordered steps', () => {
    expect(parseTaskSteps('["Fetch orders","Summarise","Post to Slack"]'))
      .toEqual(['Fetch orders', 'Summarise', 'Post to Slack'])
  })

  it('single-element array → one step', () => {
    expect(parseTaskSteps('["only step"]')).toEqual(['only step'])
  })

  it('null / blank → empty list', () => {
    expect(parseTaskSteps(null)).toEqual([])
    expect(parseTaskSteps('')).toEqual([])
    expect(parseTaskSteps('   ')).toEqual([])
  })

  it('non-string input → empty list', () => {
    expect(parseTaskSteps(undefined)).toEqual([])
    expect(parseTaskSteps(123)).toEqual([])
    expect(parseTaskSteps({ a: 1 })).toEqual([])
  })

  it('malformed array → single (raw) step', () => {
    expect(parseTaskSteps('[not valid json')).toEqual(['[not valid json'])
  })

  it('non-string array elements → single (raw) step', () => {
    expect(parseTaskSteps('["ok", 42, true]')).toEqual(['["ok", 42, true]'])
  })

  it('empty array → single (raw) step', () => {
    expect(parseTaskSteps('[]')).toEqual(['[]'])
  })
})

describe('serializeTaskSteps (JCLAW-22 — inverse of parseTaskSteps)', () => {
  it('multiple steps → JSON array string', () => {
    expect(serializeTaskSteps(['a', 'b', 'c'])).toBe('["a","b","c"]')
  })

  it('single step → plain text (not wrapped)', () => {
    expect(serializeTaskSteps(['just one'])).toBe('just one')
  })

  it('trims steps and drops blanks', () => {
    expect(serializeTaskSteps([' a ', '', '   ', 'b'])).toBe('["a","b"]')
  })

  it('all-empty → empty string', () => {
    expect(serializeTaskSteps([])).toBe('')
    expect(serializeTaskSteps(['', '  '])).toBe('')
  })

  it('round-trips with parseTaskSteps', () => {
    for (const steps of [['one'], ['a', 'b'], ['x', 'y', 'z']]) {
      expect(parseTaskSteps(serializeTaskSteps(steps))).toEqual(steps)
    }
  })
})
