// Real-time voice mode client (JCLAW-791). Opens a WebSocket to /api/voice,
// captures the mic, segments utterances with a simple energy VAD, encodes each
// as a WAV and sends it; plays the streamed TTS reply gaplessly on one
// AudioContext. One voice session app-wide (SPA singleton).
//
// Phase B — barge-in: the mic stays live while the agent is thinking/speaking,
// so the user can interrupt. A stricter frame count while the agent holds the
// floor resists speaker echo (echoCancellation covers the rest). On barge-in we
// cut playback, send {type:"cancel"}, and start the new utterance; every server
// frame carries a monotonic `turn` id so we drop straggler audio from the
// superseded turn.
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
let processor: ScriptProcessorNode | null = null
let captureRate = 16000

// --- VAD / utterance state ---
let capturing = false
let speechFrames = 0
let silenceFrames = 0
let captured: Float32Array[] = [] // doubles as a pre-roll ring while not capturing

// --- gapless playback ---
let playCtx: AudioContext | null = null
let nextStartTime = 0
const playSources: AudioBufferSourceNode[] = []
let audioPending = 0
let turnComplete = false
let currentTurn = -1 // the turn whose audio we currently accept (-1 = none)

// VAD tuning (Phase A/B defaults; Phase C tunes these).
const RMS_THRESHOLD = 0.015 // frame energy above this counts as speech
const SPEECH_START_FRAMES = 3 // consecutive speech frames to open an utterance
const BARGE_IN_FRAMES = 8 // stricter while the agent talks — resists speaker echo
const SILENCE_END_FRAMES = 12 // consecutive silence frames (hangover) to close it
const PREROLL_FRAMES = 8 // frames of pre-roll kept so the onset isn't clipped

function teardown() {
  if (processor) {
    processor.onaudioprocess = null
    processor.disconnect()
    processor = null
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
  nextStartTime = 0
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
  sourceNode = micCtx.createMediaStreamSource(mediaStream)
  // ScriptProcessorNode is deprecated but universally supported and needs no
  // separate worklet module; AudioWorklet is the Phase-C upgrade.
  processor = micCtx.createScriptProcessor(4096, 1, 1)
  processor.onaudioprocess = onAudioFrame
  sourceNode.connect(processor)
  processor.connect(micCtx.destination) // some browsers only fire onaudioprocess when connected
}

function onAudioFrame(e: AudioProcessingEvent) {
  // Barge-in: stay live while the agent holds the floor so the user can cut in;
  // ignore only while connecting/idle/error.
  const holdingFloor = state.value === 'thinking' || state.value === 'speaking'
  if (state.value !== 'listening' && state.value !== 'capturing' && !holdingFloor) return

  const input = e.inputBuffer.getChannelData(0)
  let sum = 0
  for (let i = 0; i < input.length; i++) {
    const v = input[i]!
    sum += v * v
  }
  const rms = Math.sqrt(sum / input.length)

  // Always buffer the frame; while not yet capturing, keep only a short pre-roll
  // ring so the utterance onset (the detection frames) survives.
  captured.push(new Float32Array(input))
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
      ensurePlayCtx()
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
}

function ensurePlayCtx() {
  if (!playCtx) {
    playCtx = new AudioContext()
    nextStartTime = playCtx.currentTime
  }
  turnComplete = false
  audioPending = 0
}

async function playChunk(b64: string) {
  if (!playCtx || !b64) return
  try {
    const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0))
    const buf = await playCtx.decodeAudioData(bytes.buffer)
    if (!playCtx) return
    const src = playCtx.createBufferSource()
    src.buffer = buf
    src.connect(playCtx.destination)
    const at = Math.max(playCtx.currentTime, nextStartTime)
    src.start(at)
    nextStartTime = at + buf.duration
    audioPending++
    playSources.push(src)
    src.onended = () => {
      audioPending--
      maybeEndTurn()
    }
  }
  catch (e) {
    console.error('voice: failed to decode/play audio chunk', e)
  }
}

function stopPlayback() {
  for (const s of playSources) {
    try {
      s.onended = null
      s.stop()
    }
    catch {
      // already stopped
    }
  }
  playSources.length = 0
  audioPending = 0
  turnComplete = false
  if (playCtx) nextStartTime = playCtx.currentTime
}

function maybeEndTurn() {
  // The turn ends once the server signalled completion AND every scheduled audio
  // chunk has finished — then we hand the floor back to the mic.
  if (turnComplete && audioPending <= 0 && state.value === 'speaking') {
    state.value = 'listening'
  }
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
