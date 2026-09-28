/**
 * v-structure-viewers (JCLAW-1321): mounts StructureViewer into the ```structure figures of a v-html container.
 * Markdown renders each fence as a figure holding its code block; this claims the figure, hides the code, and mounts
 * the viewer only while the figure is near the viewport. A viewer that fails hands the figure back to its code block.
 */
import { h, render, type AppContext, type Directive, type DirectiveBinding } from 'vue'
import StructureViewer from '~/components/chat/StructureViewer.vue'
import { parseStructureFence, type StructureSource } from './fence'
import type { ViewerOptions } from './viewer'

interface Figure { host: HTMLElement, code: HTMLElement, source: StructureSource, live: boolean }
interface Container {
  observer: IntersectionObserver
  figures: Map<HTMLElement, Figure>
  agentId: number | null
  appContext: AppContext | null
}

const containers = new WeakMap<HTMLElement, Container>()
// Keyed by figure element, so a figure scrolled away and back keeps the view the user chose.
const chosen = new WeakMap<HTMLElement, ViewerOptions>()

function mount(state: Container, el: HTMLElement, figure: Figure) {
  if (figure.live) return
  figure.live = true
  const vnode = h(StructureViewer, {
    source: figure.source,
    agentId: state.agentId,
    initial: chosen.get(el) ?? { style: 'ball', cellBox: true, cells: 1, polyhedra: figure.source.format === 'cif' },
    onChange: (options: ViewerOptions) => chosen.set(el, options),
    // Deferred: the viewer reports from inside its own watcher or load, which must not unmount it mid-call.
    onFailed: (message: string) => queueMicrotask(() => fail(state, el, figure, message)),
  })
  vnode.appContext = state.appContext
  render(vnode, figure.host)
}

function unmount(figure: Figure) {
  if (!figure.live) return
  figure.live = false
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
  error.className = 'structure-error'
  error.textContent = message
  el.append(error)
}

function scan(el: HTMLElement, binding: DirectiveBinding<number | null>) {
  // Without IntersectionObserver (jsdom) the figures keep their code blocks.
  if (typeof IntersectionObserver === 'undefined') return
  let state = containers.get(el)
  if (!state) {
    const figures = new Map<HTMLElement, Figure>()
    const created: Container = {
      figures,
      agentId: null,
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
  state.agentId = binding.value ?? null
  state.appContext = binding.instance?.$.appContext ?? null
  // A new v-html string replaces every figure; release the viewers of those it removed.
  for (const [figureEl, figure] of state.figures) {
    if (!el.contains(figureEl)) forget(state, figureEl, figure)
  }
  for (const figureEl of el.querySelectorAll<HTMLElement>('figure.structure-view:not(.structure-pending):not([data-structure-claimed])')) {
    const code = figureEl.querySelector<HTMLElement>(':scope > .code-block')
    const spec = parseStructureFence(figureEl.dataset.info ?? '', figureEl.querySelector('code')?.textContent ?? '')
    if (!code || 'error' in spec) continue
    figureEl.dataset.structureClaimed = ''
    const host = document.createElement('div')
    host.className = 'structure-host'
    code.before(host)
    code.hidden = true
    state.figures.set(figureEl, { host, code, source: spec, live: false })
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

export const vStructureViewers: Directive<HTMLElement, number | null> = {
  mounted: scan,
  updated: scan,
  beforeUnmount: teardown,
}
