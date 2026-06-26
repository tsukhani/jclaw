import { describe, it, expect } from 'vitest'
import { videoDisplayState, videoSrc, isVideoJobPending, videoProgressPercent, type VideoJobStatus } from '~/utils/video-job'

/** JCLAW-234: the generated-video card's display-state logic, isolated from chat.vue and the poll timer. */
function ph(over: Record<string, unknown> = {}) {
  return { uuid: 'u1', kind: 'VIDEO', generated: true, generationJobId: 7, sizeBytes: 0, ...over } as Parameters<typeof videoDisplayState>[0]
}

describe('videoDisplayState', () => {
  it('is generating with no status and no bytes yet', () => {
    expect(videoDisplayState(ph(), undefined)).toBe('generating')
  })

  it('stays generating while RUNNING', () => {
    expect(videoDisplayState(ph(), { id: 7, state: 'RUNNING' })).toBe('generating')
  })

  it('is ready once the placeholder has bytes (reload of a completed video, pre-poll)', () => {
    expect(videoDisplayState(ph({ sizeBytes: 1024 }), undefined)).toBe('ready')
  })

  it('is ready on SUCCEEDED with a result uuid', () => {
    const s: VideoJobStatus = { id: 7, state: 'SUCCEEDED', resultAttachmentUuid: 'u1' }
    expect(videoDisplayState(ph(), s)).toBe('ready')
  })

  it('is failed on SUCCEEDED without a result uuid (fill failed — must not spin forever)', () => {
    const s: VideoJobStatus = { id: 7, state: 'SUCCEEDED', resultAttachmentUuid: null }
    expect(videoDisplayState(ph(), s)).toBe('failed')
  })

  it('is failed on FAILED', () => {
    expect(videoDisplayState(ph(), { id: 7, state: 'FAILED', errorMessage: 'nope' })).toBe('failed')
  })
})

describe('videoSrc', () => {
  it('prefers the result uuid', () => {
    expect(videoSrc(ph(), { id: 7, state: 'SUCCEEDED', resultAttachmentUuid: 'rUuid' })).toBe('/api/attachments/rUuid')
  })

  it('falls back to the placeholder uuid', () => {
    expect(videoSrc(ph(), undefined)).toBe('/api/attachments/u1')
  })
})

describe('videoProgressPercent', () => {
  it('is null when no status', () => {
    expect(videoProgressPercent(undefined)).toBeNull()
  })

  it('is null for cloud (percent null/undefined — SV-1)', () => {
    expect(videoProgressPercent({ id: 7, state: 'RUNNING', percent: null })).toBeNull()
    expect(videoProgressPercent({ id: 7, state: 'RUNNING' })).toBeNull()
  })

  it('passes a real local percent through (JCLAW-232)', () => {
    expect(videoProgressPercent({ id: 7, state: 'RUNNING', percent: 42 })).toBe(42)
  })

  it('clamps to 0..100 and rounds', () => {
    expect(videoProgressPercent({ id: 7, state: 'RUNNING', percent: -5 })).toBe(0)
    expect(videoProgressPercent({ id: 7, state: 'RUNNING', percent: 150 })).toBe(100)
    expect(videoProgressPercent({ id: 7, state: 'RUNNING', percent: 42.7 })).toBe(43)
  })

  it('is null for NaN', () => {
    expect(videoProgressPercent({ id: 7, state: 'RUNNING', percent: Number.NaN })).toBeNull()
  })
})

describe('isVideoJobPending', () => {
  it('is true for a fresh video placeholder', () => {
    expect(isVideoJobPending(ph(), undefined)).toBe(true)
  })

  it('is false once bytes are present', () => {
    expect(isVideoJobPending(ph({ sizeBytes: 10 }), undefined)).toBe(false)
  })

  it('is false on terminal states', () => {
    expect(isVideoJobPending(ph(), { id: 7, state: 'SUCCEEDED', resultAttachmentUuid: 'u1' })).toBe(false)
    expect(isVideoJobPending(ph(), { id: 7, state: 'FAILED' })).toBe(false)
  })

  it('is false for non-video, non-generated, or unlinked attachments', () => {
    expect(isVideoJobPending(ph({ kind: 'IMAGE' }), undefined)).toBe(false)
    expect(isVideoJobPending(ph({ generated: false }), undefined)).toBe(false)
    expect(isVideoJobPending(ph({ generationJobId: undefined }), undefined)).toBe(false)
  })
})
