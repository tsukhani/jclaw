package services.video;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.MessageAttachment;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.AttachmentService;
import services.ConfigService;
import utils.GsonHolder;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.Strings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dedicated video-interpretation backend: interprets a clip with a configured video model
 * ({@code video.provider} + {@code video.model}) and returns a prose interpretation, using one of two
 * wire modes chosen per provider ({@link WireMode}):
 * <ul>
 *   <li>{@link WireMode#NATIVE_VIDEO} — sends the whole clip as an OpenAI-compatible {@code video_url}
 *       part (OpenRouter Qwen-VL via DashScope; the model watches the clip).</li>
 *   <li>{@link WireMode#MULTI_IMAGE} — samples frames ({@link FrameSampler}) and sends them as
 *       {@code image_url} parts in a single request (a self-hosted vLLM serving Qwen-VL, whose native
 *       video path is broken; the model reasons over the frame sequence, preserving temporal cues such
 *       as panning).</li>
 * </ul>
 * Mirrors {@link services.caption.OpenAiCompatibleImageCaptionClient} but for whole-video understanding.
 *
 * <p>The returned text is spliced into the chat model's message by {@link VideoUnderstandingDispatcher},
 * so even a text-only chat model gains video understanding (the transcription/captioning pattern: a
 * dedicated media model produces text the chat model consumes).
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code provider.{name}.baseUrl} — {@code /chat/completions} is appended (required).</li>
 *   <li>{@code provider.{name}.apiKey} — {@code Authorization: Bearer} when present; omitted when
 *       blank, so a self-hosted vLLM with no auth still works.</li>
 *   <li>{@code video.model} — a video- or vision-capable model id on the chosen provider (required; no
 *       default).</li>
 * </ul>
 *
 * <p>In {@link WireMode#NATIVE_VIDEO} the clip is inlined as base64 in the {@code video_url}, so it is
 * bounded by the inline size cap ({@link VideoUrlAdapter#isWithinInlineCap}); an over-cap clip throws
 * so the dispatcher falls back to frames on the chat model. {@link WireMode#MULTI_IMAGE} has no such
 * cap — it sends small per-frame JPEGs, not the whole clip.
 */
public class VideoInterpretationClient {

    private static final MediaType JSON = MediaType.parse("application/json");
    /** OpenAI chat-completions message field (request part + response parse). */
    private static final String CONTENT = "content";
    private static final String INSTRUCTION =
            "Describe this video in detail for a reader who cannot see it. Cover the setting, who and "
            + "what appears, the sequence of actions over time, any on-screen or spoken text, and notable "
            + "visual or audio cues. Be objective and specific; do not speculate beyond what is shown.";
    /** Video understanding warrants a fuller reply than a one-line caption. */
    private static final int MAX_TOKENS = 800;

    /**
     * How the clip reaches the dedicated model. {@link #NATIVE_VIDEO} sends the whole clip as a
     * {@code video_url} part; {@link #MULTI_IMAGE} sends sampled frames as {@code image_url} parts in
     * one request. Chosen per provider by {@link VideoInterpretationRouter}.
     */
    public enum WireMode { NATIVE_VIDEO, MULTI_IMAGE }

    private final String providerName;
    private final WireMode wireMode;
    private final OkHttpClient client;

    public VideoInterpretationClient(String providerName, WireMode wireMode) {
        this(providerName, wireMode, HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public VideoInterpretationClient(String providerName, WireMode wireMode, OkHttpClient client) {
        this.providerName = providerName;
        this.wireMode = wireMode;
        this.client = client;
    }

    /** Back-compat: provider-only defaults to {@link WireMode#NATIVE_VIDEO}. */
    public VideoInterpretationClient(String providerName) {
        this(providerName, WireMode.NATIVE_VIDEO);
    }

    /** Back-compat test seam: provider + client defaults to {@link WireMode#NATIVE_VIDEO}. */
    public VideoInterpretationClient(String providerName, OkHttpClient client) {
        this(providerName, WireMode.NATIVE_VIDEO, client);
    }

    /** The wire format this client uses, per its provider. */
    public WireMode wireMode() {
        return wireMode;
    }

    /**
     * Interpret the whole video and return the model's prose. Dispatches on {@link #wireMode}. Throws
     * {@link VideoAdapterException} on any failure (over-cap clip, frame-sampling failure, HTTP error)
     * so the dispatcher can fall back to the chat-model path.
     */
    public String interpret(MessageAttachment video) {
        return switch (wireMode) {
            case NATIVE_VIDEO -> interpretNativeVideo(video);
            case MULTI_IMAGE -> interpretMultiImage(video);
        };
    }

    /** Whole clip as a {@code video_url} part. Bounded by the inline size cap. */
    private String interpretNativeVideo(MessageAttachment video) {
        if (!VideoUrlAdapter.isWithinInlineCap(video)) {
            throw new VideoAdapterException(
                    "video too large to send to the dedicated model (%d bytes; cap %d) — set video.maxInlineMb higher or use a smaller clip"
                            .formatted(video.sizeBytes, VideoUrlAdapter.maxInlineBytes()));
        }
        return interpretDataUrl(AttachmentService.readAsDataUrl(video));
    }

    /**
     * Sampled frames as {@code image_url} parts in one request. No inline size cap — frames are small
     * JPEGs, not the whole clip. Frame-sampling failure (no ffmpeg / corrupt video) surfaces as a
     * {@link VideoAdapterException} so the dispatcher falls back to the chat model.
     */
    private String interpretMultiImage(MessageAttachment video) {
        FrameSampler.SampleResult sampled;
        try {
            sampled = FrameSampler.sample(video);
        } catch (FrameSampler.FrameSamplingException e) {
            throw new VideoAdapterException(
                    "%s multi-image video interpretation could not sample frames: %s"
                            .formatted(providerName, e.getMessage()), e);
        }
        return interpretFrames(sampled.frames(), sampled.durationSeconds());
    }

    /**
     * HTTP core for {@link WireMode#NATIVE_VIDEO} — interpret a video given its {@code data:} URL.
     * Reads {@code provider.{name}.*} + {@code video.model}, wraps the URL as a {@code video_url} part,
     * POSTs to {@code /chat/completions}, and returns the prose. Split out so it's unit-testable
     * without an on-disk attachment. Throws {@link VideoAdapterException} on failure.
     */
    public String interpretDataUrl(String videoDataUrl) {
        var baseUrl = requireBaseUrl();
        var model = requireModel();
        return executeChat(baseUrl, buildRequestJson(model, videoDataUrl));
    }

    /**
     * HTTP core for {@link WireMode#MULTI_IMAGE} — interpret a pre-sampled frame list. POSTs an
     * instruction text part followed by one {@code image_url} part per frame to {@code video.model}
     * and returns the prose. Split out so it's unit-testable with synthetic frames and no ffmpeg
     * (mirrors {@link #interpretDataUrl} for the native path). Throws {@link VideoAdapterException}.
     */
    public String interpretFrames(List<FrameSampler.Frame> frames, double durationSeconds) {
        var baseUrl = requireBaseUrl();
        var model = requireModel();
        var parts = new ArrayList<Map<String, Object>>();
        parts.add(Map.of("type", "text", "text", INSTRUCTION));
        parts.addAll(MultiImageVideoAdapter.contentParts(frames, durationSeconds));
        return executeChat(baseUrl, buildMultiImageRequestJson(model, parts));
    }

    /** Read + validate {@code provider.{name}.baseUrl}. */
    private String requireBaseUrl() {
        var baseUrl = ConfigService.get("provider." + providerName + ".baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new VideoAdapterException("provider." + providerName + ".baseUrl is not configured");
        }
        return baseUrl;
    }

    /** Read + validate {@code video.model}. */
    private static String requireModel() {
        var model = ConfigService.get("video.model");
        if (model == null || model.isBlank()) {
            throw new VideoAdapterException("no video model configured — set video.model");
        }
        return model;
    }

    /** POST a prebuilt {@code /chat/completions} body and return the parsed prose. Shared by both modes. */
    private String executeChat(String baseUrl, String requestJson) {
        var builder = new Request.Builder()
                .url(Strings.trimTrailingSlash(baseUrl) + "/chat/completions")
                .post(RequestBody.create(requestJson, JSON));
        var apiKey = ConfigService.get("provider." + providerName + ".apiKey");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey);
        }

        try (var response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                var snippet = Strings.truncate(response.body().string(), Strings.ERROR_SNIPPET_MAX_CHARS);
                throw new VideoAdapterException("%s video interpretation failed: HTTP %d %s%s".formatted(
                        providerName, response.code(), response.message(),
                        snippet.isEmpty() ? "" : (" — " + snippet)));
            }
            return parseText(response.body().string());
        } catch (IOException e) {
            throw new VideoAdapterException(providerName + " video interpretation transport failed: " + e.getMessage(), e);
        }
    }

    /** One user message: a text instruction followed by a {@code video_url} content part. */
    private String buildRequestJson(String model, String videoDataUrl) {
        var textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", INSTRUCTION);

        var urlObj = new JsonObject();
        urlObj.addProperty("url", videoDataUrl);
        var videoPart = new JsonObject();
        videoPart.addProperty("type", "video_url");
        videoPart.add("video_url", urlObj);

        var content = new JsonArray();
        content.add(textPart);
        content.add(videoPart);

        var message = new JsonObject();
        message.addProperty("role", "user");
        message.add(CONTENT, content);
        var messages = new JsonArray();
        messages.add(message);

        var root = new JsonObject();
        root.addProperty("model", model);
        root.add("messages", messages);
        root.addProperty("max_tokens", MAX_TOKENS);
        return root.toString();
    }

    /** One user message: a text instruction followed by N {@code image_url} frame parts. */
    private static String buildMultiImageRequestJson(String model, List<Map<String, Object>> contentParts) {
        var message = new JsonObject();
        message.addProperty("role", "user");
        message.add(CONTENT, GsonHolder.GSON.toJsonTree(contentParts));
        var messages = new JsonArray();
        messages.add(message);

        var root = new JsonObject();
        root.addProperty("model", model);
        root.add("messages", messages);
        root.addProperty("max_tokens", MAX_TOKENS);
        return root.toString();
    }

    private String parseText(String responseBody) {
        var json = JsonParser.parseString(responseBody).getAsJsonObject();
        var choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new VideoAdapterException(providerName + " video interpretation: no choices in response");
        }
        var message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has(CONTENT) || message.get(CONTENT).isJsonNull()) {
            throw new VideoAdapterException(providerName + " video interpretation: empty content in response");
        }
        return message.get(CONTENT).getAsString().trim();
    }
}
