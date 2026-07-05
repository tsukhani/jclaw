package services.transcription;

import services.UvProbe;

import java.nio.file.Path;
import java.util.List;

/**
 * The prerequisite gate in front of {@link PyannoteDiarizationClient}
 * (JCLAW-614/631): validates uv + Hugging Face token with actionable
 * errors BEFORE any sidecar contact, then delegates. Not a router since
 * the sherpa fallback was scrapped — there is exactly one engine; the
 * name survives because {@code DiarizationRouter.Result} is load-bearing
 * vocabulary across the pipeline, cache and tests, and a rename would be
 * churn without behavior.
 */
public final class DiarizationRouter {

    /** Diarization plus the overlap regions the overlap-aware annotation
     *  detected (the JCLAW-605 re-attribution gate). */
    public record Result(List<SpeakerSegment> segments, List<double[]> overlaps) {}

    // Test seam: lets router tests run without a sidecar. Production never
    // writes this field.
    static PyannoteDiarizationClient clientOverride = null;

    private DiarizationRouter() {}

    /**
     * Diarize {@code audioFile}. Blocking and CPU/GPU-bound — run off the
     * request thread. Throws {@link TranscriptionException} with an
     * actionable message when prerequisites are missing or the sidecar
     * fails; there is no degraded fallback.
     *
     * @param numSpeakers exact speaker count when known, or any value
     *                    below 2 to let the pipeline decide
     */
    public static Result diarizeRich(Path audioFile, int numSpeakers) {
        requirePrerequisites();
        var output = client().diarizeRich(audioFile, numSpeakers);
        return new Result(output.segments(), output.overlaps());
    }

    /** As {@link #diarizeRich} but segments only. */
    public static List<SpeakerSegment> diarize(Path audioFile, int numSpeakers) {
        return diarizeRich(audioFile, numSpeakers).segments();
    }

    /** Fail fast with setup instructions instead of a doomed sidecar spawn.
     *  Package-visible so tests pin the messages. */
    static void requirePrerequisites() {
        if (!UvProbe.isAvailable()) {
            throw new TranscriptionException(
                    "Speaker diarization requires the 'uv' launcher on PATH "
                            + "(https://docs.astral.sh/uv) — it runs the local diarization sidecar.");
        }
        var token = PyannoteSidecarManager.effectiveHfToken();
        if (token == null || token.isBlank()) {
            throw new TranscriptionException(
                    "Speaker diarization requires a Hugging Face token with the gated "
                            + "pyannote/speaker-diarization-community-1 model accepted. Configure it "
                            + "in Settings under Transcription > Diarization (the Image Generation "
                            + "token is reused automatically when set).");
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
