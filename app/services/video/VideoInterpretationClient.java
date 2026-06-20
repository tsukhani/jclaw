package services.video;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.MessageAttachment;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.ConfigService;
import utils.GsonHolder;
import utils.HttpFactories;
import utils.HttpKeys;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Dedicated video-interpretation backend: sends a sampled video as a single Qwen-native video
 * content part to a configured video model ({@code video.provider} + {@code video.model}) and
 * returns a prose interpretation. Mirrors {@link services.caption.OpenAiCompatibleImageCaptionClient}
 * but for whole-video understanding — one call for the whole clip rather than per-frame captioning.
 *
 * <p>The returned text is spliced into the chat model's message by {@link VideoUnderstandingDispatcher},
 * so any chat model — even text-only — gains video understanding. This is the transcription/captioning
 * pattern (a dedicated media model produces text the chat model consumes), and is "always on when
 * configured": once {@code video.provider}/{@code video.model} are set, every video routes here.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code provider.{name}.baseUrl} — {@code /chat/completions} is appended (required).</li>
 *   <li>{@code provider.{name}.apiKey} — {@code Authorization: Bearer} when present; omitted when
 *       blank, so a self-hosted vLLM with no auth still works.</li>
 *   <li>{@code video.model} — a video-capable model id on the chosen provider (required; no default,
 *       like {@code ollama-local} captioning).</li>
 * </ul>
 *
 * <p>The wire shape ({@code video} array + {@code sample_fps} for OpenRouter/DashScope, base64
 * {@code video/jpeg} for vLLM) is chosen from the provider name by {@link QwenVideoAdapter}.
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

    private final String providerName;
    private final OkHttpClient client;

    public VideoInterpretationClient(String providerName) {
        this(providerName, HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public VideoInterpretationClient(String providerName, OkHttpClient client) {
        this.providerName = providerName;
        this.client = client;
    }

    /**
     * Sample the video, send it as a native Qwen video part to {@code video.model}, and return the
     * model's prose interpretation. Throws {@link VideoAdapterException} (or
     * {@link FrameSampler.FrameSamplingException}) on any failure so the dispatcher can fall back to
     * the chat-model-capability path.
     */
    public String interpret(MessageAttachment video) {
        var sampled = FrameSampler.sample(video); // may throw FrameSamplingException (ffmpeg layer)
        return interpret(sampled.frames(), sampled.durationSeconds());
    }

    /**
     * HTTP core — interpret pre-sampled frames. Reads {@code provider.{name}.*} + {@code video.model}
     * from config, wraps the frames as a Qwen video part, POSTs to {@code /chat/completions}, and
     * returns the prose. Split out from {@link #interpret(MessageAttachment)} so it's unit-testable
     * without ffmpeg or an on-disk attachment (mirrors {@code captionDataUrl}). Throws
     * {@link VideoAdapterException} on any failure.
     */
    public String interpret(List<FrameSampler.Frame> frames, double durationSeconds) {
        var baseUrl = ConfigService.get("provider." + providerName + ".baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new VideoAdapterException("provider." + providerName + ".baseUrl is not configured");
        }
        var model = ConfigService.get("video.model");
        if (model == null || model.isBlank()) {
            throw new VideoAdapterException("no video model configured — set video.model");
        }

        var shape = QwenVideoAdapter.shapeForProvider(providerName);
        var videoPart = QwenVideoAdapter.contentParts(frames, durationSeconds, shape).get(0);

        var builder = new Request.Builder()
                .url(trimTrailingSlash(baseUrl) + "/chat/completions")
                .post(RequestBody.create(buildRequestJson(model, videoPart), JSON));
        var apiKey = ConfigService.get("provider." + providerName + ".apiKey");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey);
        }

        try (var response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                var snippet = truncate(response.body().string(), 500);
                throw new VideoAdapterException("%s video interpretation failed: HTTP %d %s%s".formatted(
                        providerName, response.code(), response.message(),
                        snippet.isEmpty() ? "" : (" — " + snippet)));
            }
            return parseText(response.body().string());
        } catch (IOException e) {
            throw new VideoAdapterException(providerName + " video interpretation transport failed: " + e.getMessage(), e);
        }
    }

    /** One user message: a text instruction followed by the Qwen video content part. */
    private String buildRequestJson(String model, Map<String, Object> videoPart) {
        var textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", INSTRUCTION);

        var content = new JsonArray();
        content.add(textPart);
        content.add(GsonHolder.INSTANCE.toJsonTree(videoPart));

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

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
