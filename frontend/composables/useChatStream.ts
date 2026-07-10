import { onUnmounted, ref, triggerRef, nextTick, type Ref, type ShallowRef } from 'vue'
import { useStreamMarkdownRender } from '~/composables/useStreamMarkdownRender'
import type { UploadedAttachment } from '~/composables/useChatAttachments'
import type { Message, MessageAttachment, ToolCall } from '~/types/api'

/**
 * SSE streaming state machine + send flow (JCLAW-690 stage 4g; behaviour
 * extracted verbatim from pages/chat.vue). Owns the per-turn stream buffers and
 * the abort controller, composes {@link useStreamMarkdownRender} for the
 * throttled bubble HTML, and drives the whole send → stream → finalize turn.
 *
 * `streaming` and `streamReasoning` are passed IN rather than owned: they are
 * cross-cutting shared state (useChatScroll pins the reasoning body off them,
 * useMediaGenPolling tears down on streaming-end, displayMessages/isEmptyChat
 * and the template read `streaming`), and those composables are constructed
 * before this one. Keeping them page-level dissolves the scroll/media ↔ stream
 * construction cycle. Everything this composable needs from later-constructed
 * composables (scrollToBottom, start*Polling, reconcileMessageIds, refresh*) is
 * injected and only invoked at turn time.
 */

// Local fields a streaming bubble carries beyond the persisted Message shape.
interface StreamingMessage extends Message {
  _thinkingInProgress?: boolean
}

// Queue status from GET /api/conversations/:id/queue — keeps the "agent busy"
// banner showing after a stream completes.
interface ConversationQueueStatus {
  busy: boolean
  [key: string]: unknown
}

interface ToolCallEvent {
  id: string
  name: string
  icon?: string
  arguments?: string
  resultText?: string | null
  resultStructured?: ToolCall['resultStructured']
  // JCLAW-228/562: the tool produced inline attachments (generate_image's image, diarize_audio's
  // per-speaker voice clips); the backend ships them so they render live on the streaming bubble
  // (not only after a reload).
  generatedAttachments?: MessageAttachment[]
}

/**
 * Mutable cursor that the SSE handlers share. {@code assistantIdx} is updated
 * by the {@code init} handler when the server mints a new conversation and
 * the local history is reset to just the streaming placeholder.
 */
interface StreamContext {
  assistantIdx: number
  readonly sentConversationId: number | null
}

export interface UseChatStreamDeps {
  messages: ShallowRef<Message[]>
  selectedConvoId: Ref<number | null>
  subagentTranscript: Ref<{ agentId: number, agentName: string } | null>
  selectedAgentId: Ref<number | null>
  /** Cross-cutting shared refs owned by the page (scroll/media also read them). */
  streaming: Ref<boolean>
  streamReasoning: Ref<string>
  input: Ref<string>
  chatInput: Ref<HTMLTextAreaElement | null>
  attachedFiles: Ref<File[]>
  attachError: Ref<string | null>
  attachmentPreviews: Ref<Map<File, string>>
  uploadAttachments: (agentId: number) => Promise<UploadedAttachment[]>
  scrollToBottom: () => void
  focusInput: () => void
  imageGenTurnKey: Ref<string | null>
  startImageProgressPolling: () => void
  startVideoPolling: () => void
  reconcileMessageIds: () => Promise<void>
  refreshConversations: () => Promise<void> | void
  refreshAgents: () => Promise<void> | void
}

export interface UseChatStream {
  streamContent: Ref<string>
  streamStatus: Ref<string>
  streamingMessageKey: Ref<string | null>
  agentBusy: Ref<boolean>
  streamContentHtml: Ref<string>
  streamReasoningHtml: Ref<string>
  sendMessage: () => Promise<void>
  stopStreaming: () => void
}

/**
 * Translate uploaded server handles into the shape the chat bubble's
 * optimistic-attachment chips expect (attachmentId → uuid). Returns
 * undefined when there were no uploads so the field is omitted entirely.
 */
function buildOptimisticAttachments(uploaded: UploadedAttachment[]) {
  if (!uploaded.length) return undefined
  return uploaded.map(u => ({
    uuid: u.attachmentId,
    originalFilename: u.originalFilename,
    mimeType: u.mimeType,
    sizeBytes: u.sizeBytes,
    kind: u.kind,
  }))
}

/**
 * Stamp the once-per-turn "thinking started" fields on the first reasoning
 * chunk. Subsequent chunks are no-ops here to avoid Vue reactivity churn.
 */
function markThinkingStartedIfFirst(m: StreamingMessage): boolean {
  if (m._thinkingStartedAt) return false
  m._thinkingStartedAt = Date.now()
  m._thinkingInProgress = true
  m._thinkingDurationMs = null
  m.thinkingCollapsed = false
  return true
}

/**
 * Stamp the reasoning→content transition exactly once. Returns true if
 * the transition fired so the caller can decide whether to triggerRef.
 */
function finalizeThinkingOnTransition(m: StreamingMessage): boolean {
  if (m._thinkingInProgress !== true) return false
  m._thinkingDurationMs = Date.now() - (m._thinkingStartedAt ?? Date.now())
  m._thinkingInProgress = false
  m.thinkingCollapsed = true
  return true
}

export function useChatStream(deps: UseChatStreamDeps): UseChatStream {
  const {
    messages, selectedConvoId, subagentTranscript, selectedAgentId,
    streaming, streamReasoning, input, chatInput,
    attachedFiles, attachError, attachmentPreviews, uploadAttachments,
    scrollToBottom, focusInput,
    imageGenTurnKey, startImageProgressPolling, startVideoPolling,
    reconcileMessageIds, refreshConversations, refreshAgents,
  } = deps

  const streamStatus = ref('')
  // _key of the assistant message currently being streamed. Set when we push the
  // placeholder in sendMessage, cleared when the stream ends. Used by the
  // template to route the in-flight bubble to streamContentHtml /
  // streamReasoningHtml (throttled markdown render) instead of running the full
  // renderMarkdown pipeline on every token mutation.
  const streamingMessageKey = ref<string | null>(null)
  const agentBusy = ref(false)
  const streamContent = ref('')
  const abortController = ref<AbortController | null>(null)

  const {
    streamContentHtml,
    streamReasoningHtml,
    scheduleStreamContentRender,
    scheduleStreamReasoningRender,
    flushStreamRender,
  } = useStreamMarkdownRender(streamContent, streamReasoning, selectedAgentId, messages)

  function stopStreaming() {
    if (!streaming.value) return
    abortController.value?.abort()
    streaming.value = false
    streamStatus.value = ''
    focusInput()
  }

  /**
   * Upload any attached files and return the server-side handles. Sets
   * {@code attachError} and returns null on failure so the caller can bail.
   */
  async function uploadOrReportAttachError(): Promise<UploadedAttachment[] | null> {
    if (!attachedFiles.value.length || !selectedAgentId.value) return []
    try {
      return await uploadAttachments(selectedAgentId.value)
    }
    catch (e: unknown) {
      const err = e as { data?: { error?: string }, message?: string } | undefined
      attachError.value = 'Upload failed: ' + (err?.data?.error || err?.message || 'unknown error')
      return null
    }
  }

  /** Revoke blob URLs held for the about-to-be-sent attachments. */
  function revokeAttachmentPreviews(pending: File[]) {
    for (const f of pending) {
      const url = attachmentPreviews.value.get(f)
      if (url) URL.revokeObjectURL(url)
      attachmentPreviews.value.delete(f)
    }
  }

  /** Reset all streaming buffers and flip the {@code streaming} flag on. */
  function beginStreamingState() {
    streaming.value = true
    streamContent.value = ''
    streamReasoning.value = ''
    streamStatus.value = ''
    streamContentHtml.value = ''
    streamReasoningHtml.value = ''
  }

  /**
   * JCLAW-26: if the backend minted a NEW conversation (e.g. /new slash-
   * command), discard the stale visible history and re-anchor to just the
   * streaming placeholder so the view matches what the new conversation
   * actually contains.
   */
  function handleInitConversationSwap(ctx: StreamContext, newConversationId: number) {
    if (ctx.sentConversationId === null) return
    if (ctx.sentConversationId === newConversationId) return
    const placeholder = messages.value[ctx.assistantIdx]
    if (!placeholder) return
    messages.value = [placeholder]
    ctx.assistantIdx = 0
    triggerRef(messages)
  }

  function handleStreamInitEvent(ctx: StreamContext, event: { conversationId?: number, thinkingMode?: string }) {
    if (!event.conversationId) return
    handleInitConversationSwap(ctx, event.conversationId)
    selectedConvoId.value = event.conversationId
    if (event.thinkingMode) {
      streamStatus.value = `thinking (${event.thinkingMode})...`
    }
  }

  /** Parse a status frame that may carry a usage JSON payload. Returns true if it was consumed as usage. */
  function tryApplyUsageFromStatusContent(ctx: StreamContext, content: string): boolean {
    if (!content.startsWith('{') || !content.includes('"usage"')) return false
    try {
      const parsed = JSON.parse(content)
      if (!parsed.usage) return false
      messages.value[ctx.assistantIdx]!.usage = parsed.usage
      triggerRef(messages)
      // The metrics pill renders below the bubble the moment usage lands.
      // Without this scroll the per-token scroller leaves the viewport
      // pinned to the pre-metrics bottom and the user sees a half-cropped
      // stats row.
      scrollToBottom()
      return true
    }
    catch {
      return false
    }
  }

  function handleStreamStatusEvent(ctx: StreamContext, event: { content?: string }) {
    const content = event.content
    if (content && tryApplyUsageFromStatusContent(ctx, content)) return
    streamStatus.value = content ?? ''
  }

  function handleStreamReasoningEvent(ctx: StreamContext, event: { content: string }) {
    const m = messages.value[ctx.assistantIdx] as StreamingMessage
    const stateChanged = markThinkingStartedIfFirst(m)
    streamReasoning.value += event.content
    // Property mutation on a shallowRef-held object does not trigger
    // reactivity. The streaming bubble reads streamReasoningHtml.value
    // (throttled markdown render below) instead of msg.reasoning, so
    // the user still sees the live update. msg.reasoning is kept in
    // sync so the post-stream renderMarkdown(msg.reasoning) path
    // displays the final text after the finally{} triggerRef.
    m.reasoning = streamReasoning.value
    scheduleStreamReasoningRender()
    if (stateChanged) triggerRef(messages)
    if (!streamContent.value) {
      streamStatus.value = 'thinking...'
    }
    scrollToBottom()
  }

  function handleStreamTokenEvent(ctx: StreamContext, event: { content?: string, timestamp?: string }) {
    // Empty-content token events are emitted by OpenAI-compatible providers
    // (observed on Kimi K2.5 via OpenRouter) interleaved with every reasoning
    // chunk — the `content` field is always present in the API schema and
    // defaults to "" when the chunk only carries reasoning. Treating those as
    // a real reasoning→content transition was the "Thought for 0.01 seconds
    // during streaming" bug: the second empty-content token after the first
    // reasoning event was tripping the flip. Skip them entirely so the
    // transition fires only on the first byte of genuine content.
    if (!event.content) return
    const m = messages.value[ctx.assistantIdx] as StreamingMessage
    const transitioned = finalizeThinkingOnTransition(m)
    streamStatus.value = ''
    if (event.timestamp) m.createdAt = event.timestamp
    streamContent.value += event.content
    m.content = streamContent.value
    scheduleStreamContentRender()
    // Trigger a single re-render at the reasoning→content transition so the
    // thinking card collapses and the content bubble appears. Subsequent
    // content tokens flow through streamContentHtml without touching the
    // messages-array reactivity, which is the whole point of the shallowRef
    // migration.
    if (transitioned) triggerRef(messages)
    scrollToBottom()
  }

  function handleStreamToolCallEvent(ctx: StreamContext, event: ToolCallEvent) {
    // JCLAW-170: a tool invocation completed on the backend. Append the
    // structured payload to the streaming assistant message's toolCalls
    // array so the collapsible block surfaces it live. The accordion starts
    // expanded so users see in-progress activity the instant the first tool
    // finishes; once content begins to stream they can collapse it
    // themselves, and it auto-collapses on reload for historical turns.
    // Per-call: collapse any prior calls and auto-expand the just-arrived
    // one so its results are visible without an extra click.
    const m = messages.value[ctx.assistantIdx] as StreamingMessage
    if (!m.toolCalls) {
      m.toolCalls = []
      m.toolCallsCollapsed = false
    }
    for (const prev of m.toolCalls) prev._expanded = false
    m.toolCalls.push({
      id: event.id,
      name: event.name,
      icon: event.icon || 'wrench',
      arguments: event.arguments ?? '',
      resultText: event.resultText ?? null,
      resultStructured: event.resultStructured ?? null,
      _expanded: true,
    })
    // JCLAW-683: scope the local image-gen progress bar to THIS turn. A
    // generate_image call is the only thing that runs the local sidecar's
    // step loop, so start polling now — keyed to the invoking assistant
    // message's _key — rather than on every streaming turn. This is what stops
    // a concurrent generation's load phase from leaking onto an unrelated turn.
    if (event.name === 'generate_image') {
      imageGenTurnKey.value = m._key ?? null
      startImageProgressPolling()
    }
    // JCLAW-228/562: the call produced inline attachments (image / voice clips) — attach them to the
    // streaming bubble so they render immediately (the server already persisted them on the
    // assistant turn).
    if (event.generatedAttachments?.length) {
      m.attachments ??= []
      for (const att of event.generatedAttachments) {
        if (!m.attachments.some(a => a.uuid === att.uuid)) {
          m.attachments.push(att)
        }
      }
      startVideoPolling() // a generate_video placeholder (JCLAW-234) needs the progress poll to begin
    }
    triggerRef(messages)
    scrollToBottom()
  }

  function handleStreamCompleteEvent(ctx: StreamContext, event: { content?: string }) {
    const m = messages.value[ctx.assistantIdx] as StreamingMessage
    // Reasoning-only turn (no content streamed): finalize duration here.
    finalizeThinkingOnTransition(m)
    streamStatus.value = ''
    m.content = event.content || streamContent.value
    // Collapsing thinking + swapping in final content shifts layout height.
    // Re-pin to the bottom so the next thing the user sees (the metrics pill
    // landing on the subsequent status event) is fully in view.
    scrollToBottom()
  }

  function handleStreamErrorEvent(ctx: StreamContext, event: { content: string }) {
    messages.value[ctx.assistantIdx]!.content = event.content
    triggerRef(messages)
  }

  function handleStreamQueuedEvent(ctx: StreamContext, event: { position?: number | string }) {
    messages.value[ctx.assistantIdx]!.content = 'Your message has been queued (position: ' + (event.position || '?') + '). Processing shortly...'
    triggerRef(messages)
  }

  /** Dispatch one SSE event to its dedicated handler. */
  function dispatchStreamEvent(ctx: StreamContext, event: { type?: string } & Record<string, unknown>) {
    switch (event.type) {
      case 'init':
        handleStreamInitEvent(ctx, event as { conversationId?: number, thinkingMode?: string })
        break
      case 'status':
        handleStreamStatusEvent(ctx, event as { content?: string })
        break
      case 'reasoning':
        handleStreamReasoningEvent(ctx, event as { content: string })
        break
      case 'token':
        handleStreamTokenEvent(ctx, event as { content?: string, timestamp?: string })
        break
      case 'tool_call':
        handleStreamToolCallEvent(ctx, event as unknown as ToolCallEvent)
        break
      case 'complete':
        handleStreamCompleteEvent(ctx, event as { content?: string })
        break
      case 'error':
        handleStreamErrorEvent(ctx, event as { content: string })
        break
      case 'queued':
        handleStreamQueuedEvent(ctx, event as { position?: number | string })
        break
    }
  }

  /** Parse one `data: …` SSE line and dispatch; swallow malformed JSON. */
  function processStreamLine(ctx: StreamContext, line: string) {
    if (!line.startsWith('data: ')) return
    try {
      const event = JSON.parse(line.slice(6))
      dispatchStreamEvent(ctx, event)
    }
    catch {
      // Skip malformed events
    }
  }

  /** Drain the SSE response stream, dispatching each event line as it arrives. */
  async function consumeStreamResponse(ctx: StreamContext, res: Response) {
    if (!res.body) throw new Error('No response body')
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) processStreamLine(ctx, line)
    }
  }

  /** Render the user-visible message into the assistant placeholder when the stream throws. */
  function applyStreamErrorToPlaceholder(assistantIdx: number, e: unknown) {
    const err = e as { name?: string, message?: string } | undefined
    if (err?.name === 'AbortError') {
      // AbortError is expected when the user clicks Stop — preserve any content
      // that was already streamed and append a small "(stopped)" marker instead
      // of replacing the bubble with a scary error.
      const existing = messages.value[assistantIdx]!.content || streamContent.value || ''
      messages.value[assistantIdx]!.content = existing
        ? existing.trimEnd() + '\n\n_(stopped)_'
        : '_(stopped before any response)_'
    }
    else {
      messages.value[assistantIdx]!.content = 'Error: ' + (err?.message || 'Failed to get response')
    }
  }

  async function sendMessage() {
    if (streaming.value || !selectedAgentId.value) return
    // Subagent transcripts are read-only — the user reached this view via a
    // "View transcript" link, and there's no well-defined target agent to
    // post to (the dropdown shows a top-level agent, not the subagent).
    if (subagentTranscript.value) return
    const rawText = input.value.trim()
    if (!rawText && !attachedFiles.value.length) return

    attachError.value = null
    const pending = attachedFiles.value.slice()
    const uploaded = await uploadOrReportAttachError()
    if (uploaded === null) return

    // JCLAW-25: message.content is the user's raw text. Attachment metadata
    // rides in the `attachments` field; the backend persists chat_message_attachment
    // rows and synthesizes the LLM content parts (image_url for images, bracketed
    // file references for non-images) from those rows on every turn.
    const text = rawText

    input.value = ''
    revokeAttachmentPreviews(pending)
    attachedFiles.value = []
    if (chatInput.value) chatInput.value.style.height = 'auto'
    // Map the just-uploaded attachments onto the optimistic user bubble so
    // the file chips render immediately. Without this, the chips stayed
    // hidden until the next /messages refetch surfaced the persisted
    // {@link MessageAttachment} rows — observable as "I uploaded a file
    // but it doesn't show until I leave and come back."
    messages.value.push({ _key: crypto.randomUUID(), role: 'user', content: text, createdAt: new Date().toISOString(), attachments: buildOptimisticAttachments(uploaded) })
    triggerRef(messages)
    scrollToBottom()

    beginStreamingState()

    // Capture the conversation id we're sending — if the server returns a
    // DIFFERENT id in the init frame, the backend minted a new conversation
    // (e.g. /new slash-command, JCLAW-26). We need to discard the stale
    // visible history from the prior conversation so the view matches what
    // the new conversation row actually contains on reload.
    const ctx: StreamContext = {
      assistantIdx: messages.value.length,
      sentConversationId: selectedConvoId.value,
    }

    // Add placeholder for streaming response
    const assistantKey = crypto.randomUUID()
    messages.value.push({ _key: assistantKey, role: 'assistant', content: '', createdAt: new Date().toISOString() })
    streamingMessageKey.value = assistantKey
    triggerRef(messages)

    abortController.value?.abort() // cancel any orphaned previous stream
    abortController.value = new AbortController()
    try {
      const res = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: abortController.value.signal,
        body: JSON.stringify({
          agentId: selectedAgentId.value,
          conversationId: selectedConvoId.value,
          message: text,
          attachments: uploaded,
        }),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      await consumeStreamResponse(ctx, res)
    }
    catch (e: unknown) {
      applyStreamErrorToPlaceholder(ctx.assistantIdx, e)
    }
    finally {
      streaming.value = false
      streamingMessageKey.value = null
      flushStreamRender()
      triggerRef(messages) // re-render with final content + markdown
      // triggerRef can add height (markdown codeblocks expand, metrics pill
      // appears if usage arrived after complete). Wait a tick so the DOM
      // reflects the final layout, then pin to the bottom.
      nextTick().then(scrollToBottom)
      focusInput()
      // Check if agent is still processing queued messages
      if (selectedConvoId.value) {
        try {
          const status = await $fetch<ConversationQueueStatus>(`/api/conversations/${selectedConvoId.value}/queue`)
          agentBusy.value = status.busy
        }
        catch { agentBusy.value = false }
      }
      // Fire the refresh twice. Immediate: catches the common case where the
      // backend persist already landed by the time onComplete fires. Delayed:
      // catches the race where the SSE `complete` frame beat the persist Tx
      // commit (see AgentRunner.streamLlmLoop — emitUsageAndComplete is
      // intentionally called before the Tx.run persist block for latency).
      refreshConversations()
      // Re-sync agents too: the agent can rewrite its own model mid-turn via
      // the jclaw_api tool (PUT /api/agents/{id} → Agent.modelProvider/modelId).
      // The header selector reads selectedAgent through effectiveModel, so
      // without this it stays pinned to the pre-switch model until a manual
      // reload. Single fire is enough — the tool's PUT commits in its own Tx
      // mid-turn, well before the SSE `complete` frame, so the new value is
      // already persisted by the time we get here (unlike the conversation
      // persist-race the double-fire above guards against).
      refreshAgents()
      // Delayed reconcile rides the same persist-race window so the in-flight
      // user + assistant bubbles pick up their server ids (delete button gates
      // on msg.id — without this the Delete action was a no-op until the user
      // navigated away and back).
      setTimeout(() => {
        refreshConversations()
        void reconcileMessageIds()
      }, 600)
    }
  }

  onUnmounted(() => {
    abortController.value?.abort()
  })

  return {
    streamContent,
    streamStatus,
    streamingMessageKey,
    agentBusy,
    streamContentHtml,
    streamReasoningHtml,
    sendMessage,
    stopStreaming,
  }
}
