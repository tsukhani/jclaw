import { ref, watch, type Ref } from 'vue'
import { useEventBus } from '~/composables/useEventBus'
import { useLatestRequest } from '~/composables/useLatestRequest'

interface ConversationQueueStatus {
  busy: boolean
}

export interface UseChatBackgroundTurnDeps {
  selectedConvoId: Ref<number | null>
  streaming: Ref<boolean>
  /** Merges the rows the server added since the last fetch (useChatAnnouncePoller's pollForAnnounce). */
  fetchNewRows: () => Promise<void>
}

/**
 * Whether a turn this tab is not streaming holds the open conversation: a yield resume, a drained
 * queued message, a follow-up, or POST /api/chat/send. It is read from GET /api/conversations/{id}/queue
 * on open, on each reconnect, after the tab's own stream and on each `conversation.busy` event. The event
 * is only a cue to read: two threads' transitions can publish out of order. When such a turn ends, the
 * rows it wrote last, its reply among them, are fetched.
 */
export function useChatBackgroundTurn(deps: UseChatBackgroundTurnDeps): { backgroundBusy: Ref<boolean> } {
  const { selectedConvoId, streaming, fetchNewRows } = deps
  const backgroundBusy = ref(false)
  const reads = useLatestRequest()

  /** @param cued a busy event or a bus reopen asked for this read, so a turn may have ended unseen */
  async function refresh(cued = false) {
    const token = reads.begin()
    const convoId = selectedConvoId.value
    if (convoId == null) {
      backgroundBusy.value = false
      return
    }
    let busy: boolean | null
    try {
      busy = (await $fetch<ConversationQueueStatus>(`/api/conversations/${convoId}/queue`)).busy === true
    }
    catch {
      busy = null
    }
    if (!reads.isCurrent(token) || selectedConvoId.value !== convoId) return
    // A turn the line was shown for, or one a cue reported, wrote rows this tab did not stream.
    const unseenRows = !streaming.value && (backgroundBusy.value || cued)
    backgroundBusy.value = busy === true
    if (unseenRows && busy === false) void fetchNewRows()
  }

  function reread() {
    backgroundBusy.value = false
    void refresh()
  }

  watch(selectedConvoId, reread, { immediate: true })
  // The tab's own turn set the flag while it streamed; a message queued behind it runs on with no stream.
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

  return { backgroundBusy }
}
