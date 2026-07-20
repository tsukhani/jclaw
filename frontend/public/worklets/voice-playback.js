/* global AudioWorkletProcessor, registerProcessor, sampleRate */
// Voice-mode TTS playback (JCLAW-796). A single-reader/single-writer ring
// buffer fed by the main thread. Replaces timeline-scheduled
// AudioBufferSourceNodes: on barge-in the main thread posts one `flush`
// message and playback stops within a single render quantum (~2.7 ms), instead
// of stopping and discarding N already-scheduled source nodes.
//
// Messages in:  { type: 'push', data: Float32Array }  — append decoded PCM
//               { type: 'flush' }                      — hard-stop, drop all audio
// Messages out: { type: 'drained' }                    — buffer emptied after playing
class VoicePlaybackProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    // ~10 s headroom; TTS chunks arrive faster than real-time, so this only
    // needs to absorb bursts, never a whole reply.
    this.capacity = Math.max(1, Math.floor(sampleRate * 10))
    this.ring = new Float32Array(this.capacity)
    this.read = 0
    this.write = 0
    this.filled = 0
    this.hadData = false
    this.port.onmessage = (e) => {
      const msg = e.data
      if (!msg) return
      if (msg.type === 'push' && msg.data) {
        this.enqueue(msg.data)
      }
      else if (msg.type === 'flush') {
        this.read = 0
        this.write = 0
        this.filled = 0
        this.hadData = false
      }
    }
  }

  enqueue(data) {
    for (let i = 0; i < data.length; i++) {
      this.ring[this.write] = data[i]
      this.write = (this.write + 1) % this.capacity
      if (this.filled < this.capacity) {
        this.filled++
      }
      else {
        // Overflow (shouldn't happen with 10 s headroom): drop the oldest sample.
        this.read = (this.read + 1) % this.capacity
      }
    }
    this.hadData = true
  }

  process(_inputs, outputs) {
    const out = outputs[0]
    const frames = out[0].length
    for (let i = 0; i < frames; i++) {
      let sample = 0
      if (this.filled > 0) {
        sample = this.ring[this.read]
        this.read = (this.read + 1) % this.capacity
        this.filled--
      }
      for (let c = 0; c < out.length; c++) {
        out[c][i] = sample
      }
    }
    // Announce the non-empty -> empty transition once, so the main thread can
    // end the turn only after the server has also signalled turn_complete.
    if (this.hadData && this.filled === 0) {
      this.hadData = false
      this.port.postMessage({ type: 'drained' })
    }
    return true
  }
}

registerProcessor('voice-playback-processor', VoicePlaybackProcessor)
