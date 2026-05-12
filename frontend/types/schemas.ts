/**
 * JCLAW-287 Phase 1: runtime zod schemas for the highest-risk API shapes.
 *
 * Why this exists alongside {@code types/api.ts}: the interfaces in that file
 * are *compile-time* contracts — TypeScript trusts whatever JSON the backend
 * sends. When the wire shape drifts (renamed field, missing column, a new
 * union branch on a tool result), the UI silently renders {@code undefined}
 * until something blows up far from the cause. These schemas re-state the
 * same contracts so {@link fetchParsed} can validate at the network boundary
 * and fail loudly with a useful error path.
 *
 * Scope is deliberately narrow: chat messages, conversation rows, and tool
 * calls. Every other endpoint keeps using {@code useApi}/{@code $fetch} with
 * raw type assertions until we have a reason to widen.
 *
 * The schemas mirror {@code types/api.ts} where the field set is known and
 * lean on {@code .passthrough()} where the backend may add fields the UI
 * doesn't care about (notably {@link ToolCallResultStructuredSchema}, which
 * carries provider-specific blobs).
 */
import { z } from 'zod'

/**
 * One row from {@code Message.attachments} (JCLAW-279). Mirrors
 * {@code MessageAttachment} in {@code types/api.ts}.
 */
export const MessageAttachmentSchema = z.object({
  uuid: z.string(),
  originalFilename: z.string(),
  mimeType: z.string(),
  sizeBytes: z.number(),
  kind: z.enum(['IMAGE', 'AUDIO', 'FILE']),
})

/**
 * Structured tool-result payload (JCLAW-170). Intentionally permissive: the
 * backend ships provider-specific fields here (search chips today, future
 * tools may add their own), and we don't want a new field to reject the
 * whole payload. Known fields are typed; unknown extras pass through.
 */
export const ToolCallResultChipSchema = z.object({
  title: z.string().nullable(),
  url: z.string().nullable(),
  snippet: z.string().nullable(),
  faviconUrl: z.string().nullable(),
})

export const ToolCallResultStructuredSchema = z
  .object({
    provider: z.string().nullable().optional(),
    results: z.array(ToolCallResultChipSchema).optional(),
  })
  .loose()

/**
 * One tool invocation on an assistant turn (JCLAW-170). Mirrors
 * {@code ToolCall} in {@code types/api.ts}.
 */
export const ToolCallSchema = z.object({
  id: z.string(),
  name: z.string(),
  icon: z.string(),
  arguments: z.string(),
  resultText: z.string().nullable().optional(),
  resultStructured: ToolCallResultStructuredSchema.nullable().optional(),
})

/**
 * One message in a conversation thread. Mirrors {@code Message} in
 * {@code types/api.ts}; client-only fields ({@code _key},
 * {@code thinkingCollapsed}, etc.) are not validated here since they're
 * never on the wire. {@code usage} is intentionally accepted as a passthrough
 * object — its shape is owned by {@code utils/usage-cost} and a stricter
 * schema would couple the two modules.
 */
export const MessageSchema = z.object({
  id: z.number().optional(),
  role: z.enum(['user', 'assistant', 'tool']),
  content: z.string().nullable(),
  reasoning: z.string().nullable().optional(),
  createdAt: z.string(),
  usage: z.object({}).loose().nullable().optional(),
  toolCalls: z.array(ToolCallSchema).optional(),
  attachments: z.array(MessageAttachmentSchema).optional(),
})

/**
 * One conversation row from {@code GET /api/conversations}. Mirrors
 * {@code Conversation} in {@code types/api.ts}.
 */
export const ConversationSchema = z.object({
  id: z.number(),
  preview: z.string().nullable(),
  channelType: z.string(),
  agentName: z.string(),
  peerId: z.string().nullable(),
  messageCount: z.number(),
  createdAt: z.string(),
  updatedAt: z.string(),
  modelProviderOverride: z.string().nullable().optional(),
  modelIdOverride: z.string().nullable().optional(),
})

export type MessageAttachment = z.infer<typeof MessageAttachmentSchema>
export type ToolCallResultChip = z.infer<typeof ToolCallResultChipSchema>
export type ToolCallResultStructured = z.infer<typeof ToolCallResultStructuredSchema>
export type ToolCall = z.infer<typeof ToolCallSchema>
export type Message = z.infer<typeof MessageSchema>
export type Conversation = z.infer<typeof ConversationSchema>
