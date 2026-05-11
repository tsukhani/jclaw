package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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

    /**
     * GET /api/providers — billing-shape projection of each configured
     * provider. Returns name, selected modality, subscription monthly
     * price, and the supported-modality set so the Settings UI knows
     * which choices to offer and the Chat Cost dashboard knows how to
     * partition spend.
     */
    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProviderInfo.class))))
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
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DiscoverModelsResponse.class)))
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
            case DiscoveryResult.Ok ok ->
                    renderJSON(gson.toJson(new DiscoverModelsResponse(ok.models(), ok.models().size())));
            case DiscoveryResult.Error err ->
                    error(err.statusCode(), err.message());
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
