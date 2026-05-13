package services;

import models.Agent;
import models.Config;
import play.cache.CacheConfig;
import play.cache.Caches;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class ConfigService {

    private ConfigService() {}

    // The cache stores Optional<String> rather than String so we can distinguish
    // "key absent in DB" (empty Optional, cached) from "key not yet fetched"
    // (cache miss, triggers a DB lookup). The previous hand-rolled cache
    // achieved the same by storing String|null in CachedValue, but the typed
    // Caches.get(key, loader) returns null for a null loader result instead
    // of caching the absence — wrapping in Optional preserves negative caching.
    private static final play.cache.Cache<String, Optional<String>> cache = Caches.named(
            "config",
            CacheConfig.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(60))
                    .build());

    public static String get(String key) {
        // get(key, loader) provides single-flight semantics — concurrent misses
        // for the same key invoke the loader at most once, replacing the prior
        // hand-rolled merge()-based reconciliation.
        return cache.get(key, k -> {
            var config = Tx.run(() -> Config.findByKey(k));
            return Optional.ofNullable(config != null ? config.value : null);
        }).orElse(null);
    }

    public static String get(String key, String defaultValue) {
        var value = get(key);
        return value != null ? value : defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        var raw = get(key);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException _) {
            return defaultValue;
        }
    }

    public static void set(String key, String value) {
        Tx.run(() -> Config.upsert(key, value));
        cache.put(key, Optional.ofNullable(value));
    }

    /**
     * Validate, persist, and trigger side effects for a config key/value.
     * Encapsulates the shell-privilege guard, provider sync, and tool re-registration
     * that were previously spread across the controller.
     *
     * @return an error message if the key is rejected, or {@code null} on success
     */
    public static String setWithSideEffects(String key, String value) {
        // Shell exec privileges are restricted to the main agent
        if (key.matches("agent\\..+\\.shell\\.(bypassAllowlist|allowGlobalPaths)")) {
            var agentName = key.split("\\.")[1];
            var agent = Agent.findByName(agentName);
            if (agent == null || !agent.isMain()) {
                return "Shell exec privileges can only be set for the main agent.";
            }
        }

        set(key, value);

        if (key.startsWith("provider.")) {
            AgentService.syncEnabledStates();
        }
        // JCLAW-172: shell.enabled / playwright.enabled are gone — the tools
        // register unconditionally now. Only the loadtest mock provider still
        // toggles a tool registration via this side effect.
        if (key.equals("provider.loadtest-mock.enabled")) {
            jobs.ToolRegistrationJob.registerAll();
        }

        // Live-apply LLM dispatcher cap changes from Settings without
        // requiring a restart. HttpFactories.applyDispatcherConfig reads
        // both keys and pushes them into the live OkHttp dispatcher, so
        // the next outbound LLM call uses the new cap.
        if (key.equals("dispatcher.llm.maxRequestsPerHost")
                || key.equals("dispatcher.llm.maxRequests")) {
            utils.HttpFactories.applyDispatcherConfig();
        }

        // Convenience linkage: when the operator first sets the Ollama Cloud
        // LLM apiKey, mirror that value into the Ollama search provider's
        // apiKey AND flip search.ollama.enabled to true — but ONLY if the
        // search key is currently empty. Both providers authenticate against
        // the same Ollama account, so the usual case is one key serving
        // both surfaces; this saves the operator a redundant paste.
        //
        // Once the search key has any value (operator-set or previously
        // mirrored), this branch becomes a no-op — subsequent rotations of
        // the LLM key don't drag the search key along, preserving the
        // "set once, owned by you" model that operators expect from
        // independent settings.
        if (key.equals("provider.ollama-cloud.apiKey") && value != null && !value.isBlank()) {
            String existingSearchKey = get("search.ollama.apiKey");
            if (existingSearchKey == null || existingSearchKey.isBlank()) {
                set("search.ollama.apiKey", value);
                set("search.ollama.enabled", "true");
                services.EventLogger.info("config",
                        "Mirrored ollama-cloud LLM apiKey into search.ollama.apiKey "
                                + "and enabled web search (search key was empty)");
            }
        }

        return null;
    }

    public static void delete(String key) {
        Tx.run(() -> {
            var config = Config.findByKey(key);
            if (config != null) {
                config.delete();
            }
        });
        cache.invalidate(key);
    }

    /**
     * Delete a config key and trigger the same side effects as {@link #setWithSideEffects}.
     * Mirrors save-with-side-effects so controller delete and save stay in sync.
     */
    public static void deleteWithSideEffects(String key) {
        delete(key);
        if (key.startsWith("provider.")) {
            AgentService.syncEnabledStates();
        }
        // JCLAW-172: see setWithSideEffects for the rationale — only the
        // loadtest mock still triggers a tool re-registration on toggle.
        if (key.equals("provider.loadtest-mock.enabled")) {
            jobs.ToolRegistrationJob.registerAll();
        }
    }

    public static List<Config> listAll() {
        return Tx.run(() -> Config.<Config>findAll());
    }

    public static void clearCache() {
        cache.invalidateAll();
    }

    private static final Set<String> SENSITIVE_PATTERNS = Set.of(
            "key", "secret", "password", "token"
    );

    public static boolean isSensitive(String key) {
        var lower = key.toLowerCase();
        return SENSITIVE_PATTERNS.stream().anyMatch(lower::contains);
    }

    public static String maskValue(String key, String value) {
        if (value == null) return null;
        if (isSensitive(key) && value.length() > 4) {
            return value.substring(0, 4) + "****";
        }
        return value;
    }
}
