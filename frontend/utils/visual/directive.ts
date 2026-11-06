/**
 * v-visual-figures (JF-195): mounts VisualFigure into the ```visual figures of a v-html container, as
 * v-structure-viewers does for structures. A figure is drawn only while it is near the viewport, and one that fails
 * hands the figure back to its code block.
 */
import { h, render, type AppContext, type Directive, type DirectiveBinding } from 'vue'
import VisualFigure from '~/components/chat/VisualFigure.vue'
import { parseVisualSpec, type VisualSpec, type VisualState } from './spec'

interface Figure { host: HTMLElement, code: HTMLElement, spec: VisualSpec, live: boolean }
interface Container { observer: IntersectionObserver, figures: Map<HTMLElement, Figure>, appContext: AppContext | null }

const containers = new WeakMap<HTMLElement, Container>()
// Keyed by figure element, so a figure scrolled away and back keeps its step or slider positions.
const chosen = new WeakMap<HTMLElement, VisualState>()

function mount(state: Container, el: HTMLElement, figure: Figure) {
  if (figure.live) return
  figure.live = true
  const vnode = h(VisualFigure, {
    spec: figure.spec,
    initial: chosen.get(el) ?? {},
    onChange: (next: VisualState) => chosen.set(el, next),
    // Deferred: the figure reports from inside its own render, which must not unmount it mid-call.
    onFailed: (message: string) => queueMicrotask(() => fail(state, el, figure, message)),
  })
  vnode.appContext = state.appContext
  render(vnode, figure.host)
  figure.host.style.minHeight = ''
}

function unmount(figure: Figure) {
  if (!figure.live) return
  figure.live = false
  // An emptied host would collapse to nothing: the transcript would jump, and every collapsed figure would fall
  // inside the observer's margin and mount at once.
  const height = figure.host.offsetHeight
  if (height > 0) figure.host.style.minHeight = `${height}px`
  render(null, figure.host)
}

function forget(state: Container, el: HTMLElement, figure: Figure) {
  unmount(figure)
  state.observer.unobserve(el)
  state.figures.delete(el)
}

function fail(state: Container, el: HTMLElement, figure: Figure, message: string) {
  forget(state, el, figure)
  figure.host.remove()
  figure.code.hidden = false
  const error = document.createElement('p')
  error.className = 'visual-error'
  error.textContent = message
  el.append(error)
}

function scan(el: HTMLElement, binding: DirectiveBinding<void>) {
  // Without IntersectionObserver (jsdom) the figures keep their code blocks.
  if (typeof IntersectionObserver === 'undefined') return
  let state = containers.get(el)
  if (!state) {
    const figures = new Map<HTMLElement, Figure>()
    const created: Container = {
      figures,
      appContext: null,
      observer: new IntersectionObserver((entries) => {
        for (const entry of entries) {
          const figure = figures.get(entry.target as HTMLElement)
          if (!figure) continue
          if (entry.isIntersecting) mount(created, entry.target as HTMLElement, figure)
          else unmount(figure)
        }
      }, { rootMargin: '300px 0px' }),
    }
    containers.set(el, created)
    state = created
  }
  state.appContext = binding.instance?.$.appContext ?? null
  // A new v-html string replaces every figure; release those it removed.
  for (const [figureEl, figure] of state.figures) {
    if (!el.contains(figureEl)) forget(state, figureEl, figure)
  }
  for (const figureEl of el.querySelectorAll<HTMLElement>('figure.visual-view:not(.visual-pending):not([data-visual-claimed])')) {
    const code = figureEl.querySelector<HTMLElement>(':scope > .code-block')
    // Marked ends a code block's text with a newline the fence body did not have; it would count against the limit.
    const spec = parseVisualSpec((figureEl.querySelector('code')?.textContent ?? '').replace(/\n$/, ''))
    if (!code || 'error' in spec) continue
    figureEl.dataset.visualClaimed = ''
    const host = document.createElement('div')
    host.className = 'visual-host'
    code.before(host)
    code.hidden = true
    state.figures.set(figureEl, { host, code, spec, live: false })
    state.observer.observe(figureEl)
  }
}

function teardown(el: HTMLElement) {
  const state = containers.get(el)
  if (!state) return
  for (const figure of state.figures.values()) unmount(figure)
  state.observer.disconnect()
  containers.delete(el)
}

export const vVisualFigures: Directive<HTMLElement, void> = {
  mounted: scan,
  updated: scan,
  beforeUnmount: teardown,
}
