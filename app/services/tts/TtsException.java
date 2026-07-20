package services.tts;

/**
 * Failure surfacing from any TTS backend — the Python sidecar
 * ({@link TtsSidecarClient}: Qwen3-TTS / Kokoro via mlx-audio) or the in-JVM
 * sherpa-onnx engine ({@code TtsJvmEngine}). Callers — the read-aloud
 * controller and the Settings state probe — catch this to decide whether to
 * surface the error or report the engine unavailable.
 *
 * <p>Top-level rather than nested inside a single backend so every engine
 * throws the same type and the router's dispatch/catch logic stays uniform
 * (mirrors {@link services.transcription.TranscriptionException}).
 */
public class TtsException extends RuntimeException {
    public TtsException(String message) { super(message); }
    public TtsException(String message, Throwable cause) { super(message, cause); }
}
