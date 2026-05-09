package services.transcription;

import okhttp3.OkHttpClient;

/**
 * OpenAI {@code /audio/transcriptions} client (JCLAW-162). Delegates the
 * entire wire shape to {@link OpenAiCompatibleTranscriptionClient}; only
 * the provider name (which selects the {@code provider.openai.*} config
 * keys) is bound here.
 */
public class OpenAiTranscriptionClient extends OpenAiCompatibleTranscriptionClient {

    public OpenAiTranscriptionClient() {
        super("openai");
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public OpenAiTranscriptionClient(OkHttpClient client) {
        super("openai", client);
    }
}
