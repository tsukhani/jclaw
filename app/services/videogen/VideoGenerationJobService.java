package services.videogen;

import models.Agent;
import models.Conversation;
import models.MessageAttachment;
import models.VideoGenerationJob;
import models.VideoGenerationJob.State;
import okhttp3.HttpUrl;
import okhttp3.Request;
import play.Logger;
import services.AttachmentService;
import services.ConfigService;
import services.LocalSidecarDaemon;
import services.videogen.VideoGenerationService.PollResult;
import services.videogen.VideoGenerationService.VideoGenRequest;
import tools.GeneratedMediaFile;
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
        // Resolved rather than stored raw (JCLAW-984): the columns are foreign keys now, so an
        // id naming nothing has to become null here instead of a row the cascade cannot reach.
        job.agent = agentId == null ? null : Agent.findById(agentId);
        job.conversation = conversationId == null ? null : Conversation.findById(conversationId);
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
        if (resultUrl == null) return;

        byte[] bytes;
        try {
            bytes = fetchBytes(resultUrl);
        } catch (RuntimeException e) {
            Logger.error(e, "videogen: failed to fetch result for job %s", job.id);
            return;
        }

        // Workspace copy first, and independently of the placeholder (JCLAW-1057). A job
        // submitted from a scheduled task has no conversation and therefore no placeholder
        // attachment, so folding this in after the placeholder null-check would skip the
        // write in exactly the case the option exists for.
        if (job.saveToPath != null && job.agent != null) {
            try {
                var written = GeneratedMediaFile.write(job.agent.name, job.saveToPath, bytes);
                Logger.info("videogen: job %s wrote %s", job.id, written);
            } catch (IllegalArgumentException e) {
                // Containment was checked at submit time, so reaching here means the
                // workspace changed underneath the job. Log and keep the attachment path.
                Logger.error(e, "videogen: failed to save result for job %s to %s",
                        job.id, job.saveToPath);
            }
        }

        var placeholder = MessageAttachment.findByGenerationJobId(job.id);
        if (placeholder != null) {
            try {
                AttachmentService.fillGeneratedVideo(placeholder, bytes, "video/mp4");
                job.resultAttachmentId = placeholder.id;
                job.save();
            } catch (RuntimeException e) {
                Logger.error(e, "videogen: failed to store result for job %s", job.id);
            }
        }

        // Push it to the channel that asked (JCLAW-1057). Storing the attachment is
        // enough for web chat, whose UI polls the message and re-renders once the bytes
        // land — every other channel got only the "started generating" text and never
        // saw the clip. Last, and best-effort: a delivery failure must not undo a
        // generation that succeeded.
        if (VideoDelivery.send(job.conversation, bytes, job.prompt)) {
            Logger.info("videogen: delivered job %s to %s", job.id, job.conversation.channelType);
        }
    }

    /** Whether {@code url} really addresses this host's video sidecar, compared as
     *  parsed host and port so userinfo cannot spoof the authority. */
    private static boolean isLocalSidecar(String url) {
        var sidecar = HttpUrl.parse(LocalVideoSidecarManager.baseUrl());
        var target = HttpUrl.parse(url);
        return sidecar != null && target != null
                && sidecar.host().equals(target.host())
                && sidecar.port() == target.port();
    }

    private static byte[] fetchBytes(String url) {
        var req = new Request.Builder().url(url);
        // Host and port, not a prefix: "http://127.0.0.1:9528@evil.example/x" starts with
        // the sidecar's base URL while OkHttp resolves the host as evil.example, which
        // would hand a provider-supplied URL the sidecar secret.
        if (isLocalSidecar(url)) {
            req.header(LocalSidecarDaemon.AUTH_HEADER, LocalVideoSidecarManager.authToken());
        }
        try (var resp = HttpFactories.general().newCall(req.get().build()).execute()) {
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
