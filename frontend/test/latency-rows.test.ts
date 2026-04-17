import { describe, it, expect } from 'vitest'
import {
  buildLatencyRows,
  buildChartSeries,
  type LatencyHistogram,
} from '~/utils/latency-rows'

const h = (count: number, p50 = count): LatencyHistogram => ({
  count,
  p50,
  p90: p50,
  p99: p50,
  p999: p50,
  min: p50,
  max: p50,
})

describe('buildLatencyRows (JCLAW-74 prologue nesting)', () => {
  it('nests known prologue_* children immediately under Prologue', () => {
    const rows = buildLatencyRows({
      queue_wait: h(1, 5),
      prologue: h(3, 20),
      prologue_parse: h(3, 1),
      prologue_tools: h(3, 2),
      prologue_conv: h(3, 9),
      prologue_prompt: h(3, 13),
      ttft: h(3, 1000),
      total: h(3, 17000),
    })

    const keys = rows.map(r => r.key)
    // Prologue parent sits at index 1, followed immediately by its children.
    const prologueIdx = keys.indexOf('prologue')
    expect(prologueIdx).toBeGreaterThan(-1)
    expect(keys.slice(prologueIdx + 1, prologueIdx + 5)).toEqual([
      'prologue_parse',
      'prologue_conv',
      'prologue_tools',
      'prologue_prompt',
    ])
    // No prologue_* row appears after ttft.
    const ttftIdx = keys.indexOf('ttft')
    expect(keys.slice(ttftIdx + 1).some(k => k.startsWith('prologue_'))).toBe(false)
  })

  it('flags children with isChild and assigns clean labels', () => {
    const rows = buildLatencyRows({
      prologue: h(1, 20),
      prologue_parse: h(1, 1),
      prologue_conv: h(1, 9),
    })
    const parse = rows.find(r => r.key === 'prologue_parse')!
    const conv = rows.find(r => r.key === 'prologue_conv')!
    const prologue = rows.find(r => r.key === 'prologue')!
    expect(parse.isChild).toBe(true)
    expect(parse.label).toBe('Parse')
    expect(conv.isChild).toBe(true)
    expect(conv.label).toBe('Conversation')
    expect(prologue.isChild).toBe(false)
    expect(prologue.label).toBe('Prologue')
  })

  it('silently skips absent or zero-count children', () => {
    const rows = buildLatencyRows({
      prologue: h(3, 20),
      prologue_parse: h(3, 1),
      // prologue_tools missing entirely
      prologue_conv: { count: 0 },  // zero count → skip
      prologue_prompt: h(3, 13),
    })
    const childKeys = rows.filter(r => r.isChild).map(r => r.key)
    expect(childKeys).toEqual(['prologue_parse', 'prologue_prompt'])
  })

  it('surfaces unknown prologue_* keys as children after known ones', () => {
    const rows = buildLatencyRows({
      prologue: h(3, 20),
      prologue_parse: h(3, 1),
      prologue_future_thing: h(3, 7),  // unknown key
    })
    const childKeys = rows.filter(r => r.isChild).map(r => r.key)
    expect(childKeys).toEqual(['prologue_parse', 'prologue_future_thing'])
    const unknown = rows.find(r => r.key === 'prologue_future_thing')!
    expect(unknown.isChild).toBe(true)
    // Falls back to the suffix after the prologue_ prefix as label.
    expect(unknown.label).toBe('future_thing')
  })

  it('emits top-level segments in canonical order regardless of input key order', () => {
    const rows = buildLatencyRows({
      total: h(3, 100),
      queue_wait: h(3, 5),
      prologue: h(3, 20),
      ttft: h(3, 50),
    })
    const topLevelKeys = rows.filter(r => !r.isChild).map(r => r.key)
    expect(topLevelKeys).toEqual(['queue_wait', 'prologue', 'ttft', 'total'])
  })

  it('appends unknown non-prologue top-level keys at the bottom', () => {
    const rows = buildLatencyRows({
      prologue: h(3, 20),
      total: h(3, 100),
      some_future_segment: h(3, 42),
    })
    const keys = rows.map(r => r.key)
    expect(keys).toEqual(['prologue', 'total', 'some_future_segment'])
    expect(rows.find(r => r.key === 'some_future_segment')!.isChild).toBe(false)
  })

  it('skips prologue entirely if its histogram is absent but still nests any present children', () => {
    // Edge case: prologue parent missing but children present. Children still
    // nest in "prologue position" — in practice prologue should always be
    // present when children are, but the helper should not crash.
    const rows = buildLatencyRows({
      queue_wait: h(3, 5),
      prologue_parse: h(3, 1),
    })
    // When prologue parent is missing, the nesting slot never fires, so the
    // unknown-key catch-all picks up prologue_parse. Label still clean.
    const parse = rows.find(r => r.key === 'prologue_parse')!
    expect(parse).toBeDefined()
    expect(parse.label).toBe('prologue_parse')  // treated as top-level fallback
  })
})

describe('buildChartSeries (JCLAW-74)', () => {
  it('excludes prologue_* children from the chart input', () => {
    const series = buildChartSeries({
      queue_wait: h(3, 5),
      prologue: h(3, 20),
      prologue_parse: h(3, 1),
      prologue_tools: h(3, 2),
      ttft: h(3, 1000),
      total: h(3, 17000),
    })
    const keys = series.map(s => s.key)
    expect(keys).toContain('prologue')
    expect(keys.some(k => k.startsWith('prologue_'))).toBe(false)
    expect(keys).toEqual(['queue_wait', 'prologue', 'ttft', 'total'])
  })

  it('exposes {key, label, histogram} shape for the chart component', () => {
    const series = buildChartSeries({
      prologue: h(1, 20),
    })
    expect(series).toEqual([
      { key: 'prologue', label: 'Prologue', histogram: h(1, 20) },
    ])
  })
})
