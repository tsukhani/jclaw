package controllers;

import com.google.gson.Gson;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.ModelDiscoveryService;
import services.ModelDiscoveryService.DiscoveryResult;

import java.util.Map;

/**
 * Provider management endpoints — model discovery from provider APIs.
 */
@With(AuthCheck.class)
public class ApiProvidersController extends Controller {

    private static final Gson gson = new Gson();

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
}
