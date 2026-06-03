package controllers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import llm.PaymentModality;
import llm.ProviderRegistry;
import play.mvc.Controller;
import play.mvc.With;

import static utils.GsonHolder.INSTANCE;
import services.ConfigService;
import services.ModelDiscoveryService;
import services.ModelDiscoveryService.DiscoveryResult;
import services.PricingRefreshService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provider management endpoints — model discovery from provider APIs.
 */
@With(AuthCheck.class)
public class ApiProvidersController extends Controller {

    private static final Gson gson = INSTANCE;

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

    /**
     * GET /api/providers — billing-shape projection of each configured
     * provider. Returns name, selected modality, subscription monthly
     * price, and the supported-modality set so the Settings UI knows
     * which choices to offer and the Chat Cost dashboard knows how to
     * partition spend.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProviderInfo.class))))
    @ChatSafe(summary = "List configured LLM providers")
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
    @ChatSafe(summary = "Discover a provider's available models from its live API")
    public static void discoverModels(String name) {
        var baseUrl = ConfigService.get("provider." + name + ".baseUrl");
        var apiKey = ConfigService.get("provider." + name + ".apiKey");

        if (baseUrl == null || baseUrl.isBlank()) {
            error(400, "Provider '%s' has no base URL configured".formatted(name));
        }
        if (apiKey == null || apiKey.isBlank()) {
            error(400, "Provider '%s' has no API key configured".formatted(name));
        }

        var result = ModelDiscoveryService.discover(name, baseUrl, apiKey);
        switch (result) {
            case DiscoveryResult.Ok(var models) ->
                    renderJSON(gson.toJson(new DiscoverModelsResponse(models, models.size())));
            case DiscoveryResult.Error(var statusCode, var message) ->
                    error(statusCode, message);
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
    @ChatSafe(summary = "List a provider's configured models (id + display name)")
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
    @ChatSafe(summary = "Add a model to a provider by id",
            body = "id (required); optional name, contextWindow, maxTokens, supportsThinking, supportsVision, supportsAudio")
    public static void addModel(String name) {
        requireConfiguredProvider(name);

        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("id") || body.get("id").isJsonNull()
                || body.get("id").getAsString().isBlank()) {
            error(400, "Field 'id' is required");
        }
        var id = body.get("id").getAsString().trim();

        var key = modelsKey(name);
        var models = parseModelsArray(ConfigService.get(key));
        for (var el : models) {
            if (el.isJsonObject() && id.equals(str(el.getAsJsonObject(), "id"))) {
                error(409, "Model '%s' already exists for provider '%s'".formatted(id, name));
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
        return "provider." + name + ".models";
    }

    /** 404s unless {@code name} is a configured provider (has a base URL). */
    private static void requireConfiguredProvider(String name) {
        var baseUrl = ConfigService.get("provider." + name + ".baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            error(404, "Provider '%s' is not configured".formatted(name));
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
        m.addProperty("supportsVision", optBool(body, "supportsVision"));
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
    public static void refreshPrices() {
        var result = PricingRefreshService.refresh();
        renderJSON(gson.toJson(new RefreshPricesResponse(
                result.skipped(),
                result.providersScanned(),
                result.modelsUpdated(),
                result.warnings())));
    }
}
