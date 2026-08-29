package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import llm.PaymentModality;
import llm.ProviderLocality;
import llm.ProviderRegistry;
import memory.JpaMemoryStore;
import memory.MemoryVectorSettings;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.LoadTestRunner;
import services.LocalProviderProbeSupport;
import services.ModelDiscoveryService;
import services.ModelDiscoveryService.DiscoveryResult;
import services.PricingRefreshService;
import services.video.VideoInterpretationClient;
import services.video.VideoInterpretationRouter;
import utils.ApiResponses;
import utils.JsonArgs;
import utils.Strings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static utils.GsonHolder.GSON;

/**
 * Provider management endpoints — model discovery from provider APIs.
 */
@With(AuthCheck.class)
public class ApiProvidersController extends Controller {

    private static final Gson gson = GSON;
    private static final String PROVIDER_CONFIG_PREFIX = "provider.";
    private static final String BASE_URL_SUFFIX = ".baseUrl";
    private static final String API_KEY_SUFFIX = ".apiKey";
    private static final String SUPPORTS_VISION = "supportsVision";

    public record DiscoverModelsResponse(List<Map<String, Object>> models, int count) {}

    public record RefreshPricesResponse(boolean skipped, int providersScanned, int modelsUpdated, List<String> warnings) {}

    /**
     * @param local effective locality — the base URL is local, or the operator classified the
     *              provider as self-hosted (JCLAW-1102). Gates the memory embedding and
     *              reranker pickers.
     */
    public record ProviderInfo(String name,
                               String paymentModality,
                               BigDecimal subscriptionMonthlyUsd,
                               List<String> supportedModalities,
                               boolean local) {}

    /** A model's canonical id paired with its human-readable display name. */
    public record ModelRef(String id, String name) {}

    public record ProviderModelsResponse(String provider, List<ModelRef> models, int count) {}

    public record AddModelResponse(String provider, ModelRef model, int count) {}

    /** Live reachability of a (typically local) provider's OpenAI-compatible endpoint. */
    public record ReachableResponse(String provider, boolean reachable, int modelCount, String reason) {}

    /**
     * Result of embedding-probing one model (JCLAW-931). {@code dimensions} is the length
     * of the vector the model actually returned, and is 0 when {@code ok} is false.
     */
    public record EmbeddingProbeResponse(String provider, String model, boolean ok, int dimensions, String error) {}

    /**
     * GET /api/providers — billing-shape projection of each configured
     * provider. Returns name, selected modality, subscription monthly
     * price, and the supported-modality set so the Settings UI knows
     * which choices to offer and the Chat Cost dashboard knows how to
     * partition spend.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProviderInfo.class))))
    @Operation(summary = "List configured LLM providers")
    public static void list() {
        var infos = ProviderRegistry.listAll().stream()
                .filter(p -> !isInternalProvider(p.config().name()))
                .map(p -> {
                    var cfg = p.config();
                    var supported = PaymentModality.supportedFor(cfg.name()).stream()
                            .map(Enum::name)
                            .sorted()
                            .toList();
                    return new ProviderInfo(
                            cfg.name(),
                            cfg.paymentModality().name(),
                            cfg.subscriptionMonthlyUsd(),
                            supported,
                            ProviderLocality.isLocal(cfg.name()));
                })
                .toList();
        renderJSON(gson.toJson(infos));
    }

    /**
     * POST /api/providers/{name}/discover-models
     * Fetches the model catalog from the provider's /models endpoint.
     * Returns normalized model info including auto-detected capabilities.
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DiscoverModelsResponse.class)))
    @Operation(summary = "Discover a provider's available models from its live API")
    public static void discoverModels(String name) {
        var baseUrl = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + BASE_URL_SUFFIX);
        var apiKey = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + API_KEY_SUFFIX);

        if (baseUrl == null || baseUrl.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Provider '%s' has no base URL configured".formatted(name));
        }
        if (apiKey == null || apiKey.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Provider '%s' has no API key configured".formatted(name));
        }

        var result = ModelDiscoveryService.discover(name, baseUrl, apiKey);
        switch (result) {
            case DiscoveryResult.Ok(var models) ->
                    renderJSON(gson.toJson(new DiscoverModelsResponse(models, models.size())));
            case DiscoveryResult.Error(var statusCode, var message) ->
                    ApiResponses.error(statusCode, "upstream_error", message);
        }
    }

    /**
     * GET /api/providers/{name}/reachable — a live liveness check of a provider's
     * OpenAI-compatible {@code /models} endpoint (a short GET with a 7s timeout).
     * Used by Settings → Video Interpretation to offer the vLLM option only when a
     * self-hosted vLLM is actually running and reachable, not merely configured.
     * Always 200 with {@code reachable=false} + a reason when down/unconfigured, so
     * the UI can render a "not reachable" hint rather than treating it as an error.
     */
    @SuppressWarnings("java:S2259") // null guard halts via Play's renderJSON() throwing — baseUrl is non-null at the probe call (same as discoverModels)
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ReachableResponse.class)))
    @Operation(summary = "Check whether a provider's endpoint is reachable right now")
    public static void reachable(String name) {
        var baseUrl = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + BASE_URL_SUFFIX);
        if (baseUrl == null || baseUrl.isBlank()) {
            renderJSON(gson.toJson(new ReachableResponse(name, false, 0, "not configured")));
        }
        var r = LocalProviderProbeSupport.probeModels(Strings.trimTrailingSlash(baseUrl), name);
        renderJSON(gson.toJson(new ReachableResponse(name, r.available(), r.modelCount(), r.reason())));
    }

    /**
     * GET /api/providers/{name}/video-models — the provider's live catalog filtered to the models that
     * can drive its video-interpretation wire mode ({@link VideoInterpretationRouter#wireModeFor}),
     * projected to {@code id} + display name. Backs the Settings → Video Interpretation model picker.
     *
     * <p>A {@code NATIVE_VIDEO} provider (OpenRouter) needs true video input, so it filters
     * {@code supportsVideo}. A {@code MULTI_IMAGE} provider (vLLM, Ollama) interprets sampled frames as
     * images, so it filters {@code supportsVision} (which subsumes any {@code supportsVideo} model, since
     * a video-capable model is also vision-capable). An unrecognized provider keeps the legacy
     * {@code supportsVideo} filter. Lists models available from the provider rather than the operator's
     * manually-configured set (a dedicated model needn't be pre-added — the client calls it by id).
     * Unlike {@link #discoverModels} the API key is optional, so a self-hosted vLLM or Ollama with no
     * auth works.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProviderModelsResponse.class)))
    @Operation(summary = "List a provider's video-capable models from its live API")
    public static void videoModels(String name) {
        var baseUrl = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + BASE_URL_SUFFIX);
        if (baseUrl == null || baseUrl.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Provider '%s' has no base URL configured".formatted(name));
        }
        var apiKey = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + API_KEY_SUFFIX);
        // Page-load read (Settings video-model dropdown) — cached; the explicit
        // POST /discover-models refresh still hits the provider live.
        var result = ModelDiscoveryService.discoverCached(name, baseUrl, apiKey == null ? "" : apiKey);
        // MULTI_IMAGE providers (vLLM, Ollama) interpret sampled frames, so any vision-capable model
        // qualifies; a NATIVE_VIDEO provider (OpenRouter) needs true video input; an unrecognized
        // provider keeps the legacy supportsVideo filter. A video-capable model is also vision-capable,
        // so the MULTI_IMAGE filter naturally subsumes any supportsVideo model.
        var multiImage = VideoInterpretationRouter.wireModeFor(name)
                .filter(mode -> mode == VideoInterpretationClient.WireMode.MULTI_IMAGE)
                .isPresent();
        switch (result) {
            case DiscoveryResult.Ok(var models) -> {
                var refs = new ArrayList<ModelRef>();
                for (var m : models) {
                    var id = String.valueOf(m.getOrDefault("id", ""));
                    if (id.isBlank()) continue;
                    var qualifies = multiImage
                            ? Boolean.TRUE.equals(m.get(SUPPORTS_VISION)) || Boolean.TRUE.equals(m.get("supportsVideo"))
                            : Boolean.TRUE.equals(m.get("supportsVideo"));
                    if (!qualifies) continue;
                    var displayName = String.valueOf(m.getOrDefault("name", ""));
                    refs.add(new ModelRef(id, displayName.isBlank() ? deriveName(id) : displayName));
                }
                renderJSON(gson.toJson(new ProviderModelsResponse(name, refs, refs.size())));
            }
            case DiscoveryResult.Error(var statusCode, var message) -> ApiResponses.error(statusCode, "upstream_error", message);
        }
    }

    /**
     * GET /api/providers/{name}/models — the provider's operator-configured
     * model list, projected to {@code id} + human-readable {@code name}.
     *
     * <p>Distinct from {@link #discoverModels}: that endpoint fetches the
     * provider's <em>live catalog</em> from its API, whereas this returns the
     * subset the operator has saved under {@code provider.{name}.models}. It is
     * the read complement to {@link #addModel}.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProviderModelsResponse.class)))
    @Operation(summary = "List a provider's configured models (id + display name)")
    public static void models(String name) {
        requireConfiguredProvider(name);
        var arr = parseModelsArray(ConfigService.get(modelsKey(name)));
        var refs = new ArrayList<ModelRef>();
        for (var el : arr) {
            if (!el.isJsonObject()) continue;
            var obj = el.getAsJsonObject();
            var id = JsonArgs.optString(obj, "id", "");
            if (id.isBlank()) continue;
            var displayName = JsonArgs.optString(obj, "name", "");
            refs.add(new ModelRef(id, displayName.isBlank() ? deriveName(id) : displayName));
        }
        renderJSON(gson.toJson(new ProviderModelsResponse(name, refs, refs.size())));
    }

    /**
     * POST /api/providers/{name}/models — append a model to the provider's
     * configured list by its {@code id}. Only {@code id} is required; {@code name}
     * defaults to the id's last path segment, and the optional capability/context
     * fields ({@code contextWindow}, {@code maxTokens}, {@code supportsThinking},
     * {@code supportsVision}, {@code supportsAudio}, {@code alwaysThinks}) plus the
     * four price fields mirror the Settings "add model" form exactly. Unset prices
     * are omitted from the saved JSON (matching the frontend), so they don't poison
     * the cost-computation fallbacks. Rejects a duplicate id with 409.
     */
    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = ModelInfoRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AddModelResponse.class)))
    @Operation(summary = "Add a model to a provider by id")
    public static void addModel(String name) {
        requireConfiguredProvider(name);

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("id") || body.get("id").isJsonNull()
                || body.get("id").getAsString().isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Field 'id' is required");
        }
        var id = body.get("id").getAsString().trim();

        var key = modelsKey(name);
        var models = parseModelsArray(ConfigService.get(key));
        for (var el : models) {
            if (el.isJsonObject() && id.equals(JsonArgs.optString(el.getAsJsonObject(), "id", ""))) {
                ApiResponses.error(409, ApiResponses.CONFLICT, "Model '%s' already exists for provider '%s'".formatted(id, name));
            }
        }

        var displayName = body.has("name") && !body.get("name").isJsonNull()
                && !body.get("name").getAsString().isBlank()
                ? body.get("name").getAsString().trim()
                : deriveName(id);

        models.add(buildModelObject(body, id, displayName));
        ConfigService.setWithSideEffects(key, gson.toJson(models));

        renderJSON(gson.toJson(new AddModelResponse(name, new ModelRef(id, displayName), models.size())));
    }

    /** Documents the {@link #addModel} request body for the OpenAPI schema. */
    @SuppressWarnings("unused")
    public record ModelInfoRequest(String id,
                                   String name,
                                   int contextWindow,
                                   int maxTokens,
                                   boolean supportsThinking,
                                   boolean supportsVision,
                                   boolean supportsAudio,
                                   boolean alwaysThinks,
                                   double promptPrice,
                                   double completionPrice,
                                   double cachedReadPrice,
                                   double cacheWritePrice) {}

    private static String modelsKey(String name) {
        return PROVIDER_CONFIG_PREFIX + name + ".models";
    }

    /**
     * GET /api/providers/{name}/embedding-models — the provider's advertised catalog with
     * no capability filtering, for the memory embedding picker (JCLAW-932 follow-up).
     *
     * <p>Deliberately not {@code discover-models}: that path drops embedding, TTS and STT
     * models (JCLAW-183) so a chat agent cannot be bound to one, which removes exactly
     * the models this picker exists to choose from. Against a live ollama serving ten
     * models it returned nine, omitting the embedding model.
     *
     * <p>Always 200, with an empty list when the provider is unreachable — the caller
     * falls back to the stored catalog, and a picker aid should not surface as an error.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ProviderModelsResponse.class)))
    @Operation(summary = "List a provider's models unfiltered, for the embedding picker")
    public static void embeddingModels(String name) {
        requireConfiguredProvider(name);
        var baseUrl = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + BASE_URL_SUFFIX);
        var apiKey = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + API_KEY_SUFFIX);
        var refs = ModelDiscoveryService.listAllModelIds(Strings.trimTrailingSlash(baseUrl), apiKey).stream()
                .map(id -> new ModelRef(id, deriveName(id)))
                .toList();
        renderJSON(gson.toJson(new ProviderModelsResponse(name, refs, refs.size())));
    }

    /**
     * POST /api/providers/{name}/embedding-probe — ask the provider to embed a throwaway
     * string with {@code model}, and report whether it worked and at what dimension.
     *
     * <p>JCLAW-931: this is the only authoritative answer available. {@code ModelInfo}
     * carries no embedding flag and no dimension, and neither OpenAI's nor LM Studio's
     * {@code /v1/models} marks which models serve embeddings — so calling the endpoint is
     * what distinguishes an embedding model from a chat model, and the returned vector's
     * length is what the dimension actually is rather than what someone typed.
     *
     * <p>Read-only with respect to memory: it calls the provider directly, so nothing is
     * written to the store, the Lucene index, or the store's embedding cache.
     */
    @ApiResponse(responseCode = "200")
    @Operation(summary = "Check whether a model serves embeddings, and at what dimension")
    public static void embeddingProbe(String name) {
        requireConfiguredProvider(name);
        var body = JsonBodyReader.readJsonBody();
        var model = body == null ? "" : JsonArgs.optString(body, "model", "");
        if (model.isBlank()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "model is required");
        }
        // JCLAW-939: the probe is what accepts a model for vector memory, so a remote
        // provider has to fail here too — otherwise the panel reports a usable model that
        // the save would then reject. After existence and argument checks, so an unknown
        // provider still reads as 404 rather than as a policy refusal.
        if (!ProviderLocality.isLocal(name)) {
            ApiResponses.error(403, "forbidden",
                    "Provider '%s' is not local. Memory embeddings must use a provider on this "
                            .formatted(name) + "machine so memory text never leaves it.");
        }
        var provider = ProviderRegistry.get(name);
        if (provider == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND,
                    "Provider '%s' is not available".formatted(name));
        }
        try {
            var result = provider.embeddingsDetailed(model, EMBEDDING_PROBE_INPUT, null);
            var vector = result.vector();
            if (vector == null || vector.length == 0) {
                renderJSON(gson.toJson(new EmbeddingProbeResponse(name, model, false, 0,
                        "Provider returned no embedding for this model")));
            }
            if (substituted(model, result.servedModel())) {
                renderJSON(gson.toJson(new EmbeddingProbeResponse(name, model, false, 0,
                        "Provider served '%s' instead of '%s' — it ignores the requested model on this endpoint, so this selection would not be honoured"
                                .formatted(result.servedModel(), model))));
            }
            // JCLAW-935: an oversized vector is discarded by LuceneIndexer.upsert with only
            // a warning, taking the document's keyword text with it, so a model accepted
            // here would silently remove memories from every recall path.
            if (!MemoryVectorSettings.dimensionsSupported(vector.length, JpaMemoryStore.isPostgresDialect())) {
                int max = MemoryVectorSettings.maxDimensions(JpaMemoryStore.isPostgresDialect());
                renderJSON(gson.toJson(new EmbeddingProbeResponse(name, model, false, vector.length,
                        "This model returns %d dimensions, above the %d the search index supports — memories embedded with it could not be indexed"
                                .formatted(vector.length, max))));
            }
            renderJSON(gson.toJson(new EmbeddingProbeResponse(name, model, true, vector.length, null)));
        } catch (play.mvc.results.Result r) {
            throw r;   // renderJSON above signals success by throwing — never swallow it
        } catch (Exception e) {
            // The provider's own message is the useful one: a chat-only model reports as
            // such rather than as a generic failure, and a quota or auth problem is named.
            renderJSON(gson.toJson(new EmbeddingProbeResponse(name, model, false, 0, e.getMessage())));
        }
    }

    /** Short and content-free: the probe pays for one embedding call on a metered provider. */
    private static final String EMBEDDING_PROBE_INPUT = "probe";

    /**
     * Whether the provider answered with a different model than the one requested.
     *
     * <p>Verified against LM Studio 2026-08-03: {@code /v1/embeddings} ignores the
     * requested model entirely and serves whichever embedding model is loaded, echoing
     * that name back — a chat model id and an invented one both return 200 with a valid
     * 768-dim vector. Without this check the probe would greenlight any string.
     *
     * <p>Lenient on shape, strict on identity: a provider that merely normalizes an id
     * (dropping a vendor prefix, say) still counts as a match, because one name contains
     * the other. A blank echo means the provider did not say, which cannot be treated as
     * a mismatch. Comparison is case-insensitive.
     */
    private static boolean substituted(String requested, String served) {
        if (served == null || served.isBlank()) return false;
        var a = requested.trim().toLowerCase(java.util.Locale.ROOT);
        var b = served.trim().toLowerCase(java.util.Locale.ROOT);
        return !a.contains(b) && !b.contains(a);
    }

    /**
     * Providers that exist for a harness, not for the operator to choose (JCLAW-936).
     *
     * <p>{@code loadtest-mock} is already declared a reserved name by
     * {@link LoadTestRunner#LOADTEST_PROVIDER} and its config keys are hidden from
     * {@code /api/config}, but this projection was never given the same treatment — so
     * it surfaced in every picker fed by {@code /api/providers}, including the Settings
     * provider list and the memory embedding picker.
     *
     * <p>Filtered by name rather than by {@code provider.<name>.enabled}: the harness
     * flips that flag to true for the duration of a run, so an enabled-based rule would
     * make the entry appear precisely while a load test is in flight. It is never an
     * operator choice in either state.
     *
     * <p>{@code ProviderRegistry} deliberately still sees it — the harness resolves the
     * provider through the registry, so hiding it there would break the load test rather
     * than tidy the UI.
     */
    private static boolean isInternalProvider(String name) {
        return LoadTestRunner.LOADTEST_PROVIDER.equalsIgnoreCase(name);
    }

    /** 404s unless {@code name} is a configured provider (has a base URL). */
    private static void requireConfiguredProvider(String name) {
        var baseUrl = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + BASE_URL_SUFFIX);
        if (baseUrl == null || baseUrl.isBlank()) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "Provider '%s' is not configured".formatted(name));
        }
    }

    private static JsonArray parseModelsArray(String raw) {
        if (raw == null || raw.isBlank()) return new JsonArray();
        try {
            var el = JsonParser.parseString(raw);
            return el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
        } catch (Exception _) {
            return new JsonArray();
        }
    }

    /**
     * Build the saved-model JSON object, mirroring the frontend's
     * {@code modelFormToSaved}: include the capability/context fields, enforce
     * {@code alwaysThinks ⇒ supportsThinking}, and omit any price left unset
     * ({@code < 0}) so it doesn't reach the cost-computation fallbacks.
     */
    private static JsonObject buildModelObject(JsonObject body, String id, String name) {
        var m = new JsonObject();
        m.addProperty("id", id);
        m.addProperty("name", name);
        m.addProperty("contextWindow", JsonArgs.optInt(body, "contextWindow", 0));
        m.addProperty("maxTokens", JsonArgs.optInt(body, "maxTokens", 0));
        boolean thinking = JsonArgs.optBool(body, "supportsThinking");
        m.addProperty("supportsThinking", thinking);
        if (thinking && JsonArgs.optBool(body, "alwaysThinks")) m.addProperty("alwaysThinks", true);
        m.addProperty(SUPPORTS_VISION, JsonArgs.optBool(body, SUPPORTS_VISION));
        m.addProperty("supportsAudio", JsonArgs.optBool(body, "supportsAudio"));
        addPriceIfSet(m, body, "promptPrice");
        addPriceIfSet(m, body, "completionPrice");
        addPriceIfSet(m, body, "cachedReadPrice");
        addPriceIfSet(m, body, "cacheWritePrice");
        return m;
    }

    private static void addPriceIfSet(JsonObject out, JsonObject body, String key) {
        double v = optPrice(body, key);
        if (v >= 0) out.addProperty(key, v);
    }

    private static String deriveName(String id) {
        return id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
    }

    private static double optPrice(JsonObject body, String key) {
        try {
            return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsDouble() : -1;
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /**
     * POST /api/providers/refresh-prices — manual trigger for the LiteLLM
     * price refresh. Calls {@link PricingRefreshService#refresh()}
     * synchronously so the operator gets immediate feedback rather than
     * waiting for the nightly job. Honors the same
     * {@code pricing.refresh.enabled} toggle as the scheduled job — when
     * the toggle is off the response indicates skipped status so the
     * Settings UI can surface "enable the toggle first" rather than
     * silently appearing to do nothing.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RefreshPricesResponse.class)))
    @Operation(summary = "Manually refresh LiteLLM model prices (synchronous)")
    public static void refreshPrices() {
        var result = PricingRefreshService.refresh();
        renderJSON(gson.toJson(new RefreshPricesResponse(
                result.skipped(),
                result.providersScanned(),
                result.modelsUpdated(),
                result.warnings())));
    }
}
