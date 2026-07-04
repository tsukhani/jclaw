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
 * pyannote-local} / {@code sherpa}. Auto's token gate reads config keys
 * only — the diarization token, falling back to the image-generation
 * sidecar's ({@code imagegen.local.hfToken}) — never ambient
 * ~/.cache/huggingface state, so backend choice is deterministic and
 * visible in Settings.
 */
public final class DiarizationRouter {

    public static final String BACKEND_KEY = "transcription.diarization.backend";
    public static final String BACKEND_AUTO = "auto";
    public static final String BACKEND_PYANNOTE = "pyannote-local";
    public static final String BACKEND_SHERPA = "sherpa";

    /** Diarization plus the overlap regions (empty on the sherpa path —
     *  its output is not overlap-aware) and which engine produced it. */
    public record Result(List<SherpaDiarizer.SpeakerSegment> segments,
                         List<double[]> overlaps,
                         boolean viaPyannote) {}

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
        return diarizeRich(audioFile, sherpaThreshold, numSpeakers).segments();
    }

    /** As {@link #diarize} but keeping overlap regions and engine identity
     *  for the JCLAW-605 re-attribution pass. */
    public static Result diarizeRich(Path audioFile, float sherpaThreshold, int numSpeakers) {
        var backend = ConfigService.get(BACKEND_KEY, BACKEND_AUTO);
        return switch (backend) {
            case BACKEND_SHERPA -> sherpaResult(audioFile, sherpaThreshold, numSpeakers);
            // Explicit choice: failures surface to the operator instead of
            // silently producing lower-quality diarization.
            case BACKEND_PYANNOTE -> pyannoteResult(audioFile, numSpeakers);
            default -> auto(audioFile, sherpaThreshold, numSpeakers);
        };
    }

    private static Result sherpaResult(Path audioFile, float threshold, int numSpeakers) {
        return new Result(SherpaDiarizer.diarize(audioFile, threshold, numSpeakers),
                List.of(), false);
    }

    private static Result pyannoteResult(Path audioFile, int numSpeakers) {
        var output = client().diarizeRich(audioFile, numSpeakers);
        return new Result(output.segments(), output.overlaps(), true);
    }

    /** Whether auto mode would attempt the pyannote sidecar. The token check
     *  honors the imagegen-token fallback (see
     *  {@link PyannoteSidecarManager#effectiveHfToken()}). */
    public static boolean pyannoteEligible() {
        var token = PyannoteSidecarManager.effectiveHfToken();
        return token != null && !token.isBlank() && UvProbe.isAvailable();
    }

    private static Result auto(Path audioFile, float sherpaThreshold, int numSpeakers) {
        if (!pyannoteEligible()) {
            return sherpaResult(audioFile, sherpaThreshold, numSpeakers);
        }
        try {
            return pyannoteResult(audioFile, numSpeakers);
        } catch (TranscriptionException e) {
            // Best-effort contract: a broken sidecar must never fail a
            // transcript that sherpa can still produce.
            Logger.warn("DiarizationRouter: pyannote sidecar failed (%s) — falling back to sherpa",
                    e.getMessage());
            return sherpaResult(audioFile, sherpaThreshold, numSpeakers);
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
