package services.transcription;

import services.UvProbe;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point for speaker diarization: the pyannote community-1 sidecar,
 * full stop (JCLAW-614). The in-process sherpa fallback was scrapped — the
 * JCLAW-565 bake-off already showed it strictly inferior (knife-edge
 * threshold clustering, F1 0.799 ceiling vs community-1's 0.864 / DER
 * 7.9%), and every capability since (overlap regions, MossFormer
 * re-attribution, the MSDD second opinion, under-speech recovery) exists
 * only on the sidecar path, so a silent fallback delivered a strictly
 * worse transcript. Like the image and video generation stacks,
 * diarization relies on its sidecar and reports an actionable error
 * instead of degrading.
 *
 * <p>Prerequisites are checked up front so a missing setup fails in
 * milliseconds with instructions, not after a doomed sidecar spawn:
 * {@code uv} on PATH and a Hugging Face token (the diarization key, or the
 * image-generation sidecar's as fallback) with the gated community-1
 * model's conditions accepted. Token gating reads config keys only — never
 * ambient ~/.cache/huggingface state — so behavior is deterministic and
 * visible in Settings.
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
