import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref, withDirectives } from 'vue'
import { mount } from '@vue/test-utils'
import { renderMarkdown, renderMarkdownStreaming } from '~/utils/chat-markdown'
import type { ViewerOptions } from '~/utils/structure/viewer'

interface Rendered { props: Record<string, unknown>, emit: (event: string, ...args: unknown[]) => void }
const rendered: Rendered[] = []

vi.mock('~/components/chat/StructureViewer.vue', async () => {
  const { defineComponent: define, h: hh } = await import('vue')
  return {
    default: define({
      props: ['source', 'agentId', 'initial'],
      emits: ['failed', 'change'],
      setup(props, { emit }) {
        rendered.push({ props, emit: emit as Rendered['emit'] })
        return () => hh('div', { class: 'stub-viewer' })
      },
    }),
  }
})

const { vStructureViewers } = await import('~/utils/structure/directive')

class FakeObserver {
  static last: FakeObserver
  targets = new Set<Element>()
  disconnected = false
  constructor(private callback: (entries: { target: Element, isIntersecting: boolean }[]) => void) {
    FakeObserver.last = this
  }

  observe(el: Element) { this.targets.add(el) }
  unobserve(el: Element) { this.targets.delete(el) }
  disconnect() { this.disconnected = true }
  fire(el: Element, isIntersecting: boolean) { this.callback([{ target: el, isIntersecting }]) }
}

const XYZ = '3\nwater\nO 0 0 0.117\nH 0 0.757 -0.47\nH 0 -0.757 -0.47'
const CIF = 'data_x\n_cell_length_a 5.64\n'
const fence = (body: string, info = 'structure') => `\`\`\`${info}\n${body}\n\`\`\`\n`

function mountWith(initialHtml: string, agentId: number | null = 7) {
  const html = ref(initialHtml)
  const wrapper = mount(defineComponent({
    setup: () => () => withDirectives(h('div', { innerHTML: html.value }), [[vStructureViewers, agentId]]),
  }), { attachTo: document.body })
  const root = wrapper.element as HTMLElement
  return { wrapper, html, figure: () => root.querySelector<HTMLElement>('figure.structure-view')! }
}

describe('v-structure-viewers (JCLAW-1321)', () => {
  beforeEach(() => {
    rendered.length = 0
    vi.stubGlobal('IntersectionObserver', FakeObserver)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('claims a finished figure, hides its code, and mounts a viewer only while it is near the viewport', async () => {
    const { figure } = mountWith(renderMarkdown(fence(XYZ, 'structure xyz Water')))
    const el = figure()
    expect(el.querySelector<HTMLElement>(':scope > .code-block')!.hidden).toBe(true)
    expect(el.querySelector('.structure-host')).not.toBeNull()
    expect(FakeObserver.last.targets.has(el)).toBe(true)
    expect(rendered).toHaveLength(0)

    FakeObserver.last.fire(el, true)
    await nextTick()
    expect(el.querySelector('.stub-viewer')).not.toBeNull()
    expect(rendered[0]!.props).toMatchObject({
      source: { kind: 'inline', format: 'xyz', caption: 'Water' },
      agentId: 7,
      initial: { style: 'ball', cellBox: true, cells: 1, polyhedra: false },
    })

    FakeObserver.last.fire(el, false)
    await nextTick()
    expect(el.querySelector('.stub-viewer')).toBeNull()
  })

  it('turns polyhedra on by default for a CIF, and restores the user\'s choices when the figure returns', async () => {
    const { figure } = mountWith(renderMarkdown(fence(CIF)))
    const el = figure()
    FakeObserver.last.fire(el, true)
    expect(rendered[0]!.props.initial).toMatchObject({ polyhedra: true })
    const chosen: ViewerOptions = { style: 'space', cellBox: false, cells: 2, polyhedra: false }
    rendered[0]!.emit('change', chosen)
    FakeObserver.last.fire(el, false)
    FakeObserver.last.fire(el, true)
    expect(rendered[1]!.props.initial).toEqual(chosen)
  })

  it('hands a failed figure back to its code block with the reason', async () => {
    const { figure } = mountWith(renderMarkdown(fence('missing.cif')))
    const el = figure()
    FakeObserver.last.fire(el, true)
    rendered[0]!.emit('failed', 'Could not load missing.cif: HTTP 404.')
    await vi.waitFor(() => expect(el.querySelector('.structure-error')).not.toBeNull())
    expect(el.querySelector('.structure-error')!.textContent).toBe('Could not load missing.cif: HTTP 404.')
    expect(el.querySelector<HTMLElement>(':scope > .code-block')!.hidden).toBe(false)
    expect(el.querySelector('.structure-host')).toBeNull()
    expect(FakeObserver.last.targets.has(el)).toBe(false)
  })

  it('leaves a streaming reply\'s pending figure alone', () => {
    const { figure } = mountWith(renderMarkdownStreaming(`${fence(XYZ)}\nstill typing`))
    expect(figure().classList.contains('structure-pending')).toBe(true)
    expect(figure().querySelector('.structure-host')).toBeNull()
    expect(FakeObserver.last.targets.size).toBe(0)
  })

  it('releases the viewers of figures a new render replaced, and disconnects on unmount', async () => {
    const { wrapper, html, figure } = mountWith(renderMarkdown(fence(XYZ)))
    const old = figure()
    FakeObserver.last.fire(old, true)
    html.value = renderMarkdown(`Revised.\n\n${fence(CIF)}`)
    await nextTick()
    expect(FakeObserver.last.targets.has(old)).toBe(false)
    expect(FakeObserver.last.targets.has(figure())).toBe(true)
    const observer = FakeObserver.last
    wrapper.unmount()
    expect(observer.disconnected).toBe(true)
  })

  it('does nothing without IntersectionObserver, so the code block stays readable', () => {
    vi.stubGlobal('IntersectionObserver', undefined)
    const { figure } = mountWith(renderMarkdown(fence(XYZ)))
    expect(figure().querySelector<HTMLElement>(':scope > .code-block')!.hidden).toBe(false)
  })
})
