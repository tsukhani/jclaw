import { describe, expect, it } from 'vitest'
import { MAX_VISUAL_CHARS, parseVisualSpec, syncVisualEnabled, visualEnabled } from '~/utils/visual/spec'
import { CHART, DIAGRAM, EXPLORE } from './visual-fixtures'

const parse = (spec: unknown) => parseVisualSpec(JSON.stringify(spec))
const errorOf = (spec: unknown) => {
  const result = parse(spec)
  return 'error' in result ? result.error : null
}
const points = (n: number) => Array.from({ length: n }, (_, i) => [i, i * 2])
const series = (n: number) => Array.from({ length: n }, (_, i) => ({ name: `S${i}`, points: points(2) }))
const nodes = (n: number) => Array.from({ length: n }, (_, i) => ({ id: `n${i}`, label: `Node ${i}` }))

describe('parseVisualSpec (JF-195)', () => {
  it('accepts the three ticket examples, filling the defaults', () => {
    expect(parse(CHART)).toMatchObject({ kind: 'chart', chartType: 'line', title: 'Sprint velocity' })
    expect(parse(DIAGRAM)).toMatchObject({ kind: 'diagram', layout: 'flow', nodes: [{ id: 'in' }, { id: 'run' }, { id: 'out' }] })
    expect(parse(EXPLORE)).toMatchObject({ kind: 'explore', params: [{ id: 'rate', value: 5 }] })
    expect(parse({ ...CHART, chartType: undefined })).toMatchObject({ chartType: 'line' })
    expect(parse({ ...DIAGRAM, layout: undefined })).toMatchObject({ layout: 'flow' })
  })

  it('refuses invalid JSON with the parser\'s reason, without throwing', () => {
    const result = parseVisualSpec('{"kind": "chart",')
    expect('error' in result && result.error.startsWith('The visual block is not valid JSON: ')).toBe(true)
  })

  it('names an unknown or missing kind', () => {
    expect(errorOf({ ...CHART, kind: 'pie' })).toBe('Unknown visual kind "pie". Use chart, diagram or explore.')
    expect(errorOf({ title: 'x' })).toBe('Unknown visual kind "undefined". Use chart, diagram or explore.')
    expect(errorOf([1, 2])).toBe('Unknown visual kind "undefined". Use chart, diagram or explore.')
  })

  it('reports a malformed series by its path, distinctly for a non-array and a single point', () => {
    const notArray = errorOf({ ...CHART, series: 'many' })!
    const onePoint = errorOf({ ...CHART, series: [{ name: 'A', points: [[1, 2]] }] })!
    expect(notArray.startsWith('series: ')).toBe(true)
    expect(onePoint.startsWith('series.0.points: ')).toBe(true)
    expect(notArray).not.toBe(onePoint)
  })

  it('names the explore frame whose x values differ from the first', () => {
    const frames = [EXPLORE.frames[0], { params: { rate: 10 }, points: [[0, 1000], [11, 2594], [20, 6727]] }]
    expect(errorOf({ ...EXPLORE, frames })).toBe('Frame 2 does not carry the same x values as frame 1.')
  })

  it('refuses a starting value outside the param\'s range, and keeps both ends', () => {
    const withValue = (value: number) => ({ ...EXPLORE, params: [{ ...EXPLORE.params[0], value }] })
    expect(errorOf(withValue(11))).toBe('Param "rate" starts at 11, outside its range 1 to 10.')
    expect(errorOf(withValue(0))).toBe('Param "rate" starts at 0, outside its range 1 to 10.')
    expect(errorOf(withValue(1))).toBeNull()
    expect(errorOf(withValue(10))).toBeNull()
  })

  it('refuses a param range, step or frame that cannot be drawn', () => {
    const param = (over: object) => ({ ...EXPLORE, params: [{ ...EXPLORE.params[0], ...over }] })
    expect(errorOf(param({ min: 10, max: 10, value: 10 }))).toBe('Param "rate" needs a min below its max.')
    expect(errorOf(param({ step: 0 }))).toBe('Param "rate" needs a step above 0.')
    expect(errorOf(param({ step: -1 }))).toBe('Param "rate" needs a step above 0.')
    expect(errorOf(param({ step: 0.5 }))).toBeNull()
    const frames = [EXPLORE.frames[0], { params: { rate: 10, fee: 1 }, points: EXPLORE.frames[1]!.points }]
    expect(errorOf({ ...EXPLORE, frames })).toBe('Frame 2 must set exactly the params rate.')
    expect(errorOf({ ...EXPLORE, frames: [EXPLORE.frames[0], { params: {}, points: EXPLORE.frames[1]!.points }] }))
      .toBe('Frame 2 must set exactly the params rate.')
  })

  it('holds 1 to 3 params and at most 80 edges', () => {
    const params = (n: number) => Array.from({ length: n }, (_, i) => ({ id: `p${i}`, label: `P${i}`, min: 0, max: 1, step: 1, value: 0 }))
    const withParams = (n: number) => ({
      ...EXPLORE,
      params: params(n),
      frames: [{ params: Object.fromEntries(params(n).map(p => [p.id, 0])), points: [[0, 1], [1, 2]] }],
    })
    expect(errorOf(withParams(1))).toBeNull()
    expect(errorOf(withParams(3))).toBeNull()
    expect(errorOf(withParams(0))!.startsWith('params: ')).toBe(true)
    expect(errorOf(withParams(4))!.startsWith('params: ')).toBe(true)
    const edges = (n: number) => Array.from({ length: n }, () => ({ from: 'in', to: 'run' }))
    expect(errorOf({ ...DIAGRAM, edges: edges(80) })).toBeNull()
    expect(errorOf({ ...DIAGRAM, edges: edges(81) })!.startsWith('edges: ')).toBe(true)
  })

  it('refuses a duplicate node id, an edge to an undeclared node and a step focusing one', () => {
    expect(errorOf({ ...DIAGRAM, nodes: [...DIAGRAM.nodes, { id: 'run', label: 'Again' }] }))
      .toBe('Node id "run" is declared twice.')
    expect(errorOf({ ...DIAGRAM, edges: [...DIAGRAM.edges, { from: 'out', to: 'gone' }] }))
      .toBe('Edge 3 points to undeclared node "gone".')
    expect(errorOf({ ...DIAGRAM, steps: [{ title: 'Lost', focus: ['nowhere'] }] }))
      .toBe('Step 1 focuses undeclared node "nowhere".')
  })

  it('draws a body of exactly the limit and refuses one character more', () => {
    const exact = JSON.stringify(CHART)
    const padded = exact.slice(0, -1) + ' '.repeat(MAX_VISUAL_CHARS - exact.length) + '}'
    expect(padded).toHaveLength(MAX_VISUAL_CHARS)
    expect('error' in parseVisualSpec(padded)).toBe(false)
    const over = padded.slice(0, -1) + ' }'
    expect(parseVisualSpec(over)).toEqual({
      error: 'Too large to draw: 20001 characters, over the limit of 20000. Draw fewer series or fewer points.',
    })
  })

  it('holds the series, point, node and id limits on both sides', () => {
    expect(errorOf({ ...CHART, series: series(6) })).toBeNull()
    expect(errorOf({ ...CHART, series: series(7) })).not.toBeNull()
    expect(errorOf({ ...CHART, series: [{ name: 'A', points: points(2) }] })).toBeNull()
    expect(errorOf({ ...CHART, series: [{ name: 'A', points: points(400) }] })).toBeNull()
    expect(errorOf({ ...CHART, series: [{ name: 'A', points: points(401) }] })!.startsWith('series.0.points: ')).toBe(true)
    expect(errorOf({ ...DIAGRAM, nodes: nodes(40), edges: [], steps: undefined })).toBeNull()
    expect(errorOf({ ...DIAGRAM, nodes: nodes(41), edges: [], steps: undefined })!.startsWith('nodes: ')).toBe(true)
    const withId = (id: string) => ({ ...DIAGRAM, nodes: [{ id, label: 'One' }], edges: [], steps: undefined })
    expect(errorOf(withId('a'.repeat(32)))).toBeNull()
    expect(errorOf(withId('a'.repeat(33)))!.startsWith('nodes.0.id: ')).toBe(true)
  })
})

describe('syncVisualEnabled (JF-195)', () => {
  const config = (value?: string) => ({ entries: value === undefined ? [] : [{ key: 'visual.enabled', value }] })

  it('reads the key as ConfigService.getBoolean(key, true) does', () => {
    syncVisualEnabled(config('false'))
    expect(visualEnabled.value).toBe(false)
    syncVisualEnabled(config())
    expect(visualEnabled.value).toBe(true)
    syncVisualEnabled(config(' TRUE '))
    expect(visualEnabled.value).toBe(true)
    syncVisualEnabled(config('yes'))
    expect(visualEnabled.value).toBe(false)
    syncVisualEnabled(null)
    expect(visualEnabled.value).toBe(true)
  })
})
