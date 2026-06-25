package services.videogen;

import services.ConfigService;

import java.util.Optional;

/**
 * Picks the {@link VideoGenerationService} matching the operator's {@code videogen.provider} selection
 * (JCLAW-231), single-select like {@code services.imagegen.ImageGenerationRouter}. Cloud video ships
 * with a single provider:
 *
 * <ul>
 *   <li>{@code replicate} → {@link ReplicateVideoGenerationClient} — fronts WAN 2 and LTX in cloud form
 *       for users without a local GPU, so one aggregator covers the whole cloud path.</li>
 * </ul>
 *
 * <p>A second cloud client was deliberately dropped (SV-1 / JCLAW-510): Replicate already fronts both
 * WAN 2 and LTX, so another aggregator would add no capability, only a price/speed hedge — fal.ai is the
 * documented future option if that hedge is ever wanted (the true Replicate peer: same poll-without-webhook
 * queue API), rather than a first-party API like Runway/Luma/Kling. The local engines ({@code wan-local},
 * {@code ltx-local}) land in JCLAW-232/233 and slot into the same switch. Returns {@link Optional#empty()}
 * when unset/unrecognised — the {@code generate_video} tool (JCLAW-235) then reports "video generation is
 * not configured" rather than attempting a call. Resolved per call so a Settings change takes effect on
 * the next generation without a restart.
 */
public final class VideoGenerationRouter {

    private VideoGenerationRouter() {}

    public static Optional<VideoGenerationService> configuredService() {
        return serviceFor(ConfigService.get("videogen.provider"));
    }

    /**
     * Resolve a client by provider name. Used by {@code jobs.VideoGenerationJobRunner} to poll a job by
     * the provider it was <em>submitted</em> with (stored on the job row), which may differ from the
     * current {@code videogen.provider} if the operator changed the Settings mid-job.
     */
    public static Optional<VideoGenerationService> serviceFor(String provider) {
        if (provider == null || provider.isBlank()) return Optional.empty();
        return switch (provider) {
            case "replicate" -> Optional.of(new ReplicateVideoGenerationClient());
            default -> Optional.empty();
        };
    }
}
