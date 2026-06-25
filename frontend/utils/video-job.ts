/**
 * Display-state logic for generated-video attachments (JCLAW-234). Pure functions, so the user-visible
 * correctness — given a placeholder attachment and its polled job status, is the card "generating",
 * "ready", or "failed"? — is unit-testable without mounting chat.vue or faking the poll timer. The poll
 * loop in chat.vue is thin glue over these.
 */

export interface VideoJobStatus {
  id: number
  state: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  percent?: number | null
  errorMessage?: string | null
  resultAttachmentUuid?: string | null
}

export type VideoDisplayState = 'generating' | 'ready' | 'failed'

/** Minimal attachment shape these helpers read (a structural subset of MessageAttachment). */
interface VideoAttachmentLike {
  uuid: string
  kind: string
  generated?: boolean
  generationJobId?: number
  sizeBytes: number
}

/**
 * Resolve what the card should show. SUCCEEDED without a result uuid means the fetch/store failed after
 * a successful generation — surfaced as "failed" rather than spinning forever. A reloaded, already-filled
 * placeholder (sizeBytes > 0) is "ready" before the first poll even returns.
 */
export function videoDisplayState(att: VideoAttachmentLike, status: VideoJobStatus | undefined): VideoDisplayState {
  if (status?.state === 'FAILED') return 'failed'
  if (status?.state === 'SUCCEEDED') return status.resultAttachmentUuid ? 'ready' : 'failed'
  if (att.sizeBytes > 0) return 'ready'
  return 'generating'
}

/**
 * Source URL for the inline player — the result uuid once known, else the placeholder's own uuid (the
 * same row, once the runner fills it).
 */
export function videoSrc(att: VideoAttachmentLike, status: VideoJobStatus | undefined): string {
  return `/api/attachments/${status?.resultAttachmentUuid ?? att.uuid}`
}

/**
 * True when this attachment is a video placeholder whose job still needs polling (not yet terminal, not
 * yet filled). Drives the poll loop's keep-going / stop decision.
 */
export function isVideoJobPending(att: VideoAttachmentLike, status: VideoJobStatus | undefined): boolean {
  if (att.kind !== 'VIDEO' || !att.generated || att.generationJobId == null) return false
  if (att.sizeBytes > 0) return false
  return status?.state !== 'SUCCEEDED' && status?.state !== 'FAILED'
}
