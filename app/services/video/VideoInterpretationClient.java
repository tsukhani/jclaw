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
import utils.HttpFactories;
import utils.HttpKeys;

import java.io.IOException;

/**
 * Dedicated video-interpretation backend: sends the whole video as an OpenAI-compatible {@code video_url}
 * content part to a configured video model ({@code video.provider} + {@code video.model}) and returns a
 * prose interpretation. Mirrors {@link services.caption.OpenAiCompatibleImageCaptionClient} but for
 * whole-video understanding — one call for the whole clip.
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
 *   <li>{@code video.model} — a video-capable model id on the chosen provider (required; no default).</li>
 * </ul>
 *
 * <p>The clip is inlined as base64 in the {@code video_url}, so it is bounded by the same size cap as
 * the chat-native path ({@link VideoUrlAdapter#isWithinInlineCap}); an over-cap clip throws so the
 * dispatcher falls back to frames on the chat model.
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
     * Send the whole video as a {@code video_url} part to {@code video.model} and return the model's
     * prose interpretation. Throws {@link VideoAdapterException} on any failure (including an over-cap
     * clip) so the dispatcher can fall back to the chat-model path.
     */
    public String interpret(MessageAttachment video) {
        if (!VideoUrlAdapter.isWithinInlineCap(video)) {
            throw new VideoAdapterException(
                    "video too large to send to the dedicated model (%d bytes; cap %d) — set video.maxInlineMb higher or use a smaller clip"
                            .formatted(video.sizeBytes, VideoUrlAdapter.maxInlineBytes()));
        }
        return interpretDataUrl(AttachmentService.readAsDataUrl(video));
    }

    /**
     * HTTP core — interpret a video given its {@code data:} URL. Reads {@code provider.{name}.*} +
     * {@code video.model} from config, wraps the URL as a {@code video_url} part, POSTs to
     * {@code /chat/completions}, and returns the prose. Split out so it's unit-testable without an
     * on-disk attachment (mirrors {@code captionDataUrl}). Throws {@link VideoAdapterException} on failure.
     */
    public String interpretDataUrl(String videoDataUrl) {
        var baseUrl = ConfigService.get("provider." + providerName + ".baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new VideoAdapterException("provider." + providerName + ".baseUrl is not configured");
        }
        var model = ConfigService.get("video.model");
        if (model == null || model.isBlank()) {
            throw new VideoAdapterException("no video model configured — set video.model");
        }

        var builder = new Request.Builder()
                .url(trimTrailingSlash(baseUrl) + "/chat/completions")
                .post(RequestBody.create(buildRequestJson(model, videoDataUrl), JSON));
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
