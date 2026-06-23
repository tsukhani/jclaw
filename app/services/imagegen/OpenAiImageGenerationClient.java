package services.imagegen;

import okhttp3.OkHttpClient;

/**
 * OpenAI image-generation client (JCLAW-225). Delegates the wire shape to
 * {@link OpenAiCompatibleImageGenerationClient}; binds the {@code provider.openai.*} config
 * namespace and {@code gpt-image-1} as the default model.
 */
public class OpenAiImageGenerationClient extends OpenAiCompatibleImageGenerationClient {

    public OpenAiImageGenerationClient() {
        super("openai", "gpt-image-1");
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public OpenAiImageGenerationClient(OkHttpClient client) {
        super("openai", "gpt-image-1", client);
    }
}
