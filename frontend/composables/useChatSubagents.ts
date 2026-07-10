import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { useEventBus } from '~/composables/useEventBus'
import type { Message } from '~/types/api'

/**
 * Inline-subagent display state (JCLAW-690 stage 4b; behaviour extracted
 * verbatim from pages/chat.vue).
 *
 * Owns the collapsible-block state for inline subagent runs (JCLAW-267), the
 * per-conversation active coding-run tracking (JCLAW-663), and the derived
 * header/status labels the ChatMessage renderer consumes. This is pure display
 * derivation over the already-filtered `displayMessages` list plus a collapsed
 * Set — it does not touch the network or the message store, so it stays a
 * separate cohesive unit from the async-announce poller (which is conversation
 * refresh, not display).
 */

/**
 * Per-position grouping hints derived from displayMessages: for each message
 * index, whether that message opens / continues / closes an inline-subagent
 * run, and whether the surrounding run should be collapsed.
 */
export interface SubagentRunSlice {
  runId: number
  position: 'first' | 'middle' | 'last'
  collapsed: boolean
}

export interface UseChatSubagents {
  subagentRunSlices: ComputedRef<Array<SubagentRunSlice | null>>
  activeCodingRunId: ComputedRef<number | null>
  initSubagentCollapsedState: (msgs: Message[]) => void
  toggleSubagentRun: (runId: number) => void
  subagentBlockLabel: (runId: number, msgs: Message[]) => string
  subagentBlockStatus: (runId: number, msgs: Message[]) => string
}

export function useChatSubagents(
  displayMessages: Ref<Message[]>,
  selectedConvoId: Ref<number | null>,
): UseChatSubagents {
  /**
   * JCLAW-267: state for the inline-subagent collapsible blocks. Each unique
   * subagentRunId in displayMessages becomes one fold-able block; the Set
   * tracks which blocks are currently collapsed. Default is "collapsed unless
   * the run failed" — see {@link initSubagentCollapsedState} below.
   */
  const collapsedSubagentRuns = ref<Set<number>>(new Set())

  /**
   * Per-position grouping hints derived from displayMessages. Computed once
   * per render so the template doesn't re-walk the list inside v-if guards.
   */
  const subagentRunSlices = computed<Array<SubagentRunSlice | null>>(() => {
    const msgs = displayMessages.value
    const out: Array<SubagentRunSlice | null> = []
    for (let i = 0; i < msgs.length; i++) {
      const runId = msgs[i]?.subagentRunId ?? null
      if (!runId) {
        out.push(null)
        continue
      }
      const prevRunId = i > 0 ? (msgs[i - 1]?.subagentRunId ?? null) : null
      const nextRunId = i < msgs.length - 1 ? (msgs[i + 1]?.subagentRunId ?? null) : null
      const isFirst = prevRunId !== runId
      const isLast = nextRunId !== runId
      let position: 'first' | 'middle' | 'last'
      if (isFirst) position = 'first'
      else if (isLast) position = 'last'
      else position = 'middle'
      out.push({
        runId,
        position,
        collapsed: collapsedSubagentRuns.value.has(runId),
      })
    }
    return out
  })

  /**
   * JCLAW-663: track the active external coding-harness ("acp" runtime) run per
   * conversation. A `codingrun.step` event carries the parent {@code
   * conversationId} and the {@code runId}; the first step for a conversation
   * mounts the {@link CodingRunMonitor} docked above the composer. Keyed by
   * conversation so switching chats shows the right run (or none), and so a run
   * left visible on one conversation doesn't leak into another.
   */
  const codingRunByConversation = ref<Record<number, number>>({})
  const activeCodingRunId = computed<number | null>(() => {
    const cid = selectedConvoId.value
    return cid == null ? null : (codingRunByConversation.value[cid] ?? null)
  })
  useEventBus().onEvent('codingrun.step', (data) => {
    const d = data as { runId?: number, conversationId?: number }
    if (!d.runId) return
    // conversationId is the parent (chat) conversation; fall back to the open
    // conversation when the harness omits it (run triggered in this chat).
    const cid = d.conversationId ?? selectedConvoId.value
    if (cid == null || codingRunByConversation.value[cid] === d.runId) return
    codingRunByConversation.value = { ...codingRunByConversation.value, [cid]: d.runId }
  })

  /**
   * Initialize the collapsed state: every run in displayMessages starts
   * collapsed UNLESS its boundary-end marker indicates a non-completed terminal
   * status (failed / timed out) — operators want failed runs expanded by
   * default so the error is visible without a click. Idempotent: re-running
   * preserves the operator's per-run toggle state for runs already in the set.
   */
  function initSubagentCollapsedState(msgs: Message[]): void {
    const seen = new Set<number>()
    const next = new Set<number>(collapsedSubagentRuns.value)
    for (const m of msgs) {
      const runId = m.subagentRunId ?? null
      if (!runId || seen.has(runId)) continue
      seen.add(runId)
      if (next.has(runId)) continue // operator already toggled this run
      // Walk forward to find the boundary-end marker for this run; expand the
      // block by default on a failed terminal status so the error is visible.
      let collapse = true
      for (const m2 of msgs) {
        if (m2.subagentRunId !== runId) continue
        const c = m2.content ?? ''
        if (c.startsWith('Subagent failed') || c.startsWith('Subagent timeout')) {
          collapse = false
          break
        }
      }
      if (collapse) next.add(runId)
    }
    collapsedSubagentRuns.value = next
  }

  function toggleSubagentRun(runId: number): void {
    const next = new Set(collapsedSubagentRuns.value)
    if (next.has(runId)) next.delete(runId)
    else next.add(runId)
    collapsedSubagentRuns.value = next
  }

  /**
   * Build the header label for an inline-subagent collapsible block. Derived
   * from the boundary-start marker's content (set by SubagentSpawnTool); falls
   * back to a neutral "Subagent run" label when the marker is absent (shouldn't
   * happen in practice but defensive).
   */
  function subagentBlockLabel(runId: number, msgs: Message[]): string {
    for (const m of msgs) {
      if (m.subagentRunId !== runId) continue
      const c = m.content ?? ''
      if (c.startsWith('Spawning subagent:')) {
        return c.replace(/^Spawning subagent:\s*/, '').trim() || 'Subagent run'
      }
    }
    return 'Subagent run'
  }

  /**
   * Extract a short terminal-status hint ("Completed" / "Failed" / "Timed out" /
   * "Running") for the run header from its boundary-end marker. Empty string
   * while the block has no end marker yet (streaming case).
   */
  function subagentBlockStatus(runId: number, msgs: Message[]): string {
    for (const m of msgs) {
      if (m.subagentRunId !== runId) continue
      const c = m.content ?? ''
      if (c.startsWith('Subagent completed')) return 'Completed'
      if (c.startsWith('Subagent failed')) return 'Failed'
      if (c.startsWith('Subagent timeout')) return 'Timed out'
    }
    return 'Running'
  }

  return {
    subagentRunSlices,
    activeCodingRunId,
    initSubagentCollapsedState,
    toggleSubagentRun,
    subagentBlockLabel,
    subagentBlockStatus,
  }
}
