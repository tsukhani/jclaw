/* global AudioWorkletProcessor, registerProcessor */
// Voice-mode mic capture (JCLAW-796). Runs on the audio render thread and
// forwards fixed-size mono PCM frames to the main thread, replacing the
// deprecated ScriptProcessorNode. The render quantum is 128 samples; we
// accumulate up to `frame` samples (default 4096, ~85 ms at 48 kHz — matching
// the pre-worklet buffer size so the energy-VAD frame-count thresholds keep
// their timing) and post a transferable copy so no audio data is cloned.
class VoiceCaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super()
    const opt = options?.processorOptions || {}
    this.frame = opt.frame > 0 ? opt.frame : 4096
    this.buf = new Float32Array(this.frame)
    this.n = 0
  }

  process(inputs) {
    const channel = inputs[0]?.[0]
    if (channel) {
      for (let i = 0; i < channel.length; i++) {
        this.buf[this.n++] = channel[i]
        if (this.n === this.frame) {
          const out = this.buf.slice(0)
          this.port.postMessage(out, [out.buffer])
          this.n = 0
        }
      }
    }
    // No output is written, so the node emits silence — connecting it to the
    // destination only keeps the graph pulling; the mic is never played back.
    return true
  }
}

registerProcessor('voice-capture-processor', VoiceCaptureProcessor)
