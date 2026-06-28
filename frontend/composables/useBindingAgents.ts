import type { Ref } from 'vue'
import type { Agent } from '~/types/api'

interface BindingLike {
  id: number
  agentId: number | null
}

/**
 * Agent selection shared by the per-channel binding pages (slack, telegram).
 * `enabledAgents` are the assignable agents; `availableAgents` further hides any
 * agent already bound elsewhere — agent memory is scoped per agent, so a binding
 * must not share its agent with another — while keeping the binding currently
 * being edited selectable.
 */
export function useBindingAgents<T extends BindingLike>(
  agents: Ref<Agent[] | undefined>,
  bindings: Ref<T[] | undefined>,
  editing: Ref<T | null>,
) {
  const enabledAgents = computed(() => (agents.value ?? []).filter(a => a.enabled))

  const availableAgents = computed(() => {
    const takenAgentIds = new Set<number>()
    for (const b of bindings.value ?? []) {
      if (b.agentId == null) continue
      if (editing.value && b.id === editing.value.id) continue
      takenAgentIds.add(b.agentId)
    }
    return enabledAgents.value.filter(a => !takenAgentIds.has(a.id))
  })

  return { enabledAgents, availableAgents }
}
