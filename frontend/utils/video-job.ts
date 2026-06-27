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
  /** Byte size of the filled result. The placeholder is filled in-place, so until a reload the chat's own
   *  attachment still reads sizeBytes=0; the video chip uses this for the live "size" label (JCLAW-234). */
  resultSizeBytes?: number | null
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
 * Progress percent for the generating card, clamped to 0..100, or null when the provider reports none
 * (cloud — SV-1, no reliable percentage). Local engines report a real per-step number (JCLAW-232, from
 * the sidecar's diffusion step callback), which drives a determinate bar instead of a bare spinner.
 */
export function videoProgressPercent(status: VideoJobStatus | undefined): number | null {
  const p = status?.percent
  if (p == null || Number.isNaN(p)) return null
  return Math.max(0, Math.min(100, Math.round(p)))
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

// --- Generated-video chip metadata (JCLAW-234) ---------------------------------------------------------
// The chip shows four facts pulled from three sources: fps + aspectRatio from the persisted
// generationMetadata (fps isn't recoverable from a <video> element), size from the poll status or the
// attachment, and duration from the rendered clip itself (truest value, with the requested value as a
// fallback). These pure helpers keep that resolution testable without mounting chat.vue.

/**
 * Parse the chip-relevant fields out of an attachment's {@code generationMetadata} JSON. fps/aspectRatio
 * are the effective request values the tool persisted; durationSeconds is present only when the caller
 * asked for a specific length. Returns all-null on absent or malformed metadata.
 */
export function videoGenMeta(generationMetadata: string | undefined): {
  aspectRatio: string | null
  fps: number | null
  durationSeconds: number | null
} {
  if (generationMetadata) {
    try {
      const m = JSON.parse(generationMetadata) as { aspectRatio?: unknown, fps?: unknown, durationSeconds?: unknown }
      return {
        aspectRatio: typeof m.aspectRatio === 'string' ? m.aspectRatio : null,
        fps: typeof m.fps === 'number' ? m.fps : null,
        durationSeconds: typeof m.durationSeconds === 'number' ? m.durationSeconds : null,
      }
    }
    catch { /* malformed metadata — show what we can */ }
  }
  return { aspectRatio: null, fps: null, durationSeconds: null }
}

/**
 * Result byte size for the chip: the poll status's size (live — the placeholder is filled in-place so the
 * chat's own attachment still reads 0 until a reload) else the attachment's own size. 0 when neither is known.
 */
export function videoResultSizeBytes(att: VideoAttachmentLike, status: VideoJobStatus | undefined): number {
  if (status?.resultSizeBytes != null && status.resultSizeBytes > 0) return status.resultSizeBytes
  return att.sizeBytes > 0 ? att.sizeBytes : 0
}

/**
 * Human "Ns" duration label, preferring the rendered clip's real duration (off the {@code <video>}
 * element) and falling back to the requested value from metadata. Empty string when neither is known.
 */
export function formatVideoDuration(elementDuration: number | null | undefined, requestedSeconds: number | null): string {
  const d = (elementDuration != null && Number.isFinite(elementDuration) && elementDuration > 0)
    ? elementDuration
    : requestedSeconds
  if (d == null || d <= 0) return ''
  return d < 1 ? `${d.toFixed(1)}s` : `${Math.round(d)}s`
}
