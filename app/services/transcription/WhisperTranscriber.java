package services.transcription;


import java.nio.file.Path;
import java.util.List;

/**
 * The ASR facade (JCLAW-650): every transcription goes through the sidecar's
 * host-relevant GPU engine — mlx-whisper on Apple silicon, faster-whisper on
 * CUDA/CPU hosts — same Whisper weights everywhere. The in-process
 * whisper.cpp JNI engine this class was named for is retired (614 pattern:
 * one best implementation, actionable error when its prerequisite — just
 * {@code uv}; whisper weights are ungated — is missing). The name survives
 * because {@link Segment} is load-bearing vocabulary across the pipeline
 * and tests. The sidecar reads audio files directly — no ffmpeg step.
 */
public final class WhisperTranscriber {

    private WhisperTranscriber() {}

    /** One recognised segment, timestamps in milliseconds. (The JCLAW-635
     *  confidence triple was removed with the local diarization pipeline —
     *  its hallucination gates were the only reader.) */
    public record Segment(long startMs, long endMs, String text) {}

    /**
     * Transcribe an audio file using the named whisper model. Blocks the
     * caller; long-running and CPU-bound. Caller is responsible for running
     * this off the request thread (via a virtual thread or job).
     *
     * <p>Weights download on first use in the sidecar (or ahead of time via
     * the Settings page, which provisions the host engine's artifact).
     */
    public static String transcribe(Path audioFile, WhisperModel model) {
        var sb = new StringBuilder();
        for (var segment : transcribeSegments(audioFile, model, null)) {
            sb.append(segment.text());
        }
        return sb.toString().trim();
    }

    /**
     * Segment-level transcription (JCLAW-556).
     * Same blocking/preconditions contract as {@link #transcribe}; returns
     * one {@link Segment} per whisper.cpp segment, in order.
     *
     * <p>{@code language}: an ISO 639-1 code ({@code "en"}, {@code "ms"}, …)
     * forces that language. Null/blank means auto-detect on multilingual
     * models and whisper-jni's {@code "en"} default on English-only models
     * (whisper.cpp rejects detection on {@code .en} models).
     */
    public static List<Segment> transcribeSegments(Path audioFile, WhisperModel model, String language) {
        // JCLAW-650: sidecar-or-error, the JCLAW-614 pattern. The engine is
        // host-relevant (mlx-whisper on Apple silicon, faster-whisper on
        // CUDA/CPU) and its only prerequisite is uv — whisper weights are
        // NOT gated, unlike the diarization model.
        if (!services.UvProbe.isAvailable()) {
            throw new TranscriptionException(
                    "transcription requires the 'uv' launcher on PATH (it runs the GPU ASR sidecar; "
                            + "./jclaw.sh setup installs it): " + services.UvProbe.lastResult().reason());
        }
        return new AsrSidecarClient().transcribe(audioFile, model.id(), language);
    }



}
