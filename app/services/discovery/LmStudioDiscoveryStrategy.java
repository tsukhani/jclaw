package services.discovery;

import com.google.gson.JsonParser;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import services.ModelDiscoveryService;
import services.ModelDiscoveryService.DiscoveryResult;
import utils.HttpFactories;
import utils.HttpKeys;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LM Studio Tier 1 path (JCLAW-183). Hits the native {@code /api/v0/models}
 * endpoint, which returns a {@code type} field per model — one of {@code "llm"},
 * {@code "vlm"} (vision-language), {@code "embeddings"}, {@code "tts"}
 * (text-to-speech), {@code "stt"} (speech-to-text). Keeps {@code "llm"} and
 * {@code "vlm"}; drops the other three so an operator can't accidentally bind a
 * chat agent to an embedding or audio model.
 *
 * <p>On any failure (404 from older LM Studio versions that predate the native
 * endpoint, malformed JSON, connection refused) it falls back to the standard
 * OpenAI-compat path with the Tier 3 id heuristic.
 */
public final class LmStudioDiscoveryStrategy implements DiscoveryStrategy {

    private static final DiscoveryStrategy FALLBACK = new OpenAiCompatDiscoveryStrategy();

    @Override
    public DiscoveryResult discover(String providerName, String baseUrl, String apiKey) {
        var models = fetchNative(baseUrl, apiKey);
        if (models == null) return FALLBACK.discover(providerName, baseUrl, apiKey);

        ModelDiscoveryService.sortByRankThenName(models);
        return new DiscoveryResult.Ok(models);
    }

    /** @return the parsed native catalog, or {@code null} when the endpoint is unusable. */
    @SuppressWarnings("java:S1168") // null means "fall back to OpenAI-compat"; an empty list is a valid catalog
    private static @Nullable List<Map<String, Object>> fetchNative(String baseUrl, String apiKey) {
        try {
            var nativeBase = ModelDiscoveryService.stripV1Suffix(baseUrl);
            var url = nativeBase + "/api/v0/models";
            var req = new Request.Builder()
                    .url(url)
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + (apiKey != null ? apiKey : ""))
                    .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                    .get()
                    .build();
            var call = HttpFactories.llmSingleShotGuarded().newCall(req);
            call.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var resp = call.execute()) {
                statusCode = resp.code();
                responseBody = resp.body().string();
            }
            if (statusCode != 200) return null;

            var body = JsonParser.parseString(responseBody).getAsJsonObject();
            return ModelDiscoveryService.parseLmStudioNativeResponse(body);
        } catch (Exception _) {
            return null;
        }
    }
}
