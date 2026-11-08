import { ref, watch, type Ref } from 'vue'
import { useEventBus } from '~/composables/useEventBus'
import { useLatestRequest } from '~/composables/useLatestRequest'
import { splitQuotedReply } from '~/utils/quoted-reply'

/** A message waiting in the conversation's queue, as GET /api/conversations/{id}/queue lists it. */
export interface QueuedMessageView {
  text: string
  attachmentNames: string[]
  /** Its place in line, 1 for the next to run. */
  position: number
}

/** GET /api/conversations/{id}/queue. */
export interface ConversationQueueStatus {
  busy: boolean
  queueSize?: number
  /** Whether the operator's Stop can stop the running turn: any but an app conversation's (JF-202, JF-197b). */
  stoppable?: boolean
  /** Whether Stop drops the messages queued behind the turn, as on a voice conversation; on every other the next runs. */
  stopDropsQueue?: boolean
  pending?: QueuedMessageView[]
  /** How many `/queue clear`s have dropped a queued message: a read after one lists fewer, as after a hand-over (JF-227). */
  clears?: number
}

export interface UseChatBackgroundTurnDeps {
  selectedConvoId: Ref<number | null>
  /** True while the conversation stream reports a turn, which then renders it. */
  streaming: Ref<boolean>
  /** Merges the rows the server added since the last fetch (useChatAnnouncePoller's pollForAnnounce). */
  fetchNewRows: () => Promise<void>
  /** Whether the conversation stream is up; while it is down, no turn's init comes to end a held Queued row. */
  streamUp?: Ref<boolean>
}

export interface UseChatBackgroundTurn {
  /** A turn holds the conversation that the stream does not report: the "Working…" line. */
  backgroundBusy: Ref<boolean>
  /** From the last read: whether Stop can stop the running turn. */
  stoppable: Ref<boolean>
  /** From the last read: every message queued behind the turn, a waiting subagent result included, which `pending` leaves out. */
  queueSize: Ref<number>
  /** From the last read: whether Stop drops those messages rather than running the next one. */
  stopDropsQueue: Ref<boolean>
  /** The queued messages, from the last read, or a send the server just answered queued. */
  pending: Ref<QueuedMessageView[]>
  /**
   * Read the queue now: the server's answer, applied unless a newer read overtook it; null when the read failed or the
   * operator switched conversations meanwhile. `init` marks the read a turn's `init` makes once its rows are in, which
   * may drop the message that turn took from the queue.
   */
  readQueue: (opts?: { init?: boolean }) => Promise<ConversationQueueStatus | null>
  /** Show a message the server just queued until the next read lists it. */
  showQueued: (message: QueuedMessageView) => void
  /** A turn began: until its own read lands, Stop may not show from the turn before it. */
  clearStoppable: () => void
}

/**
 * The open conversation's queue, read from GET /api/conversations/{id}/queue on open, on each reconnect, after each
 * turn, on each `conversation.busy` event and whenever the conversation stream asks (on open, on each turn's `init`).
 * The event is only a cue to read: two threads' transitions can publish out of order.
 *
 * `backgroundBusy` is a turn holding the conversation that the stream does not report (the gap before its `init`, or a
 * stream that is down); it stays false while the stream reports one. When such a turn ends, the rows it wrote last,
 * its reply among them, are fetched.
 */
export function useChatBackgroundTurn(deps: UseChatBackgroundTurnDeps): UseChatBackgroundTurn {
  const { selectedConvoId, streaming, fetchNewRows } = deps
  const backgroundBusy = ref(false)
  const stoppable = ref(false)
  const queueSize = ref(0)
  const stopDropsQueue = ref(false)
  const pending = ref<QueuedMessageView[]>([])
  const reads = useLatestRequest()
  /** The `clears` of the last read applied to this conversation; null until one is. */
  let appliedClears: number | null = null

  /** @param cued a busy event or a bus reopen asked for this read, so a turn may have ended unseen */
  async function refresh(cued = false, init = false): Promise<ConversationQueueStatus | null> {
    const token = reads.begin()
    const convoId = selectedConvoId.value
    if (convoId == null) {
      backgroundBusy.value = false
      stoppable.value = false
      queueSize.value = 0
      stopDropsQueue.value = false
      pending.value = []
      return null
    }
    let status: ConversationQueueStatus | null
    try {
      status = await $fetch<ConversationQueueStatus>(`/api/conversations/${convoId}/queue`)
    }
    catch {
      status = null
    }
    if (selectedConvoId.value !== convoId) return null
    if (!reads.isCurrent(token)) return status
    const busy = status ? status.busy === true : null
    // A turn the line was shown for, or one a cue reported, wrote rows this tab did not stream.
    const unseenRows = !streaming.value && (backgroundBusy.value || cued)
    backgroundBusy.value = busy === true && !streaming.value
    if (status) {
      stoppable.value = status.stoppable === true
      queueSize.value = status.queueSize ?? 0
      stopDropsQueue.value = status.stopDropsQueue === true
      const next = status.pending ?? []
      const clears = status.clears ?? 0
      // A message leaves the list when its turn takes it, before that turn's init brings its saved row: kept until then.
      // A clear shortens it too, but for good, so a read whose `clears` moved is never held.
      const held = busy && !init && deps.streamUp?.value !== false && next.length < pending.value.length
        && clears === appliedClears
      if (!held) pending.value = next
      appliedClears = clears
    }
    if (unseenRows && busy === false) void fetchNewRows()
    return status
  }

  function reread() {
    backgroundBusy.value = false
    void refresh()
  }

  watch(selectedConvoId, () => {
    stoppable.value = false
    queueSize.value = 0
    stopDropsQueue.value = false
    pending.value = []
    appliedClears = null
    reread()
  }, { immediate: true })
  // Each turn's end: a message queued behind it may run on before its init arrives.
  watch(streaming, (now, was) => {
    if (was && !now) reread()
  })

  const { onEvent, onOpen } = useEventBus()
  onEvent('conversation.busy', (data) => {
    const id = (data as { conversationId?: unknown } | null)?.conversationId
    if (id != null && id === selectedConvoId.value) void refresh(true)
  })
  // The bus replays nothing, so a change published while it was down is read here.
  onOpen(() => void refresh(true))

  // A read can list the message before the send's answer arrives; it carries the quote the server folded in.
  function showQueued(message: QueuedMessageView) {
    const listed = pending.value.some(p => p.position === message.position
      && (splitQuotedReply(p.text)?.reply ?? p.text) === message.text)
    if (listed) return
    pending.value = [...pending.value, message]
    queueSize.value++
  }

  return {
    backgroundBusy,
    stoppable,
    queueSize,
    stopDropsQueue,
    pending,
    readQueue: opts => refresh(false, opts?.init === true),
    showQueued,
    clearStoppable: () => {
      stoppable.value = false
    },
  }
}
