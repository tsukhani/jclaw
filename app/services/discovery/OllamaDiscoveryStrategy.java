package services.discovery;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jspecify.annotations.Nullable;
import play.Logger;
import services.ModelDiscoveryService;
import services.ModelDiscoveryService.DiscoveryResult;
import utils.HttpFactories;
import utils.HttpKeys;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Ollama Tier 1 path (JCLAW-118). Uses the richer {@code /api/tags} +
 * {@code /api/show} pair to extract {@code context_length},
 * {@code capabilities}, and architecture metadata that the OpenAI-compatible
 * {@code /v1/models} stub omits entirely for Ollama Cloud. Fans out
 * {@code /api/show} calls concurrently on virtual threads so a provider with
 * dozens of models discovers in one round-trip's worth of wall time rather than N.
 *
 * <p>The {@code capabilities} array also distinguishes chat-capable models from
 * embedding-only ones — {@code ModelDiscoveryService.parseOllamaShow} drops
 * entries whose capabilities lack {@code "completion"} (JCLAW-183).
 */
public final class OllamaDiscoveryStrategy implements DiscoveryStrategy {

    @Override
    @SuppressWarnings("java:S1193") // Catches Exception broadly; instanceof InterruptedException restores interrupt status defensively
    public DiscoveryResult discover(String providerName, String baseUrl, String apiKey) {
        try {
            var nativeBase = ModelDiscoveryService.stripV1Suffix(baseUrl);
            var tagsResult = fetchTags(nativeBase, apiKey);
            if (tagsResult.error() != null) return tagsResult.error();
            var modelIds = tagsResult.resolvedModelIds();
            if (modelIds.isEmpty()) return new DiscoveryResult.Ok(List.of());

            var results = fanOutShow(nativeBase + "/api/show", apiKey, modelIds);
            if (results.isEmpty()) {
                // JCLAW-183: covers both "every /api/show call failed" and
                // "every model was filtered out as non-chat" (e.g. an Ollama
                // install with only nomic-embed-text pulled). Either way the
                // operator gets a clear "nothing chat-capable here" message.
                return new DiscoveryResult.Error(502,
                        "No chat-capable models discovered for provider " + providerName);
            }

            ModelDiscoveryService.applyLeaderboardAndSort(providerName, results);

            return new DiscoveryResult.Ok(results);

        } catch (JsonSyntaxException _) {
            return new DiscoveryResult.Error(502, "Invalid JSON response from provider");
        } catch (Exception e) {
            // Defensive interrupt-status restore: the broad catch is unavoidable (provider
            // calls can surface InterruptedException wrapped or unwrapped), so the
            // instanceof check is the simplest way to honor cooperative cancellation.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new DiscoveryResult.Error(502,
                    "Failed to connect to provider: %s".formatted(e.getMessage()));
        }
    }

    /**
     * Internal carrier for the /api/tags step: either an {@code error} (non-200
     * upstream) or a {@code modelIds} list. Exactly one is non-null.
     */
    private record TagsResult(DiscoveryResult.@Nullable Error error, @Nullable List<String> modelIds) {

        /** Valid only once {@link #error()} has been checked null — exactly one side is set. */
        List<String> resolvedModelIds() {
            if (modelIds == null) throw new IllegalStateException("tags fetch failed: " + error);
            return modelIds;
        }
    }

    /**
     * GET {@code <nativeBase>/api/tags} and extract the model id list. On
     * non-200, returns a {@link TagsResult} carrying a populated {@link DiscoveryResult.Error}.
     */
    private static TagsResult fetchTags(String nativeBase, String apiKey) throws IOException {
        var tagsReq = new Request.Builder()
                .url(nativeBase + "/api/tags")
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + (apiKey != null ? apiKey : ""))
                .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                .get()
                .build();
        var tagsCall = HttpFactories.llmSingleShotGuarded().newCall(tagsReq);
        tagsCall.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        int tagsStatus;
        String tagsResponseBody;
        try (var tagsResp = tagsCall.execute()) {
            tagsStatus = tagsResp.code();
            tagsResponseBody = tagsResp.body().string();
        }
        if (tagsStatus != 200) {
            // JCLAW-778: status only — do not reflect the attacker-influenced
            // upstream body from an agent-settable base URL.
            return new TagsResult(new DiscoveryResult.Error(502,
                    "Provider returned HTTP %d from /api/tags".formatted(tagsStatus)),
                    null);
        }
        var tagsBody = JsonParser.parseString(tagsResponseBody).getAsJsonObject();
        return new TagsResult(null, ModelDiscoveryService.extractTagIds(tagsBody));
    }

    /**
     * Fan out {@code /api/show} calls on virtual threads, one per model id.
     * Per-future timeouts and parse failures are logged and skipped — the
     * caller only sees the survivors. Models filtered out by
     * {@code ModelDiscoveryService.parseOllamaShow} (no {@code "completion"}
     * capability) are also absent from the return.
     */
    private static List<Map<String, Object>> fanOutShow(
            String showUrl, String apiKey, List<String> modelIds) {
        var results = new ArrayList<Map<String, Object>>(modelIds.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = modelIds.stream()
                    .map(id -> executor.submit(() -> fetchShow(showUrl, apiKey, id)))
                    .toList();
            for (int i = 0; i < futures.size(); i++) {
                collectShowResult(futures.get(i), modelIds.get(i), results);
            }
        }
        return results;
    }

    /**
     * Await a single {@code /api/show} future. Logs per-model failures (including
     * timeouts) without aborting the broader discovery; restores the interrupt
     * status on {@link InterruptedException} for cooperative cancellation.
     */
    private static void collectShowResult(
            Future<Map<String, Object>> future,
            String modelId,
            List<Map<String, Object>> out) {
        try {
            var model = future.get(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (model != null) out.add(model);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            Logger.warn("Ollama /api/show interrupted for %s", modelId);
        } catch (Exception e) {
            Logger.warn("Ollama /api/show failed for %s: %s", modelId, e.getMessage());
        }
    }

    @SuppressWarnings("java:S1168") // null means "drop this model from discovery"; empty map would be misread as a successful but empty result
    private static @Nullable Map<String, Object> fetchShow(String url, String apiKey, String id) {
        try {
            var body = "{\"name\":\"" + id.replace("\"", "\\\"") + "\"}";
            var jsonMediaType = MediaType.get(HttpKeys.APPLICATION_JSON);
            var req = new Request.Builder()
                    .url(url)
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + (apiKey != null ? apiKey : ""))
                    .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                    .post(RequestBody.create(body, jsonMediaType))
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
            return ModelDiscoveryService.parseOllamaShow(id, JsonParser.parseString(responseBody).getAsJsonObject());
        } catch (Exception _) {
            return null;
        }
    }
}
