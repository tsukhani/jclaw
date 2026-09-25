import { describe, it, expect } from 'vitest'
import { quoteFor, splitQuotedReply } from '~/utils/quoted-reply'
import type { Message } from '~/types/api'

function msg(o: Partial<Message>): Message {
  return { id: 1, role: 'user', content: '', createdAt: '2026-09-25T00:00:00Z', ...o } as Message
}

describe('splitQuotedReply (JCLAW-1299)', () => {
  it('splits the server\'s block from the reply under it', () => {
    expect(splitQuotedReply('[Replying to a reminder this bot sent]\n> line one\n>\n> line three\n\nmy reply'))
      .toEqual({ label: 'Replying to a reminder this bot sent', quoted: 'line one\n\nline three', reply: 'my reply' })
  })

  it('reads a partial quote', () => {
    expect(splitQuotedReply('[Quoting part of a message from Bob]\n> ship it\n\nwhen?')?.label)
      .toBe('Quoting part of a message from Bob')
  })

  it('keeps a group reply\'s sender tag on the reply', () => {
    expect(splitQuotedReply('[Replying to x]\n> q\n\n[Ada (id 42)]: why?')?.reply).toBe('[Ada (id 42)]: why?')
  })

  it('reads a block with no words under it', () => {
    expect(splitQuotedReply('[Replying to x]\n> q')).toEqual({ label: 'Replying to x', quoted: 'q', reply: '' })
  })

  it.each([
    ['plain text', 'hello'],
    ['a bracketed line that is not a quote header', '[note]\n> not ours'],
    ['a header with no quoted lines', '[Replying to x]\nno quote'],
    ['nothing', null],
  ])('finds no block in %s', (_label, content) => {
    expect(splitQuotedReply(content)).toBeNull()
  })
})

describe('quoteFor (JCLAW-1299)', () => {
  it('names what the reply quotes by who wrote it', () => {
    expect(quoteFor(msg({ role: 'assistant', content: 'a' })).kind).toBe('assistant')
    expect(quoteFor(msg({ role: 'user', content: 'u' })).kind).toBe('user')
    expect(quoteFor(msg({ role: 'user', content: 'd', messageKind: 'subagent_send' })).kind).toBe('delivered')
  })

  it('quotes a reply by its own words, never nesting the block it carried', () => {
    expect(quoteFor(msg({ content: '[Replying to x]\n> earlier\n\nmy words' })).text).toBe('my words')
  })
})
