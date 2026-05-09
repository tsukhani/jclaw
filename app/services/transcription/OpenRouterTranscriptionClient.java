package services.transcription;

import okhttp3.OkHttpClient;

/**
 * OpenRouter {@code /audio/transcriptions} client (JCLAW-162). OpenRouter
 * proxies OpenAI's audio transcription endpoint at the same wire shape, so
 * implementation reuses {@link OpenAiCompatibleTranscriptionClient};
 * only the provider name (which selects the {@code provider.openrouter.*}
 * config keys) is bound here.
 */
public class OpenRouterTranscriptionClient extends OpenAiCompatibleTranscriptionClient {

    public OpenRouterTranscriptionClient() {
        super("openrouter");
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public OpenRouterTranscriptionClient(OkHttpClient client) {
        super("openrouter", client);
    }
}
