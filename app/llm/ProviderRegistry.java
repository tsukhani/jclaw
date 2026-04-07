package llm;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import llm.LlmTypes.*;
import services.ConfigService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads LLM provider configurations from the Config database table.
 * Provider configs are stored as:
 *   provider.{name}.baseUrl
 *   provider.{name}.apiKey
 *   provider.{name}.models  (JSON array)
 */
public class ProviderRegistry {

    private static final Gson gson = new Gson();
    private static volatile Map<String, ProviderConfig> cache = Map.of();
    private static volatile long lastRefresh = 0;
    private static final long REFRESH_INTERVAL_MS = 60_000;
    private static final Object refreshLock = new Object();

    public static ProviderConfig get(String name) {
        refreshIfNeeded();
        return cache.get(name);
    }

    public static List<ProviderConfig> listAll() {
        refreshIfNeeded();
        return new ArrayList<>(cache.values());
    }

    public static ProviderConfig getPrimary() {
        var providers = listAll();
        return providers.isEmpty() ? null : providers.getFirst();
    }

    public static ProviderConfig getSecondary() {
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
        var newCache = new java.util.HashMap<String, ProviderConfig>();
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
                } catch (Exception e) {
                    // Skip malformed model JSON
                }
            }

            newCache.put(name, new ProviderConfig(name, baseUrl, apiKey, models));
        }
        cache = Map.copyOf(newCache);
        lastRefresh = System.currentTimeMillis();
    }
}
