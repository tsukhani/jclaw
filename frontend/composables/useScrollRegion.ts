import { onBeforeUnmount, watch, type Ref } from 'vue'
import { markScrollRegion } from '~/utils/scroll-region'

/**
 * Keeps `target` a keyboard-reachable region exactly while it overflows, as the column or its content resizes.
 * It follows the element as it comes and goes, since a card rendered after its data loads appears after mount.
 */
export function useScrollRegion(target: Ref<HTMLElement | null>, label: string) {
  let observer: ResizeObserver | null = null
  const stop = watch(target, (el) => {
    observer?.disconnect()
    observer = null
    if (!el) return
    markScrollRegion(el, label)
    if (typeof ResizeObserver === 'undefined') return
    observer = new ResizeObserver(() => markScrollRegion(el, label))
    observer.observe(el)
    if (el.firstElementChild) observer.observe(el.firstElementChild)
  }, { immediate: true, flush: 'post' })
  onBeforeUnmount(() => {
    stop()
    observer?.disconnect()
  })
}
