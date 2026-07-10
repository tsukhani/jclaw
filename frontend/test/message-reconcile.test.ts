import { describe, it, expect } from 'vitest'
import type { Message } from '~/types/api'
import {
  collectLocalTakenIds,
  groupUnpairedServerIdsByRole,
  indexFreshById,
  backfillServerIds,
} from '~/utils/message-reconcile'

function m(over: Partial<Message>): Message {
  return { role: 'user', content: '', ...over } as Message
}

describe('collectLocalTakenIds', () => {
  it('collects only numeric ids', () => {
    const ids = collectLocalTakenIds([m({ id: 1 }), m({}), m({ id: 3 })])
    expect([...ids].sort()).toEqual([1, 3])
  })

  it('is empty when no local row has an id', () => {
    expect(collectLocalTakenIds([m({}), m({})]).size).toBe(0)
  })
})

describe('groupUnpairedServerIdsByRole', () => {
  it('groups server ids per role in encounter order, excluding already-taken ids', () => {
    const fresh = [
      m({ id: 10, role: 'user' }),
      m({ id: 11, role: 'assistant' }),
      m({ id: 12, role: 'user' }),
    ]
    const byRole = groupUnpairedServerIdsByRole(fresh, new Set([11]))
    expect(byRole.get('user')).toEqual([10, 12])
    expect(byRole.get('assistant')).toBeUndefined() // 11 already taken
  })

  it('skips rows lacking a numeric id or a role', () => {
    const fresh = [m({ role: 'user' }), m({ id: 5, role: undefined })] // no id / no role
    const byRole = groupUnpairedServerIdsByRole(fresh, new Set())
    expect(byRole.size).toBe(0)
  })
})

describe('indexFreshById', () => {
  it('maps numeric ids to their rows', () => {
    const a = m({ id: 1, role: 'user' })
    const idx = indexFreshById([a, m({})])
    expect(idx.get(1)).toBe(a)
    expect(idx.size).toBe(1)
  })
})

describe('backfillServerIds', () => {
  it('pairs id-less local rows with same-role server ids back-to-front', () => {
    const local = [m({ role: 'user' }), m({ role: 'assistant' })]
    const fresh = [m({ id: 100, role: 'user' }), m({ id: 101, role: 'assistant' })]
    const mutated = backfillServerIds(local, fresh)
    expect(mutated).toBe(true)
    expect(local[0]!.id).toBe(100)
    expect(local[1]!.id).toBe(101)
  })

  it('skips intermediate tool server rows the local list never tracked (JCLAW-270)', () => {
    // Local has just the user turn + final assistant reply; the server injected
    // an intermediate tool-role row in between. The role-stack walk must not
    // mis-pair the assistant bubble onto that intermediate row.
    const local = [m({ role: 'user' }), m({ role: 'assistant' })]
    const fresh = [
      m({ id: 100, role: 'user' }),
      m({ id: 101, role: 'tool' }),
      m({ id: 102, role: 'assistant' }),
    ]
    backfillServerIds(local, fresh)
    expect(local[0]!.id).toBe(100)
    expect(local[1]!.id).toBe(102) // paired to the assistant row, not the tool one
  })

  it('leaves a local row id-less when its role stack runs dry', () => {
    const local = [m({ role: 'assistant' }), m({ role: 'assistant' })]
    const fresh = [m({ id: 100, role: 'assistant' })]
    const mutated = backfillServerIds(local, fresh)
    expect(mutated).toBe(true)
    // Back-to-front: the last assistant row grabs the only id; the earlier one stays id-less.
    expect(local[1]!.id).toBe(100)
    expect(local[0]!.id).toBeUndefined()
  })

  it('does not re-steal a server id already present on a local row', () => {
    const local = [m({ id: 100, role: 'user' }), m({ role: 'user' })]
    const fresh = [m({ id: 100, role: 'user' }), m({ id: 101, role: 'user' })]
    backfillServerIds(local, fresh)
    expect(local[0]!.id).toBe(100) // unchanged
    expect(local[1]!.id).toBe(101) // gets the only unpaired id
  })

  it('copies the JCLAW-291 truncated flag from the paired server row', () => {
    const local = [m({ role: 'assistant' })]
    const fresh = [m({ id: 100, role: 'assistant', truncated: true } as Partial<Message>)]
    backfillServerIds(local, fresh)
    expect(local[0]!.id).toBe(100)
    expect(local[0]!.truncated).toBe(true)
  })

  it('returns false when there is nothing to pair', () => {
    const local = [m({ id: 1, role: 'user' })]
    const fresh = [m({ id: 1, role: 'user' })]
    expect(backfillServerIds(local, fresh)).toBe(false)
  })
})
