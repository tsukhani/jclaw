import type { ComputedRef, Ref } from 'vue'
import type { Conversation } from '~/types/api'

/**
 * The open conversation's row for the context meter. The sidebar list holds only the agent's newest 50 web
 * conversations, so an older one, or one from another channel, is not in it: this fetches that one row instead,
 * and again after each turn, as the list refreshes.
 */
export function useOpenConversationRow(
  selectedConvoId: Ref<number | null>,
  listed: ComputedRef<Conversation | null>,
  streaming: Ref<boolean>,
  /** Bumped when the row changed outside a turn, as a compaction after a reply changes it. */
  revision: Ref<number> = ref(0),
) {
  const fetched = ref<Conversation | null>(null)

  async function load() {
    const id = selectedConvoId.value
    if (id == null || streaming.value || listed.value) return
    try {
      const row = await $fetch<Conversation>(`/api/conversations/${id}`)
      if (selectedConvoId.value === id) fetched.value = row
    }
    catch { fetched.value = null }
  }

  watch([selectedConvoId, streaming, () => listed.value != null, revision], load, { immediate: true })

  return computed<Conversation | null>(() => {
    if (listed.value) return listed.value
    return fetched.value?.id === selectedConvoId.value ? fetched.value : null
  })
}
