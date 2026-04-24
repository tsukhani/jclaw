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
