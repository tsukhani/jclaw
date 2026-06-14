package services.caption;

import okhttp3.OkHttpClient;

/**
 * OpenAI image-captioning client (JCLAW-212). Delegates the wire shape to
 * {@link OpenAiCompatibleImageCaptionClient}; binds the {@code provider.openai.*} config namespace
 * and a cheap default vision model.
 */
public class OpenAiImageCaptionClient extends OpenAiCompatibleImageCaptionClient {

    public OpenAiImageCaptionClient() {
        super("openai", "gpt-4o-mini");
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public OpenAiImageCaptionClient(OkHttpClient client) {
        super("openai", "gpt-4o-mini", client);
    }
}
