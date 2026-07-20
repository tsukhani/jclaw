// Real-time voice mode client (JCLAW-791; capture/playback JCLAW-796). Opens a
// WebSocket to /api/voice, captures the mic via an AudioWorklet, segments
// utterances with a simple energy VAD, encodes each as a WAV and sends it; plays
// the streamed TTS reply through an AudioWorklet ring buffer that can hard-stop
// within one render quantum. One voice session app-wide (SPA singleton).
//
// Phase B — barge-in: the mic stays live while the agent is thinking/speaking,
// so the user can interrupt. A stricter frame count while the agent holds the
// floor resists speaker echo (echoCancellation covers the rest). On barge-in we
// cut playback, send {type:"cancel"}, and start the new utterance; every server
// frame carries a monotonic `turn` id so we drop straggler audio from the
// superseded turn.
//
// JCLAW-796: capture moved off the deprecated ScriptProcessorNode to a
// dedicated-thread AudioWorklet, and playback moved off timeline-scheduled
// AudioBufferSourceNodes to a ring-buffer worklet — barge-in now flushes the
// buffer in one message instead of stopping N scheduled nodes, so playback dies
// within a single render quantum. Both worklet modules are served statically
// from /worklets (public/).
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
let captureRate = 16000

// --- VAD / utterance state ---
let capturing = false
let speechFrames = 0
let silenceFrames = 0
let captured: Float32Array[] = [] // doubles as a pre-roll ring while not capturing

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
// Samples per posted capture frame — kept at the pre-worklet buffer size so the
// energy-VAD frame counts below keep their timing (~85 ms at 48 kHz).
const CAPTURE_FRAME = 4096

// VAD tuning (Phase A/B defaults; Phase C / JCLAW-797 move endpointing server-side).
const RMS_THRESHOLD = 0.015 // frame energy above this counts as speech
const SPEECH_START_FRAMES = 3 // consecutive speech frames to open an utterance
const BARGE_IN_FRAMES = 8 // stricter while the agent talks — resists speaker echo
const SILENCE_END_FRAMES = 12 // consecutive silence frames (hangover) to close it
const PREROLL_FRAMES = 8 // frames of pre-roll kept so the onset isn't clipped

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
  capturing = false
  speechFrames = 0
  silenceFrames = 0
  captured = []
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
    // CONNECT — RFC 8441 / 9220, which the play1 fork advertises
    // (connectProtocolEnabled) since 1.13.46 — multiplexed on the existing
    // connection; otherwise it falls back to a separate HTTP/1.1 connection.
    // Same new WebSocket(url) either way; there is no client-side transport knob.
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
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
  })
  micCtx = new AudioContext()
  captureRate = micCtx.sampleRate
  await micCtx.audioWorklet.addModule(CAPTURE_WORKLET)
  sourceNode = micCtx.createMediaStreamSource(mediaStream)
  captureNode = new AudioWorkletNode(micCtx, 'voice-capture-processor', {
    numberOfInputs: 1,
    numberOfOutputs: 1,
    processorOptions: { frame: CAPTURE_FRAME },
  })
  captureNode.port.onmessage = e => onAudioFrame(e.data as Float32Array)
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

function onAudioFrame(input: Float32Array) {
  // Barge-in: stay live while the agent holds the floor so the user can cut in;
  // ignore only while connecting/idle/error.
  const holdingFloor = state.value === 'thinking' || state.value === 'speaking'
  if (state.value !== 'listening' && state.value !== 'capturing' && !holdingFloor) return

  let sum = 0
  for (let i = 0; i < input.length; i++) {
    const v = input[i]!
    sum += v * v
  }
  const rms = Math.sqrt(sum / input.length)

  // Always buffer the frame; while not yet capturing, keep only a short pre-roll
  // ring so the utterance onset (the detection frames) survives.
  captured.push(input)
  if (!capturing && captured.length > PREROLL_FRAMES) captured.shift()

  if (rms > RMS_THRESHOLD) {
    silenceFrames = 0
    if (!capturing) {
      speechFrames++
      const needed = holdingFloor ? BARGE_IN_FRAMES : SPEECH_START_FRAMES
      if (speechFrames >= needed) {
        if (holdingFloor) bargeIn()
        capturing = true // `captured` already holds the pre-roll incl. the onset
        state.value = 'capturing'
      }
    }
  }
  else {
    speechFrames = 0
    if (capturing) {
      silenceFrames++
      if (silenceFrames >= SILENCE_END_FRAMES) endUtterance()
    }
  }
}

function bargeIn() {
  // The user started talking over the agent — cut playback and tell the server
  // to abandon the in-flight turn; reject its straggler audio until the new turn
  // identifies itself.
  stopPlayback()
  currentTurn = -1
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'cancel' }))
}

function endUtterance() {
  capturing = false
  silenceFrames = 0
  speechFrames = 0
  const frames = captured
  captured = []
  const wav = encodeWav(frames, captureRate)
  if (ws?.readyState === WebSocket.OPEN) {
    ws.send(wav)
    state.value = 'thinking'
    armWatchdog()
  }
  else {
    state.value = 'listening'
  }
}

function encodeWav(frames: Float32Array[], sampleRate: number): ArrayBuffer {
  let total = 0
  for (const f of frames) total += f.length
  const pcm = new Int16Array(total)
  let o = 0
  for (const f of frames) {
    for (let i = 0; i < f.length; i++) {
      const s = Math.max(-1, Math.min(1, f[i]!))
      pcm[o++] = s < 0 ? s * 0x8000 : s * 0x7FFF
    }
  }
  const buf = new ArrayBuffer(44 + pcm.length * 2)
  const dv = new DataView(buf)
  const str = (off: number, s: string) => {
    for (let i = 0; i < s.length; i++) dv.setUint8(off + i, s.charCodeAt(i))
  }
  str(0, 'RIFF')
  dv.setUint32(4, 36 + pcm.length * 2, true)
  str(8, 'WAVE')
  str(12, 'fmt ')
  dv.setUint32(16, 16, true)
  dv.setUint16(20, 1, true) // PCM
  dv.setUint16(22, 1, true) // mono
  dv.setUint32(24, sampleRate, true)
  dv.setUint32(28, sampleRate * 2, true) // byte rate
  dv.setUint16(32, 2, true) // block align
  dv.setUint16(34, 16, true) // bits per sample
  str(36, 'data')
  dv.setUint32(40, pcm.length * 2, true)
  new Int16Array(buf, 44).set(pcm)
  return buf
}

async function onMessage(ev: MessageEvent) {
  if (typeof ev.data !== 'string') return
  let msg: { type?: string, text?: string, audio?: string, message?: string, turn?: number }
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
      maybeEndTurn()
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
// listening/capturing/idle. Called on every progress signal (a frame arrived, an
// audio chunk finished, a turn started) so a healthy turn never trips it.
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
  bargeIn() // stop playback, cancel the stuck turn server-side, reject its frames
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
