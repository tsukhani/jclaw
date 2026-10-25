import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useEventBus } from '~/composables/useEventBus'
import { useLatestRequest } from '~/composables/useLatestRequest'
import type { McpElicitation } from '~/types/api'

/**
 * The MCP servers' forms waiting on the operator in the open conversation, oldest first. They arrive on the event
 * bus and are fetched on load and on each reconnect, since the bus replays nothing, so a reloaded page shows a
 * form still waiting.
 */
export function useMcpElicitations(selectedConvoId: Ref<number | null>): {
  current: ComputedRef<McpElicitation | null>
  remove: (id: string) => void
} {
  const pending = ref<McpElicitation[]>([])
  // A form closed while a fetch was in flight must not come back with its response, nor one announced go.
  const closed = new Set<string>()
  let announced = new Set<string>()
  const latest = useLatestRequest()

  async function refresh() {
    const token = latest.begin()
    announced = new Set()
    const convoId = selectedConvoId.value
    if (convoId == null) {
      pending.value = []
      return
    }
    let rows: McpElicitation[]
    try {
      rows = await $fetch<McpElicitation[]>('/api/mcp-servers/elicitations', { query: { conversationId: convoId } })
    }
    catch (e) {
      console.error('Failed to load MCP server questions:', e)
      return
    }
    if (!latest.isCurrent(token) || selectedConvoId.value !== convoId) return
    const fetched = (rows ?? []).filter(q => !closed.has(q.id))
    const arrived = pending.value.filter(q => announced.has(q.id) && !fetched.some(f => f.id === q.id))
    pending.value = [...fetched, ...arrived]
  }

  function add(question: McpElicitation) {
    if (question.conversationId !== selectedConvoId.value || closed.has(question.id)) return
    if (pending.value.some(q => q.id === question.id)) return
    announced.add(question.id)
    pending.value = [...pending.value, question]
  }

  function remove(id: string) {
    closed.add(id)
    pending.value = pending.value.filter(q => q.id !== id)
  }

  watch(selectedConvoId, () => {
    pending.value = []
    void refresh()
  }, { immediate: true })

  const { onEvent, onOpen } = useEventBus()
  onEvent('mcp.elicitation', data => add(data as McpElicitation))
  onEvent('mcp.elicitation.closed', (data) => {
    const id = (data as { id?: unknown } | null)?.id
    if (typeof id === 'string') remove(id)
  })
  onOpen(() => void refresh())

  return { current: computed(() => pending.value[0] ?? null), remove }
}
