package services.caption;

import okhttp3.OkHttpClient;

/**
 * OpenRouter image-captioning client (JCLAW-212). Same wire shape as OpenAI; binds the
 * {@code provider.openrouter.*} config namespace and a cheap default vision model. Override the
 * model with the {@code caption.model} config key.
 */
public class OpenRouterImageCaptionClient extends OpenAiCompatibleImageCaptionClient {

    public OpenRouterImageCaptionClient() {
        super("openrouter", "google/gemini-2.5-flash");
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public OpenRouterImageCaptionClient(OkHttpClient client) {
        super("openrouter", "google/gemini-2.5-flash", client);
    }
}
