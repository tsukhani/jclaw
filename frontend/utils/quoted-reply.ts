import type { ChatQuote, ChatQuoteKind, Message } from '~/types/api'

export const QUOTE_KIND_LABELS: Record<ChatQuoteKind, string> = {
  assistant: 'Replying to the agent',
  user: 'Replying to your message',
  delivered: 'Replying to a delivered message',
  reminder: 'Replying to a reminder',
}

/** The quoted block a reply carries at the start of its stored text, and the reply after it. */
export interface QuotedReplyParts {
  label: string
  quoted: string
  reply: string
}

// The server's QuotedReply.block: a bracketed source line, then every quoted line prefixed "> ".
const HEADER = /^\[((?:Replying to|Quoting part of) [^\]\n]*)\]\n/

export function splitQuotedReply(content: string | null | undefined): QuotedReplyParts | null {
  if (!content) return null
  const header = HEADER.exec(content)
  if (!header) return null
  const lines = content.slice(header[0].length).split('\n')
  let end = 0
  while (end < lines.length && (lines[end] === '>' || lines[end]!.startsWith('> '))) end++
  if (end === 0) return null
  const rest = lines.slice(end)
  if (rest[0] === '') rest.shift()
  return {
    label: header[1]!,
    quoted: lines.slice(0, end).map(line => line.slice(2)).join('\n'),
    reply: rest.join('\n'),
  }
}

/** The quote a Reply on {@code msg} starts; a message that itself quotes is quoted by its own words only. */
export function quoteFor(msg: Message): ChatQuote {
  const content = msg.content ?? ''
  const kind: ChatQuoteKind = msg.messageKind === 'subagent_send'
    ? 'delivered'
    : msg.role === 'assistant' ? 'assistant' : 'user'
  return { kind, text: splitQuotedReply(content)?.reply ?? content }
}
