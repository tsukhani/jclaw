import { nextTick, ref, triggerRef, type Ref, type ShallowRef } from 'vue'
import type { Message, MessageAttachment, ToolCall } from '~/types/api'

/**
 * Per-message user actions (JCLAW-690 stage 5e; behaviour extracted verbatim
 * from pages/chat.vue). Copy, delete, edit, regenerate, and the thinking /
 * tool-call collapse toggles — everything the ChatMessage row emits back up.
 * Mutates the conversation's messages shallowRef in place (with triggerRef,
 * since it's shallow) and talks to the message/attachment DELETE endpoints;
 * regenerate/edit reach into the composer (input + autoResize) and the stream
 * (sendMessage), which are injected.
 */
export interface UseChatMessageActionsDeps {
  messages: ShallowRef<Message[]>
  selectedConvoId: Ref<number | null>
  streaming: Ref<boolean>
  input: Ref<string>
  chatInput: Ref<HTMLTextAreaElement | null>
  sendMessage: () => void
  autoResize: () => void
}

export interface UseChatMessageActions {
  copiedMessageId: Ref<string | number | null>
  tokStatsHoverKey: Ref<string | number | null>
  copyMessage: (msg: Message) => Promise<void>
  copyReasoning: (msg: Message) => Promise<void>
  deleteMessage: (msg: Message) => Promise<void>
  deleteAttachment: (att: MessageAttachment) => Promise<void>
  editUserMessage: (msg: Message) => Promise<void>
  regenerateMessage: (msg: Message) => Promise<void>
  toggleThinking: (msg: Message) => void
  toggleToolCalls: (msg: Message) => void
  toggleToolCallExpansion: (tc: ToolCall) => void
}

export function useChatMessageActions(deps: UseChatMessageActionsDeps): UseChatMessageActions {
  const { messages, selectedConvoId, streaming, input, chatInput, sendMessage, autoResize } = deps

  // Per-bubble collapse toggle handler. Header label + default-collapse rules
  // live in ~/utils/thinking.ts (thinkingHeaderLabel, initCollapsedState) so
  // they are unit-testable without mounting the page.
  //
  // triggerRef is required because messages is a shallowRef — mutating a
  // property on a Message object inside the array does not trigger reactivity
  // on its own. The same applies to the tool-call toggles below.
  function toggleThinking(msg: Message) {
    msg.thinkingCollapsed = !msg.thinkingCollapsed
    triggerRef(messages)
  }

  // JCLAW-170: tool-calls block collapse toggle. Mirrors the thinking card's
  // single-boolean collapse UX.
  function toggleToolCalls(msg: Message) {
    (msg as Message & { toolCallsCollapsed?: boolean }).toolCallsCollapsed
      = !(msg as Message & { toolCallsCollapsed?: boolean }).toolCallsCollapsed
    triggerRef(messages)
  }

  // JCLAW-170: per-call expand toggle. Each tool call inside the outer block
  // has its own collapsible body (chip grid or result text), independent of
  // the surrounding "N tool calls" accordion.
  function toggleToolCallExpansion(tc: ToolCall) {
    tc._expanded = !tc._expanded
    triggerRef(messages)
  }

  // Per-message "just copied" flash so the user gets visual feedback without a toast.
  const copiedMessageId = ref<string | number | null>(null)
  async function copyMessage(msg: Message) {
    try {
      // Copy the raw source — for assistant turns that means the markdown, not
      // the rendered HTML. Pasting into a doc or another chat stays faithful to
      // what the model actually produced.
      await navigator.clipboard.writeText(msg.content ?? '')
      copiedMessageId.value = msg.id ?? msg._key ?? null
      setTimeout(() => {
        if (copiedMessageId.value === (msg.id ?? msg._key ?? null)) copiedMessageId.value = null
      }, 1200)
    }
    catch (e) {
      console.error('Failed to copy message:', e)
    }
  }

  // Copy the reasoning body only (not the final response). Namespaced key so the
  // per-message flash doesn't clash with copyMessage on the same row.
  async function copyReasoning(msg: Message) {
    if (!msg.reasoning) return
    try {
      await navigator.clipboard.writeText(msg.reasoning)
      const key = `reason:${msg.id ?? msg._key}`
      copiedMessageId.value = key
      setTimeout(() => {
        if (copiedMessageId.value === key) copiedMessageId.value = null
      }, 1200)
    }
    catch (e) {
      console.error('Failed to copy reasoning:', e)
    }
  }

  /**
   * Find the index of the most recent user message at-or-before {@code fromIdx}.
   * Returns -1 if none is found.
   */
  function findPriorUserMessageIdx(fromIdx: number): number {
    for (let i = fromIdx - 1; i >= 0; i--) {
      if (messages.value[i]!.role === 'user') return i
    }
    return -1
  }

  /**
   * Best-effort server-side delete of messages [startIdx..end). Failures are
   * swallowed because the local truncate happens regardless.
   */
  async function deleteServerMessagesFrom(convoId: number, startIdx: number) {
    for (let i = startIdx; i < messages.value.length; i++) {
      const m = messages.value[i]!
      if (!m.id) continue
      try {
        await $fetch(`/api/conversations/${convoId}/messages/${m.id}`, { method: 'DELETE' })
      }
      catch { /* best-effort — local truncate still happens */ }
    }
  }

  /**
   * Regenerate the assistant's response to the last user prompt. Semantically
   * equivalent to "rewind to the user message, re-send it". Deletes the user +
   * assistant pair (and anything after) both server-side and locally, then
   * calls sendMessage() with the user's original text so the backend runs a
   * fresh turn against the pre-existing conversation history.
   */
  async function regenerateMessage(msg: Message) {
    if (streaming.value) return
    const convoId = selectedConvoId.value
    const idx = messages.value.indexOf(msg)
    if (idx < 0) return
    const userIdx = findPriorUserMessageIdx(idx)
    if (userIdx < 0) return
    const userContent = messages.value[userIdx]!.content ?? ''
    if (convoId) await deleteServerMessagesFrom(convoId, userIdx)
    messages.value = messages.value.slice(0, userIdx)
    input.value = userContent
    await nextTick()
    sendMessage()
  }

  // Per-message hover state for the tok/s statistics popover. Stores the
  // currently-open message's id/_key so only one popover is visible at a
  // time; v-for rows bind their individual open state off this ref.
  const tokStatsHoverKey = ref<string | number | null>(null)

  async function deleteMessage(msg: Message) {
    // Skip mid-stream placeholders that have no server-side row yet — the
    // outer stop-streaming path already handles those.
    if (!msg.id) return
    const convoId = selectedConvoId.value
    if (!convoId) return
    try {
      await $fetch(`/api/conversations/${convoId}/messages/${msg.id}`, { method: 'DELETE' })
      // Splice optimistically rather than refetching the whole transcript —
      // keeps the remaining messages' thinkingCollapsed / _thinkingDurationMs
      // bubble state intact (they're client-only refs that a refetch would lose).
      const idx = messages.value.findIndex(m => m.id === msg.id)
      if (idx >= 0) {
        messages.value.splice(idx, 1)
        triggerRef(messages)
      }
    }
    catch (e) {
      console.error('Failed to delete message:', e)
    }
  }

  /**
   * JCLAW-209: delete a generated image's bytes from the workspace. Soft delete —
   * the server frees the on-disk file and flags the row, so the chip persists with
   * a "deleted from workspace" marker rather than vanishing. Flip the local flag
   * and triggerRef (messages is a shallowRef) instead of refetching the transcript.
   */
  async function deleteAttachment(att: MessageAttachment) {
    try {
      await $fetch(`/api/attachments/${att.uuid}`, { method: 'DELETE' })
      att.deleted = true
      triggerRef(messages)
    }
    catch (e) {
      console.error('Failed to delete attachment:', e)
    }
  }

  async function editUserMessage(msg: Message) {
    if (streaming.value) return
    input.value = msg.content ?? ''
    await nextTick()
    const el = chatInput.value
    if (el) {
      autoResize()
      el.focus()
      el.setSelectionRange(el.value.length, el.value.length)
      el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
  }

  return {
    copiedMessageId,
    tokStatsHoverKey,
    copyMessage,
    copyReasoning,
    deleteMessage,
    deleteAttachment,
    editUserMessage,
    regenerateMessage,
    toggleThinking,
    toggleToolCalls,
    toggleToolCallExpansion,
  }
}
