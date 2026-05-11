package llm;

import com.google.gson.reflect.TypeToken;
import llm.LlmTypes.*;
import services.ConfigService;

import java.math.BigDecimal;
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
 * Provider type is resolved by name (substring match, case-insensitive):
 *   "openrouter" → {@link OpenRouterProvider}
 *   "ollama"     → {@link OllamaProvider}  (covers ollama-cloud, ollama-local)
 *   "openai"     → {@link OpenAiProvider}
 *   anything else → {@link OpenAiProvider}  (lm-studio, groq, azure, …)
 */
public class ProviderRegistry {

    private static final com.google.gson.Gson gson = utils.GsonHolder.INSTANCE;
    private static volatile Map<String, LlmProvider> cache = Map.of();
    private static volatile long lastRefresh;
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
            // refreshInner() reads from the Config table, which requires a JPA
            // transaction. Wrap in Tx.run so callers (like streaming prologue code)
            // can invoke get()/getPrimary() without holding an ambient transaction
            // just in case the 60s cache is stale. Tx.run short-circuits when
            // already inside a tx, so we don't pay twice.
            services.Tx.run(ProviderRegistry::refreshInner);
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

            // JCLAW-280: payment modality + monthly subscription price.
            // Defaults derive from the provider's supported-modality set,
            // so a fresh-install Ollama-Cloud row is SUBSCRIPTION without
            // any explicit config row; OpenAI defaults to PER_TOKEN; and
            // free-at-point-of-use providers (ollama-local, lm-studio)
            // get an empty supported set so the registry leaves modality
            // at its safe PER_TOKEN default — the cost path treats them
            // as free-tier regardless.
            var modalityRaw = configMap.get("provider." + name + ".paymentModality");
            var modality = PaymentModality.parseOrDefault(modalityRaw, name);
            var subscriptionRaw = configMap.get("provider." + name + ".subscriptionMonthlyUsd");
            BigDecimal subscriptionMonthly = BigDecimal.ZERO;
            if (subscriptionRaw != null && !subscriptionRaw.isBlank()) {
                try {
                    subscriptionMonthly = new BigDecimal(subscriptionRaw.trim());
                    if (subscriptionMonthly.signum() < 0) subscriptionMonthly = BigDecimal.ZERO;
                } catch (NumberFormatException _) {
                    // Malformed price — fall back to zero rather than refuse the provider.
                }
            }

            var config = new ProviderConfig(name, baseUrl, apiKey, models, modality, subscriptionMonthly);
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
