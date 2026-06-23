import { describe, it, expect } from 'vitest'
import { hydrateToolCalls } from '~/utils/tool-calls'

/**
 * JCLAW-170: hydration fold for persisted tool activity. Asserts the three
 * invariants the chat page relies on when rendering a reloaded conversation:
 *
 *   - The intermediate assistant-with-toolCalls row loses its raw persisted
 *     array so the renderer doesn't double-render the same invocations.
 *   - Tool-role rows' text AND structured JSON payloads attach to the
 *     correct ToolCall (keyed by tool_call_id) — mismatched IDs drop.
 *   - The assembled ToolCall[] lands on the NEXT assistant row that has
 *     actual content (the final response), not on the intermediate row.
 */
describe('hydrateToolCalls', () => {
  it('folds tool-row results onto the next assistant content row', () => {
    const msgs = [
      { role: 'user', content: 'search please', id: 1 },
      {
        role: 'assistant',
        id: 2,
        content: null,
        toolCalls: [
          {
            id: 'call_a',
            type: 'function',
            function: { name: 'web_search', arguments: '{"query":"jclaw"}' },
            icon: 'search',
          },
        ],
      },
      {
        role: 'tool',
        id: 3,
        content: 'Found 2 results...',
        toolResults: 'call_a',
        toolResultStructured: {
          provider: 'Exa',
          results: [
            { title: 'JClaw', url: 'https://example.com', snippet: 'hi',
              faviconUrl: 'https://icons.duckduckgo.com/ip3/example.com.ico' },
          ],
        },
      },
      { role: 'assistant', id: 4, content: 'Here is what I found.' },
    ]

    hydrateToolCalls(msgs as unknown as Array<Record<string, unknown>>)

    // Intermediate row: raw array consumed.
    expect(msgs[1]!.toolCalls).toBeNull()

    // Final row: gets the normalized ToolCall[] with the structured payload.
    const final = msgs[3] as unknown as { toolCalls: Array<{
      id: string
      name: string
      icon: string
      resultText: string | null
      resultStructured: { provider?: string | null, results?: Array<{ title: string | null, url: string | null }> } | null
    }> }
    expect(final.toolCalls).toHaveLength(1)
    const first = final.toolCalls[0]!
    expect(first.id).toBe('call_a')
    expect(first.name).toBe('web_search')
    expect(first.icon).toBe('search')
    expect(first.resultText).toBe('Found 2 results...')
    expect(first.resultStructured?.results?.[0]?.url).toBe('https://example.com')
  })

  it('dangles pending calls on the last assistant row when the final content row has not arrived yet', () => {
    // Mid-turn reload scenario: the intermediate assistant-with-toolCalls row
    // and its matching tool-result row landed, but the final content row
    // didn't. The dangling ToolCall[] should still render somewhere so the
    // user sees the tool activity.
    const msgs = [
      { role: 'user', content: 'query', id: 1 },
      {
        role: 'assistant',
        id: 2,
        content: null,
        toolCalls: [{
          id: 'call_b',
          type: 'function',
          function: { name: 'web_search', arguments: '{"query":"x"}' },
          icon: 'search',
        }],
      },
      {
        role: 'tool', id: 3, content: 'result body', toolResults: 'call_b',
      },
    ]

    hydrateToolCalls(msgs as unknown as Array<Record<string, unknown>>)

    const intermediate = msgs[1] as unknown as { toolCalls: unknown }
    expect(Array.isArray(intermediate.toolCalls) && (intermediate.toolCalls as unknown[]).length > 0).toBe(true)
  })

  it('consolidates N intermediate assistant rows into one toolCalls list on the final content row', () => {
    // Production persists ONE assistant row per tool call (not one row per
    // LLM turn with an N-element array). A two-call turn therefore lands
    // as: user → asst(call_1) → tool(call_1) → asst(call_2) → tool(call_2)
    // → asst(content). Hydration must flatten all N calls onto the final
    // content row — not leave each intermediate row with its own one-call
    // rendering, which is what produced the "N × 1 tool call" regression.
    const msgs = [
      { role: 'user', content: 'do two searches', id: 1 },
      {
        role: 'assistant', id: 2, content: null,
        toolCalls: [{ id: 'call_1', type: 'function',
          function: { name: 'web_search', arguments: '{"query":"a"}' },
          icon: 'search' }],
      },
      { role: 'tool', id: 3, content: 'first result body', toolResults: 'call_1' },
      {
        role: 'assistant', id: 4, content: null,
        toolCalls: [{ id: 'call_2', type: 'function',
          function: { name: 'web_search', arguments: '{"query":"b"}' },
          icon: 'search' }],
      },
      { role: 'tool', id: 5, content: 'second result body', toolResults: 'call_2' },
      { role: 'assistant', id: 6, content: 'Here are both results.' },
    ]

    hydrateToolCalls(msgs as unknown as Array<Record<string, unknown>>)

    // Intermediate rows must be emptied so the renderer doesn't attach
    // per-row "1 tool call" cards to each of them.
    expect(msgs[1]!.toolCalls).toBeNull()
    expect(msgs[3]!.toolCalls).toBeNull()

    // Final row gets the combined 2-call array in declared order.
    const final = msgs[5] as unknown as { toolCalls: Array<{ id: string }> }
    expect(final.toolCalls).toHaveLength(2)
    expect(final.toolCalls[0]!.id).toBe('call_1')
    expect(final.toolCalls[1]!.id).toBe('call_2')
  })

  it('carries a generated-image attachment off the filtered tool-call row onto the rendering row', () => {
    // JCLAW-228: generate_image persists its produced image on the
    // intermediate (content-null) assistant row — which shouldDisplayMessage
    // filters out. Hydration must move the attachment forward onto the final
    // content row so the image renders on reload, not just live.
    const msgs = [
      { role: 'user', content: 'draw a clown', id: 1 },
      {
        role: 'assistant', id: 2, content: null,
        toolCalls: [{ id: 'call_g', type: 'function',
          function: { name: 'generate_image', arguments: '{"prompt":"a clown"}' },
          icon: 'photo' }],
        attachments: [{ uuid: 'u-1', kind: 'IMAGE', generated: true,
          originalFilename: 'generated-x.png', sizeBytes: 1024,
          generationMetadata: '{"prompt":"a clown"}' }],
      },
      { role: 'tool', id: 3, content: 'Generated an image…', toolResults: 'call_g' },
      { role: 'assistant', id: 4, content: 'Done — here it is.' },
    ]

    hydrateToolCalls(msgs as unknown as Array<Record<string, unknown>>)

    // Intermediate row: both raw toolCalls and the attachment are moved off it.
    expect(msgs[1]!.toolCalls).toBeNull()
    expect((msgs[1] as unknown as { attachments: unknown }).attachments).toBeNull()

    // Final (rendering) row: gets the carried-over attachment.
    const final = msgs[3] as unknown as { attachments: Array<{ uuid: string, generated: boolean }> }
    expect(final.attachments).toHaveLength(1)
    expect(final.attachments[0]!.uuid).toBe('u-1')
    expect(final.attachments[0]!.generated).toBe(true)
  })

  it('dangles a carried attachment on the last assistant row when the final content row is missing', () => {
    // Mid-turn reload: the content row has not landed, so the attachment must
    // still surface on the last assistant row rather than vanish.
    const msgs = [
      { role: 'user', content: 'draw', id: 1 },
      {
        role: 'assistant', id: 2, content: null,
        toolCalls: [{ id: 'call_h', type: 'function',
          function: { name: 'generate_image', arguments: '{"prompt":"a cat"}' },
          icon: 'photo' }],
        attachments: [{ uuid: 'u-2', kind: 'IMAGE', generated: true,
          originalFilename: 'generated-y.png', sizeBytes: 2048,
          generationMetadata: '{"prompt":"a cat"}' }],
      },
      { role: 'tool', id: 3, content: 'Generated an image…', toolResults: 'call_h' },
    ]

    hydrateToolCalls(msgs as unknown as Array<Record<string, unknown>>)

    const row = msgs[1] as unknown as { attachments: Array<{ uuid: string }> }
    expect(row.attachments).toHaveLength(1)
    expect(row.attachments[0]!.uuid).toBe('u-2')
  })

  it('falls back to wrench icon when the persisted entry has no icon hint', () => {
    // Older conversation rows predate JCLAW-170's icon enrichment at /messages
    // read-time (or the registry lost the tool). The hydrator must still
    // produce a valid ToolCall so the block renders.
    const msgs = [
      {
        role: 'assistant', id: 1, content: null,
        toolCalls: [{
          id: 'call_c', type: 'function',
          function: { name: 'mystery_tool', arguments: '{}' },
        }],
      },
      { role: 'assistant', id: 2, content: 'reply' },
    ]
    hydrateToolCalls(msgs as unknown as Array<Record<string, unknown>>)
    const final = msgs[1] as unknown as { toolCalls: Array<{ icon: string }> }
    expect(final.toolCalls[0]!.icon).toBe('wrench')
  })
})
