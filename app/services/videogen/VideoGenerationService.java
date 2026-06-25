package services.videogen;

/**
 * Single contract for any video-generation backend in jclaw (JCLAW-231) — the video analogue of
 * {@code services.imagegen.ImageGenerationService}. The key difference: video generation is
 * unavoidably <b>asynchronous</b> (local WAN/LTX take minutes; every cloud provider returns a job
 * handle), so this splits into a non-blocking {@link #submit} that returns the provider's job id
 * immediately and a {@link #poll} that the {@code VideoGenerationJobRunner} (JCLAW-230) drives until
 * the job reaches a terminal state. The produced bytes are fetched from {@link PollResult#resultUrl}
 * by the storage path (JCLAW-234) — never inline here.
 *
 * <p>Failure surface: {@link #submit} throws {@link VideoGenerationException} when the submit request
 * itself fails (bad config, transport error, provider rejection). {@link #poll} throws only on a
 * transport/parse failure of the poll request; an upstream <em>generation</em> failure is a normal
 * {@link State#FAILED} result carrying the reason, so the runner's loop records it without aborting.
 */
public interface VideoGenerationService {

    /**
     * Start a generation job. Returns the provider's job id immediately and never blocks for the
     * (minutes-long) generation.
     *
     * @throws VideoGenerationException if the submit request itself fails
     */
    String submit(VideoGenRequest request);

    /**
     * Poll a previously-submitted job, mapping the provider's own status vocabulary onto {@link State}.
     *
     * @throws VideoGenerationException only on a transport/parse failure of the poll request
     */
    PollResult poll(String providerJobId);

    /**
     * A generation request. {@code model} may be null/blank to use the configured/default model
     * ({@code videogen.cloud.model}); {@code durationSeconds} and {@code aspectRatio} may be null to
     * use provider defaults.
     */
    record VideoGenRequest(String prompt, String model, Integer durationSeconds, String aspectRatio) {}

    /** Running-or-terminal lifecycle state, mapped from each provider's status strings. */
    enum State { RUNNING, SUCCEEDED, FAILED }

    /**
     * One poll outcome. {@code resultUrl} is the produced video URL, set only on {@link State#SUCCEEDED};
     * {@code percent} is best-effort progress 0..100 ({@code null} for cloud providers, which don't
     * report a reliable percentage — the local sidecar does, JCLAW-232); {@code error} is the upstream
     * failure reason, set only on {@link State#FAILED}.
     */
    record PollResult(State state, String resultUrl, Integer percent, String error) {
        public static PollResult running(Integer percent) {
            return new PollResult(State.RUNNING, null, percent, null);
        }

        public static PollResult succeeded(String resultUrl) {
            return new PollResult(State.SUCCEEDED, resultUrl, 100, null);
        }

        public static PollResult failed(String error) {
            return new PollResult(State.FAILED, null, null, error);
        }
    }
}
