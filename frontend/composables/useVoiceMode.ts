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

import { AudioPump } from '~/utils/audio-pump'

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
let ringDrained = true // mirrors the worklet ring buffer (drained = true)
/** Serialises decodes so chunks enqueue in arrival order — decodeAudioData
 *  promises resolve in completion order, which is not the same thing. */
let decodeChain: Promise<void> = Promise.resolve()
/** Bumped on barge-in/teardown so a chunk mid-decode can be discarded. */
let playbackEpoch = 0

/**
 * Seconds of audio the worklet ring holds. The writer must not exceed it:
 * the sidecar synthesises faster than real-time, so a long reply produces
 * audio far quicker than it plays out (JCLAW-845).
 */
const PLAYBACK_BUFFER_SECONDS = 10
/** Backpressure queue in front of the worklet ring. Null until playback starts. */
let pump: AudioPump | null = null
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
  pump?.reset()
  pump = null
  playbackEpoch++
  decodeChain = Promise.resolve()
  ringDrained = true
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
    processorOptions: { bufferSeconds: PLAYBACK_BUFFER_SECONDS },
  })
  // Seeded with the capacity we just configured, so the pump can push
  // immediately. Waiting for the worklet's first `level` message here would make
  // that one message load-bearing, and losing it deadlocks playback silently.
  pump = new AudioPump(
    Math.max(1, Math.floor(playCtx.sampleRate * PLAYBACK_BUFFER_SECONDS)),
    chunk => playNode?.port.postMessage({ type: 'push', data: chunk }, [chunk.buffer]),
  )
  playNode.port.onmessage = (e) => {
    const msg = e.data as { type?: string, free?: number, dropped?: number }
    if (msg?.type === 'drained') onDrained()
    else if (msg?.type === 'level') {
      // The worklet is authoritative on free space; adopt its number and top up.
      pump?.setFree(msg.free ?? 0)
      // A level report means the ring is actively draining — samples are
      // reaching the speakers, which is the definition of progress this
      // watchdog exists to look for. Without this the stall timer is re-armed
      // only by chunk ARRIVAL, and since the sidecar synthesises much faster
      // than real-time, a long reply arrives in full within seconds and then
      // plays for minutes with nothing touching the timer — so the watchdog
      // fired mid-sentence and handed the floor back to the mic.
      armWatchdog()
    }
    else if (msg?.type === 'overflow') {
      // Backpressure should make this unreachable; if it fires, audio was lost
      // and we want it in the console rather than silently missing from playback.
      console.warn(`voice: playback ring overflowed, dropped ${msg.dropped} samples`)
    }
  }
  playNode.connect(playCtx.destination)
}

// Stream one capture frame as little-endian PCM16. The mic streams continuously
// (including through the agent's reply, for barge-in); the server's VAD decides
// what is speech, so there is no client-side gating.
function sendFrame(input: Float32Array) {
  if (ws?.readyState !== WebSocket.OPEN) return
  const pcm = new Int16Array(input.length)
  for (let i = 0; i < input.length; i++) {
    const s = Math.max(-1, Math.min(1, input[i]!))
    pcm[i] = s < 0 ? s * 0x8000 : s * 0x7FFF
  }
  ws.send(pcm.buffer)
}

function onMessage(ev: MessageEvent) {
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
      // JCLAW-860: deliberately does NOT enter `speaking`. The server now sends
      // reply text before synthesising it, so the text arriving says nothing
      // about whether audio is close. Entering `speaking` here would start the
      // short SPEAKING_STALL_MS leash while the engine is still working, and a
      // cold model load blows straight through it — Chatterbox measures ~52s on
      // its first synthesis. Staying in `thinking` keeps the longer leash, and
      // this frame re-arms it, so the wait for audio starts counting from the
      // text rather than from whenever thinking began.
      turnComplete = false // a fresh turn's audio is about to stream
      break
    case 'audio':
      if (msg.turn !== currentTurn) break // straggler from a superseded turn
      // `speaking` begins when audio does. A turn that never produces audio
      // stays in `thinking` and is released by turn_complete below, which is the
      // same path a blank or native-audio-only reply already took.
      if (state.value === 'thinking') state.value = 'speaking'
      playChunk(msg.audio || '')
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

/**
 * Accept one base64 audio chunk. Decoding is asynchronous, so this returns
 * immediately and the work is chained (queueing and splitting live in AudioPump
 * so they can be tested without the Web Audio graph).
 *
 * Two things the chain buys us. The socket does not serialise our async handler
 * and `decodeAudioData` promises resolve in whatever order the decoder finishes,
 * so chaining is what keeps chunks enqueued in arrival order. And the pump counts
 * work in flight, so a chunk still being decoded when `turn_complete` arrives
 * holds the turn open — otherwise the server's completion signal could land while
 * the ring happened to be drained and cut the reply off mid-sentence.
 */
function playChunk(b64: string) {
  if (!playCtx || !playNode || !b64) return
  pump?.beginDecode()
  const epoch = playbackEpoch
  decodeChain = decodeChain
    .then(() => decodeAndEnqueue(b64, epoch))
    .catch(e => console.error('voice: failed to decode/play audio chunk', e))
    .finally(() => {
      pump?.endDecode()
      // A chunk that failed to decode enqueues nothing, so no `drained` will
      // follow to close the turn — re-check here so a bad final chunk can't
      // leave the session stuck in `speaking`.
      maybeEndTurn()
    })
}

async function decodeAndEnqueue(b64: string, epoch: number) {
  if (!playCtx || !playNode) return
  const bytes = Uint8Array.from(atob(b64), c => c.codePointAt(0)!)
  const audio = await playCtx.decodeAudioData(bytes.buffer)
  // Barge-in (or teardown) while this was decoding — the chunk belongs to an
  // abandoned turn and must not play behind the user's new one.
  if (epoch !== playbackEpoch || !playCtx || !playNode) return
  if (playCtx.state === 'suspended') await playCtx.resume()
  // decodeAudioData resamples to the context rate, so the samples already
  // match the worklet's output rate. Copy channel 0 to transfer ownership.
  const mono = new Float32Array(audio.getChannelData(0))
  ringDrained = false
  // Queue rather than push: the ring holds seconds and a reply can be
  // minutes, so the writer waits for space instead of overrunning it.
  pump?.enqueue(mono)
  armWatchdog()
}

function stopPlayback() {
  if (playNode) playNode.port.postMessage({ type: 'flush' })
  // Barge-in drops queued audio too — otherwise the interrupted reply would
  // resume playing behind the user's new turn. Bumping the epoch also discards
  // any chunk still mid-decode when the interruption landed.
  playbackEpoch++
  pump?.reset()
  ringDrained = true
  turnComplete = false
}

function onDrained() {
  ringDrained = true
  maybeEndTurn()
  armWatchdog() // a chunk finishing is progress during a long reply
}

/** True once nothing is left to play: the ring is empty, the backpressure queue
 *  is empty, and no chunk is still being decoded. */
function playbackIdle(): boolean {
  return ringDrained && (pump?.isIdle ?? true)
}

function maybeEndTurn() {
  // The turn ends once the server signalled completion AND every sample has
  // played — including any still queued behind the ring or still decoding.
  if (turnComplete && playbackIdle() && state.value === 'speaking') {
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
  let ms = 0
  if (state.value === 'speaking') ms = SPEAKING_STALL_MS
  else if (state.value === 'thinking') ms = THINKING_STALL_MS
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
