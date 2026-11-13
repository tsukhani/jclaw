import { onMounted, type Ref } from 'vue'

/** The router's classifier failing turn after turn, as `GET /api/router/status` and the `router.classifier` event carry it. */
export interface ClassifierAlert {
  classifier: string
  consecutiveFailures: number
  since: string
  lastError: string
}

/**
 * The router classifier's alert, shared by every caller. It is read once when a caller mounts and again on each
 * event-stream reconnect, which replays nothing missed, and follows the `router.classifier` events in between, so
 * showing it adds no poll.
 */
export function useClassifierAlert(): { alert: Ref<ClassifierAlert | null> } {
  const alert = useState<ClassifierAlert | null>('router:classifierAlert', () => null)
  const authenticated = useState<boolean>('auth:authenticated')
  const { onEvent, onOpen } = useEventBus()

  async function load() {
    if (!authenticated.value) return
    try {
      const status = await $fetch<{ classifierAlert?: ClassifierAlert | null }>('/api/router/status')
      alert.value = status.classifierAlert ?? null
    }
    catch { /* the next event or reconnect corrects it */ }
  }

  onEvent('router.classifier', (data) => {
    alert.value = (data as { alert?: ClassifierAlert }).alert ?? null
  })
  onOpen(() => {
    void load()
  })
  onMounted(() => {
    void load()
  })
  return { alert }
}

/** One sentence for the banner and the router settings. */
export function classifierAlertMessage(alert: ClassifierAlert): string {
  return `The router's classifier ${alert.classifier} has failed on ${alert.consecutiveFailures} turns in a row, `
    + `so keyword rules are choosing the models: ${alert.lastError}`
}
