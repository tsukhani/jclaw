import { computed, ref, shallowRef, triggerRef, watch, type ComputedRef, type Ref } from 'vue'
import {
  fetchHistoryPage,
  fetchMessagesAfter,
  fetchUsageBefore,
  HISTORY_PAGE_SIZE,
  newRowsCursor,
  type MessageUsageRow,
} from '~/utils/conversation-messages'
import { hydrateToolCalls, turnWindowStart } from '~/utils/tool-calls'
import { initCollapsedState } from '~/utils/thinking'
import { backfillServerIds } from '~/utils/message-reconcile'
import type { Agent, Conversation, Message } from '~/types/api'

/**
 * Chat conversation lifecycle (JCLAW-690 stage 4f; behaviour extracted verbatim
 * from pages/chat.vue). Owns the current-conversation message state — the
 * `messages` shallowRef every other chat composable reads — plus the load /
 * switch / resolve flow, the end-of-stream id reconciliation, and the read-only
 * subagent-transcript mode.
 *
 * `loadConversation` has cross-composable side effects (stop/start video
 * polling, subagent-collapse init, autoscroll, focus) that live in other
 * composables created *after* this one — a genuine construction-order cycle,
 * since those composables in turn read this composable's `messages`. It's
 * broken with a {@link ChatConversationLoadHooks} bag: this composable is
 * created first (owning messages), the page creates the leaf composables, then
 * the page wires the hooks. The hooks only fire at load time (runtime), by
 * which point everything is wired. The page keeps the deep-link resolution
 * orchestration so it runs after that wiring.
 */
export interface ChatConversationLoadHooks {
  /** Runs before the messages fetch (e.g. tear down a prior conversation's video poll). */
  beforeLoad?: () => void
  /** Runs after messages land (subagent-collapse init, autoscroll, focus, resume video poll). */
  afterLoad?: (messages: Message[]) => void
  /** Runs after older rows are prepended, with just those rows. */
  afterPrepend?: (older: Message[]) => void
}

/** Applies a prepend of older rows while keeping the reader's place; false refuses it and leaves the list as it was. */
export type PrependOlder = (prepend: () => void) => Promise<boolean>

export interface UseChatConversationDeps {
  /** useFetch data ref — `undefined` while pending, so accept it alongside null. */
  agents: Ref<Agent[] | null | undefined>
  /** Writable — the auto-select watcher and resolveAndLoadConversation set it. */
  selectedAgentId: Ref<number | null>
  hooks: ChatConversationLoadHooks
}

export interface UseChatConversation {
  messages: Ref<Message[]>
  selectedConvoId: Ref<number | null>
  subagentTranscript: Ref<{ agentId: number, agentName: string, parentConversationId?: number | null } | null>
  effectiveDisplayAgentId: ComputedRef<number | null>
  clearSubagentTranscript: () => void
  initializing: Ref<boolean>
  reconcileMessageIds: () => Promise<void>
  resolveAndLoadConversation: (id: number) => Promise<boolean>
  loadConversation: (id: number) => Promise<void>
  /** True while the shown messages start after the conversation's first row. */
  hasOlderMessages: Ref<boolean>
  loadingOlder: Ref<boolean>
  /** Token usage of the rows older than the shown ones, so a total still covers the whole conversation. */
  earlierUsages: Ref<MessageUsageRow[]>
  loadOlderMessages: (apply?: PrependOlder) => Promise<void>
}

export function useChatConversation(deps: UseChatConversationDeps): UseChatConversation {
  const { agents, selectedAgentId, hooks } = deps

  const selectedConvoId = ref<number | null>(null)
  const messages = shallowRef<Message[]>([])
  const initializing = ref(true) // suppresses agent-change clear during setup

  /**
   * Read-only transcript mode for subagent conversations (JCLAW-274).
   *
   * Subagents are filtered out of {@code /api/agents} (they don't belong in the
   * user-facing dropdown), so their conversations can't be "selected" the way
   * top-level agents' can. When the user opens {@code /chat?conversation=ID}
   * from the /subagents page or a {@code subagent_announce} card, this ref
   * holds the owning subagent's identity so the page can:
   *   1. Render a "Read-only transcript" banner naming the subagent.
   *   2. Disable the composer — sending a message would post against whatever
   *      agent the dropdown happens to be showing, not the subagent that owns
   *      the transcript.
   *   3. Pass the subagent's id into {@code renderMarkdown} so workspace-file
   *      links inside the transcript resolve against the subagent's
   *      workspace, not the parent agent's.
   *
   * Cleared by {@code clearSubagentTranscript} whenever the user navigates
   * away from the read-only view (agent change, new chat, sidebar Recents
   * click, or deep-linking to a non-subagent conversation).
   */
  const subagentTranscript = ref<{ agentId: number, agentName: string, parentConversationId?: number | null } | null>(null)

  /** Effective agent id for {@code renderMarkdown} workspace-link rewriting. */
  const effectiveDisplayAgentId = computed(() => subagentTranscript.value?.agentId ?? selectedAgentId.value)

  function clearSubagentTranscript() {
    subagentTranscript.value = null
  }

  /**
   * Backfill server ids onto the local `messages` array after a stream turn.
   *
   * The stream protocol doesn't emit persisted message ids (see
   * ApiChatController.writeSse — the complete event carries only content).
   * Without this, the optimistic user + assistant bubbles stay id-less until
   * the user leaves and re-enters the conversation, which disabled the Delete
   * button (gated on `msg.id`) and caused pollForAnnounce to treat the
   * server-side copy of those same rows as new additions, duplicating the
   * user bubble in the transcript.
   */
  async function reconcileMessageIds() {
    const convoId = selectedConvoId.value
    if (!convoId) return
    try {
      const fresh = await fetchMessagesAfter(convoId, newRowsCursor(messages.value))
      if (selectedConvoId.value !== convoId) return
      if (!fresh?.length) return
      if (backfillServerIds(messages.value, fresh)) triggerRef(messages)
    }
    catch (e) {
      console.error('Failed to reconcile message ids:', e)
    }
  }

  // Auto-select agent on load
  watch(agents, (val) => {
    if (!val?.length || selectedAgentId.value) return
    const def = val.find(a => a.isMain) || val[0]
    if (!def) return
    selectedAgentId.value = def.id
  }, { immediate: true })

  // When agent changes, clear current conversation (unless during initial setup)
  watch(selectedAgentId, () => {
    if (initializing.value) return
    selectedConvoId.value = null
    messages.value = []
    clearSubagentTranscript()
  })

  /**
   * Resolve and load a deep-linked conversation that isn't in the current
   * agent's list. Returns {@code true} if the conversation was loaded (either
   * by switching agents or by entering read-only subagent-transcript mode),
   * {@code false} if it couldn't be resolved.
   *
   * Uses {@code GET /api/conversations/{id}} so it works for any channel type
   * (web, telegram, subagent, etc.) — the prior implementation scanned
   * {@code /api/conversations?channel=web&limit=100} which structurally
   * missed subagent conversations stamped with {@code channelType="subagent"}.
   */
  async function resolveAndLoadConversation(id: number): Promise<boolean> {
    let convo: Conversation | null = null
    try {
      convo = await $fetch<Conversation>(`/api/conversations/${id}`)
    }
    catch { return false }
    if (!convo) return false

    const owningAgent = agents.value?.find(a => a.id === convo.agentId)
    if (owningAgent) {
      // Top-level agent we know about — switch the dropdown to it. The
      // selectedAgentId watcher would normally clear messages, but we suppress
      // that during initial setup via {@code initializing}; for in-page
      // navigations we accept the brief clear since loadConversation runs next.
      clearSubagentTranscript()
      if (owningAgent.id !== selectedAgentId.value) {
        selectedAgentId.value = owningAgent.id
      }
      await loadConversation(id)
      return true
    }

    // Owning agent is filtered from the dropdown — almost always a subagent
    // (parentAgent != null). Enter read-only transcript mode. We deliberately
    // do NOT mutate selectedAgentId here: the dropdown should keep showing
    // whatever top-level agent the user had chosen, and the banner names the
    // subagent.
    subagentTranscript.value = {
      agentId: convo.agentId,
      agentName: convo.agentName,
      parentConversationId: convo.parentConversationId,
    }
    await loadConversation(id)
    return true
  }

  // JCLAW-170: fold persisted tool-role rows into the following assistant
  // message's toolCalls array so the tool-calls block re-renders on reload.
  // Mutates in place, then we also collapse the block by default for
  // historical turns — same UX as the thinking card.
  function prepareHistorical(rows: Message[]) {
    hydrateToolCalls(rows as unknown as Array<Record<string, unknown>>)
    for (const m of rows) {
      if (!m.toolCalls?.length) continue
      // Outer accordion: collapsed by default on historical turns to keep the
      // transcript dense; users click "N tool calls" to drill in.
      (m as Message & { toolCallsCollapsed?: boolean }).toolCallsCollapsed = true
      // Per-call expansion: pre-expand the LAST call so opening the accordion
      // surfaces its chip grid immediately. Earlier calls stay collapsed —
      // user can expand them individually if they want to compare results.
      for (let i = 0; i < m.toolCalls.length; i++) {
        m.toolCalls[i]!._expanded = i === m.toolCalls.length - 1
      }
    }
    initCollapsedState(rows)
  }

  // The shown rows are the newest part of the conversation; rows fetched but not yet shown wait in heldBack, because
  // the window may only open where hydration can start (turnWindowStart). Bumping windowGen orphans in-flight fetches.
  let heldBack: Message[] = []
  let historyExhausted = true
  let windowGen = 0
  const hasOlderMessages = ref(false)
  const loadingOlder = ref(false)
  const earlierUsages = shallowRef<MessageUsageRow[]>([])

  function resetWindow() {
    windowGen++
    heldBack = []
    historyExhausted = true
    hasOlderMessages.value = false
    loadingOlder.value = false
    earlierUsages.value = []
  }
  watch(selectedConvoId, resetWindow, { flush: 'sync' })

  /** Pulls older pages in front of `rows` until a turn can open the window; null when `current` turns false meanwhile. */
  async function windowFrom(id: number, rows: Message[], exhausted: boolean, oldestShown: number | null,
    current: () => boolean) {
    let start = turnWindowStart(rows, exhausted)
    while (start < 0) {
      const page = await fetchHistoryPage(id, rows[0]?.id ?? oldestShown)
      if (!current()) return null
      rows = [...page, ...rows]
      exhausted = page.length < HISTORY_PAGE_SIZE
      start = turnWindowStart(rows, exhausted)
    }
    return { held: rows.slice(0, start), shown: rows.slice(start), exhausted }
  }

  function keepWindow(held: Message[], exhausted: boolean) {
    heldBack = held
    historyExhausted = exhausted
    hasOlderMessages.value = held.length > 0 || !exhausted
  }

  async function loadEarlierUsages(id: number, before: number, current: () => boolean) {
    try {
      const rows = await fetchUsageBefore(id, before)
      if (!current()) return
      const oldestShown = messages.value[0]?.id ?? before
      earlierUsages.value = rows.filter(u => u.id < oldestShown)
    }
    catch (e) {
      console.error('Failed to load the earlier usage:', e)
    }
  }

  const loads = useLatestRequest()
  async function loadConversation(id: number) {
    const request = loads.begin()
    selectedConvoId.value = id
    resetWindow()
    const gen = windowGen
    const current = () => loads.isCurrent(request) && gen === windowGen
    hooks.beforeLoad?.() // a prior conversation's poll loop shouldn't leak into this one
    const newest = await fetchHistoryPage(id, null)
    if (!current()) return
    const window = await windowFrom(id, newest, newest.length < HISTORY_PAGE_SIZE, null, current)
    if (!window) return
    keepWindow(window.held, window.exhausted)
    prepareHistorical(window.shown)
    messages.value = window.shown
    hooks.afterLoad?.(messages.value)
    const oldestShown = window.shown[0]?.id
    if (hasOlderMessages.value && oldestShown != null) void loadEarlierUsages(id, oldestShown, current)
  }

  async function loadOlderMessages(apply: PrependOlder = async (prepend) => {
    prepend()
    return true
  }) {
    const id = selectedConvoId.value
    const oldestShown = messages.value[0]?.id
    if (!id || oldestShown == null || !hasOlderMessages.value || loadingOlder.value) return
    const gen = windowGen
    const current = () => gen === windowGen
    loadingOlder.value = true
    try {
      const window = await windowFrom(id, heldBack, historyExhausted, oldestShown, current)
      if (!window) return
      const committed = await apply(() => {
        prepareHistorical(window.shown)
        keepWindow(window.held, window.exhausted)
        const oldestNow = window.shown[0]?.id ?? oldestShown
        earlierUsages.value = earlierUsages.value.filter(u => u.id < oldestNow)
        messages.value = [...window.shown, ...messages.value]
        hooks.afterPrepend?.(window.shown)
      })
      // Refused rows stay fetched, unhydrated, so the next attempt shows them without asking again.
      if (!committed && current()) keepWindow([...window.held, ...window.shown], window.exhausted)
    }
    catch (e) {
      console.error('Failed to load earlier messages:', e)
    }
    finally {
      if (current()) loadingOlder.value = false
    }
  }

  return {
    messages,
    selectedConvoId,
    subagentTranscript,
    effectiveDisplayAgentId,
    clearSubagentTranscript,
    initializing,
    reconcileMessageIds,
    resolveAndLoadConversation,
    loadConversation,
    hasOlderMessages,
    loadingOlder,
    earlierUsages,
    loadOlderMessages,
  }
}
