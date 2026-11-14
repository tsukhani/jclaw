/**
 * A box that scrolls sideways has to be reachable by keyboard (WCAG 2.1.1). Only a box that overflows becomes a
 * focusable region, so one that fits adds no empty tab stop.
 */
export function markScrollRegion(el: HTMLElement, label: string) {
  if (el.scrollWidth > el.clientWidth + 1) {
    el.tabIndex = 0
    el.setAttribute('role', 'region')
    el.setAttribute('aria-label', label)
  }
  else {
    el.removeAttribute('tabindex')
    el.removeAttribute('role')
    el.removeAttribute('aria-label')
  }
}
