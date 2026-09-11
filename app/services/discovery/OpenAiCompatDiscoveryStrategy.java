package services.discovery;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import okhttp3.Request;
import play.Logger;
import services.EmbeddingModelFilter;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.Strings;

import java.util.concurrent.TimeUnit;

/**
 * JCLAW-183 Tier 2 / Tier 3: the OpenAI-compatible {@code /models} endpoint, and
 * the catch-all for every provider no other strategy claims. OpenRouter returns
 * rich metadata (its catalog is empirically chat-only, so the filter is a no-op);
 * plain providers (OpenAI, Groq, vanilla OpenAI-compat) get the Tier 3 ID
 * heuristic from {@link EmbeddingModelFilter}.
 */
public final class OpenAiCompatDiscoveryStrategy implements DiscoveryStrategy {

    private static final String MODELS_PATH = "models";
    private static final String KEY_ID = "id";

    @Override
    public DiscoveryResult discover(String providerName, String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new DiscoveryResult.Error(400, "Provider base URL is required for discovery");
        }
        try {
            var url = baseUrl.endsWith("/") ? baseUrl + MODELS_PATH : baseUrl + "/" + MODELS_PATH;
            var req = new Request.Builder()
                    .url(url)
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                    .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                    .get()
                    .build();
            var call = HttpFactories.llmSingleShotGuarded().newCall(req);
            call.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var response = call.execute()) {
                statusCode = response.code();
                responseBody = response.body().string();
            }

            if (statusCode != 200) {
                Logger.warn("[discover/%s] upstream returned HTTP %d: %s",
                        providerName, statusCode, Strings.truncate(responseBody, Strings.ERROR_SNIPPET_MAX_CHARS));
                // JCLAW-778: status only — the upstream body is attacker-influenced
                // (agent-settable base URL) and must not be reflected to the caller.
                return new DiscoveryResult.Error(502, "Provider returned HTTP %d".formatted(statusCode));
            }

            // Together returns a bare JSON array `[{id, ...}, ...]` here,
            // not OpenAI's wrapped `{data: [...]}` shape; parseModels
            // accepts either via JsonElement detection.
            var body = JsonParser.parseString(responseBody);
            var models = ModelCatalogParser.parseModels(body);

            // JCLAW-183 Tier 3: drop entries whose id matches a non-chat
            // pattern. Safe to apply universally — chat-model ids never
            // collide with the embedding/audio/image-gen prefixes the
            // filter checks for.
            models.removeIf(m -> EmbeddingModelFilter.isLikelyNonChat((String) m.get(KEY_ID)));

            LeaderboardRanker.applyLeaderboardAndSort(providerName, models);

            return new DiscoveryResult.Ok(models);

        } catch (JsonSyntaxException e) {
            Logger.warn("[discover/%s] invalid JSON: %s", providerName, e.getMessage());
            return new DiscoveryResult.Error(502, "Invalid JSON response from provider");
        } catch (Exception e) {
            Logger.warn("[discover/%s] connect/parse failed: %s", providerName, e.getMessage());
            return new DiscoveryResult.Error(502, "Failed to connect to provider: %s".formatted(e.getMessage()));
        }
    }
}
