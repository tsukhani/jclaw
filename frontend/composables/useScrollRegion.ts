import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import { markScrollRegion } from '~/utils/scroll-region'

/** Keeps `target` a keyboard-reachable region exactly while it overflows, as the column or its content resizes. */
export function useScrollRegion(target: Ref<HTMLElement | null>, label: string) {
  let observer: ResizeObserver | null = null
  onMounted(() => {
    const el = target.value
    if (!el) return
    markScrollRegion(el, label)
    if (typeof ResizeObserver === 'undefined') return
    observer = new ResizeObserver(() => markScrollRegion(el, label))
    observer.observe(el)
    if (el.firstElementChild) observer.observe(el.firstElementChild)
  })
  onBeforeUnmount(() => observer?.disconnect())
}
