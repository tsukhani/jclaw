import type { Ref } from 'vue'
import type { ConversationCompaction } from '~/types/api'
import type { CompactionCard } from '~/composables/useChatStream'

/**
 * Every summary stored for the open conversation (GET /api/conversations/{id}/compactions), for Chat's dividers. Read
 * when the conversation opens, again when `revision` moves, as a summary stored outside any turn moves it, and when
 * `card` ends done, as a turn's or a `/compact`'s summary ends it. A list that fails to load holds none, so the
 * transcript shows no divider rather than a stale one.
 */
export function useConversationSummaries(
  selectedConvoId: Ref<number | null>,
  revision: Ref<number>,
  card: Ref<CompactionCard | null>,
) {
  const summaries = ref<ConversationCompaction[]>([])
  const latest = useLatestRequest()

  async function load() {
    const token = latest.begin()
    const id = selectedConvoId.value
    if (id == null) {
      summaries.value = []
      return
    }
    try {
      const list = await $fetch<ConversationCompaction[]>(`/api/conversations/${id}/compactions`)
      if (latest.isCurrent(token)) summaries.value = Array.isArray(list) ? list : []
    }
    catch {
      if (latest.isCurrent(token)) summaries.value = []
    }
  }

  watch(selectedConvoId, () => {
    summaries.value = []
    void load()
  }, { immediate: true })
  // One watcher, so a bus event that both bumps the revision and opens a done card reads the list once.
  watch([revision, () => (card.value?.phase === 'done' ? card.value : null)], ([rev, done], [prevRev, prevDone]) => {
    if (rev !== prevRev || (done != null && done !== prevDone)) void load()
  })

  return summaries
}
