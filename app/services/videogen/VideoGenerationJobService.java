package services.videogen;

import models.VideoGenerationJob;
import models.VideoGenerationJob.State;
import play.Logger;
import services.ConfigService;
import services.videogen.VideoGenerationService.PollResult;
import services.videogen.VideoGenerationService.VideoGenRequest;

import java.time.Duration;
import java.time.Instant;

/**
 * Drives the {@link VideoGenerationJob} lifecycle (JCLAW-230). {@link #submit} creates a job and hands
 * off to the configured provider; {@link #tickOnce} is one pass of the poll loop — called every 5s by
 * {@code jobs.VideoGenerationJobRunner}, and directly by tests. The loop lives here, not in the Job
 * class, so it is unit-testable without the scheduler.
 */
public final class VideoGenerationJobService {

    private VideoGenerationJobService() {}

    /**
     * Create a job in {@code PENDING}, submit it to the configured provider, and transition to
     * {@code RUNNING} with the returned provider id. A provider submit failure lands the job in
     * {@code FAILED} (the async UX shows an error card, JCLAW-234) rather than throwing — the job row is
     * always returned so the caller (the {@code generate_video} tool, JCLAW-235) can link a placeholder
     * attachment to it regardless.
     */
    public static VideoGenerationJob submit(Long agentId, Long conversationId, VideoGenRequest request) {
        var provider = ConfigService.get("videogen.provider");
        var job = new VideoGenerationJob();
        job.agentId = agentId;
        job.conversationId = conversationId;
        job.prompt = request.prompt();
        job.provider = provider;
        job.state = State.PENDING;
        job.save();

        var svc = VideoGenerationRouter.serviceFor(provider);
        if (svc.isEmpty()) {
            return fail(job, "video generation is not configured (videogen.provider=" + provider + ")");
        }
        try {
            job.providerJobId = svc.get().submit(request);
            job.state = State.RUNNING;
            job.save();
        } catch (VideoGenerationException e) {
            return fail(job, e.getMessage());
        }
        return job;
    }

    /**
     * One pass of the poll loop: for each {@code RUNNING} job, time it out if it has run longer than
     * {@code videogen.maxJobMinutes}, otherwise poll its provider and transition on a terminal result.
     * Per-job failures are isolated so one bad job never stalls the sweep.
     */
    public static void tickOnce() {
        int maxMinutes = ConfigService.getInt("videogen.maxJobMinutes", 30);
        for (var job : VideoGenerationJob.findRunning()) {
            try {
                pollOne(job, maxMinutes);
            } catch (RuntimeException e) {
                Logger.error(e, "VideoGenerationJobRunner: error advancing job %s", job.id);
            }
        }
    }

    private static void pollOne(VideoGenerationJob job, int maxMinutes) {
        // Timeout is checked first, so a stuck job is failed without another (pointless) provider poll.
        if (isTimedOut(job, maxMinutes)) {
            fail(job, "video generation timed out after " + maxMinutes + " minutes");
            return;
        }
        if (job.providerJobId == null || job.providerJobId.isBlank()) {
            fail(job, "RUNNING job has no provider job id");
            return;
        }
        var svc = VideoGenerationRouter.serviceFor(job.provider);
        if (svc.isEmpty()) {
            fail(job, "no video provider for '" + job.provider + "'");
            return;
        }
        PollResult r = svc.get().poll(job.providerJobId);
        switch (r.state()) {
            case SUCCEEDED -> {
                // JCLAW-234 will fetch r.resultUrl() into a MessageAttachment here and set
                // resultAttachmentId; for now the runner just records completion.
                job.state = State.SUCCEEDED;
                job.completedAt = Instant.now();
                job.save();
            }
            case FAILED -> fail(job, r.error());
            case RUNNING -> job.save(); // bump updated_at to reflect liveness
        }
    }

    private static boolean isTimedOut(VideoGenerationJob job, int maxMinutes) {
        var since = job.createdAt != null ? job.createdAt : Instant.now();
        return Duration.between(since, Instant.now()).toMinutes() >= maxMinutes;
    }

    private static VideoGenerationJob fail(VideoGenerationJob job, String message) {
        job.state = State.FAILED;
        job.errorMessage = message;
        job.completedAt = Instant.now();
        job.save();
        return job;
    }
}
