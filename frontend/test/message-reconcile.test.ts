import { describe, it, expect } from 'vitest'
import type { Message, ToolCall } from '~/types/api'
import {
  collectLocalTakenIds,
  groupUnpairedServerIdsByRole,
  indexFreshById,
  backfillServerIds,
  reconcileWindow,
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

  it('gives a sent reply the stored text, quoted block included (JCLAW-1299)', () => {
    const local = [m({ role: 'user', content: 'which locker?', _quote: { kind: 'reminder', text: 'Collect the parcel' } })]
    const stored = '[Replying to a reminder]\n> Collect the parcel\n\nwhich locker?'
    backfillServerIds(local, [m({ id: 7, role: 'user', content: stored })])
    expect(local[0]!.content).toBe(stored)
    expect(local[0]!._quote).toBeUndefined()
  })

  it('leaves a row sent without a quote as typed', () => {
    const local = [m({ role: 'user', content: 'hi' })]
    backfillServerIds(local, [m({ id: 7, role: 'user', content: 'hi (server)' })])
    expect(local[0]!.content).toBe('hi')
  })
})

describe('reconcileWindow', () => {
  /** A saved row as the messages endpoint returns it, with a call in its persisted shape. */
  function callRow(id: number, callId: string, content: string | null = null): Message {
    return m({ id, role: 'assistant', content,
      toolCalls: [{ id: callId, type: 'function', function: { name: 'exec', arguments: '{}' } }] as unknown as ToolCall[] })
  }

  function resultRow(id: number, callId: string): Message {
    return m({ id, role: 'tool', content: `${callId} done`, toolResults: callId } as Partial<Message>)
  }

  function shownCall(callId: string, expanded: boolean): ToolCall {
    return { id: callId, name: 'exec', icon: 'wrench', arguments: '{}', resultText: `${callId} done`, resultStructured: null, _expanded: expanded }
  }

  /** Each row that holds tool calls, as its id and its calls' ids. */
  function owners(rows: Message[]) {
    return rows.filter(r => r.toolCalls?.length).map(r => [r.id, r.toolCalls!.map(tc => tc.id)])
  }

  it('gives a call the window hydrates onto one row to no other row', () => {
    const shown = [
      m({ id: 1, role: 'assistant', content: 'Starting.' }),
      m({ id: 2, role: 'assistant', content: null, toolCalls: [shownCall('a', true)], toolCallsCollapsed: true }),
      m({ id: 3, role: 'tool', content: 'a done' }),
    ]
    const fresh = [callRow(2, 'a'), resultRow(3, 'a'), m({ id: 4, role: 'assistant', content: 'Halfway.' }), callRow(5, 'b'), resultRow(6, 'b')]

    const merged = reconcileWindow(shown, fresh, true)

    expect(owners(merged.rows)).toEqual([[4, ['a']], [5, ['b']]])
    expect(merged.rows[0]).toBe(shown[1])
    expect(merged.rows[1]).toBe(shown[2])
    expect(shown[1]!.toolCalls).toEqual([])
    expect(merged.additions.map(r => r.id)).toEqual([4, 5, 6])
    expect(merged.changed).toBe(true)
  })

  it.each([
    { collapsed: false, state: 'open' },
    { collapsed: true, state: 'closed' },
  ])('leaves the row that takes the calls of a block left $state as it was, each call too, and a block that keeps its calls as it is', ({ collapsed }) => {
    const shown = [
      m({ id: 10, role: 'assistant', content: 'Done before.', toolCalls: [shownCall('x', true)], toolCallsCollapsed: false }),
      m({ id: 11, role: 'tool', content: 'x done' }),
      m({ id: 12, role: 'user', content: 'again' }),
      m({ id: 13, role: 'assistant', content: null, toolCalls: [shownCall('a', false)], toolCallsCollapsed: collapsed }),
      m({ id: 14, role: 'tool', content: 'a done' }),
    ]
    const fresh = [callRow(10, 'x', 'Done before.'), resultRow(11, 'x'), m({ id: 12, role: 'user', content: 'again' }),
      callRow(13, 'a'), resultRow(14, 'a'), callRow(15, 'b'), resultRow(16, 'b')]

    const merged = reconcileWindow(shown, fresh, true)

    expect(owners(merged.rows)).toEqual([[10, ['x']], [15, ['a', 'b']]])
    const taker = merged.rows.find(r => r.id === 15)!
    expect(taker.toolCallsCollapsed).toBe(collapsed)
    expect(taker.toolCalls!.map(tc => tc._expanded)).toEqual([false, true])
    expect(shown[0]!.toolCallsCollapsed).toBe(false)
    expect(shown[0]!.toolCalls!.map(tc => tc._expanded)).toEqual([true])
  })

  it('opens only the last call of a block whose calls moved through three rounds the operator left alone', () => {
    const server = [m({ id: 1, role: 'user', content: 'go' })]
    let shown: Message[] = []
    for (const [id, callId] of [[2, 'a'], [4, 'b'], [6, 'c']] as const) {
      server.push(callRow(id, callId), resultRow(id + 1, callId))
      shown = [...shown, ...reconcileWindow(shown, structuredClone(server), false).additions]
    }

    expect(shown.filter(r => r.toolCalls?.length).map(r => [r.id, r.toolCalls!.map(tc => `${tc.id}: ${tc._expanded}`)]))
      .toEqual([[6, ['a: false', 'b: false', 'c: true']]])
  })

  it('keeps the calls the operator opened and closed on a row that keeps its calls while a pending result lands', () => {
    const calls = [
      { id: 'a', type: 'function', function: { name: 'exec', arguments: '{}' } },
      { id: 'b', type: 'function', function: { name: 'exec', arguments: '{}' } },
    ] as unknown as ToolCall[]
    const owner = m({ id: 2, role: 'assistant', content: 'Running both.', toolCallsCollapsed: false,
      toolCalls: [shownCall('a', true), { ...shownCall('b', false), resultText: null }] })
    const shown = [m({ id: 1, role: 'user', content: 'go' }), owner, m({ id: 3, role: 'tool', content: 'a done' })]
    const fresh = [m({ id: 1, role: 'user', content: 'go' }), m({ id: 2, role: 'assistant', content: 'Running both.', toolCalls: calls }),
      resultRow(3, 'a'), resultRow(4, 'b')]

    const merged = reconcileWindow(shown, fresh, false)

    expect(owner.toolCalls!.map(tc => `${tc.id}: ${tc._expanded}, ${tc.resultText}`)).toEqual(['a: true, a done', 'b: false, b done'])
    expect(owner.toolCallsCollapsed).toBe(false)
    expect(merged.changed).toBe(true)
  })

  it('shows an attachment a merge gives a shown row on that row, once', () => {
    const image = { uuid: 'img-1', originalFilename: 'cat.png', mimeType: 'image/png', sizeBytes: 10, kind: 'IMAGE' as const, generated: true }
    const reply = m({ id: 4, role: 'assistant', content: 'Here it is.' })
    const shown = [m({ id: 1, role: 'user', content: 'draw a cat' }), reply]
    const fresh = [m({ id: 1, role: 'user', content: 'draw a cat' }), { ...callRow(2, 'a'), attachments: [image] }, resultRow(3, 'a'),
      m({ id: 4, role: 'assistant', content: 'Here it is.' })]

    const merged = reconcileWindow(shown, fresh, false)

    expect(merged.rows.filter(r => r.attachments?.length).map(r => [r.id, r.attachments!.map(a => a.uuid)])).toEqual([[4, ['img-1']]])
    expect(merged.rows[3]).toBe(reply)
    expect(merged.changed).toBe(true)
  })

  it('moves a shown row\'s calls to the newer row a whole window hangs them on', () => {
    const shown = [
      m({ id: 1, role: 'user', content: 'go' }),
      m({ id: 2, role: 'assistant', content: null, toolCalls: [shownCall('a', true)], toolCallsCollapsed: true }),
      m({ id: 3, role: 'tool', content: 'a done' }),
    ]
    const fresh = [m({ id: 1, role: 'user', content: 'go' }), callRow(2, 'a'), resultRow(3, 'a'), callRow(4, 'b'), resultRow(5, 'b')]

    const merged = reconcileWindow(shown, fresh, false)

    expect(owners(merged.rows)).toEqual([[4, ['a', 'b']]])
    expect(merged.additions.map(r => r.id)).toEqual([4, 5])
  })

  it('leaves a row with no server id alone, and changes nothing when the window holds what is shown', () => {
    const streaming = m({ _key: 'k', role: 'assistant', content: 'Working on it', toolCalls: [shownCall('s', true)] })
    const shown = [m({ id: 1, role: 'user', content: 'go' }), streaming]

    const merged = reconcileWindow(shown, [m({ id: 1, role: 'user', content: 'go' }), m({ role: 'assistant', content: 'unsaved' })], true)

    expect(merged.rows).toEqual([shown[0]])
    expect(merged.additions).toEqual([])
    expect(merged.changed).toBe(false)
    expect(streaming.toolCalls!.map(tc => tc.id)).toEqual(['s'])
  })
})
