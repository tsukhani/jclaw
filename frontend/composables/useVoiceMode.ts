// Real-time voice mode client (JCLAW-791; capture/playback JCLAW-796;
// continuous streaming JCLAW-799). Opens a WebSocket to /api/voice, streams the
// mic continuously as 16 kHz PCM16, and plays the streamed TTS reply through an
// AudioWorklet ring buffer. One voice session app-wide (SPA singleton).
//
// JCLAW-799 — server-side endpointing: the browser no longer segments speech.
// It streams every frame; the SERVER runs the Silero VAD + adaptive-silence
// endpointer to decide where utterances begin and end, and drives the UI state
// (`capturing`/`thinking`) plus barge-in (`flush`) over the socket. The mic
// stays open through the agent's reply so the server can hear an interruption.
//
// JCLAW-796 — capture runs on a dedicated-thread AudioWorklet and playback on a
// ring-buffer worklet that hard-stops in one `flush` message (within a render
// quantum), rather than the deprecated ScriptProcessorNode / N scheduled nodes.
//
// Dev note: the WS is same-origin (/api/voice); works in prod (one JVM). In
// `--dev` the Nitro proxy may not forward the WS upgrade to :9000, and the
// Origin check may reject a mismatched proxy Origin — prod is the reference path.

export type VoiceState = 'idle' | 'connecting' | 'listening' | 'capturing' | 'thinking' | 'speaking' | 'error'

const state = ref<VoiceState>('idle')
const transcript = ref('')
const reply = ref('')
const errorMsg = ref('')

// --- connection + capture graph ---
let ws: WebSocket | null = null
let mediaStream: MediaStream | null = null
let micCtx: AudioContext | null = null
let sourceNode: MediaStreamAudioSourceNode | null = null
let captureNode: AudioWorkletNode | null = null

// --- ring-buffer playback ---
let playCtx: AudioContext | null = null
let playNode: AudioWorkletNode | null = null
let bufferEmpty = true // mirrors the worklet ring buffer (drained = true)
let turnComplete = false
let currentTurn = -1 // the turn whose audio we currently accept (-1 = none)
let watchdog: ReturnType<typeof setTimeout> | null = null

// Worklet modules (served from public/). Loaded once per AudioContext.
const CAPTURE_WORKLET = '/worklets/voice-capture.js'
const PLAYBACK_WORKLET = '/worklets/voice-playback.js'
// The server's Silero VAD wants 16 kHz mono; request that capture rate so no
// resampling is needed. Frames of 1024 samples (~64 ms) keep barge-in latency low.
const TARGET_RATE = 16_000
const STREAM_FRAME = 1024

// Stall watchdog: recover instead of hanging if a turn makes no progress — a
// generous ceiling while the agent is thinking (tool use), shorter once audio
// should be flowing (a synth hang or a suspended AudioContext).
const THINKING_STALL_MS = 60_000
const SPEAKING_STALL_MS = 15_000

function teardown() {
  clearWatchdog()
  if (captureNode) {
    captureNode.port.onmessage = null
    captureNode.disconnect()
    captureNode = null
  }
  if (sourceNode) {
    sourceNode.disconnect()
    sourceNode = null
  }
  if (micCtx) {
    micCtx.close().catch(() => {})
    micCtx = null
  }
  if (mediaStream) {
    mediaStream.getTracks().forEach(t => t.stop())
    mediaStream = null
  }
  stopPlayback()
  if (playNode) {
    playNode.port.onmessage = null
    playNode.disconnect()
    playNode = null
  }
  if (playCtx) {
    playCtx.close().catch(() => {})
    playCtx = null
  }
  if (ws) {
    try {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'bye' }))
      ws.onclose = null
      ws.close()
    }
    catch {
      // socket already gone
    }
    ws = null
  }
  bufferEmpty = true
  turnComplete = false
  currentTurn = -1
}

function stop() {
  teardown()
  transcript.value = ''
  reply.value = ''
  errorMsg.value = ''
  state.value = 'idle'
}

function fail(msg: string) {
  errorMsg.value = msg
  state.value = 'error'
  teardown()
}

async function start(agentId: number) {
  if (state.value !== 'idle' && state.value !== 'error') return
  state.value = 'connecting'
  transcript.value = ''
  reply.value = ''
  errorMsg.value = ''
  try {
    // Transport is negotiated by the browser + server, not chosen here: over an
    // HTTPS/h2 (or h3) origin the browser establishes this socket via Extended
    // CONNECT — RFC 8441 / 9220, which the play1 fork advertises since 1.13.46 —
    // otherwise it falls back to a separate HTTP/1.1 connection. Same
    // new WebSocket(url) either way; there is no client-side transport knob.
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
    ws = new WebSocket(`${scheme}//${location.host}/api/voice`)
    ws.binaryType = 'arraybuffer'
    ws.onopen = () => ws?.send(JSON.stringify({ type: 'init', agentId }))
    ws.onmessage = onMessage
    ws.onerror = () => fail('voice connection error')
    ws.onclose = () => {
      if (state.value !== 'error') stop()
    }
    await startMic()
    await startPlayback()
  }
  catch (e) {
    fail((e as Error)?.message || 'could not start voice mode (mic permission?)')
  }
}

async function startMic() {
  mediaStream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  })
  micCtx = new AudioContext({ sampleRate: TARGET_RATE })
  if (micCtx.sampleRate !== TARGET_RATE) {
    // Rare: the platform ignored the requested rate. Server VAD/STT assume
    // 16 kHz, so warn — resampling would go here if this proves common.
    console.warn(`voice: capture rate is ${micCtx.sampleRate}Hz, expected ${TARGET_RATE}Hz`)
  }
  await micCtx.audioWorklet.addModule(CAPTURE_WORKLET)
  sourceNode = micCtx.createMediaStreamSource(mediaStream)
  captureNode = new AudioWorkletNode(micCtx, 'voice-capture-processor', {
    numberOfInputs: 1,
    numberOfOutputs: 1,
    processorOptions: { frame: STREAM_FRAME },
  })
  captureNode.port.onmessage = e => sendFrame(e.data as Float32Array)
  sourceNode.connect(captureNode)
  // The node emits silence; connecting to the destination only keeps the graph
  // pulling so process() fires (the mic is never routed to the speakers).
  captureNode.connect(micCtx.destination)
}

async function startPlayback() {
  playCtx = new AudioContext()
  await playCtx.audioWorklet.addModule(PLAYBACK_WORKLET)
  playNode = new AudioWorkletNode(playCtx, 'voice-playback-processor', {
    numberOfInputs: 0,
    numberOfOutputs: 1,
    outputChannelCount: [1],
  })
  playNode.port.onmessage = (e) => {
    if ((e.data as { type?: string })?.type === 'drained') onDrained()
  }
  playNode.connect(playCtx.destination)
}

// Stream one capture frame as little-endian PCM16. The mic streams continuously
// (including through the agent's reply, for barge-in); the server's VAD decides
// what is speech, so there is no client-side gating.
function sendFrame(input: Float32Array) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return
  const pcm = new Int16Array(input.length)
  for (let i = 0; i < input.length; i++) {
    const s = Math.max(-1, Math.min(1, input[i]!))
    pcm[i] = s < 0 ? s * 0x8000 : s * 0x7FFF
  }
  ws.send(pcm.buffer)
}

async function onMessage(ev: MessageEvent) {
  if (typeof ev.data !== 'string') return
  let msg: { type?: string, value?: string, text?: string, audio?: string, message?: string, turn?: number }
  try {
    msg = JSON.parse(ev.data)
  }
  catch {
    return
  }
  switch (msg.type) {
    case 'ready':
      state.value = 'listening'
      break
    case 'state':
      // Server-driven UI state from its VAD/endpointer.
      if (msg.value === 'capturing') state.value = 'capturing'
      else if (msg.value === 'thinking') {
        state.value = 'thinking'
        turnComplete = false
      }
      break
    case 'flush':
      // Barge-in: the server abandoned the in-flight turn — drop queued audio.
      stopPlayback()
      break
    case 'transcript':
      if (msg.turn != null) currentTurn = msg.turn
      transcript.value = msg.text || ''
      break
    case 'reply':
      if (msg.turn != null) currentTurn = msg.turn
      reply.value = msg.text || ''
      if (state.value === 'thinking') state.value = 'speaking'
      turnComplete = false // a fresh turn's audio is about to stream
      break
    case 'audio':
      if (msg.turn !== currentTurn) break // straggler from a superseded turn
      await playChunk(msg.audio || '')
      break
    case 'turn_complete':
      if (msg.turn !== currentTurn) break
      turnComplete = true
      // A reply with no audio (blank / native-audio-only) lands here still in
      // `thinking`; hand the floor straight back to the mic.
      if (state.value === 'thinking') state.value = 'listening'
      else maybeEndTurn()
      break
    case 'error':
      fail(msg.message || 'voice error')
      break
  }
  // Any frame is progress — re-arm the stall watchdog (or clear it, if this
  // frame took us out of thinking/speaking).
  armWatchdog()
}

async function playChunk(b64: string) {
  if (!playCtx || !playNode || !b64) return
  try {
    const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0))
    const audio = await playCtx.decodeAudioData(bytes.buffer)
    if (!playCtx || !playNode) return
    if (playCtx.state === 'suspended') await playCtx.resume()
    // decodeAudioData resamples to the context rate, so the samples already
    // match the worklet's output rate. Copy channel 0 to transfer ownership.
    const mono = new Float32Array(audio.getChannelData(0))
    bufferEmpty = false
    playNode.port.postMessage({ type: 'push', data: mono }, [mono.buffer])
    armWatchdog()
  }
  catch (e) {
    console.error('voice: failed to decode/play audio chunk', e)
  }
}

function stopPlayback() {
  if (playNode) playNode.port.postMessage({ type: 'flush' })
  bufferEmpty = true
  turnComplete = false
}

function onDrained() {
  bufferEmpty = true
  maybeEndTurn()
  armWatchdog() // a chunk finishing is progress during a long reply
}

function maybeEndTurn() {
  // The turn ends once the server signalled completion AND the ring buffer has
  // played out — then we hand the floor back to the mic.
  if (turnComplete && bufferEmpty && state.value === 'speaking') {
    state.value = 'listening'
    clearWatchdog()
  }
}

function clearWatchdog() {
  if (watchdog != null) {
    clearTimeout(watchdog)
    watchdog = null
  }
}

// (Re)arm the stall watchdog for the current agent-holding state; a no-op while
// listening/capturing/idle. Called on every progress signal so a healthy turn
// never trips it.
function armWatchdog() {
  clearWatchdog()
  const ms = state.value === 'speaking'
    ? SPEAKING_STALL_MS
    : state.value === 'thinking' ? THINKING_STALL_MS : 0
  if (ms > 0) watchdog = setTimeout(onWatchdogStall, ms)
}

function onWatchdogStall() {
  // No progress for too long (server hang, a lost frame, or audio that never
  // played) — abandon the turn and hand the floor back to the mic instead of
  // hanging at Thinking/Speaking.
  clearWatchdog()
  console.warn('voice: turn stalled — recovering to listening')
  stopPlayback()
  currentTurn = -1
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'cancel' }))
  state.value = 'listening'
}

export function useVoiceMode() {
  return {
    state: readonly(state),
    transcript: readonly(transcript),
    reply: readonly(reply),
    errorMsg: readonly(errorMsg),
    start,
    stop,
  }
}
