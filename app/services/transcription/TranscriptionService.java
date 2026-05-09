package services.transcription;

import models.MessageAttachment;

/**
 * Single contract for any transcription backend in jclaw — the local
 * whisper-jni engine and the cloud OpenAI / OpenRouter clients all
 * implement this so the message-pipeline orchestrator (JCLAW-165) can
 * switch between them via the {@code transcription.provider} config key
 * without caring about the underlying transport.
 *
 * <p>Failure surface: implementations throw {@link TranscriptionException}
 * with a clear message and the originating cause when reachable. The
 * orchestrator catches and decides whether to log-and-empty (so a
 * transient cloud outage doesn't block the whole turn) or to surface
 * the error to the user.
 */
public interface TranscriptionService {

    /**
     * Transcribe the audio bytes referenced by {@code attachment}. Returns
     * the recognised text — possibly empty for silent input. Throws on
     * transport, configuration, or backend errors; never returns null.
     */
    String transcribe(MessageAttachment attachment);
}
