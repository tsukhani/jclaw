/**
 * JCLAW-287: tests for the runtime zod schemas in {@code types/schemas.ts}.
 * Covers the three guarantees the schemas are responsible for: a known-good
 * payload parses cleanly, a missing required field rejects with a path that
 * names the offending key, and the structured tool-result accepts the
 * provider-specific extras we want to leave room for.
 */
import { describe, it, expect } from 'vitest'
import {
  MessageSchema,
  ConversationSchema,
  ToolCallSchema,
  ToolCallResultStructuredSchema,
} from '~/types/schemas'

describe('MessageSchema', () => {
  it('accepts a valid assistant message with tool calls and attachments', () => {
    const result = MessageSchema.safeParse({
      id: 42,
      role: 'assistant',
      content: 'Hello',
      createdAt: '2026-05-12T00:00:00Z',
      toolCalls: [
        {
          id: 'tc_1',
          name: 'web_search',
          icon: 'search',
          arguments: '{"q":"foo"}',
          resultText: 'ok',
        },
      ],
      attachments: [
        {
          uuid: 'abc',
          originalFilename: 'cat.png',
          mimeType: 'image/png',
          sizeBytes: 1234,
          kind: 'IMAGE',
        },
      ],
    })
    expect(result.success).toBe(true)
  })

  it('accepts a minimal user message with null content', () => {
    const result = MessageSchema.safeParse({
      role: 'user',
      content: null,
      createdAt: '2026-05-12T00:00:00Z',
    })
    expect(result.success).toBe(true)
  })

  it('rejects a message missing the required role field', () => {
    const result = MessageSchema.safeParse({
      content: 'orphaned',
      createdAt: '2026-05-12T00:00:00Z',
    })
    expect(result.success).toBe(false)
    if (!result.success) {
      const paths = result.error.issues.map(i => i.path.join('.'))
      expect(paths).toContain('role')
    }
  })

  it('rejects a message whose role is not one of user/assistant/tool', () => {
    const result = MessageSchema.safeParse({
      role: 'system',
      content: 'nope',
      createdAt: '2026-05-12T00:00:00Z',
    })
    expect(result.success).toBe(false)
    if (!result.success) {
      const paths = result.error.issues.map(i => i.path.join('.'))
      expect(paths).toContain('role')
    }
  })
})

describe('ConversationSchema', () => {
  it('accepts a valid conversation row', () => {
    const result = ConversationSchema.safeParse({
      id: 1,
      preview: 'hi',
      channelType: 'web',
      agentName: 'Alice',
      peerId: null,
      messageCount: 3,
      createdAt: '2026-05-12T00:00:00Z',
      updatedAt: '2026-05-12T00:00:01Z',
    })
    expect(result.success).toBe(true)
  })

  it('rejects a conversation missing the required id field', () => {
    const result = ConversationSchema.safeParse({
      preview: 'hi',
      channelType: 'web',
      agentName: 'Alice',
      peerId: null,
      messageCount: 3,
      createdAt: '2026-05-12T00:00:00Z',
      updatedAt: '2026-05-12T00:00:01Z',
    })
    expect(result.success).toBe(false)
    if (!result.success) {
      const paths = result.error.issues.map(i => i.path.join('.'))
      expect(paths).toContain('id')
    }
  })

  it('parses an array of conversations via .array()', () => {
    const result = ConversationSchema.array().safeParse([
      {
        id: 1,
        preview: null,
        channelType: 'web',
        agentName: 'A',
        peerId: null,
        messageCount: 0,
        createdAt: 'x',
        updatedAt: 'y',
      },
    ])
    expect(result.success).toBe(true)
  })
})

describe('ToolCallSchema', () => {
  it('accepts a tool call with only the required fields', () => {
    const result = ToolCallSchema.safeParse({
      id: 'tc_1',
      name: 'web_search',
      icon: 'search',
      arguments: '{}',
    })
    expect(result.success).toBe(true)
  })

  it('rejects a tool call missing arguments', () => {
    const result = ToolCallSchema.safeParse({
      id: 'tc_1',
      name: 'web_search',
      icon: 'search',
    })
    expect(result.success).toBe(false)
    if (!result.success) {
      const paths = result.error.issues.map(i => i.path.join('.'))
      expect(paths).toContain('arguments')
    }
  })
})

describe('ToolCallResultStructuredSchema', () => {
  it('accepts the known web_search shape', () => {
    const result = ToolCallResultStructuredSchema.safeParse({
      provider: 'tavily',
      results: [
        { title: 'A page', url: 'https://example.com', snippet: 'hi', faviconUrl: null },
      ],
    })
    expect(result.success).toBe(true)
  })

  it('accepts arbitrary extra fields (passthrough) so new providers do not break the UI', () => {
    const result = ToolCallResultStructuredSchema.safeParse({
      provider: 'future',
      results: [],
      // Hypothetical extras a new tool might attach.
      thingsToRemember: ['x', 'y'],
      score: 0.92,
    })
    expect(result.success).toBe(true)
    if (result.success) {
      // Loose objects preserve extras on the parsed value.
      expect((result.data as Record<string, unknown>).thingsToRemember).toEqual(['x', 'y'])
      expect((result.data as Record<string, unknown>).score).toBe(0.92)
    }
  })

  it('accepts an empty object', () => {
    const result = ToolCallResultStructuredSchema.safeParse({})
    expect(result.success).toBe(true)
  })
})
