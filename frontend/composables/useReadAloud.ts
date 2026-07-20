// Shared read-aloud (TTS) playback for chat messages (JCLAW-789/793). One
// module-level <audio> so starting a new read-aloud stops the previous — the
// operator hears one message at a time. Message text is POSTed to
// /api/tts/synthesize, which the operator-selected engine (Settings > Speech)
// renders to a WAV; the returned blob is played and its object URL revoked on
// end. SPA-only (ssr:false), so a module-level singleton is safe.

const playingKey = ref<string | null>(null)
const loadingKey = ref<string | null>(null)
let audioEl: HTMLAudioElement | null = null
let objectUrl: string | null = null

function teardown() {
  if (audioEl) {
    audioEl.onended = null
    audioEl.onerror = null
    audioEl.pause()
    audioEl.src = ''
    audioEl = null
  }
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl)
    objectUrl = null
  }
}

function stop() {
  teardown()
  playingKey.value = null
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

async function toggle(key: string, rawText: string) {
  // Clicking the currently-playing message stops it (toggle semantics).
  if (playingKey.value === key) {
    stop()
    return
  }
  stop()
  const text = stripMarkdown(rawText)
  if (!text) return
  loadingKey.value = key
  try {
    const blob = await $fetch<Blob>('/api/tts/synthesize', {
      method: 'POST',
      body: { text },
      responseType: 'blob',
    })
    objectUrl = URL.createObjectURL(blob)
    audioEl = new Audio(objectUrl)
    audioEl.onended = () => stop()
    audioEl.onerror = () => stop()
    playingKey.value = key
    await audioEl.play()
  }
  catch (e) {
    // Read-aloud is best-effort — a missing/unprovisioned engine shouldn't throw
    // into the chat UI. Log and return the button to idle.
    console.error('read-aloud failed:', e)
    stop()
  }
  finally {
    loadingKey.value = null
  }
}

export function useReadAloud() {
  return { playingKey, loadingKey, toggle, stop }
}
