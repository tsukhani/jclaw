package llm;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import llm.LlmTypes.*;
import services.ConfigService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads LLM provider configurations from the Config database table and
 * creates the appropriate {@link LlmProvider} subclass for each.
 *
 * Provider configs are stored as:
 *   provider.{name}.baseUrl
 *   provider.{name}.apiKey
 *   provider.{name}.models  (JSON array)
 *
 * Provider type is resolved by name:
 *   "openrouter" → {@link OpenRouterProvider}
 *   "ollama*"    → {@link OllamaProvider}
 *   default      → {@link OpenAiProvider}
 */
public class ProviderRegistry {

    private static final Gson gson = new Gson();
    private static volatile Map<String, LlmProvider> cache = Map.of();
    private static volatile long lastRefresh = 0;
    private static final long REFRESH_INTERVAL_MS = 60_000;
    private static final Object refreshLock = new Object();

    public static LlmProvider get(String name) {
        refreshIfNeeded();
        return cache.get(name);
    }

    public static List<LlmProvider> listAll() {
        refreshIfNeeded();
        return new ArrayList<>(cache.values());
    }

    public static LlmProvider getPrimary() {
        var providers = listAll();
        return providers.isEmpty() ? null : providers.getFirst();
    }

    public static LlmProvider getSecondary() {
        var providers = listAll();
        return providers.size() > 1 ? providers.get(1) : null;
    }

    private static void refreshIfNeeded() {
        if (System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MS) {
            synchronized (refreshLock) {
                if (System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MS) {
                    refresh();
                }
            }
        }
    }

    public static void refresh() {
        synchronized (refreshLock) {
            var newCache = new java.util.HashMap<String, LlmProvider>();
            var allConfigs = ConfigService.listAll();
            var providerNames = allConfigs.stream()
                    .map(c -> c.key)
                    .filter(k -> k.startsWith("provider.") && k.endsWith(".baseUrl"))
                    .map(k -> k.substring("provider.".length(), k.lastIndexOf(".")))
                    .distinct()
                    .toList();

            for (var name : providerNames) {
                var baseUrl = ConfigService.get("provider." + name + ".baseUrl");
                var apiKey = ConfigService.get("provider." + name + ".apiKey");
                if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) continue;

                var modelsJson = ConfigService.get("provider." + name + ".models");
                List<ModelInfo> models = List.of();
                if (modelsJson != null && !modelsJson.isBlank()) {
                    try {
                        models = gson.fromJson(modelsJson, new TypeToken<List<ModelInfo>>() {}.getType());
                    } catch (Exception _) {
                        // Skip malformed model JSON
                    }
                }

                var config = new ProviderConfig(name, baseUrl, apiKey, models);
                newCache.put(name, createProvider(name, config));
            }
            cache = Map.copyOf(newCache);
            lastRefresh = System.currentTimeMillis();
        }
    }

    /**
     * Factory method: creates the right LlmProvider subclass based on provider name.
     */
    private static LlmProvider createProvider(String name, ProviderConfig config) {
        var lowerName = name.toLowerCase();
        if (lowerName.contains("openrouter")) {
            return new OpenRouterProvider(config);
        }
        if (lowerName.contains("ollama")) {
            return new OllamaProvider(config);
        }
        // Default: standard OpenAI-compatible
        return new OpenAiProvider(config);
    }
}
