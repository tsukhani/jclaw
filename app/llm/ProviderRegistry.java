package llm;

import com.google.gson.reflect.TypeToken;
import llm.LlmTypes.*;
import services.ConfigService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    private static final com.google.gson.Gson gson = utils.GsonHolder.INSTANCE;
    private static volatile Map<String, LlmProvider> cache = Map.of();
    private static volatile long lastRefresh = 0;
    private static final long REFRESH_INTERVAL_MS = 60_000;
    private static final Object refreshLock = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean(false);

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
            refresh();
        }
    }

    public static void refresh() {
        // Atomic compare-and-set prevents thundering herd — only one thread
        // enters refreshInner(), concurrent callers skip and use the stale cache.
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            refreshInner();
        } finally {
            refreshing.set(false);
        }
    }

    private static void refreshInner() {
        // Snapshot all config in one DB roundtrip — no lock held during IO,
        // so concurrent get() calls are not blocked by the DB read.
        var allConfigs = ConfigService.listAll();
        var configMap = new HashMap<String, String>();
        for (var c : allConfigs) configMap.put(c.key, c.value);

        var providerNames = configMap.keySet().stream()
                .filter(k -> k.startsWith("provider.") && k.endsWith(".baseUrl"))
                .map(k -> k.substring("provider.".length(), k.lastIndexOf(".")))
                .distinct()
                .toList();

        // LinkedHashMap preserves insertion order so getPrimary()/getSecondary() are deterministic.
        var newCache = new LinkedHashMap<String, LlmProvider>();
        for (var name : providerNames) {
            var baseUrl = configMap.get("provider." + name + ".baseUrl");
            var apiKey = configMap.get("provider." + name + ".apiKey");
            if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) continue;

            var modelsJson = configMap.get("provider." + name + ".models");
            List<ModelInfo> models = List.of();
            if (modelsJson != null && !modelsJson.isBlank()) {
                try {
                    models = gson.fromJson(modelsJson, new TypeToken<List<ModelInfo>>() {}.getType());
                } catch (Exception _) {
                    // Skip malformed model JSON
                }
            }

            var config = new ProviderConfig(name, baseUrl, apiKey, models);
            newCache.put(name, LlmProvider.forConfig(config));
        }

        // Only hold the lock for the atomic pointer swap
        synchronized (refreshLock) {
            cache = Collections.unmodifiableMap(newCache);
            lastRefresh = System.currentTimeMillis();
        }
    }

    // Provider instantiation delegated to LlmProvider.forConfig() — see Fix 4 (OCP).
}
