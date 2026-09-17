import type { ApiErrorDetails } from '~/types/api'

/**
 * Runs a write and keeps why it failed for an ApiErrorAlert, so a refused save is explained on the page
 * rather than only in the console. `attempt` resolves to whether the write went through.
 */
export function useSaveAttempt() {
  const saveError = ref<ApiErrorDetails | null>(null)

  async function attempt(write: () => Promise<unknown>): Promise<boolean> {
    saveError.value = null
    try {
      await write()
      return true
    }
    catch (e) {
      saveError.value = apiErrorDetails(e)
      return false
    }
  }

  return { saveError, attempt }
}
