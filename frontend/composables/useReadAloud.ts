// Shared streaming read-aloud (TTS) for chat messages (JCLAW-789/790). POSTs to
// /api/tts/stream, which sentence-chunks the text and SSE-streams each chunk's
// WAV as it synthesizes; we base64-decode each chunk and schedule it gaplessly
// on ONE AudioContext timeline, so playback starts on the first sentence instead
// of after the whole message. One playback at a time app-wide (starting a new
// read-aloud stops the previous). SPA-only (ssr:false), so a module singleton is
// safe. The exported API is unchanged from the non-streaming version, so callers
// (ChatMessage.vue) need no change.

const playingKey = ref<string | null>(null) // a chunk is scheduled/playing
const loadingKey = ref<string | null>(null) // requested, no audio yet

let audioCtx: AudioContext | null = null
let controller: AbortController | null = null
let activeKey: string | null = null
let nextStartTime = 0
let scheduledCount = 0
let endedCount = 0
let streamDone = false
const sources: AudioBufferSourceNode[] = []

function teardown() {
  if (controller) {
    controller.abort()
    controller = null
  }
  for (const s of sources) {
    try {
      s.onended = null
      s.stop()
    }
    catch {
      // already stopped / never started — fine
    }
  }
  sources.length = 0
  if (audioCtx) {
    audioCtx.close().catch(() => {})
    audioCtx = null
  }
  activeKey = null
  nextStartTime = 0
  scheduledCount = 0
  endedCount = 0
  streamDone = false
}

function stop() {
  teardown()
  playingKey.value = null
  loadingKey.value = null
}

/** Resolve playback once the stream has ended and every scheduled chunk has
 *  finished — clears the button back to idle. */
function maybeFinish() {
  if (streamDone && endedCount >= scheduledCount) stop()
}

/** Reduce message markdown to speakable plain text — drop code fences, image
 *  syntax, link URLs (keep the text), and stray markup symbols. */
function stripMarkdown(md: string): string {
  return (md || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/[*_~>#]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

async function decodeChunk(b64: string): Promise<AudioBuffer | null> {
  if (!audioCtx) return null
  try {
    const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0))
    return await audioCtx.decodeAudioData(bytes.buffer)
  }
  catch (e) {
    console.error('read-aloud: failed to decode audio chunk', e)
    return null
  }
}

function scheduleBuffer(key: string, buf: AudioBuffer) {
  if (activeKey !== key || !audioCtx) return
  const src = audioCtx.createBufferSource()
  src.buffer = buf
  src.connect(audioCtx.destination)
  const startAt = Math.max(audioCtx.currentTime, nextStartTime)
  src.start(startAt)
  nextStartTime = startAt + buf.duration
  scheduledCount++
  sources.push(src)
  src.onended = () => {
    endedCount++
    maybeFinish()
  }
}

async function handleEvent(key: string, ev: { type?: string, audio?: string, message?: string }) {
  if (activeKey !== key) return
  if (ev.type === 'audio' && ev.audio) {
    const buf = await decodeChunk(ev.audio)
    if (activeKey !== key || !buf) return
    scheduleBuffer(key, buf)
    if (loadingKey.value === key) {
      loadingKey.value = null
      playingKey.value = key
    }
  }
  else if (ev.type === 'complete') {
    streamDone = true
    maybeFinish()
  }
  else if (ev.type === 'error') {
    console.error('read-aloud error:', ev.message)
    stop()
  }
}

async function toggle(key: string, rawText: string) {
  // Clicking the currently-active message stops it (toggle semantics).
  if (playingKey.value === key || loadingKey.value === key) {
    stop()
    return
  }
  stop()
  const text = stripMarkdown(rawText)
  if (!text) return

  activeKey = key
  loadingKey.value = key
  controller = new AbortController()
  // Created on the click (a user gesture), so autoplay policy is satisfied.
  audioCtx = new AudioContext()
  nextStartTime = audioCtx.currentTime

  try {
    const res = await fetch('/api/tts/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
      signal: controller.signal,
    })
    if (!res.ok || !res.body) throw new Error(`tts stream HTTP ${res.status}`)

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (!line.startsWith('data: ')) continue
        let ev: { type?: string, audio?: string, message?: string }
        try {
          ev = JSON.parse(line.slice(6))
        }
        catch {
          continue // skip malformed frame
        }
        await handleEvent(key, ev)
      }
      if (activeKey !== key) return // stopped / superseded mid-stream
    }
    // Stream closed — treat as complete so playback finishes cleanly even if the
    // server never sent a final 'complete' frame.
    if (activeKey === key) {
      streamDone = true
      maybeFinish()
    }
  }
  catch (e) {
    if ((e as { name?: string })?.name !== 'AbortError') {
      console.error('read-aloud stream failed:', e)
    }
    stop()
  }
}

export function useReadAloud() {
  return { playingKey, loadingKey, toggle, stop }
}
