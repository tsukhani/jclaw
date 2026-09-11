package services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonParser;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import play.Logger;
import services.discovery.DiscoveryResult;
import services.discovery.DiscoveryStrategy;
import services.discovery.ModelCatalogParser;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Model catalog discovery from LLM provider APIs: the SSRF screen, the per-protocol
 * dispatch through {@link DiscoveryStrategy}, and the page-load cache. Parsing and
 * capability inference live in {@link ModelCatalogParser}, ranking in
 * {@code LeaderboardRanker} (JCLAW-999), so {@code services.discovery} never calls back
 * into this class.
 */
public class ModelDiscoveryService {

    private ModelDiscoveryService() {}


    // Provider model catalogs change on the order of days, so the read-heavy
    // page-load paths (e.g. the Settings video-model dropdown, ~1.3s/call) cache
    // discovery for 10 minutes rather than hitting the provider on every load.
    // Keyed by name+baseUrl+apiKey so a config change re-discovers immediately;
    // only Ok results are cached, so an error/misconfig retries on the next call.
    // The explicit POST /discover-models refresh keeps calling uncached discover().
    private static final Cache<String, DiscoveryResult> DISCOVER_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(32)
            .build();

    /**
     * Cached variant of {@link #discover} for page-load reads (see DISCOVER_CACHE).
     * Use this on GET paths that re-discover on every render; keep {@link #discover}
     * for explicit refreshes that must hit the provider live.
     */
    public static DiscoveryResult discoverCached(String providerName, String baseUrl, String apiKey) {
        var key = providerName + "|" + baseUrl + "|" + (apiKey == null ? "" : apiKey);
        var cached = DISCOVER_CACHE.getIfPresent(key);
        if (cached != null) return cached;
        var fresh = discover(providerName, baseUrl, apiKey);
        if (fresh instanceof DiscoveryResult.Ok) DISCOVER_CACHE.put(key, fresh);
        return fresh;
    }

    /**
     * Fetch the model catalog from a provider, apply leaderboard rankings if
     * configured, and return normalized model info sorted by rank.
     *
     * <p>The protocol — native Ollama, native LM Studio, or OpenAI-compatible —
     * is resolved from the provider name by {@link DiscoveryStrategy#forProvider},
     * and each one applies its own tier of the JCLAW-183 non-chat filter. Adding a
     * protocol is a new {@link DiscoveryStrategy} subtype, not an edit here.
     */
    public static DiscoveryResult discover(String providerName, String baseUrl, String apiKey) {
        // JCLAW-778: the base URL is agent-settable config. Reject a metadata /
        // link-local target before any connect; loopback/LAN stay allowed for
        // local inference. Hostname rebinding is screened at connect by the
        // PROVIDER_SAFE_DNS-guarded client.
        if (baseUrl != null) {
            try {
                SsrfGuard.assertProviderUrlSafe(baseUrl);
            } catch (SecurityException e) {
                Logger.warn("[discover/%s] rejected SSRF target: %s", providerName, e.getMessage());
                return new DiscoveryResult.Error(400, "Provider base URL rejected by SSRF guard");
            }
        }
        return DiscoveryStrategy.forProvider(providerName).discover(providerName, baseUrl, apiKey);
    }

    @SuppressWarnings("java:S1193") // Catches Exception broadly; instanceof InterruptedException restores interrupt status defensively
    /**
     * Every model id the provider's OpenAI-compatible {@code /models} advertises, with
     * no capability filtering at all (JCLAW-932 follow-up).
     *
     * <p>{@link #discover} exists to populate <em>chat</em> pickers, so it deliberately
     * drops embedding, TTS and STT models — JCLAW-183, so an operator cannot bind a chat
     * agent to an embedding model. That is exactly backwards for the memory embedding
     * picker, which needs the models the chat path throws away: on a live ollama serving
     * ten models, discovery returned nine and the one omitted was the embedding model.
     *
     * <p>Unfiltered rather than embedding-filtered on purpose. There is no capability
     * flag to filter on, so the caller shortlists by name and settles it by probing
     * (JCLAW-931); filtering here would just relocate the guesswork and make a validly
     * named model from an unfamiliar provider unreachable.
     *
     * @return the ids, or an empty list on any failure — the caller falls back to the
     *         stored catalog rather than surfacing an error for a picker aid.
     */
    public static List<String> listAllModelIds(String baseUrl, @Nullable String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) return List.of();
        try {
            var url = baseUrl.endsWith("/") ? baseUrl + ModelCatalogParser.FIELD_MODELS : baseUrl + "/" + ModelCatalogParser.FIELD_MODELS;
            var req = new Request.Builder()
                    .url(url)
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + (apiKey != null ? apiKey : ""))
                    .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                    .get()
                    .build();
            var call = HttpFactories.llmSingleShotGuarded().newCall(req);
            call.timeout().timeout(DiscoveryStrategy.DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String responseBody;
            try (var response = call.execute()) {
                if (response.code() != 200) return List.of();
                responseBody = response.body().string();
            }
            var out = new ArrayList<String>();
            for (var m : ModelCatalogParser.parseModels(JsonParser.parseString(responseBody))) {
                var id = (String) m.get(ModelCatalogParser.KEY_ID);
                if (id != null && !id.isBlank()) out.add(id);
            }
            return out;
        } catch (Exception e) {
            Logger.warn("[discover] raw model listing failed: %s", e.getMessage());
            return List.of();
        }
    }
}
