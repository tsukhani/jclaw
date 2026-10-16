package services.videogen;

import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import services.BflTasks;
import services.ConfigService;
import utils.HttpFactories;
import utils.Strings;

import java.util.Set;

/**
 * Black Forest Labs video-generation client (FLUX 3 Video). It rides the same async task API as
 * {@code BflImageGenerationClient}, through {@link BflTasks}: {@link #submit} posts a text-to-video
 * request and returns the task's {@code polling_url}, which becomes the job's provider id, and the
 * {@code VideoGenerationJobRunner} drives {@link #poll} until the task is Ready with an mp4 in
 * {@code result.sample}. That URL expires about ten minutes after it is issued, so the runner's fetch
 * on the first succeeded poll is in time.
 *
 * <p>Config: {@code provider.bfl.baseUrl}, {@code provider.bfl.apiKey} (both shared with image
 * generation) and {@code videogen.bfl.model} (default {@code flux-3-video}). Duration is clamped to
 * the endpoint's 5–20 s range; an aspect ratio outside its enum is left to {@code auto}; fps has no
 * BFL parameter and is ignored. Resolution is never sent, so BFL renders its default, HD.
 */
public class BflVideoGenerationClient implements VideoGenerationService {

    public static final String DEFAULT_MODEL = "flux-3-video";
    private static final int MIN_SECONDS = 5;
    private static final int MAX_SECONDS = 20;
    private static final Set<String> ASPECT_RATIOS =
            Set.of("21:9", "2:1", "16:9", "4:3", "1:1", "3:4", "9:16", "9:21");

    private final BflTasks tasks;

    public BflVideoGenerationClient() {
        this(HttpFactories.generalGuarded());
    }

    /** Test seam — inject a canned transport. */
    public BflVideoGenerationClient(OkHttpClient client) {
        this.tasks = new BflTasks(client);
    }

    @Override
    public String submit(VideoGenRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new VideoGenerationException("video generation: prompt is required");
        }
        var baseUrl = requireConfig("provider.bfl.baseUrl");
        var apiKey = requireConfig("provider.bfl.apiKey");
        var model = Strings.firstNonBlankOr(DEFAULT_MODEL, request.model(), ConfigService.get("videogen.bfl.model"));

        var body = new JsonObject();
        body.addProperty("prompt", request.prompt());
        body.addProperty("mode", "t2v");
        // BFL bills per second and its own default (auto) can run to 20 s, so an unasked length is the minimum.
        var seconds = request.durationSeconds();
        body.addProperty("duration", seconds == null ? MIN_SECONDS : Math.clamp(seconds, MIN_SECONDS, MAX_SECONDS));
        var aspect = request.aspectRatio();
        if (aspect != null && ASPECT_RATIOS.contains(aspect)) {
            body.addProperty("aspect_ratio", aspect);
        }
        try {
            return tasks.submit(Strings.trimTrailingSlash(baseUrl), model, apiKey, body);
        } catch (BflTasks.BflException e) {
            throw new VideoGenerationException(e.getMessage(), e);
        }
    }

    /** {@code providerJobId} is the task's polling URL, as {@link #submit} returned it. */
    @Override
    public PollResult poll(String providerJobId) {
        var apiKey = requireConfig("provider.bfl.apiKey");
        BflTasks.Poll poll;
        try {
            poll = tasks.poll(providerJobId, apiKey);
        } catch (BflTasks.BflException e) {
            throw new VideoGenerationException(e.getMessage(), e);
        }
        return switch (poll.state()) {
            case READY -> {
                var sample = poll.sampleUrl();
                yield sample == null ? PollResult.failed("bfl result Ready but no sample URL") : PollResult.succeeded(sample);
            }
            case FAILED -> PollResult.failed(String.valueOf(poll.error()));
            // BFL's progress field has no documented scale, so cloud stays at null like Replicate.
            case PENDING -> PollResult.running(null);
        };
    }

    private static String requireConfig(String key) {
        var v = ConfigService.get(key);
        if (v == null || v.isBlank()) throw new VideoGenerationException(key + " is not configured");
        return v;
    }
}
