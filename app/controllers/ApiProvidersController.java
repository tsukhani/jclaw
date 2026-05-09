package controllers;

import com.google.gson.Gson;
import play.mvc.Controller;
import play.mvc.With;

import static utils.GsonHolder.INSTANCE;
import services.ConfigService;
import services.ModelDiscoveryService;
import services.ModelDiscoveryService.DiscoveryResult;
import services.PricingRefreshService;

import java.util.Map;

/**
 * Provider management endpoints — model discovery from provider APIs.
 */
@With(AuthCheck.class)
public class ApiProvidersController extends Controller {

    private static final Gson gson = INSTANCE;

    /**
     * POST /api/providers/{name}/discover-models
     * Fetches the model catalog from the provider's /models endpoint.
     * Returns normalized model info including auto-detected capabilities.
     */
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
                    renderJSON(gson.toJson(Map.of("models", ok.models(), "count", ok.models().size())));
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
    public static void refreshPrices() {
        var result = PricingRefreshService.refresh();
        renderJSON(gson.toJson(Map.of(
                "skipped", result.skipped(),
                "providersScanned", result.providersScanned(),
                "modelsUpdated", result.modelsUpdated(),
                "warnings", result.warnings()
        )));
    }
}
