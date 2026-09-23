import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import SettingsJvmPanel from '~/components/settings/SettingsJvmPanel.vue'

/**
 * Settings → Performance → Runtime (JCLAW-1057).
 *
 * The assertions concentrate on the ways this panel could quietly lie: reporting an
 * unavailable reading as a real number, collapsing the three memory figures into one,
 * or presenting a cumulative GC counter as though it described the present.
 */

let stats: Record<string, unknown> | null
let requests = 0

registerEndpoint('/api/metrics/jvm', () => {
  requests += 1
  if (stats === null) throw new Error('metrics unavailable')
  return stats
})

function sample(over: Record<string, unknown> = {}) {
  return {
    heapUsed: 268_435_456,
    heapCommitted: 536_870_912,
    heapMax: 2_147_483_648,
    nonHeapUsed: 134_217_728,
    nonHeapCommitted: 150_000_000,
    rssBytes: 1_610_612_736,
    gcCount: 412,
    gcTimeMs: 3_200,
    platformThreads: 44,
    peakPlatformThreads: 61,
    uptimeMs: 9_000_000,
    processCpuLoad: 0.0734,
    availableProcessors: 10,
    machineMemoryBytes: 17_179_869_184,
    llmCallsRunning: 3,
    llmCallsQueued: 0,
    llmCallsMax: 224,
    javaVersion: '25.0.2',
    javaVendor: 'Azul Systems, Inc.',
    javaVendorVersion: 'Zulu25.32+21-CA',
    ...over,
  }
}

beforeEach(() => {
  clearNuxtData()
  requests = 0
  stats = sample()
})

describe('SettingsJvmPanel — memory is three figures, not one', () => {
  it('reports heap, non-heap and process memory as distinct values', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    // 256 MB live inside a 512 MB heap, 128 MB non-heap, 1.5 GB charged by the OS.
    // The held/max figures sit on the card's context line, not in the headline value.
    expect(c.find('[data-testid="jvm-heap"]').text()).toContain('256 MB')
    expect(c.text()).toContain('512 MB')
    expect(c.text()).toContain('2.0 GB')
    expect(c.find('[data-testid="jvm-nonheap"]').text()).toContain('128 MB')
    expect(c.find('[data-testid="jvm-rss"]').text()).toContain('1.5 GB')
  })

  it('says process memory is not the heap, so the gap does not read as a leak', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    // Without this the panel invites the opposite conclusion: RSS far above heap-used
    // is the normal ZGC picture, not evidence of a problem.
    expect(c.text()).toContain('what the operating system')
    expect(c.text()).toContain('not a leak')
  })

  it('shows a dash when the platform cannot report process memory', async () => {
    stats = sample({ rssBytes: null })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    // The one substitution that must never happen: falling back to a heap figure would
    // report 256 MB as the process footprint.
    expect(c.find('[data-testid="jvm-rss"]').text()).toBe('—')
    expect(c.find('[data-testid="jvm-rss"]').text()).not.toContain('256')
  })

  it('renders an absent heap ceiling as "no ceiling" rather than -1 bytes', async () => {
    stats = sample({ heapMax: -1 })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    expect(c.text()).toContain('no ceiling')
    expect(c.text()).not.toContain('-1')
  })
})

describe('SettingsJvmPanel — absent readings stay absent', () => {
  it('distinguishes an unreported CPU share from an idle one', async () => {
    stats = sample({ processCpuLoad: null })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    // "0.0%" would assert the process is idle, which is a different claim from
    // "the JVM did not tell us".
    expect(c.find('[data-testid="jvm-cpu"]').text()).toContain('—')
    expect(c.find('[data-testid="jvm-cpu"]').text()).not.toContain('0.0%')
  })

  it('reports a genuine zero CPU share as a measurement', async () => {
    stats = sample({ processCpuLoad: 0 })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    expect(c.find('[data-testid="jvm-cpu"]').text()).toContain('0.0%')
  })
})

describe('SettingsJvmPanel — threads', () => {
  it('labels the count as platform threads and says virtual ones are excluded', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    expect(c.find('[data-testid="jvm-threads"]').text()).toContain('44')
    expect(c.text()).toContain('peak 61')
    // A low flat number here is expected on a virtual-thread-only fork; unlabelled it
    // reads as an idle instance.
    expect(c.text()).toContain('excludes virtual threads')
  })
})

describe('SettingsJvmPanel — garbage collection', () => {
  it('shows the cumulative counters on the first sample', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    // Without a prior sample there is no delta to claim, so the headline falls back to
    // the running total rather than asserting a change of zero.
    expect(c.find('[data-testid="jvm-gc"]').text()).toBe('412')
  })

  it('derives collections-since-last-sample once a second sample arrives', async () => {
    vi.useFakeTimers()
    try {
      const c = await mountSuspended(SettingsJvmPanel)
      await flushPromises()

      stats = sample({ gcCount: 419 })
      await vi.advanceTimersByTimeAsync(5_000)
      await flushPromises()

      // The reason this panel polls at all: 419 on its own says nothing, +7 does. The
      // headline is the delta; the cumulative total is demoted to the context line.
      expect(c.find('[data-testid="jvm-gc"]').text()).toBe('+7')
      expect(c.text()).toContain('419 total')
    }
    finally {
      vi.useRealTimers()
    }
  })
})

describe('SettingsJvmPanel — polling discipline', () => {
  it('stops polling once unmounted', async () => {
    vi.useFakeTimers()
    try {
      const c = await mountSuspended(SettingsJvmPanel)
      await flushPromises()
      const afterMount = requests

      c.unmount()
      await vi.advanceTimersByTimeAsync(20_000)

      // A leaked interval outlives the section and keeps requesting forever.
      expect(requests).toBe(afterMount)
    }
    finally {
      vi.useRealTimers()
    }
  })

  it('surfaces a failure instead of rendering an empty grid', async () => {
    stats = null
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    expect(c.text()).toContain('Could not read the JVM metrics')
  })
})

describe('SettingsJvmPanel — visuals', () => {
  /**
   * The layout bug this design exists to prevent: cards whose value text wraps to a
   * different number of lines used to push their chart to a different height, so a row
   * of charts stepped down the page. jsdom performs no layout, so the pixel result is
   * unassertable — what is pinned here is the mechanism that produces it: every visual
   * sits in an mt-auto box inside a flex-col cell, which bottom-aligns it whatever the
   * text above does.
   */
  it('anchors every visual to the bottom of its cell', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    for (const id of ['jvm-heap-bar', 'jvm-rss-bar', 'jvm-cpu-spark', 'jvm-gc-spark']) {
      const anchor = c.find(`[data-testid="${id}"]`).element.parentElement
      expect(anchor?.className, `${id} should sit in a bottom-anchored box`).toContain('mt-auto')
      expect(anchor?.parentElement?.className, `${id}'s cell should be a flex column`)
        .toContain('flex-col')
    }
  })

  it('draws the heap as used and held-unused against the ceiling', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    const segs = c.find('[data-testid="jvm-heap-bar"]').findAll('div')
    expect(segs).toHaveLength(2)
    // 256 MB used and 256 MB held-but-unused inside a 2 GB ceiling — an eighth each.
    expect(segs[0]!.attributes('style')).toContain('12.5%')
    expect(segs[1]!.attributes('style')).toContain('12.5%')
  })

  it('scales the heap bar to committed when there is no ceiling', async () => {
    stats = sample({ heapMax: -1 })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    // Against committed (512 MB), not -1, which would make every width negative.
    expect(c.find('[data-testid="jvm-heap-bar"]').find('div').attributes('style')).toContain('50%')
  })

  /** A real-but-tiny share must still be visible, or it reads as a rendering fault. */
  it('keeps a sub-pixel proportion visible without overstating it', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    // 1.5 GB of 16 GB is 9.4%; the floor only matters below ~0.2%, so both the true
    // percentage and the floor appear in the width expression.
    const style = c.find('[data-testid="jvm-rss-bar"]').find('div').attributes('style')!
    expect(style).toContain('9.375%')
    expect(style).toContain('2px')
  })

  it('omits the process-memory bar when the machine size is unknown', async () => {
    stats = sample({ machineMemoryBytes: null })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    // Without a bound there is no proportion to draw; the figure still renders.
    expect(c.find('[data-testid="jvm-rss-bar"]').findAll('div')).toHaveLength(0)
    expect(c.find('[data-testid="jvm-rss"]').text()).toContain('1.5 GB')
  })

  it('draws no trend line until there are two samples to join', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()
    expect(c.find('[data-testid="jvm-cpu-spark"]').find('path').exists()).toBe(false)
  })

  it('plots processor share once a second sample arrives', async () => {
    vi.useFakeTimers()
    try {
      const c = await mountSuspended(SettingsJvmPanel)
      await flushPromises()

      stats = sample({ processCpuLoad: 0.5 })
      await vi.advanceTimersByTimeAsync(5_000)
      await flushPromises()

      expect(c.find('[data-testid="jvm-cpu-spark"]').find('path').exists()).toBe(true)
    }
    finally {
      vi.useRealTimers()
    }
  })
})

describe('SettingsJvmPanel — the JDK serving the page', () => {
  it('shows the Java version with the vendor and its build', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    expect(c.find('[data-testid="jvm-version"]').text()).toBe('25.0.2')
    expect(c.find('[data-testid="jvm-vendor"]').text()).toBe('Azul Systems, Inc. · Zulu25.32+21-CA')
  })

  it('leaves out a vendor build the JDK does not report', async () => {
    stats = sample({ javaVendorVersion: null })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    expect(c.find('[data-testid="jvm-vendor"]').text()).toBe('Azul Systems, Inc.')
  })
})

describe('SettingsJvmPanel — LLM dispatcher occupancy', () => {
  /**
   * The reason this metric sits here: the dispatcher caps render directly beneath this
   * panel, and without in-flight and queued counts there is no evidence to tune them on.
   */
  it('reports calls in flight against the cap they are bounded by', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    expect(c.find('[data-testid="jvm-llm"]').text()).toBe('3')
    expect(c.text()).toContain('0 queued')
    expect(c.text()).toContain('cap 224')
  })

  /** Queued is the alarm: it only moves when the cap, not the provider, is the limit. */
  it('surfaces a queue building behind the cap', async () => {
    stats = sample({ llmCallsRunning: 224, llmCallsQueued: 17 })
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    expect(c.find('[data-testid="jvm-llm"]').text()).toBe('224')
    expect(c.text()).toContain('17 queued')
  })

  it('plots calls in flight once a second sample arrives', async () => {
    vi.useFakeTimers()
    try {
      const c = await mountSuspended(SettingsJvmPanel)
      await flushPromises()

      stats = sample({ llmCallsRunning: 9 })
      await vi.advanceTimersByTimeAsync(5_000)
      await flushPromises()

      expect(c.find('[data-testid="jvm-llm-spark"]').find('path').exists()).toBe(true)
    }
    finally {
      vi.useRealTimers()
    }
  })

  /**
   * The layout change this accompanies: all three trend cells share row two, and uptime
   * moved down to sit beside platform threads. jsdom performs no layout, so the ordering
   * of the cells is what is pinned — the visual alignment is verified in the browser.
   */
  it('groups the three sparkline cells together, ahead of the plain-figure cells', async () => {
    const c = await mountSuspended(SettingsJvmPanel)
    await flushPromises()

    const labels = c.findAll('dt').map(d => d.text())
    expect(labels).toEqual([
      'Heap', 'Non-heap', 'Process memory',
      'CPU', 'Garbage collection', 'LLM calls in flight',
      'Uptime', 'Platform threads', 'JVM',
    ])
  })
})
