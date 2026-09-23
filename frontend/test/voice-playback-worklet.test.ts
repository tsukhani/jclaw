import { describe, it, expect, beforeAll, beforeEach } from 'vitest'
import { resolve } from 'node:path'

/**
 * Ring-buffer semantics of the voice playback worklet (JCLAW-845).
 *
 * The worklet runs in an AudioWorkletGlobalScope that jsdom does not provide,
 * so we install minimal stand-ins for AudioWorkletProcessor / registerProcessor
 * / sampleRate on globalThis and import the real module, capturing the class
 * from the registerProcessor call. That exercises the actual enqueue + process
 * arithmetic rather than a re-implementation of it.
 */

type Msg = { type: string, free?: number, dropped?: number }

interface Worklet {
  port: { onmessage: ((e: { data: unknown }) => void) | null, postMessage: (m: Msg) => void }
  capacity: number
  filled: number
  process: (inputs: unknown[], outputs: Float32Array[][]) => boolean
}

const SAMPLE_RATE = 48000
const WORKLET_PATH = resolve(__dirname, '../public/worklets/voice-playback.js')

let posted: Msg[] = []
let Processor: new (opts?: { processorOptions?: { bufferSeconds?: number } }) => Worklet

beforeAll(async () => {
  class FakeProcessor {
    port = {
      onmessage: null as ((e: { data: unknown }) => void) | null,
      postMessage: (m: Msg) => { posted.push(m) },
    }
  }
  const g = globalThis as unknown as Record<string, unknown>
  g.AudioWorkletProcessor = FakeProcessor
  g.sampleRate = SAMPLE_RATE
  g.registerProcessor = (_name: string, cls: unknown) => {
    Processor = cls as typeof Processor
  }
  // Executes the module, which calls our registerProcessor stub at the bottom.
  await import(/* @vite-ignore */ WORKLET_PATH)
})

beforeEach(() => {
  posted = []
})

function make(bufferSeconds: number): Worklet {
  return new Processor({ processorOptions: { bufferSeconds } })
}

const push = (p: Worklet, n: number) =>
  p.port.onmessage!({ data: { type: 'push', data: new Float32Array(n) } })

/** One render quantum's worth of output buffers: outputs[0] is the channel list. */
const outputs = (): Float32Array[][] => [[new Float32Array(128)]]

/** Drive `process()` for `quanta` render quanta. */
function render(p: Worklet, quanta: number) {
  const out = outputs()
  for (let i = 0; i < quanta; i++) p.process([], out)
}

describe('voice playback worklet ring', () => {
  it('sizes the ring from the bufferSeconds processor option', () => {
    expect(make(4).capacity).toBe(SAMPLE_RATE * 4)
  })

  it('reports free space on construction so the writer can seed backpressure', () => {
    const p = make(1)
    expect(posted.find(m => m.type === 'level')?.free).toBe(p.capacity)
  })

  it('never overwrites unplayed audio when a push exceeds free space', () => {
    const p = make(1)
    push(p, p.capacity)
    expect(p.filled).toBe(p.capacity)
    push(p, 100)
    // The old writer advanced `read` to make room, discarding audio the listener
    // had not heard yet. The ring must stay exactly full and reject the excess.
    expect(p.filled).toBe(p.capacity)
    expect(posted.find(m => m.type === 'overflow')?.dropped).toBe(100)
  })

  it('accepts the portion that fits and reports only the remainder as dropped', () => {
    const p = make(1)
    push(p, p.capacity - 50)
    push(p, 200)
    expect(p.filled).toBe(p.capacity)
    expect(posted.find(m => m.type === 'overflow')?.dropped).toBe(150)
  })

  it('plays samples back in order and frees space as it drains', () => {
    const p = make(1)
    const data = Float32Array.from({ length: 256 }, (_, i) => i / 256)
    p.port.onmessage!({ data: { type: 'push', data } })
    const out = outputs()
    p.process([], out)
    expect(out[0]![0]![0]).toBeCloseTo(0, 6)
    expect(out[0]![0]![127]).toBeCloseTo(127 / 256, 6)
    expect(p.filled).toBe(128)
  })

  it('emits drained once when the ring empties after playing', () => {
    const p = make(1)
    push(p, 128)
    render(p, 3) // one quantum to drain, two more that must not re-announce
    expect(posted.filter(m => m.type === 'drained')).toHaveLength(1)
  })

  it('reports free space while draining so the writer can top up', () => {
    const p = make(1)
    push(p, SAMPLE_RATE)
    posted = []
    render(p, 20) // past the 16-quantum report interval
    const levels = posted.filter(m => m.type === 'level')
    expect(levels.length).toBeGreaterThan(0)
    expect(levels.at(-1)!.free).toBeGreaterThan(0)
  })

  it('keeps reporting at a steady cadence for the whole drain', () => {
    // The stall watchdog treats a level report as proof that samples are still
    // reaching the speakers. A long reply arrives from the sidecar in seconds
    // and then plays for minutes, so these reports are the ONLY progress signal
    // in that window — if they ever stopped, the turn would be cut off
    // mid-sentence. Free space must also climb monotonically as it drains.
    const p = make(1)
    push(p, SAMPLE_RATE)
    posted = []
    render(p, 160) // 10x the report interval
    const levels = posted.filter(m => m.type === 'level')
    expect(levels).toHaveLength(10)
    const frees = levels.map(l => l.free!)
    expect(frees).toEqual([...frees].sort((a, b) => a - b))
    expect(new Set(frees).size).toBe(frees.length)
  })

  it('flush drops everything and re-reports full free space', () => {
    const p = make(1)
    push(p, 1000)
    posted = []
    p.port.onmessage!({ data: { type: 'flush' } })
    expect(p.filled).toBe(0)
    expect(posted.find(m => m.type === 'level')?.free).toBe(p.capacity)
  })

  it('does not report levels while idle', () => {
    const p = make(1)
    posted = []
    render(p, 40)
    expect(posted.filter(m => m.type === 'level')).toHaveLength(0)
  })

  it('loses no audio for a reply far longer than the ring when the writer respects level', () => {
    // The regression scenario: 30 s of speech through a 1 s ring. The sidecar
    // produces faster than real-time, so the writer is always ready to push —
    // backpressure is the only thing preventing loss.
    const p = make(1)
    const total = SAMPLE_RATE * 30
    let written = 0
    let played = 0
    const out = outputs()
    while (played < total) {
      const free = p.capacity - p.filled
      if (written < total && free > 0) {
        const n = Math.min(free, total - written)
        push(p, n)
        written += n
      }
      p.process([], out)
      played += 128
    }
    expect(written).toBe(total)
    expect(posted.filter(m => m.type === 'overflow')).toHaveLength(0)
  })
})
