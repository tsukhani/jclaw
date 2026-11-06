import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref, withDirectives } from 'vue'
import { mount } from '@vue/test-utils'
import { renderMarkdown, renderMarkdownStreaming } from '~/utils/chat-markdown'
import { MAX_VISUAL_CHARS } from '~/utils/visual/spec'
import { CHART, DIAGRAM } from './visual-fixtures'

interface Rendered { props: Record<string, unknown>, emit: (event: string, ...args: unknown[]) => void }
const rendered: Rendered[] = []

vi.mock('~/components/chat/VisualFigure.vue', async () => {
  const { defineComponent: define, h: hh } = await import('vue')
  return {
    default: define({
      props: ['spec', 'initial'],
      emits: ['failed', 'change'],
      setup(props, { emit }) {
        rendered.push({ props, emit: emit as Rendered['emit'] })
        return () => hh('div', { class: 'stub-figure' })
      },
    }),
  }
})

const { vVisualFigures } = await import('~/utils/visual/directive')

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

const fence = (spec: unknown) => `\`\`\`visual\n${JSON.stringify(spec)}\n\`\`\`\n`

function mountWith(initialHtml: string) {
  const html = ref(initialHtml)
  const wrapper = mount(defineComponent({
    setup: () => () => withDirectives(h('div', { innerHTML: html.value }), [[vVisualFigures]]),
  }), { attachTo: document.body })
  const root = wrapper.element as HTMLElement
  return {
    wrapper,
    html,
    figure: () => root.querySelector<HTMLElement>('figure.visual-view')!,
    figures: () => [...root.querySelectorAll<HTMLElement>('figure.visual-view')],
  }
}

describe('v-visual-figures (JF-195)', () => {
  beforeEach(() => {
    rendered.length = 0
    vi.stubGlobal('IntersectionObserver', FakeObserver)
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('claims a finished figure, hides its code, and mounts only while it is near the viewport', async () => {
    const { figure } = mountWith(renderMarkdown(fence(CHART)))
    const el = figure()
    expect(el.hasAttribute('data-visual-claimed')).toBe(true)
    expect(el.querySelector<HTMLElement>(':scope > .code-block')!.hidden).toBe(true)
    expect(el.querySelector(':scope > .visual-host + .code-block')).not.toBeNull()
    expect(rendered).toHaveLength(0)

    FakeObserver.last.fire(el, true)
    await nextTick()
    expect(el.querySelector('.stub-figure')).not.toBeNull()
    expect(rendered[0]!.props).toMatchObject({ spec: { kind: 'chart', title: 'Sprint velocity' }, initial: {} })

    FakeObserver.last.fire(el, false)
    await nextTick()
    expect(el.querySelector('.stub-figure')).toBeNull()
  })

  it('holds a drawn figure\'s height after it unmounts, and lets go of it on the next mount', async () => {
    const { figure } = mountWith(renderMarkdown(fence(CHART)))
    const el = figure()
    const host = el.querySelector<HTMLElement>('.visual-host')!
    expect(host.style.minHeight).toBe('')
    FakeObserver.last.fire(el, true)
    await nextTick()
    Object.defineProperty(host, 'offsetHeight', { configurable: true, value: 412 })
    FakeObserver.last.fire(el, false)
    await nextTick()
    expect(host.childElementCount).toBe(0)
    expect(host.style.minHeight).toBe('412px')
    FakeObserver.last.fire(el, true)
    await nextTick()
    expect(host.querySelector('.stub-figure')).not.toBeNull()
    expect(host.style.minHeight).toBe('')
  })

  it('reserves no measured height for a figure that never laid out', async () => {
    const { figure } = mountWith(renderMarkdown(fence(CHART)))
    const el = figure()
    FakeObserver.last.fire(el, true)
    await nextTick()
    FakeObserver.last.fire(el, false)
    await nextTick()
    expect(el.querySelector<HTMLElement>('.visual-host')!.style.minHeight).toBe('')
  })

  it('claims a figure whose body is exactly the size limit', () => {
    const json = JSON.stringify(CHART)
    const body = json.slice(0, -1) + ' '.repeat(MAX_VISUAL_CHARS - json.length) + '}'
    expect(body.length).toBe(MAX_VISUAL_CHARS)
    const { figure } = mountWith(renderMarkdown(`\`\`\`visual\n${body}\n\`\`\`\n`))
    expect(figure().hasAttribute('data-visual-claimed')).toBe(true)
    expect(FakeObserver.last.targets.has(figure())).toBe(true)
  })

  it('leaves a pending figure and an invalid fence alone', () => {
    const pending = mountWith(renderMarkdownStreaming(`${fence(CHART)}\nstill typing`))
    expect(pending.figure().classList.contains('visual-pending')).toBe(true)
    expect(pending.figure().querySelector('.visual-host')).toBeNull()
    expect(FakeObserver.last.targets.size).toBe(0)

    const invalid = mountWith(renderMarkdown(fence({ ...CHART, kind: 'pie' })))
    expect(invalid.figure()).toBeNull()
    expect(FakeObserver.last.targets.size).toBe(0)
  })

  it('mounts only the figures reported near the viewport among 20, and unmounts one that leaves', async () => {
    const text = Array.from({ length: 20 }, (_, i) => fence({ ...CHART, title: `Chart ${i}`, caption: undefined })).join('\n')
    const { figures } = mountWith(renderMarkdown(text))
    const all = figures()
    expect(all).toHaveLength(20)
    expect(FakeObserver.last.targets.size).toBe(20)
    FakeObserver.last.fire(all[3]!, true)
    FakeObserver.last.fire(all[4]!, true)
    await nextTick()
    expect(all.filter(f => f.querySelector('.stub-figure')).map(f => f.querySelector('figcaption')!.textContent))
      .toEqual(['Chart 3', 'Chart 4'])
    FakeObserver.last.fire(all[3]!, false)
    await nextTick()
    expect(all.filter(f => f.querySelector('.stub-figure'))).toEqual([all[4]])
  })

  it('releases the figures a new render replaced, and disconnects on unmount', async () => {
    const { wrapper, html, figure } = mountWith(renderMarkdown(fence(CHART)))
    const old = figure()
    FakeObserver.last.fire(old, true)
    html.value = renderMarkdown(`Revised.\n\n${fence(DIAGRAM)}`)
    await nextTick()
    expect(FakeObserver.last.targets.has(old)).toBe(false)
    expect(FakeObserver.last.targets.has(figure())).toBe(true)
    const observer = FakeObserver.last
    wrapper.unmount()
    expect(observer.disconnected).toBe(true)
  })

  it('restores the reader\'s choice when the figure comes back', () => {
    const { figure } = mountWith(renderMarkdown(fence(DIAGRAM)))
    const el = figure()
    FakeObserver.last.fire(el, true)
    rendered[0]!.emit('change', { step: 2 })
    FakeObserver.last.fire(el, false)
    FakeObserver.last.fire(el, true)
    expect(rendered[1]!.props.initial).toEqual({ step: 2 })
  })

  it('hands a failed figure back to its code block with the reason', async () => {
    const { figure } = mountWith(renderMarkdown(fence(CHART)))
    const el = figure()
    FakeObserver.last.fire(el, true)
    rendered[0]!.emit('failed', 'Could not draw this visual: boom')
    await vi.waitFor(() => expect(el.querySelector('.visual-error')).not.toBeNull())
    expect(el.querySelector('p.visual-error')!.textContent).toBe('Could not draw this visual: boom')
    expect(el.querySelector<HTMLElement>(':scope > .code-block')!.hidden).toBe(false)
    expect(el.querySelector('.visual-host')).toBeNull()
    expect(FakeObserver.last.targets.has(el)).toBe(false)
  })

  it('does nothing without IntersectionObserver, so the code block stays readable', () => {
    vi.stubGlobal('IntersectionObserver', undefined)
    const { figure } = mountWith(renderMarkdown(fence(CHART)))
    expect(figure().querySelector<HTMLElement>(':scope > .code-block')!.hidden).toBe(false)
  })
})
