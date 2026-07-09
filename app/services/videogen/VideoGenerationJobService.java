package services.videogen;

import models.MessageAttachment;
import models.VideoGenerationJob;
import models.VideoGenerationJob.State;
import okhttp3.Request;
import play.Logger;
import services.AttachmentService;
import services.ConfigService;
import services.videogen.VideoGenerationService.PollResult;
import services.videogen.VideoGenerationService.VideoGenRequest;
import utils.HttpFactories;

import java.io.IOException;
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
     * JCLAW-153: entity-lookup accessor so controllers route through the
     * service layer instead of calling {@code VideoGenerationJob.findById(...)}
     * raw. Thin passthrough relying on the caller's ambient JPA transaction.
     */
    public static VideoGenerationJob findById(Long id) {
        return VideoGenerationJob.findById(id);
    }

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
            case SUCCEEDED -> completeSucceeded(job, r.resultUrl());
            case FAILED -> fail(job, r.error());
            // Persist the latest progress (real for local, null for cloud) and bump updated_at for liveness.
            case RUNNING -> {
                job.percent = r.percent();
                job.save();
            }
        }
    }

    /**
     * JCLAW-234: mark the job succeeded, then best-effort fetch the produced video into the placeholder
     * attachment created for it (by the {@code generate_video} tool). A fetch/store failure leaves the
     * job SUCCEEDED with no attachment rather than flipping it to FAILED — the state transition is the
     * contract, and losing the bytes shouldn't lose the job. A succeeded job with no placeholder (e.g.
     * one submitted outside the tool flow) simply records completion.
     */
    private static void completeSucceeded(VideoGenerationJob job, String resultUrl) {
        job.state = State.SUCCEEDED;
        // A finished job is 100% — normalize across providers: cloud reports null progress (SV-1) and the
        // local MLX hook caps RUNNING at 95 (reserving headroom for the decode/mux phase), so the terminal
        // transition is where 100 belongs.
        job.percent = 100;
        job.completedAt = Instant.now();
        job.save();
        var placeholder = MessageAttachment.findByGenerationJobId(job.id);
        if (placeholder == null || resultUrl == null) return;
        try {
            var bytes = fetchBytes(resultUrl);
            AttachmentService.fillGeneratedVideo(placeholder, bytes, "video/mp4");
            job.resultAttachmentId = placeholder.id;
            job.save();
        } catch (RuntimeException e) {
            Logger.error(e, "videogen: failed to store result for job %s", job.id);
        }
    }

    private static byte[] fetchBytes(String url) {
        var req = new Request.Builder().url(url).get().build();
        try (var resp = HttpFactories.general().newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new VideoGenerationException("video fetch failed: HTTP " + resp.code());
            }
            return resp.body().bytes();
        } catch (IOException e) {
            throw new VideoGenerationException("video fetch transport failed: " + e.getMessage(), e);
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
