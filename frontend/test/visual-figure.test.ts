import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import VisualFigure from '~/components/chat/VisualFigure.vue'
import { interpolateFrames, snapParam } from '~/utils/visual/explore'
import { layoutDiagram, niceTicks, wrapLabel } from '~/utils/visual/layout'
import { parseVisualSpec, type DiagramSpec, type ExploreSpec, type VisualSpec } from '~/utils/visual/spec'
import { CHART, DIAGRAM, EXPLORE } from './visual-fixtures'

function spec<T extends VisualSpec>(raw: unknown): T {
  const parsed = parseVisualSpec(JSON.stringify(raw))
  if ('error' in parsed) throw new Error(parsed.error)
  return parsed as T
}

function reduceMotion(on: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: on && query === '(prefers-reduced-motion: reduce)',
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

const mountFigure = (raw: unknown, initial = {}) => mount(VisualFigure, { props: { spec: spec(raw), initial }, attachTo: document.body })
const curveYs = (s: ExploreSpec, rate: number) => interpolateFrames(s, { rate }).map(([, y]) => y)

describe('VisualFigure (JF-195)', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('draws each series of a line chart with its own dash pattern, labeled by its title', () => {
    const wrapper = mountFigure(CHART)
    const svg = wrapper.find('svg[role="img"]')
    expect(svg.attributes('aria-label')).toBe('Sprint velocity')
    const lines = wrapper.findAll('path.visual-line')
    expect(lines).toHaveLength(2)
    expect(lines[0]!.attributes('stroke-dasharray')).not.toBe(lines[1]!.attributes('stroke-dasharray'))
    expect(wrapper.findAll('.visual-legend li').map(li => li.text())).toEqual(['Committed', 'Delivered'])
    expect(wrapper.find('p.visual-title').text()).toBe('Sprint velocity')
  })

  it('lists every plotted number in the Data table, with row and column headers', () => {
    const wrapper = mountFigure(CHART)
    expect(wrapper.find('details.visual-data > summary').text()).toBe('Data')
    const [points, marks] = wrapper.findAll('details table')
    expect(points!.findAll('thead th').map(th => th.text())).toEqual(['Series', 'Sprint', 'Points (pts)'])
    const rows = points!.findAll('tbody tr').map(tr => [tr.find('th[scope="row"]').text(), ...tr.findAll('td').map(td => td.text())])
    expect(rows).toEqual([
      ['Committed', '1', '34'], ['Committed', '2', '38'], ['Committed', '3', '41'],
      ['Delivered', '1', '30'], ['Delivered', '2', '37'], ['Delivered', '3', '40'],
    ])
    expect(marks!.find('caption').text()).toBe('Marks')
    expect(marks!.findAll('thead th').map(th => th.text())).toEqual(['Mark', 'Sprint'])
    expect(marks!.findAll('tbody tr').map(tr => [tr.find('th[scope="row"]').text(), tr.find('td').text()]))
      .toEqual([['release', '3']])
  })

  it('leaves the Marks table out when the chart has no marks', () => {
    expect(mountFigure({ ...CHART, marks: undefined }).findAll('details table')).toHaveLength(1)
  })

  it('draws a mark as a labeled line, and a scatter chart as markers alone', () => {
    expect(mountFigure(CHART).findAll('g.visual-mark text').map(t => t.text())).toEqual(['release'])
    const scatter = mountFigure({ ...CHART, chartType: 'scatter' })
    expect(scatter.findAll('path.visual-line')).toHaveLength(0)
    expect(scatter.findAll('path.visual-marker')).toHaveLength(2)
  })

  it('omits the title above the drawing when the figcaption already holds it', () => {
    expect(mountFigure({ ...CHART, caption: undefined }).find('p.visual-title').exists()).toBe(false)
  })

  it('draws in with motion on, and not under reduced motion', () => {
    reduceMotion(false)
    expect(mountFigure(CHART).find('svg.visual-draw-in').exists()).toBe(true)
    reduceMotion(true)
    expect(mountFigure(CHART).find('svg.visual-draw-in').exists()).toBe(false)
  })

  it('grows bars from the baseline, a marker capping each', () => {
    const wrapper = mountFigure({ ...CHART, chartType: 'bar' })
    expect(wrapper.findAll('rect.visual-bar')).toHaveLength(6)
    expect(wrapper.findAll('path.visual-line')).toHaveLength(0)
  })

  it('highlights a selected step\'s focus nodes, dims the rest and shows its body', async () => {
    const wrapper = mountFigure(DIAGRAM)
    expect(wrapper.findAll('g.visual-node')).toHaveLength(3)
    expect(wrapper.find('.visual-focus').exists()).toBe(false)
    await wrapper.findAll('.visual-step-list button')[0]!.trigger('click')
    expect(wrapper.find('g[data-node="in"]').classes()).toContain('visual-focus')
    expect(wrapper.find('g[data-node="run"]').classes()).toContain('visual-dim')
    expect(wrapper.find('g[data-node="out"]').classes()).toContain('visual-dim')
    expect(wrapper.find('.visual-step-body p').text()).toBe('The message is persisted first.')
    expect(wrapper.find('.visual-step-body .visual-detail').text()).toBe('Inbound message: Saved before it is acknowledged')
    expect(wrapper.emitted('change')!.at(-1)).toEqual([{ step: 0 }])
  })

  it('plays the steps every 2.5 s and stops at the last one', async () => {
    reduceMotion(false)
    vi.useFakeTimers()
    const wrapper = mountFigure(DIAGRAM)
    const play = wrapper.findAll('.visual-step-controls button')[1]!
    expect(play.attributes('disabled')).toBeUndefined()
    await play.trigger('click')
    const current = () => wrapper.find('.visual-step-list button[aria-current="step"]').text()
    expect(current()).toBe('Receive')
    await vi.advanceTimersByTimeAsync(2499)
    expect(current()).toBe('Receive')
    await vi.advanceTimersByTimeAsync(1)
    expect(current()).toBe('Run')
    await vi.advanceTimersByTimeAsync(2500)
    expect(current()).toBe('Deliver')
    expect(play.text()).toBe('Play')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('disables Play under reduced motion and leaves the step buttons working', async () => {
    reduceMotion(true)
    const wrapper = mountFigure(DIAGRAM)
    expect(wrapper.findAll('.visual-step-controls button')[1]!.attributes('disabled')).toBeDefined()
    await wrapper.findAll('.visual-step-controls button')[2]!.trigger('click')
    expect(wrapper.find('g[data-node="in"]').classes()).toContain('visual-focus')
  })

  it('clears a running playback timer on unmount', async () => {
    reduceMotion(false)
    vi.useFakeTimers()
    const wrapper = mountFigure(DIAGRAM)
    await wrapper.findAll('.visual-step-controls button')[1]!.trigger('click')
    expect(vi.getTimerCount()).toBe(1)
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('reports a kind that throws while drawing, rather than breaking the reply', () => {
    const broken = { ...spec(CHART), series: [{ name: 'A', points: null }] } as unknown as VisualSpec
    const wrapper = mount(VisualFigure, { props: { spec: broken, initial: {} } })
    expect(wrapper.emitted('failed')![0]![0]).toMatch(/^Could not draw this visual: /)
  })

  it('restores the step it was given', () => {
    const wrapper = mountFigure(DIAGRAM, { step: 2 })
    expect(wrapper.find('g[data-node="out"]').classes()).toContain('visual-focus')
  })
})

describe('explore interpolation (JF-195)', () => {
  const explore = spec<ExploreSpec>(EXPLORE)

  it('gives a frame\'s own values at its parameter value', () => {
    expect(curveYs(explore, 1)).toEqual([1000, 1105, 1220])
    expect(curveYs(explore, 10)).toEqual([1000, 2594, 6727])
  })

  it('gives the midpoint of the bracketing frames halfway between them', () => {
    expect(curveYs(explore, 5.5)).toEqual([1000, (1105 + 2594) / 2, (1220 + 6727) / 2])
  })

  it('clamps a value outside the frames\' span to the nearest frame', () => {
    expect(curveYs(explore, 0)).toEqual([1000, 1105, 1220])
    expect(curveYs(explore, 12)).toEqual([1000, 2594, 6727])
  })

  it('matches every other param to its nearest frame, the lower on a tie, whatever the order', () => {
    const two = spec<ExploreSpec>({
      ...EXPLORE,
      params: [EXPLORE.params[0], { id: 'fee', label: 'Fee', min: 0, max: 2, step: 1, value: 0 }],
      frames: [
        { params: { rate: 1, fee: 0 }, points: [[0, 10], [1, 20]] },
        { params: { rate: 10, fee: 0 }, points: [[0, 30], [1, 40]] },
        { params: { rate: 1, fee: 2 }, points: [[0, 50], [1, 60]] },
        { params: { rate: 10, fee: 2 }, points: [[0, 70], [1, 80]] },
      ],
    })
    expect(interpolateFrames(two, { rate: 1, fee: 2 }).map(([, y]) => y)).toEqual([50, 60])
    expect(interpolateFrames(two, { fee: 1, rate: 5.5 }).map(([, y]) => y)).toEqual([20, 30])
  })

  it('keeps a snapped value inside the range and on the step grid', () => {
    const p = { id: 'r', label: 'R', min: 1, max: 10, step: 2, value: 5 }
    expect(snapParam(p, 4.2)).toBe(5)
    expect(snapParam(p, 3.9)).toBe(3)
    expect(snapParam(p, -40)).toBe(1)
    expect(snapParam(p, 10)).toBe(9)
    expect(snapParam(p, 99)).toBe(9)
    expect(snapParam({ ...p, min: 0, max: 1, step: 0.1 }, 0.33)).toBe(0.3)
  })

  it('redraws the curve on a slider input, without a request', async () => {
    reduceMotion(true)
    const fetchSpy = vi.fn()
    vi.stubGlobal('$fetch', fetchSpy)
    vi.stubGlobal('fetch', fetchSpy)
    const wrapper = mount(VisualFigure, { props: { spec: explore, initial: {} } })
    const slider = wrapper.find('input[type="range"]')
    expect(wrapper.find(`label[for="${slider.attributes('id')}"]`).text()).toBe('Annual rate')
    expect(wrapper.find('.visual-param-value').text()).toBe('5 %')
    const before = wrapper.find('path.visual-line').attributes('d')
    await slider.setValue('10')
    expect(wrapper.find('.visual-param-value').text()).toBe('10 %')
    expect(wrapper.find('path.visual-line').attributes('d')).not.toBe(before)
    const shown = wrapper.findAll('details table tbody tr').map(tr => tr.findAll('td')[0]!.text())
    expect(shown).toEqual(['1000', '2594', '6727'])
    expect(wrapper.emitted('change')!.at(-1)).toEqual([{ values: { rate: 10 } }])
    expect(fetchSpy).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('restores the slider positions it was given', () => {
    const wrapper = mount(VisualFigure, { props: { spec: explore, initial: { values: { rate: 1 } } } })
    expect(wrapper.find('.visual-param-value').text()).toBe('1 %')
    expect(wrapper.findAll('details table tbody tr').map(tr => tr.findAll('td')[0]!.text())).toEqual(['1000', '1105', '1220'])
  })
})

describe('diagram layout (JF-195)', () => {
  const cyclic = (layout: string) => spec<DiagramSpec>({ ...DIAGRAM, layout, edges: [...DIAGRAM.edges, { from: 'out', to: 'in' }] })

  it('layers a flow left to right and routes the edge closing a cycle below the row', () => {
    const laid = layoutDiagram(cyclic('flow'))
    const x = Object.fromEntries(laid.nodes.map(n => [n.id, n.x]))
    expect(x.in! < x.run! && x.run! < x.out!).toBe(true)
    expect(laid.edges.map(e => e.back)).toEqual([false, false, true])
    const bottom = Math.max(...laid.nodes.map(n => n.y + n.h))
    expect(laid.edges[2]!.labelY > bottom).toBe(true)
  })

  it.each([4, 7])('keeps every node of a %i-node cycle inside the drawing', (count) => {
    const ids = Array.from({ length: count }, (_, i) => `n${i}`)
    const laid = layoutDiagram(spec<DiagramSpec>({
      kind: 'diagram',
      layout: 'cycle',
      title: 'Ring',
      nodes: ids.map(id => ({ id, label: id })),
      edges: ids.map((id, i) => ({ from: id, to: ids[(i + 1) % count] })),
    }))
    for (const n of laid.nodes) {
      expect(n.x >= 0 && n.y >= 0, n.id).toBe(true)
      expect(n.x + n.w <= laid.width && n.y + n.h <= laid.height, n.id).toBe(true)
    }
  })

  it('cuts a word longer than a line into line-long chunks', () => {
    expect(wrapLabel('Calls ConfigService.setWithSideEffects now')).toEqual(['Calls', 'ConfigService.setWit', 'hSideEffects now'])
  })

  it('stacks a tree top down, and puts a cycle\'s nodes on an ellipse with no back edges', () => {
    const tree = layoutDiagram(cyclic('tree'))
    const y = Object.fromEntries(tree.nodes.map(n => [n.id, n.y]))
    expect(y.in! < y.run! && y.run! < y.out!).toBe(true)
    expect(layoutDiagram(cyclic('cycle')).edges.some(e => e.back)).toBe(false)
  })
})

describe('chart ticks (JF-195)', () => {
  it('returns a bounded list when the step is below the values\' precision', () => {
    const ticks = niceTicks(1e17, 1e17 + 16)
    expect(ticks.length > 0 && ticks.length <= 102).toBe(true)
    expect(ticks.every(Number.isFinite)).toBe(true)
  })

  it('covers an ordinary range with round steps', () => {
    expect(niceTicks(0, 10)).toEqual([0, 2, 4, 6, 8, 10])
    expect(niceTicks(30, 41)).toEqual([30, 32.5, 35, 37.5, 40, 42.5])
  })
})
