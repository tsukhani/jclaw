/** One line of a diff: kept from both texts, only in the old one, or only in the new one. */
export interface DiffLine {
  kind: 'same' | 'removed' | 'added'
  text: string
}

// Persona files run to a few hundred lines; past this many table cells the diff shows every old line as removed and
// every new one as added rather than spend the memory.
const MAX_CELLS = 4_000_000

/**
 * A line diff of `before` to `after`, by longest common subsequence: each line in order, marked kept,
 * removed or added. A removal comes before the addition that replaces it.
 */
export function lineDiff(before: string, after: string): DiffLine[] {
  const a = before === '' ? [] : before.split('\n')
  const b = after === '' ? [] : after.split('\n')
  if (a.length * b.length > MAX_CELLS) {
    return [...a.map(text => ({ kind: 'removed' as const, text })), ...b.map(text => ({ kind: 'added' as const, text }))]
  }
  // common[i][j]: the longest common subsequence of a[i..] and b[j..].
  const common = Array.from({ length: a.length + 1 }, () => new Uint32Array(b.length + 1))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      common[i]![j] = a[i] === b[j] ? common[i + 1]![j + 1]! + 1 : Math.max(common[i + 1]![j]!, common[i]![j + 1]!)
    }
  }
  const lines: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      lines.push({ kind: 'same', text: a[i]! })
      i++
      j++
    }
    else if (common[i + 1]![j]! >= common[i]![j + 1]!) {
      lines.push({ kind: 'removed', text: a[i++]! })
    }
    else {
      lines.push({ kind: 'added', text: b[j++]! })
    }
  }
  while (i < a.length) lines.push({ kind: 'removed', text: a[i++]! })
  while (j < b.length) lines.push({ kind: 'added', text: b[j++]! })
  return lines
}
