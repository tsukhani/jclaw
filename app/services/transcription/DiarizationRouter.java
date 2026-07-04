package services.transcription;

import play.Logger;
import services.ConfigService;
import services.UvProbe;

import java.nio.file.Path;
import java.util.List;

/**
 * Backend selection for speaker diarization (JCLAW-565). Two engines produce
 * the same {@link SherpaDiarizer.SpeakerSegment} list:
 *
 * <ul>
 *   <li><b>pyannote-local</b> — the community-1 pipeline in a Python sidecar.
 *       The JCLAW-565 bake-off winner (DER 12.5% / F1 0.864 vs sherpa's best
 *       0.799 on 2-speaker podcast audio; finds the speaker count itself).
 *       Needs {@code uv} on PATH and a Hugging Face token with the gated
 *       model's conditions accepted.</li>
 *   <li><b>sherpa</b> — the JCLAW-556 in-process sherpa-onnx path. Zero
 *       setup, no network prerequisites; threshold-clustering quality.</li>
 * </ul>
 *
 * <p>{@code transcription.diarization.backend} picks: {@code auto} (default —
 * pyannote when its prerequisites are met, sherpa otherwise, with a logged
 * fallback to sherpa if the sidecar fails), or the explicit {@code
 * pyannote-local} / {@code sherpa}. Auto's token gate is deliberately the
 * config key only — not ambient ~/.cache/huggingface state — so backend
 * choice is deterministic and visible in Settings.
 */
public final class DiarizationRouter {

    public static final String BACKEND_KEY = "transcription.diarization.backend";
    public static final String BACKEND_AUTO = "auto";
    public static final String BACKEND_PYANNOTE = "pyannote-local";
    public static final String BACKEND_SHERPA = "sherpa";

    // Test seam: lets router tests exercise selection/fallback without a
    // sidecar. Production never writes this field.
    static PyannoteDiarizationClient clientOverride = null;

    private DiarizationRouter() {}

    /**
     * Diarize {@code audioFile} with the configured backend. Blocking and
     * CPU/GPU-bound — run off the request thread.
     *
     * @param sherpaThreshold clustering threshold for the sherpa engine
     *                        (ignored by pyannote, which needs no tuning)
     * @param numSpeakers     exact speaker count when known, or any value
     *                        below 2 to let the engine decide
     */
    public static List<SherpaDiarizer.SpeakerSegment> diarize(
            Path audioFile, float sherpaThreshold, int numSpeakers) {
        var backend = ConfigService.get(BACKEND_KEY, BACKEND_AUTO);
        return switch (backend) {
            case BACKEND_SHERPA -> SherpaDiarizer.diarize(audioFile, sherpaThreshold, numSpeakers);
            // Explicit choice: failures surface to the operator instead of
            // silently producing lower-quality diarization.
            case BACKEND_PYANNOTE -> client().diarize(audioFile, numSpeakers);
            default -> auto(audioFile, sherpaThreshold, numSpeakers);
        };
    }

    /** Whether auto mode would attempt the pyannote sidecar. */
    public static boolean pyannoteEligible() {
        var token = ConfigService.get(PyannoteSidecarManager.CONFIG_PREFIX + ".hfToken");
        return token != null && !token.isBlank() && UvProbe.isAvailable();
    }

    private static List<SherpaDiarizer.SpeakerSegment> auto(
            Path audioFile, float sherpaThreshold, int numSpeakers) {
        if (!pyannoteEligible()) {
            return SherpaDiarizer.diarize(audioFile, sherpaThreshold, numSpeakers);
        }
        try {
            return client().diarize(audioFile, numSpeakers);
        } catch (TranscriptionException e) {
            // Best-effort contract: a broken sidecar must never fail a
            // transcript that sherpa can still produce.
            Logger.warn("DiarizationRouter: pyannote sidecar failed (%s) — falling back to sherpa",
                    e.getMessage());
            return SherpaDiarizer.diarize(audioFile, sherpaThreshold, numSpeakers);
        }
    }

    private static PyannoteDiarizationClient client() {
        return clientOverride != null ? clientOverride : new PyannoteDiarizationClient();
    }

    /** Test-only: inject a client (null restores production behavior). */
    public static void setClientForTest(PyannoteDiarizationClient client) {
        clientOverride = client;
    }
}
