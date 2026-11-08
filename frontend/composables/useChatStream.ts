import { nextTick, onUnmounted, ref, triggerRef, watch, type Ref, type ShallowRef } from 'vue'
import type { CommandPick, PendingOverrides } from '~/composables/useAgentModel'
import { useStreamMarkdownRender } from '~/composables/useStreamMarkdownRender'
import type { UploadedAttachment } from '~/composables/useChatAttachments'
import type { MergeSavedRowsOptions } from '~/composables/useChatAnnouncePoller'
import type { ConversationQueueStatus, QueuedMessageView } from '~/composables/useChatBackgroundTurn'
import { useConversationStream, type TurnFrame } from '~/composables/useConversationStream'
import { useEventBus } from '~/composables/useEventBus'
import { apiErrorDetails } from '~/composables/useApiMutation'
import type { ChatQuote, ChecklistItem, Message, MessageAttachment, ToolCallResultStructured } from '~/types/api'
import { checklistFromCall } from '~/utils/checklist'
import type { MessageRoute } from '~/utils/usage-cost'

/**
 * The chat page's send flow and turn renderer (JCLAW-690 stage 4g; JF-202). A send posts to `POST /api/chat/turn`,
 * which answers once the turn has started or queued; every turn of the open conversation, the tab's own included,
 * then renders the same way from the conversation stream (useConversationStream): its `init` opens the in-flight
 * bubble, its frames fill it, and its terminal frame (`complete`, `error` or `stopped`) ends it and reconciles with the
 * saved rows. Composes {@link useStreamMarkdownRender} for the throttled bubble HTML.
 *
 * `streaming` and `streamReasoning` are passed IN rather than owned: they are cross-cutting shared state (useChatScroll
 * pins the reasoning body off them, useMediaGenPolling tears down on streaming-end, displayMessages/isEmptyChat and the
 * template read `streaming`), and those composables are constructed before this one. `streaming` means the stream has
 * a turn open. `followedConvoId` names the conversation the stream follows; the page sets it once a conversation's rows
 * have loaded and clears it before the next load, and a send that creates a conversation sets it here.
 */

// Local fields a streaming bubble carries beyond the persisted Message shape.
interface StreamingMessage extends Message {
  _thinkingInProgress?: boolean
}

interface ToolCallEvent {
  id: string
  name: string
  icon?: string
  arguments?: string
  resultText?: string | null
  resultStructured?: ToolCallResultStructured | null
  // JCLAW-228/562: the tool produced inline attachments (generate_image's image, diarize_audio's
  // per-speaker voice clips); the backend ships them so they render live on the streaming bubble
  // (not only after a reload).
  generatedAttachments?: MessageAttachment[]
}

/** `POST /api/chat/turn`'s answer (JF-202). */
interface TurnAnswer {
  status: 'started' | 'queued' | 'command'
  conversationId: number | null
  position?: number
  content?: string
  /** A command's reply is a saved row the page may pair its copy with. */
  saved?: boolean
  /** The pick a command typed in a fresh chat made, which that chat holds until its first message. */
  pick?: CommandPick
}

/**
 * The turn the stream has open: its bubble, the SSE id of its `init`, which a replay of the same turn repeats, and the
 * id the server gave the turn, which Stop and a typed `/stop` name so that one landing after it ended stops nothing
 * (JF-226); null when its `init` carried none.
 */
interface OpenTurn {
  bubble: StreamingMessage
  initId: string
  turnId: number | null
}

// A /stop as the server reads one: the command, any `@bot` suffix, then whitespace or nothing.
const STOP_COMMAND = /^\/stop(?:@\S*)?(?:\s|$)/i

export interface UseChatStreamDeps {
  messages: ShallowRef<Message[]>
  selectedConvoId: Ref<number | null>
  subagentTranscript: Ref<{ agentId: number, agentName: string } | null>
  selectedAgentId: Ref<number | null>
  /** Cross-cutting shared refs owned by the page (scroll/media also read them). */
  streaming: Ref<boolean>
  streamReasoning: Ref<string>
  /** The conversation the stream follows; null closes it. */
  followedConvoId: Ref<number | null>
  input: Ref<string>
  chatInput: Ref<HTMLTextAreaElement | null>
  attachedFiles: Ref<File[]>
  attachError: Ref<string | null>
  attachmentPreviews: Ref<Map<File, string>>
  uploadAttachments: (agentId: number) => Promise<UploadedAttachment[]>
  /** For the operator's own send: scrolls whatever the reader's position. */
  scrollToBottom: () => void
  /** For a turn's frames: scrolls a reader at the bottom only (useChatScroll); defaults to scrollToBottom. */
  followToBottom?: () => void
  /** Opens a conversation as the page's load does (useChatConversation's loadConversation). */
  openConversation?: (id: number) => Promise<void>
  /** Written here: whether the conversation stream is up, which the queue read's hold rule needs. */
  streamUp?: Ref<boolean>
  focusInput: () => void
  imageGenTurnKey: Ref<string | null>
  startImageProgressPolling: () => void
  browserSetupTurnKey: Ref<string | null>
  startBrowserSetupPolling: () => void
  startVideoPolling: () => void
  /** Fetches the rows saved since the last read and merges them in server order (useChatAnnouncePoller). */
  mergeSavedRows: (opts?: MergeSavedRowsOptions) => Promise<void>
  /** Reads the conversation's queue: busy, stoppable, the queued messages (useChatBackgroundTurn). */
  readQueue: (opts?: { init?: boolean }) => Promise<ConversationQueueStatus | null>
  /** Shows a message the server just queued until the next queue read lists it. */
  showQueued: (message: QueuedMessageView) => void
  /** Whether the last merge held back a saved row for a send not yet answered (useChatAnnouncePoller). */
  holdingRows?: () => boolean
  /** Hides Stop until a new turn's own queue read says whether it applies (useChatBackgroundTurn). */
  clearStoppable?: () => void
  /** JCLAW-1196: picks made on a fresh chat, carried by the message that creates its conversation. */
  pendingOverrides?: { readonly value: PendingOverrides | null }
  /** Holds a command's pick for the fresh chat, as the picker does (useAgentModel). */
  applyPick?: (pick: CommandPick) => void
  /** JCLAW-1299: the message the next send replies to; cleared once sent. */
  quote?: Ref<ChatQuote | null>
  refreshConversations: () => Promise<void> | void
  refreshAgents: () => Promise<void> | void
  /** JCLAW-1569: the list the turn in flight last set; cleared when a turn starts, when the stored row takes over. */
  liveChecklist?: Ref<ChecklistItem[] | null>
}

export interface UseChatStream {
  streamContent: Ref<string>
  streamStatus: Ref<string>
  streamingMessageKey: Ref<string | null>
  streamContentHtml: Ref<string>
  streamReasoningHtml: Ref<string>
  /** Epoch ms the open turn started, from its `init`, for the progress timer. */
  streamStartedAt: Ref<number | null>
  sendMessage: () => Promise<void>
  stopStreaming: () => void
  /** New Chat or an agent change: an answer, read or merge for the view being left changes nothing. */
  leaveView: () => void
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

/** What the bubble holds before its first frame; a resync rebuilds it from here. */
function clearBubble(m: StreamingMessage) {
  m.content = ''
  m.reasoning = null
  m.usage = null
  delete m.toolCalls
  delete m.toolCallsCollapsed
  delete m.attachments
  delete m._thinkingStartedAt
  delete m._thinkingInProgress
  delete m._thinkingDurationMs
  delete m.thinkingCollapsed
  delete m._route
  delete m._contextTokens
}

export function useChatStream(deps: UseChatStreamDeps): UseChatStream {
  const {
    messages, selectedConvoId, subagentTranscript, selectedAgentId,
    streaming, streamReasoning, followedConvoId, input, chatInput,
    attachedFiles, attachError, attachmentPreviews, uploadAttachments,
    scrollToBottom, focusInput, openConversation,
    imageGenTurnKey, startImageProgressPolling, startVideoPolling,
    browserSetupTurnKey, startBrowserSetupPolling,
    mergeSavedRows, readQueue, showQueued, refreshConversations, refreshAgents,
  } = deps

  const streamStatus = ref('')
  // _key of the open turn's bubble. The template routes that bubble to streamContentHtml / streamReasoningHtml
  // (throttled markdown render) instead of running the full renderMarkdown pipeline on every token mutation.
  const streamingMessageKey = ref<string | null>(null)
  const streamContent = ref('')
  const streamStartedAt = ref<number | null>(null)

  const {
    streamContentHtml,
    streamReasoningHtml,
    scheduleStreamContentRender,
    scheduleStreamReasoningRender,
    flushStreamRender,
  } = useStreamMarkdownRender(streamContent, streamReasoning, selectedAgentId, messages)

  const followToBottom = deps.followToBottom ?? scrollToBottom
  const { mutate } = useApiMutation()
  // GH-12: the in-flight stop POST; a send waits for it so the backend has released the turn.
  let pendingStop: Promise<unknown> | null = null

  let turn: OpenTurn | null = null
  // The id of the last turn the page showed, kept past its terminal: a /stop typed before the next init names it, not
  // the queued message that already runs (JF-226). Cleared with the view.
  let shownTurnId: number | null = null
  // Counts inits, so a read begun before one does not act on a turn it never saw.
  let inits = 0
  // Bumped whenever the stream closes: an answer, read or timer for an earlier view changes nothing.
  let viewGen = 0
  // Sends whose answer has not come: a turn's rows wait for them, so the optimistic row is paired, never doubled.
  const sends = new Set<Promise<unknown>>()
  // A New Chat's send, settled with the conversation it created once its answer is applied, a load it started included;
  // a second send from that chat goes there. `left` once the operator leaves that chat, after which nothing from it is
  // placed.
  let newChatSend: { conversation: Promise<number | null>, left: boolean } | null = null

  const stream = useConversationStream({ onFrame: dispatchFrame, onOpen: () => void settleIfIdle(), onGiveUp: giveUpTurn })
  watch(stream.up, (up) => {
    if (deps.streamUp) deps.streamUp.value = up
  }, { flush: 'sync', immediate: true })
  // A stream that gave up tries again once the event bus is back.
  useEventBus().onOpen(() => stream.reopen())

  /** No merge may give the open bubble a saved row's id: it is still streaming, and its reply is not saved yet. */
  const unlessOpenBubble = (m: Message) => m !== turn?.bubble

  /** Reset the bubble buffers the throttled renderer reads. */
  function clearStreamBuffers() {
    streamContent.value = ''
    streamReasoning.value = ''
    streamStatus.value = ''
    streamContentHtml.value = ''
    streamReasoningHtml.value = ''
  }

  /** Forget the open turn on this page; the server's turn is untouched. */
  function dropTurn() {
    turn = null
    streaming.value = false
    streamingMessageKey.value = null
    streamStartedAt.value = null
    streamStatus.value = ''
  }

  function closeView() {
    viewGen++
    shownTurnId = null
    // Whatever closed the view, a send from it places nothing and holds up no later one.
    if (newChatSend) newChatSend.left = true
    newChatSend = null
    if (turn) {
      dropTurn()
      clearStreamBuffers()
    }
  }

  // Switching conversations, New Chat or an agent change closes the stream; the page reopens it once rows load.
  watch(followedConvoId, (id) => {
    closeView()
    stream.close()
    if (id != null) stream.open(id)
  }, { flush: 'sync', immediate: true })
  watch(selectedConvoId, (id) => {
    if (id !== followedConvoId.value) followedConvoId.value = null
  }, { flush: 'sync' })

  function leaveView() {
    followedConvoId.value = null
    closeView()
  }

  /** Every merge: the open bubble and a send not yet answered pair with nothing, and a bubble drops a call a saved row shows. */
  async function mergeRows(opts: { full?: boolean } = {}) {
    await mergeSavedRows({ ...opts, pairable: unlessOpenBubble })
    dropCallsSavedRowsShow()
  }

  // A round's saved row takes the calls the bubble streamed for it; left on the bubble too, they would show twice.
  function dropCallsSavedRowsShow() {
    let dropped = false
    for (const m of messages.value) {
      if (typeof m.id === 'number' || !m.toolCalls?.some(tc => shownOnSavedRow(tc.id))) continue
      m.toolCalls = m.toolCalls.filter(tc => !shownOnSavedRow(tc.id))
      dropped = true
    }
    if (dropped) triggerRef(messages)
  }

  function stopStreaming() {
    if (!streaming.value) return
    const convoId = stream.conversationId.value
    const stopping = turn
    if (convoId != null) {
      // mutate never rejects, so a failed stop cannot block the next send. The server publishes the turn's stopped
      // frame before it answers, but on the stream's own connection, which can land later: a stop that worked ends the
      // turn here. One that found nothing to stop leaves it to the turn's own terminal, or to an idle read.
      const named = stopping?.turnId
      const stop: Promise<unknown> = mutate<{ stopped?: boolean }>(`/api/conversations/${convoId}/stop`,
        { method: 'POST', ...(named != null ? { body: { turn: named } } : {}) })
        .then(async (answer) => {
          if (!answer?.stopped) {
            await settleIfIdle()
            return
          }
          if (stopping && turn === stopping) {
            handleStreamStoppedEvent(stopping.bubble)
            endTurn()
          }
          await readQueue()
        })
        .finally(() => {
          if (pendingStop === stop) pendingStop = null
        })
      pendingStop = stop
    }
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
      // No fallback text: for a network failure the transport's own message says more than a stock phrase.
      attachError.value = 'Upload failed: ' + apiErrorDetails(e).message
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

  /** Parse a status frame that may carry a usage JSON payload. Returns true if it was consumed as usage. */
  function tryApplyUsageFromStatusContent(m: StreamingMessage, content: string): boolean {
    if (!content.startsWith('{') || !content.includes('"usage"')) return false
    try {
      const parsed = JSON.parse(content)
      if (!parsed.usage) return false
      m.usage = parsed.usage
      triggerRef(messages)
      // The metrics pill renders below the bubble the moment usage lands.
      // Without this scroll the per-token scroller leaves the viewport
      // pinned to the pre-metrics bottom and the user sees a half-cropped
      // stats row.
      followToBottom()
      return true
    }
    catch {
      return false
    }
  }

  /** JCLAW-1222: a `{"route":…}` status frame names the model the router picked, or failed over to. Returns true if consumed. */
  function tryApplyRouteFromStatusContent(m: StreamingMessage, content: string): boolean {
    if (!content.startsWith('{"route"')) return false
    try {
      const parsed = JSON.parse(content) as { route?: MessageRoute }
      if (!parsed.route) return false
      m._route = parsed.route
      triggerRef(messages)
      return true
    }
    catch {
      return false
    }
  }

  /** Parse the context frame the backend sends after every model call, so the meter moves mid-turn. */
  function tryApplyContextFromStatusContent(m: StreamingMessage, content: string): boolean {
    if (!content.startsWith('{"contextTokens"')) return false
    try {
      const parsed = JSON.parse(content) as { contextTokens?: number }
      if (typeof parsed.contextTokens !== 'number') return false
      m._contextTokens = parsed.contextTokens
      triggerRef(messages)
      return true
    }
    catch {
      return false
    }
  }

  function handleStreamInitEvent(event: TurnFrame, eventId: string) {
    // The same turn replayed around a resync, which rebuilds the bubble from the frames after it.
    if (turn && eventId && turn.initId === eventId) return
    // A turn whose terminal never reached this page: what its bubble holds is unknown, so its saved rows replace it.
    if (turn) {
      const stale = turn.bubble
      dropTurn()
      messages.value = messages.value.filter(m => m !== stale)
    }
    const bubble: StreamingMessage = { _key: crypto.randomUUID(), role: 'assistant', content: '', createdAt: new Date().toISOString() }
    turn = { bubble, initId: eventId, turnId: typeof event.turn === 'number' ? event.turn : null }
    shownTurnId = turn.turnId
    inits++
    clearStreamBuffers()
    const startedAt = typeof event.startedAt === 'string' ? Date.parse(event.startedAt) : Number.NaN
    streamStartedAt.value = Number.isFinite(startedAt) ? startedAt : Date.now()
    streamingMessageKey.value = bubble._key!
    if (deps.liveChecklist) deps.liveChecklist.value = null
    deps.clearStoppable?.()
    messages.value.push(bubble)
    triggerRef(messages)
    streaming.value = true
    if (typeof event.thinkingMode === 'string' && event.thinkingMode) {
      streamStatus.value = `thinking (${event.thinkingMode})...`
    }
    followToBottom()
    void afterInit(bubble)
  }

  // The turn's user message, a drained one's included, is saved before its init: fetched first, so it shows before the
  // reply, and only then the queue read that drops its Queued row.
  async function afterInit(bubble: StreamingMessage) {
    const gen = viewGen
    if (sends.size) await Promise.allSettled([...sends])
    if (gen !== viewGen || turn?.bubble !== bubble) return
    await mergeRows()
    if (gen !== viewGen || turn?.bubble !== bubble) return
    await readQueue({ init: true })
  }

  function handleStreamStatusEvent(m: StreamingMessage, event: { content?: string }) {
    const content = event.content
    if (content && (tryApplyUsageFromStatusContent(m, content) || tryApplyRouteFromStatusContent(m, content)
      || tryApplyContextFromStatusContent(m, content))) return
    // Sent just before the call runs, whereas its tool_call frame arrives only once it has finished —
    // too late to show a first-use download while it happens. Scoped to this turn, like the image bar.
    if (content === 'Using tool: browser') {
      browserSetupTurnKey.value = m._key ?? null
      startBrowserSetupPolling()
    }
    streamStatus.value = content ?? ''
  }

  function handleStreamReasoningEvent(m: StreamingMessage, event: { content: string }) {
    const stateChanged = markThinkingStartedIfFirst(m)
    streamReasoning.value += event.content
    // Property mutation on a shallowRef-held object does not trigger
    // reactivity. The streaming bubble reads streamReasoningHtml.value
    // (throttled markdown render below) instead of msg.reasoning, so
    // the user still sees the live update. msg.reasoning is kept in
    // sync so the post-stream renderMarkdown(msg.reasoning) path
    // displays the final text after the terminal triggerRef.
    m.reasoning = streamReasoning.value
    scheduleStreamReasoningRender()
    if (stateChanged) triggerRef(messages)
    if (!streamContent.value) {
      streamStatus.value = 'thinking...'
    }
    followToBottom()
  }

  function handleStreamTokenEvent(m: StreamingMessage, event: { content?: string, timestamp?: string }) {
    // Empty-content token events are emitted by OpenAI-compatible providers
    // (observed on Kimi K2.5 via OpenRouter) interleaved with every reasoning
    // chunk — the `content` field is always present in the API schema and
    // defaults to "" when the chunk only carries reasoning. Treating those as
    // a real reasoning→content transition was the "Thought for 0.01 seconds
    // during streaming" bug: the second empty-content token after the first
    // reasoning event was tripping the flip. Skip them entirely so the
    // transition fires only on the first byte of genuine content.
    if (!event.content) return
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
    followToBottom()
  }

  /** A turn joined late already shows its committed rounds' calls on their saved rows. */
  function shownOnSavedRow(callId: string): boolean {
    return messages.value.some(r => typeof r.id === 'number' && r.toolCalls?.some(tc => tc.id === callId))
  }

  function handleStreamToolCallEvent(m: StreamingMessage, event: ToolCallEvent) {
    // JCLAW-170: a tool invocation completed on the backend. Append the
    // structured payload to the streaming assistant message's toolCalls
    // array so the collapsible block surfaces it live. The accordion starts
    // expanded so users see in-progress activity the instant the first tool
    // finishes; once content begins to stream they can collapse it
    // themselves, and it auto-collapses on reload for historical turns.
    // Per-call: collapse any prior calls and auto-expand the just-arrived
    // one so its results are visible without an extra click.
    if (shownOnSavedRow(event.id)) return
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
    const checklist = checklistFromCall(event.name, event.arguments, event.resultText)
    if (checklist && deps.liveChecklist) deps.liveChecklist.value = checklist
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
    followToBottom()
  }

  function handleStreamCompleteEvent(m: StreamingMessage, event: { content?: string }) {
    // Reasoning-only turn (no content streamed): finalize duration here.
    finalizeThinkingOnTransition(m)
    m.content = event.content || streamContent.value
    // Collapsing thinking + swapping in final content shifts layout height.
    // Re-pin to the bottom so the next thing the user sees is fully in view.
    followToBottom()
  }

  function handleStreamStoppedEvent(m: StreamingMessage) {
    // Keep what already streamed and mark it, rather than replacing the bubble with an error.
    const existing = m.content || streamContent.value || ''
    m.content = existing ? existing.trimEnd() + '\n\n_(stopped)_' : '_(stopped before any response)_'
  }

  /** End the open turn: the bubble keeps its final text, and the saved rows, its reply committed first, are merged. */
  function endTurn() {
    if (!turn) return
    finalizeThinkingOnTransition(turn.bubble)
    dropTurn()
    flushStreamRender()
    triggerRef(messages) // re-render with final content + markdown
    // triggerRef can add height (markdown codeblocks expand, metrics pill
    // appears if usage arrived after complete). Wait a tick so the DOM
    // reflects the final layout, then pin to the bottom.
    nextTick().then(followToBottom)
    refreshConversations()
    // Re-sync agents too: the agent can rewrite its own model mid-turn via
    // the jclaw_api tool (PUT /api/agents/{id} → Agent.modelProvider/modelId).
    // The header selector reads selectedAgent through effectiveModel, so
    // without this it stays pinned to the pre-switch model until a manual
    // reload. The tool's PUT commits in its own Tx mid-turn, well before the
    // terminal frame, so the new value is already persisted by now.
    refreshAgents()
    // Every engine commits the reply before it publishes the terminal frame, so the bubble pairs with it now.
    void mergeRows()
  }

  /**
   * On open, and after a resync: if no turn runs, the saved rows are final, which covers a turn that ended before the
   * page subscribed or while the stream was away; a turn still open here then ended unseen, and its rows replace it.
   */
  async function settleIfIdle() {
    const at = inits
    const gen = viewGen
    const status = await readQueue()
    if (!status || status.busy || gen !== viewGen || at !== inits) return
    dropUnseenTurn()
    await mergeRows()
  }

  /** The open turn ended where this page could not see it: what its bubble holds is unknown, so its saved rows replace it. */
  function dropUnseenTurn() {
    const ended = turn
    if (!ended) return
    dropTurn()
    clearStreamBuffers()
    messages.value = messages.value.filter(m => m !== ended.bubble)
    triggerRef(messages)
  }

  // The stream will not reopen by itself: the Working… line and the poller show the turn until the page reopens it.
  function giveUpTurn() {
    dropUnseenTurn()
    void mergeRows()
    void readQueue()
  }

  /** Frames the page cannot have are gone: reload the rows and rebuild the turn from the frames after this one. */
  function handleResync() {
    const open = turn
    if (open) {
      clearBubble(open.bubble)
      clearStreamBuffers()
      triggerRef(messages)
    }
    const gen = viewGen
    void (async () => {
      await mergeRows({ full: true })
      if (gen === viewGen) await settleIfIdle()
    })()
  }

  function dispatchFrame(event: TurnFrame, eventId: string) {
    if (event.type === 'init') {
      handleStreamInitEvent(event, eventId)
      return
    }
    if (event.type === 'resync') {
      handleResync()
      return
    }
    const m = turn?.bubble
    if (!m) return
    switch (event.type) {
      case 'status':
        handleStreamStatusEvent(m, event as { content?: string })
        break
      case 'reasoning':
        handleStreamReasoningEvent(m, event as { content: string })
        break
      case 'token':
        handleStreamTokenEvent(m, event as { content?: string, timestamp?: string })
        break
      case 'tool_call':
        handleStreamToolCallEvent(m, event as unknown as ToolCallEvent)
        break
      case 'complete':
        handleStreamCompleteEvent(m, event as { content?: string })
        endTurn()
        break
      case 'error':
        m.content = typeof event.content === 'string' ? event.content : ''
        // A failed turn saves no reply row, so nothing pairs with it and it keeps its place.
        m._local = true
        endTurn()
        break
      case 'stopped':
        handleStreamStoppedEvent(m)
        endTurn()
        break
    }
  }

  /** Follow the conversation a send just named, which the page shows from now on. */
  function follow(conversationId: number) {
    selectedConvoId.value = conversationId
    followedConvoId.value = conversationId
  }

  /**
   * The answer to a send: the turn renders from the stream, so this only places what the stream never carries. The
   * view follows the answer's conversation, which a new chat or a load that never opened the stream had not, and a
   * stream that gave up there opens again.
   */
  async function applyAnswer(answer: TurnAnswer, sent: Message, sentConversationId: number | null,
    uploaded: UploadedAttachment[]) {
    if (answer.status === 'command') {
      await applyCommandAnswer(answer, sent, sentConversationId)
      return
    }
    if (answer.conversationId != null && followedConvoId.value !== answer.conversationId) follow(answer.conversationId)
    else stream.reopen()
    if (answer.status === 'queued') {
      messages.value = messages.value.filter(m => m !== sent)
      triggerRef(messages)
      showQueued({
        text: sent.content ?? '',
        attachmentNames: uploaded.map(u => u.originalFilename || u.kind),
        position: answer.position ?? 1,
      })
      void readQueue()
    }
  }

  async function applyCommandAnswer(answer: TurnAnswer, sent: Message, sentConversationId: number | null) {
    // The server answers a slash command without saving its text.
    sent._local = true
    const reply: Message = { _key: crypto.randomUUID(), role: 'assistant', content: answer.content ?? '',
      createdAt: new Date().toISOString(), _local: true }
    const elsewhere = answer.conversationId != null && answer.conversationId !== sentConversationId
    if (elsewhere && openConversation) {
      // /new made a conversation (JCLAW-26): shown as a load shows it, with a reply that was not saved after its rows.
      const id = answer.conversationId!
      try {
        await openConversation(id)
      }
      catch (e) {
        console.error('Failed to open the conversation a command ran in:', e)
        if (selectedConvoId.value !== id) return
        // The command ran; only showing its conversation failed. Why goes after the message, with a reply no row
        // saved, and the conversation's stream and saved rows come as after a load.
        const failed: Message = { _key: crypto.randomUUID(), role: 'assistant', content: 'Error: ' + apiErrorDetails(e).message,
          createdAt: new Date().toISOString(), _local: true }
        const rows = messages.value.includes(sent) ? messages.value : [...messages.value, sent]
        const after = rows.indexOf(sent) + 1
        messages.value = [...rows.slice(0, after), ...(answer.saved ? [] : [reply]), failed, ...rows.slice(after)]
        triggerRef(messages)
        follow(id)
        if (answer.saved) await mergeRows()
        return
      }
      // The operator opened another conversation while this one loaded.
      if (selectedConvoId.value !== id) return
      if (!answer.saved) {
        messages.value = [...messages.value, sent, reply]
        triggerRef(messages)
      }
      nextTick().then(scrollToBottom)
      return
    }
    if (elsewhere) follow(answer.conversationId!)
    if (answer.pick) deps.applyPick?.(answer.pick)
    if (answer.saved) {
      // The reply committed before the answer: the merge puts it where the server did.
      await mergeRows()
    }
    else {
      messages.value = [...messages.value, reply]
      triggerRef(messages)
    }
    nextTick().then(scrollToBottom)
    // A /stop typed here ended the open turn, whose stopped frame may never arrive: an idle read ends it on the page.
    if (turn) await settleIfIdle()
  }

  async function sendMessage() {
    if (!selectedAgentId.value) return
    // Subagent transcripts are read-only — the user reached this view via a
    // "View transcript" link, and there's no well-defined target agent to
    // post to (the dropdown shows a top-level agent, not the subagent).
    if (subagentTranscript.value) return
    const rawText = input.value.trim()
    if (!rawText && !attachedFiles.value.length) return
    const quote = deps.quote?.value ?? null
    // The turn shown as the operator typed: the uploads and a stop in flight can outlast it.
    const stopTurn = STOP_COMMAND.test(rawText) ? shownTurnId : null

    attachError.value = null
    const pending = attachedFiles.value.slice()
    const uploaded = await uploadOrReportAttachError()
    if (uploaded === null) return
    if (pendingStop) await pendingStop

    // JCLAW-25: message.content is the user's raw text. Attachment metadata
    // rides in the `attachments` field; the backend persists chat_message_attachment
    // rows and synthesizes the LLM content parts (image_url for images, bracketed
    // file references for non-images) from those rows on every turn.
    const text = rawText

    input.value = ''
    if (deps.quote) deps.quote.value = null
    revokeAttachmentPreviews(pending)
    attachedFiles.value = []
    if (chatInput.value) chatInput.value.style.height = 'auto'
    // Map the just-uploaded attachments onto the optimistic user bubble so
    // the file chips render immediately. Without this, the chips stayed
    // hidden until the next /messages refetch surfaced the persisted
    // {@link MessageAttachment} rows — observable as "I uploaded a file
    // but it doesn't show until I leave and come back."
    const sent: Message = { _key: crypto.randomUUID(), role: 'user', content: text, createdAt: new Date().toISOString(), attachments: buildOptimisticAttachments(uploaded), ...(quote ? { _quote: quote } : {}), _sending: true }
    messages.value.push(sent)
    triggerRef(messages)
    scrollToBottom()
    focusInput()

    // The message goes out as the agent it was typed under, and with the picks held then or made by a command ahead.
    const agentId = selectedAgentId.value
    let overrides = deps.pendingOverrides?.value ?? {}
    // A New Chat creates its conversation with its first message; one sent before that has settled goes there too.
    let sentConversationId = selectedConvoId.value
    let gen = viewGen
    const waited = sentConversationId === null && newChatSend != null
    for (let ahead = sentConversationId === null ? newChatSend : null; ahead;) {
      sentConversationId = await ahead.conversation
      // An answer following its conversation moved the view; leaving the chat did not.
      if (ahead.left) break
      gen = viewGen
      // A command makes no conversation: the send that went out after it may.
      ahead = sentConversationId === null && newChatSend !== ahead ? newChatSend : null
    }
    if (waited && gen === viewGen) {
      // No send ahead made a conversation, so this one does, with the picks held now: a /model or /think ahead may
      // have changed them.
      if (sentConversationId === null) overrides = deps.pendingOverrides?.value ?? {}
      // A load that answer started replaced the rows this message showed in.
      if (selectedConvoId.value === sentConversationId && !messages.value.includes(sent)) {
        messages.value = [...messages.value, sent]
        triggerRef(messages)
      }
    }
    const post = useApiMutation()
    const request = post.mutate<TurnAnswer>('/api/chat/turn', {
      method: 'POST',
      body: {
        agentId,
        conversationId: sentConversationId,
        message: text,
        attachments: uploaded,
        ...(quote ? { quote } : {}),
        ...(stopTurn != null ? { stopTurn } : {}),
        ...(sentConversationId == null ? overrides : {}),
      },
    })
    sends.add(request)
    let settle: (conversationId: number | null) => void = () => {}
    const created = sentConversationId === null
      ? {
          conversation: new Promise<number | null>((resolve) => {
            settle = resolve
          }),
          left: false,
        }
      : null
    // A message from a chat the operator left must not hold up the next chat's sends.
    if (created && gen === viewGen) newChatSend = created
    let answer: TurnAnswer | null = null
    try {
      try {
        answer = await request
      }
      finally {
        sends.delete(request)
        delete sent._sending
      }
      refreshConversations()
      // A row the last merge held back for a send not yet answered shows now, whatever the answer.
      if (deps.holdingRows?.()) void mergeRows()
      // The operator left the view the message was sent from; the turn runs on and shows wherever its conversation opens.
      if (gen !== viewGen || selectedConvoId.value !== sentConversationId) return
      // Its own answer may follow its conversation, which closes the view: that is not leaving it.
      if (created && newChatSend === created) newChatSend = null
      if (!answer) {
        sent._local = true
        const refused: Message = { _key: crypto.randomUUID(), role: 'assistant', content: 'Error: ' + (post.error.value || 'Failed to get response'), createdAt: new Date().toISOString(), _local: true }
        messages.value = [...messages.value, refused]
        triggerRef(messages)
        return
      }
      await applyAnswer(answer, sent, sentConversationId, uploaded)
    }
    finally {
      if (created) {
        if (newChatSend === created) newChatSend = null
        settle(answer?.conversationId ?? null)
      }
    }
  }

  onUnmounted(closeView)

  return {
    streamContent,
    streamStatus,
    streamingMessageKey,
    streamContentHtml,
    streamReasoningHtml,
    streamStartedAt,
    sendMessage,
    stopStreaming,
    leaveView,
  }
}
