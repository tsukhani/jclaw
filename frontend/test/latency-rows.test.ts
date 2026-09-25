import { describe, it, expect } from 'vitest'
import {
  buildLatencyRows,
  buildCountRows,
  cachedCallShare,
  buildChartSeries,
  isCountSegment,
  listAvailableChannels,
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
    const prologueIdx = keys.indexOf('prologue')
    expect(prologueIdx).toBeGreaterThan(-1)
    expect(keys.slice(prologueIdx + 1, prologueIdx + 5)).toEqual([
      'prologue_parse',
      'prologue_conv',
      'prologue_tools',
      'prologue_prompt',
    ])
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
      prologue_conv: { count: 0 },
      prologue_prompt: h(3, 13),
    })
    const childKeys = rows.filter(r => r.isChild).map(r => r.key)
    expect(childKeys).toEqual(['prologue_parse', 'prologue_prompt'])
  })

  it('surfaces unknown prologue_* keys as children after known ones', () => {
    const rows = buildLatencyRows({
      prologue: h(3, 20),
      prologue_parse: h(3, 1),
      prologue_future_thing: h(3, 7),
    })
    const childKeys = rows.filter(r => r.isChild).map(r => r.key)
    expect(childKeys).toEqual(['prologue_parse', 'prologue_future_thing'])
    const unknown = rows.find(r => r.key === 'prologue_future_thing')!
    expect(unknown.isChild).toBe(true)
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

  it('renders Terminal delivery immediately above Total (JCLAW-102)', () => {
    const rows = buildLatencyRows({
      prologue: h(3, 20),
      ttft: h(3, 50),
      stream_body: h(3, 200),
      persist: h(3, 8),
      terminal_tail: h(3, 500),
      total: h(3, 800),
    })
    const topLevelKeys = rows.filter(r => !r.isChild).map(r => r.key)
    const tailIdx = topLevelKeys.indexOf('terminal_tail')
    const totalIdx = topLevelKeys.indexOf('total')
    expect(tailIdx).toBeGreaterThan(-1)
    expect(totalIdx).toBe(tailIdx + 1)
  })

  it('renders unknown non-prologue keys immediately above Total, never below it (JCLAW-870)', () => {
    // A reverted `prefill` segment left rows behind and rendered under Total,
    // lowercase — the summary row was last only while TOP_LEVEL_ORDER happened
    // to enumerate everything the backend emitted.
    const rows = buildLatencyRows({
      prologue: h(3, 20),
      total: h(3, 100),
      some_future_segment: h(3, 42),
    })
    const keys = rows.map(r => r.key)
    expect(keys).toEqual(['prologue', 'some_future_segment', 'total'])
    expect(rows.find(r => r.key === 'some_future_segment')!.isChild).toBe(false)
  })

  it('names the browser channel\'s INP segment, which arrives with no Total beside it', () => {
    const rows = buildLatencyRows({ inp: h(3, 180) })
    expect(rows.map(r => [r.key, r.label])).toEqual([['inp', 'Interaction to next paint (INP)']])
  })

  it('keeps Total last even when several segments are unrecognised', () => {
    const rows = buildLatencyRows({
      queue_wait: h(3, 5),
      total: h(3, 100),
      zzz_late_alphabetically: h(3, 42),
      aaa_early_alphabetically: h(3, 7),
    })
    const keys = rows.map(r => r.key)
    expect(keys[keys.length - 1]).toBe('total')
    expect(keys).toContain('zzz_late_alphabetically')
    expect(keys).toContain('aaa_early_alphabetically')
  })

  it('still puts Total last when it is the only known segment present', () => {
    const rows = buildLatencyRows({ mystery: h(3, 9), total: h(3, 100) })
    expect(rows.map(r => r.key)).toEqual(['mystery', 'total'])
  })

  it('skips prologue entirely if its histogram is absent but still surfaces children', () => {
    const rows = buildLatencyRows({
      queue_wait: h(3, 5),
      prologue_parse: h(3, 1),
    })
    const parse = rows.find(r => r.key === 'prologue_parse')!
    expect(parse).toBeDefined()
    // An orphaned child falls through to the unknown-segment pass, which now
    // labels rather than echoing the wire key.
    expect(parse.label).toBe('Prologue parse')
  })

  it('labels an unrecognised segment instead of showing its wire key', () => {
    // memory_recall reached operators as "memory_recall" because this file names
    // segments explicitly and the backend adds them without it changing.
    const rows = buildLatencyRows({ memory_recall: h(3, 8), total: h(3, 100) })
    expect(rows.find(r => r.key === 'memory_recall')!.label).toBe('Memory recall')

    const unknown = buildLatencyRows({ some_new_segment: h(3, 8), total: h(3, 100) })
    expect(unknown.find(r => r.key === 'some_new_segment')!.label).toBe('Some new segment')
  })
})

describe('buildLatencyRows (JCLAW-800 voice group)', () => {
  it('leads with the Voice group, above the chat chain it encloses (JCLAW-870)', () => {
    // voice_turn is endpoint→complete and wraps the LLM leg; voice_stt precedes
    // every chat segment. Emitting the group above Total instead read as if STT
    // happened after terminal delivery, and split Terminal delivery from Total.
    const rows = buildLatencyRows({
      queue_wait: h(3, 5),
      dispatcher_wait: h(3, 2),
      ttft: h(3, 2000),
      terminal_tail: h(3, 10),
      voice_stt: h(3, 1400),
      voice_tts_synth: h(3, 900),
      voice_reply: h(3, 5300),
      voice_turn: h(3, 7500),
      total: h(3, 3000),
    })
    const keys = rows.map(r => r.key)
    expect(keys.slice(0, 4)).toEqual([
      'voice', 'voice_stt', 'voice_tts_synth', 'voice_reply',
    ])
    expect(keys[keys.length - 1]).toBe('total')
    // The additive chain stays contiguous, ending at the row Total sums to.
    expect(keys.indexOf('total')).toBe(keys.indexOf('terminal_tail') + 1)
  })

  it('makes Voice the parent (voice_turn) with the stages as labelled children', () => {
    const rows = buildLatencyRows({
      voice_stt: h(3, 1400),
      voice_tts_synth: h(3, 900),
      voice_reply: h(3, 5300),
      voice_turn: h(3, 7500),
      total: h(3, 3000),
    })
    const voice = rows.find(r => r.key === 'voice')!
    expect(voice.isChild).toBe(false)
    expect(voice.label).toBe('Voice')
    expect(voice.h.p50).toBe(7500) // parent carries voice_turn's histogram
    expect(rows.some(r => r.key === 'voice_turn')).toBe(false) // not repeated as its own row
    for (const k of ['voice_stt', 'voice_tts_synth', 'voice_reply']) {
      expect(rows.find(r => r.key === k)!.isChild).toBe(true)
    }
    expect(rows.find(r => r.key === 'voice_stt')!.label).toBe('STT')
    expect(rows.find(r => r.key === 'voice_reply')!.label).toBe('First audio')
  })

  it('omits the Voice group when there is no completed turn (no voice_turn)', () => {
    const rows = buildLatencyRows({
      voice_stt: h(3, 1400), // partial: no voice_turn parent
      total: h(3, 3000),
    })
    expect(rows.some(r => r.key === 'voice')).toBe(false)
    // The stray key still surfaces (flat, via the unknown-key catch-all) so data never disappears.
    expect(rows.find(r => r.key === 'voice_stt')!.isChild).toBe(false)
  })

  it('buildChartSeries keeps the voice segments (only prologue children drop)', () => {
    const keys = buildChartSeries({
      prologue: h(3, 20),
      prologue_parse: h(3, 1),
      voice_stt: h(3, 1400),
      voice_reply: h(3, 5300),
      voice_turn: h(3, 7500),
      total: h(3, 3000),
    }).map(s => s.key)
    expect(keys.some(k => k.startsWith('prologue_'))).toBe(false)
    expect(keys).toContain('voice') // parent (voice_turn)
    expect(keys).toContain('voice_stt')
    expect(keys).toContain('voice_reply')
  })
})

describe('listAvailableChannels (JCLAW-102 dropdown options)', () => {
  it('orders channels web → telegram → task → webhook, then unknown ones alphabetical, suppressing the LatencyStats unknown bucket', () => {
    const channels = listAvailableChannels({
      slack: { total: h(1, 25) },
      unknown: { total: h(1, 50) },
      telegram: { total: h(1, 100) },
      web: { total: h(1, 10) },
      task: { total: h(1, 75) },
    })
    // 'unknown' is the LatencyStats fallback for system-internal callers
    // (embeddings, slash compaction, skill promotion); we don't surface it
    // in the Chat Performance dropdown.
    expect(channels.map(c => c.key)).toEqual([
      'web', 'telegram', 'task', 'slack',
    ])
  })

  it('labels known channels with their friendly names and title-cases unknown ones', () => {
    const channels = listAvailableChannels({
      web: { total: h(1, 10) },
      slack: { total: h(1, 25) },
    })
    expect(channels.find(c => c.key === 'web')!.label).toBe('Web')
    expect(channels.find(c => c.key === 'slack')!.label).toBe('Slack')
  })

  it('suppresses channels with no sampled segments', () => {
    const channels = listAvailableChannels({
      web: { total: h(3, 100) },
      telegram: {},
      task: { total: { count: 0 } }, // zero-count still counts as no samples
    })
    expect(channels.map(c => c.key)).toEqual(['web'])
  })

  it('returns an empty list when the payload has no sampled data', () => {
    expect(listAvailableChannels({})).toEqual([])
    expect(listAvailableChannels({ web: {} })).toEqual([])
  })
})

describe('count segments live in their own view (JCLAW-884)', () => {
  it('keeps cardinalities out of the latency table, so Total summarises every row above it', () => {
    // They used to sit between Tool execution and Persist. The latency table's
    // Total sums an additive chain of durations and has no meaning for a count,
    // so three of its own rows were excluded from its organising idea.
    const rows = buildLatencyRows({
      ttft: h(3, 50),
      tool_exec: h(3, 400),
      tool_round_count: h(3, 4),
      llm_call_count: h(3, 6),
      llm_call_cached: h(2, 2),
      persist: h(3, 8),
      total: h(3, 800),
    })
    const keys = rows.map(r => r.key)
    expect(keys).toEqual(['ttft', 'tool_exec', 'persist', 'total'])
    expect(keys[keys.length - 1]).toBe('total')
  })

  it('puts the counts in buildCountRows, calls first', () => {
    const rows = buildCountRows({
      ttft: h(3, 50),
      tool_round_count: h(3, 4),
      llm_call_count: h(3, 6),
      llm_call_cached: h(2, 2),
      total: h(3, 800),
    })
    expect(rows.map(r => r.key)).toEqual(['llm_call_count', 'llm_call_cached', 'tool_round_count'])
    expect(rows.find(r => r.key === 'llm_call_count')!.label).toBe('LLM calls / turn')
    expect(rows.find(r => r.key === 'llm_call_cached')!.label).toBe('Cache-served calls / turn')
  })

  it('classifies tool_verify_failed as a count despite the missing _count suffix (JCLAW-836)', () => {
    // The pair is a rate: tool_verify_count carries the suffix and would be
    // routed correctly on its own, but its numerator does not. Left unlisted,
    // tool_verify_failed renders as a duration and gets summed into Total.
    const latency = buildLatencyRows({
      ttft: h(3, 50),
      tool_verify_count: h(3, 2),
      tool_verify_failed: h(3, 1),
      total: h(3, 800),
    })
    expect(latency.map(r => r.key)).toEqual(['ttft', 'total'])

    const counts = buildCountRows({ tool_verify_count: h(3, 2), tool_verify_failed: h(3, 1) })
    expect(counts.map(r => r.key)).toEqual(['tool_verify_count', 'tool_verify_failed'])
    expect(counts[1]!.label).toBe('Tool results flagged / turn')
  })

  it('omits the cached companion when turns recorded none', () => {
    // The backend clamps recorded values to a minimum of 1, so a turn with no
    // cache hits omits llm_call_cached entirely rather than recording a 0 that
    // would read back as 1.
    expect(buildCountRows({ llm_call_count: h(3, 1) }).map(r => r.key)).toEqual(['llm_call_count'])
  })

  it('routes an unrecognised _count segment to the counts view, not the latency table', () => {
    // The JCLAW-870 rule (nothing silently disappears) applied per-kind: a new
    // backend counter must surface somewhere, and the right somewhere is here.
    const metrics = { ttft: h(3, 50), total: h(3, 800), planner_call_count: h(3, 2) }
    expect(buildLatencyRows(metrics).map(r => r.key)).toEqual(['ttft', 'total'])
    expect(buildCountRows(metrics).map(r => r.key)).toEqual(['planner_call_count'])
  })

  it('classifies count segments by explicit name and by _count suffix', () => {
    expect(isCountSegment('llm_call_count')).toBe(true)
    expect(isCountSegment('llm_call_cached')).toBe(true)
    expect(isCountSegment('tool_round_count')).toBe(true)
    expect(isCountSegment('future_thing_count')).toBe(true)
    expect(isCountSegment('ttft')).toBe(false)
    expect(isCountSegment('total')).toBe(false)
  })
})

describe('cachedCallShare (JCLAW-884)', () => {
  const withSum = (count: number, sum: number) => ({ count, sum_ms: sum })

  it('divides summed cardinalities, not percentiles', () => {
    // Both segments are suppressed at zero and clamped to a minimum of 1, so a
    // turn with no cached call emits no sample. Comparing p50s or sample counts
    // would compare different populations; only the sums are commensurable.
    const share = cachedCallShare({
      llm_call_count: withSum(10, 40),
      llm_call_cached: withSum(6, 10),
    })
    expect(share).toBeCloseTo(0.25)
  })

  it('returns null when no LLM call has been recorded', () => {
    expect(cachedCallShare({})).toBeNull()
    expect(cachedCallShare({ llm_call_cached: withSum(2, 3) })).toBeNull()
  })

  it('treats an absent cached segment as zero rather than undefined', () => {
    expect(cachedCallShare({ llm_call_count: withSum(4, 9) })).toBe(0)
  })

  it('clamps to 1 so a clamped-sample artefact cannot report over 100%', () => {
    expect(cachedCallShare({
      llm_call_count: withSum(2, 3),
      llm_call_cached: withSum(2, 5),
    })).toBe(1)
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
    const series = buildChartSeries({ prologue: h(1, 20) })
    expect(series).toEqual([
      { key: 'prologue', label: 'Prologue', histogram: h(1, 20) },
    ])
  })
})
