import { onMounted, onUnmounted, ref, watch, type Ref } from 'vue'

/**
 * The still-RUNNING subagent runs the open conversation spawned, for the chips
 * pinned above its transcript. Nothing pushes a spawn or a completion to this
 * tab, so it refetches on conversation change and at turn end, and polls while
 * a turn streams (a spawn lands mid-turn) or a chip is showing (to drop it).
 */
export interface RunningSubagent {
  id: number
  childAgentName: string | null
  childConversationId: number
}

interface SubagentRunRow {
  id: number
  childAgentName: string | null
  childConversationId: number | null
}

const POLL_INTERVAL_MS = 5000

export function useChatRunningSubagents(
  selectedConvoId: Ref<number | null>,
  streaming: Ref<boolean>,
): { runningSubagents: Ref<RunningSubagent[]> } {
  const runningSubagents = ref<RunningSubagent[]>([])
  let timer: ReturnType<typeof setInterval> | undefined

  async function refresh() {
    const convoId = selectedConvoId.value
    if (!convoId) {
      runningSubagents.value = []
      return
    }
    let runs: SubagentRunRow[]
    try {
      runs = await $fetch<SubagentRunRow[]>('/api/subagent-runs', {
        query: { parentConversationId: convoId, status: 'RUNNING' },
      }) ?? []
    }
    catch (e) {
      console.error('Failed to load running subagents:', e)
      return
    }
    if (selectedConvoId.value !== convoId) return // switched conversations mid-request
    // Inline runs write into this same conversation and already render in its transcript.
    runningSubagents.value = runs.flatMap(r =>
      r.childConversationId != null && r.childConversationId !== convoId
        ? [{ id: r.id, childAgentName: r.childAgentName, childConversationId: r.childConversationId }]
        : [],
    )
  }

  watch(selectedConvoId, () => {
    runningSubagents.value = []
    void refresh()
  }, { immediate: true })

  watch(streaming, (now, was) => {
    if (was && !now) void refresh()
  })

  onMounted(() => {
    timer = setInterval(() => {
      if (document.hidden) return
      if (streaming.value || runningSubagents.value.length > 0) void refresh()
    }, POLL_INTERVAL_MS)
  })

  onUnmounted(() => clearInterval(timer))

  return { runningSubagents }
}
