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
import llm.ProviderRegistry;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.LocalProviderProbeSupport;
import services.ModelDiscoveryService;
import services.ModelDiscoveryService.DiscoveryResult;
import services.PricingRefreshService;
import services.video.VideoInterpretationClient;
import services.video.VideoInterpretationRouter;
import utils.ApiResponses;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static utils.GsonHolder.INSTANCE;

/**
 * Provider management endpoints — model discovery from provider APIs.
 */
@With(AuthCheck.class)
public class ApiProvidersController extends Controller {

    private static final Gson gson = INSTANCE;
    private static final String PROVIDER_CONFIG_PREFIX = "provider.";
    private static final String BASE_URL_SUFFIX = ".baseUrl";
    private static final String SUPPORTS_VISION = "supportsVision";

    public record DiscoverModelsResponse(List<Map<String, Object>> models, int count) {}

    public record RefreshPricesResponse(boolean skipped, int providersScanned, int modelsUpdated, List<String> warnings) {}

    public record ProviderInfo(String name,
                               String paymentModality,
                               BigDecimal subscriptionMonthlyUsd,
                               List<String> supportedModalities) {}

    /** A model's canonical id paired with its human-readable display name. */
    public record ModelRef(String id, String name) {}

    public record ProviderModelsResponse(String provider, List<ModelRef> models, int count) {}

    public record AddModelResponse(String provider, ModelRef model, int count) {}

    /** Live reachability of a (typically local) provider's OpenAI-compatible endpoint. */
    public record ReachableResponse(String provider, boolean reachable, int modelCount, String reason) {}

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
                            supported);
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
        var apiKey = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + ".apiKey");

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
        var r = LocalProviderProbeSupport.probeModels(trimTrailingSlash(baseUrl), name);
        renderJSON(gson.toJson(new ReachableResponse(name, r.available(), r.modelCount(), r.reason())));
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
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
        var apiKey = ConfigService.get(PROVIDER_CONFIG_PREFIX + name + ".apiKey");
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
            var id = str(obj, "id");
            if (id.isBlank()) continue;
            var displayName = str(obj, "name");
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
            if (el.isJsonObject() && id.equals(str(el.getAsJsonObject(), "id"))) {
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
        m.addProperty("contextWindow", optInt(body, "contextWindow"));
        m.addProperty("maxTokens", optInt(body, "maxTokens"));
        boolean thinking = optBool(body, "supportsThinking");
        m.addProperty("supportsThinking", thinking);
        if (thinking && optBool(body, "alwaysThinks")) m.addProperty("alwaysThinks", true);
        m.addProperty(SUPPORTS_VISION, optBool(body, SUPPORTS_VISION));
        m.addProperty("supportsAudio", optBool(body, "supportsAudio"));
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

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static String deriveName(String id) {
        return id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
    }

    private static int optInt(JsonObject body, String key) {
        try {
            return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsInt() : 0;
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static boolean optBool(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() && body.get(key).getAsBoolean();
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
