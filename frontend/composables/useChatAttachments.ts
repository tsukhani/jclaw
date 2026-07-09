import type { Ref } from 'vue'
import type { ConfigResponse } from '~/types/api'

/**
 * Composer attachment lifecycle (JCLAW-25 / JCLAW-131 / JCLAW-165).
 *
 * Owns the staged-file queue, per-kind size caps sourced from /api/config,
 * thumbnail previews, the paperclip upload, and the browser voice-note
 * recorder. Extracted from pages/chat.vue so the attachment gate and voice
 * pipeline are one cohesive unit; the page keeps only the read-only-transcript
 * guarded event handlers (drop/paste/file-input) that call {@link
 * UseChatAttachments.addAttachments}.
 */

/** Upload response shape returned per file from POST /api/chat/upload.
 *  AUDIO joins IMAGE / FILE on this union — the backend's
 *  {@code MessageAttachment.kindForMime} routes audio mimetypes through
 *  the AUDIO branch (browser-recorded voice notes especially). */
export interface UploadedAttachment {
  attachmentId: string
  originalFilename: string
  mimeType: string
  sizeBytes: number
  kind: 'IMAGE' | 'AUDIO' | 'FILE'
}

export interface UseChatAttachments {
  fileInput: Ref<HTMLInputElement | null>
  attachedFiles: Ref<File[]>
  attachError: Ref<string | null>
  attachmentPreviews: Ref<Map<File, string>>
  isRecording: Ref<boolean>
  addAttachments: (files: File[]) => void
  removeAttachment: (idx: number) => void
  triggerFileUpload: () => void
  toggleRecording: () => void
  uploadAttachments: (agentId: number) => Promise<UploadedAttachment[]>
}

const MAX_ATTACHMENTS = 5

// JCLAW-131: per-kind upload caps sourced from /api/config, with defaults
// matching services/UploadLimits.java. The server re-applies these caps on
// upload (authoritative); the frontend copy is just UX — showing the user
// the refusal before bytes leave the browser.
const DEFAULT_MAX_IMAGE_BYTES = 20 * 1024 * 1024
const DEFAULT_MAX_AUDIO_BYTES = 100 * 1024 * 1024
const DEFAULT_MAX_FILE_BYTES = 100 * 1024 * 1024

function isImageFile(f: File): boolean {
  return typeof f.type === 'string' && f.type.startsWith('image/')
}

function isAudioFile(f: File): boolean {
  return typeof f.type === 'string' && f.type.startsWith('audio/')
}

function kindLabelForFile(f: File): string {
  if (isImageFile(f)) return 'image'
  if (isAudioFile(f)) return 'audio'
  return 'file'
}

/** Pick the best supported MIME for this browser. Chromium ships
 *  audio/webm (Opus); Safari exposes audio/mp4 instead. Returns null if
 *  neither is supported so the caller can fall back to a default. */
function pickAudioMime(): string | null {
  if (typeof MediaRecorder === 'undefined') return null
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4']
  for (const c of candidates) {
    if (MediaRecorder.isTypeSupported(c)) return c
  }
  return null
}

function extensionForMime(mime: string): string {
  if (mime.startsWith('audio/webm')) return 'webm'
  if (mime.startsWith('audio/mp4')) return 'm4a'
  return 'bin'
}

export function useChatAttachments(configData: Ref<ConfigResponse | null>): UseChatAttachments {
  const fileInput = ref<HTMLInputElement | null>(null)
  const attachedFiles = ref<File[]>([])
  const attachError = ref<string | null>(null)

  function configInt(key: string, fallback: number): number {
    const raw = configData.value?.entries?.find(e => e.key === key)?.value
    if (!raw) return fallback
    const parsed = Number.parseInt(raw, 10)
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
  }
  const maxImageBytes = computed(() => configInt('upload.maxImageBytes', DEFAULT_MAX_IMAGE_BYTES))
  const maxAudioBytes = computed(() => configInt('upload.maxAudioBytes', DEFAULT_MAX_AUDIO_BYTES))
  const maxFileBytes = computed(() => configInt('upload.maxFileBytes', DEFAULT_MAX_FILE_BYTES))

  // Per-file thumbnail preview URL for image attachments (JCLAW-25). Keyed by
  // File identity via a WeakMap-like ref map; createObjectURL results are
  // revoked when the chip is removed so we don't leak blob handles across
  // long chat sessions.
  const attachmentPreviews = ref(new Map<File, string>())

  /** Effective byte cap for this File's kind, sourced from Settings config. */
  function capForFile(f: File): number {
    if (isImageFile(f)) return maxImageBytes.value
    if (isAudioFile(f)) return maxAudioBytes.value
    return maxFileBytes.value
  }

  function triggerFileUpload() {
    fileInput.value?.click()
  }

  function addAttachments(files: File[]) {
    attachError.value = null
    for (const f of files) {
      if (attachedFiles.value.length >= MAX_ATTACHMENTS) {
        attachError.value = `Maximum ${MAX_ATTACHMENTS} files per message`
        break
      }
      // JCLAW-131: per-kind size cap, sourced from Settings config. The
      // human-readable message names the kind so the operator knows which
      // limit to raise.
      const cap = capForFile(f)
      if (f.size > cap) {
        const label = kindLabelForFile(f)
        attachError.value = `${f.name} exceeds ${Math.round(cap / (1024 * 1024))} MB limit for ${label} uploads`
        continue
      }
      // JCLAW-215: images are universally accepted regardless of the model's
      // supportsVision flag — a non-vision model gets a server-generated caption
      // text part (via the captioning pipeline), exactly as JCLAW-165 gives a
      // text-only model an audio transcript. No client-side gate for either.
      attachedFiles.value.push(f)
      if (isImageFile(f)) {
        attachmentPreviews.value.set(f, URL.createObjectURL(f))
      }
    }
  }

  function removeAttachment(idx: number) {
    const f = attachedFiles.value[idx]
    if (f) {
      const url = attachmentPreviews.value.get(f)
      if (url) {
        URL.revokeObjectURL(url)
        attachmentPreviews.value.delete(f)
      }
    }
    attachedFiles.value.splice(idx, 1)
  }

  // ────────────────────── Voice-note recording (browser mic) ─────────────────
  // One click starts the recorder; a second click stops it and attaches the
  // captured blob through the same addAttachments() pipeline as paperclip
  // uploads. Universally available — JCLAW-165's transcription pipeline lets
  // every model consume audio, so no capability gate is needed.

  const isRecording = ref(false)
  // Kept as plain non-reactive bindings — Vue reactivity on a MediaRecorder
  // proxy would be wasteful and the ondataavailable callback can't walk a
  // reactive wrapper without surprises.
  let mediaRecorder: MediaRecorder | null = null
  let mediaStream: MediaStream | null = null
  let recordedChunks: Blob[] = []

  function releaseRecorder() {
    if (mediaStream) {
      for (const track of mediaStream.getTracks()) track.stop()
    }
    mediaStream = null
    mediaRecorder = null
    recordedChunks = []
  }

  async function startRecording() {
    if (isRecording.value) return
    // JCLAW-165: voice recording is universally available — the transcription
    // pipeline pairs every audio attachment with a transcript before the LLM
    // sees it, so model.supportsAudio no longer gates the mic.
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      attachError.value = 'Voice recording not supported in this browser'
      return
    }
    let stream: MediaStream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    }
    catch {
      // NotAllowedError (denied) and NotFoundError (no device) both land here;
      // the user-visible string is deliberately generic since the remedy —
      // "check site permissions / plug in a mic" — covers both.
      attachError.value = 'Microphone access denied or unavailable'
      return
    }
    const mime = pickAudioMime()
    mediaStream = stream
    recordedChunks = []
    try {
      mediaRecorder = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined)
    }
    catch {
      attachError.value = 'Voice recording not supported in this browser'
      releaseRecorder()
      return
    }
    mediaRecorder.ondataavailable = (e: BlobEvent) => {
      if (e.data && e.data.size > 0) recordedChunks.push(e.data)
    }
    mediaRecorder.onstop = () => {
      // mediaRecorder is nulled in releaseRecorder(); capture the type first.
      const effectiveType = mediaRecorder?.mimeType || mime || 'audio/webm'
      const blob = new Blob(recordedChunks, { type: effectiveType })
      const ext = extensionForMime(effectiveType)
      // YYYYMMDD-HHMMSS in local time — readable at a glance in the chip
      // and unique enough across a single session. We skip the timezone
      // because the filename doesn't roundtrip to the agent; it's just UX.
      const d = new Date()
      const pad = (n: number) => String(n).padStart(2, '0')
      const ts = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
        + `-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
      const file = new File([blob], `voice-${ts}.${ext}`, { type: effectiveType })
      addAttachments([file])
      releaseRecorder()
    }
    mediaRecorder.start()
    isRecording.value = true
  }

  function stopRecording() {
    if (!isRecording.value) return
    isRecording.value = false
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      // onstop fires asynchronously after this call; releaseRecorder is
      // invoked from inside the handler so the final blob is built before
      // we tear down.
      mediaRecorder.stop()
    }
    else {
      releaseRecorder()
    }
  }

  function toggleRecording() {
    if (isRecording.value) stopRecording()
    else void startRecording()
  }

  onBeforeUnmount(() => {
    if (isRecording.value) stopRecording()
    else releaseRecorder()
  })

  async function uploadAttachments(agentId: number): Promise<UploadedAttachment[]> {
    if (!attachedFiles.value.length) return []
    const form = new FormData()
    form.append('agentId', String(agentId))
    for (const f of attachedFiles.value) {
      form.append('files', f, f.name)
    }
    const res = await $fetch<{ files: UploadedAttachment[] }>(
      '/api/chat/upload',
      { method: 'POST', body: form },
    )
    return res.files
  }

  return {
    fileInput,
    attachedFiles,
    attachError,
    attachmentPreviews,
    isRecording,
    addAttachments,
    removeAttachment,
    triggerFileUpload,
    toggleRecording,
    uploadAttachments,
  }
}
