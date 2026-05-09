package services.transcription;

/**
 * Failure surfacing from any transcription backend (local whisper-jni,
 * cloud OpenAI/OpenRouter clients). Callers — typically the message
 * pipeline orchestrator (JCLAW-165) — catch this and decide whether to
 * fall through to the next backend, log and persist the empty transcript,
 * or surface the error to the user.
 *
 * <p>Top-level rather than nested inside a single transcriber so every
 * backend throws the same type and the orchestrator's catch-and-fallback
 * logic stays uniform.
 */
public class TranscriptionException extends RuntimeException {
    public TranscriptionException(String message) { super(message); }
    public TranscriptionException(String message, Throwable cause) { super(message, cause); }
}
