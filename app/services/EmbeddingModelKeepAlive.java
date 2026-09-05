package services;

import llm.ProviderLocality;
import memory.MemoryVectorSettings;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Logger;
import utils.HttpFactories;
import utils.HttpKeys;

import java.util.Locale;

/**
 * Pin a locally-served embedding model in memory so memory recall never pays a model load.
 *
 * <p>Backends that load on demand evict when idle. Measured against Ollama serving
 * snowflake-arctic-embed, the reload after its five-minute window is <b>581 ms</b> against a
 * 27-31 ms steady-state embed, and it lands inside {@code prologue_assemble} — so the first
 * chat turn after a pause wears it in full. That is exactly the gap a person leaves between
 * messages.
 *
 * <p>There is no portable directive for this, so the strategy is per backend. Resolved by
 * name substring, which is the same discriminator {@code ProviderRegistry} already uses to
 * pick a provider class:
 *
 * <ul>
 *   <li><b>Ollama</b> — {@code keep_alive: -1} holds the model until the server exits, and
 *       only its native API honors the field: sending it on the OpenAI-compatible
 *       {@code /v1} route left {@code ollama ps} reporting the ordinary five-minute expiry,
 *       while the same value on {@code /api/embed} reported "Forever". Hence the URL
 *       derivation below. The pin is durable against other models loading — measured by
 *       loading a 1.3 GB and a 9 GB model alongside it on a 48 GB host, after which all
 *       three were resident and the embed model still read "Forever". Reports of a pinned
 *       model being evicted anyway exist but track constrained-VRAM scheduler bugs, which
 *       did not reproduce here.</li>
 *   <li><b>LM Studio</b> — JIT-loaded models carry a 60-minute idle TTL, and {@code ttl}
 *       (seconds) overrides it on the OpenAI-compatible route, so no special URL is needed.
 *       No infinite value is documented, so this asks for a long one. An operator who wants
 *       true permanence loads through {@code lms load}, which carries no TTL at all.</li>
 *   <li><b>Everything else</b> — nothing to send. vLLM and TGI hold the model for the
 *       server's lifetime and never evict. llama.cpp does evict, but exposes only
 *       server-side flags ({@code --sleep-idle-seconds}) with no per-request field, so it
 *       cannot be pinned by a client and belongs in the operator's launch command.</li>
 * </ul>
 *
 * <p>Hosted providers are skipped outright: there is no model residency to maintain and a
 * warm-up would simply be billed.
 */
public final class EmbeddingModelKeepAlive {

    private EmbeddingModelKeepAlive() {}

    /** Set false to leave model residency entirely to the backend's own defaults. */
    public static final String KEY_KEEP_WARM = MemoryVectorSettings.KEY_PREFIX + "keepWarm";

    /** Short and constant: the vector is discarded, only the residency hint matters. */
    private static final String PIN_INPUT = "warm";

    /** Ollama's "never unload" sentinel. */
    private static final String OLLAMA_FOREVER = "-1";

    /** LM Studio has no infinite TTL, so ask for a day and let the job renew it. */
    private static final long LMSTUDIO_TTL_SECONDS = 86_400L;

    /** How a given backend is told to hold its model. */
    public enum Strategy {
        /** Native {@code /api/embed} carrying {@code keep_alive}. */
        OLLAMA_KEEP_ALIVE,
        /** OpenAI-compatible {@code /embeddings} carrying {@code ttl}. */
        LM_STUDIO_TTL,
        /** Nothing to send — already resident, or only configurable server-side. */
        NONE
    }

    /**
     * @param declaredLocal whether the operator classified the provider as self-hosted
     *                      (JCLAW-1102). A self-hosted backend reached over a VPN holds a
     *                      model resident exactly as a loopback one does, so it earns the
     *                      same pin.
     * @return the strategy for {@code providerName}, or {@link Strategy#NONE} when the
     *         provider is hosted or has no client-side pin. The classification arrives as an
     *         argument rather than a config read, so this stays pure and the routing is
     *         testable without a provider or a network.
     */
    public static Strategy strategyFor(String providerName, String baseUrl, boolean declaredLocal) {
        if (providerName == null || providerName.isBlank()) return Strategy.NONE;
        if (baseUrl == null || baseUrl.isBlank()) return Strategy.NONE;
        if (!declaredLocal && !ProviderLocality.isLocalUrl(baseUrl)) return Strategy.NONE;
        var name = providerName.toLowerCase(Locale.ROOT);
        if (name.contains("ollama")) return Strategy.OLLAMA_KEEP_ALIVE;
        if (name.contains("lmstudio") || name.contains("lm-studio")) return Strategy.LM_STUDIO_TTL;
        return Strategy.NONE;
    }

    /** Issue one residency pin. Returns false when skipped or when the backend refused. */
    public static boolean pin() {
        if (!MemoryVectorSettings.enabled()) return false;
        if (!ConfigService.getBoolean(KEY_KEEP_WARM, true)) return false;

        // strategyFor rejects both of these too, and is public and tested on its own. Repeating
        // them is what makes baseUrl non-null where the URL is built below, rather than
        // non-null only because a helper happened to return NONE.
        var providerName = MemoryVectorSettings.provider();
        if (providerName == null || providerName.isBlank()) return false;
        var baseUrl = ConfigService.get("provider." + providerName + ".baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) return false;

        var strategy = strategyFor(providerName, baseUrl, ProviderLocality.isLocal(providerName));
        if (strategy == Strategy.NONE) return false;

        var model = MemoryVectorSettings.model();
        if (model == null || model.isBlank()) return false;

        var url = strategy == Strategy.OLLAMA_KEEP_ALIVE
                ? nativeRoot(baseUrl) + "/api/embed"
                : trimSlash(baseUrl) + "/embeddings";
        var body = strategy == Strategy.OLLAMA_KEEP_ALIVE
                ? """
                  {"model":"%s","input":"%s","keep_alive":%s}""".formatted(model, PIN_INPUT, OLLAMA_FOREVER)
                : """
                  {"model":"%s","input":"%s","ttl":%d}""".formatted(model, PIN_INPUT, LMSTUDIO_TTL_SECONDS);

        var req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, MediaType.parse(HttpKeys.APPLICATION_JSON)))
                .build();
        try (var response = HttpFactories.llmSingleShot().newCall(req).execute()) {
            if (!response.isSuccessful()) {
                Logger.debug("[memory] embedding keep-alive returned HTTP %d", response.code());
                return false;
            }
            return true;
        } catch (Exception e) {
            // Debug, not warn: an operator whose local server is simply not running would
            // otherwise get this line on every interval, and the only cost of failing is the
            // model load this exists to avoid.
            Logger.debug("[memory] embedding keep-alive failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Ollama's configured base URL addresses its OpenAI-compatible surface ({@code .../v1});
     * the native API that honors {@code keep_alive} sits at the root.
     */
    public static String nativeRoot(String baseUrl) {
        var trimmed = trimSlash(baseUrl);
        return trimmed.endsWith("/v1") ? trimmed.substring(0, trimmed.length() - "/v1".length()) : trimmed;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
