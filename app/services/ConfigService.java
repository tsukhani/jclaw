package services;

import jakarta.persistence.PersistenceException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jobs.ToolRegistrationJob;
import models.Agent;
import models.Config;
import org.hibernate.Session;
import play.cache.Cache;
import play.cache.CacheConfig;
import play.cache.Caches;
import play.db.jpa.JPA;
import utils.HttpFactories;

import java.time.Duration;
import java.time.ZoneId;
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
    private static final Cache<String, Optional<String>> cache = Caches.named(
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

    public static long getLong(String key, long defaultValue) {
        var raw = get(key);
        if (raw == null) return defaultValue;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException _) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        var raw = get(key);
        if (raw == null) return defaultValue;
        return Boolean.parseBoolean(raw.trim());
    }

    public static double getDouble(String key, double defaultValue) {
        var raw = get(key);
        if (raw == null) return defaultValue;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException _) {
            return defaultValue;
        }
    }

    public static void set(String key, String value) {
        Tx.run(() -> Config.upsert(key, value));
        // Read-your-writes: seed the cache immediately so a later reader on a
        // different connection sees the value before the surrounding transaction
        // commits — the FunctionalTest suite and real request flows depend on this.
        cache.put(key, Optional.ofNullable(value));
        // JCLAW-832: when set() joined an ambient (outer) transaction the upsert is
        // not durable yet. If that transaction rolls back, drop the eagerly-cached
        // entry so it can't serve a value the DB never kept for the 60s TTL. On the
        // owned-transaction path Tx.run already committed, so the entry stands.
        if (JPA.isInsideTransaction()) {
            scheduleRollbackEviction(JPA.em().unwrap(Session.class), key);
        }
    }

    /**
     * Register an {@code afterCompletion} synchronization on {@code session}'s
     * transaction that evicts {@code key} from the cache iff the transaction rolls
     * back; on commit the eagerly-cached value stands. One synchronization per
     * {@code set()}-in-a-transaction — config writes are rarely batched, so this is
     * simpler and more robust than deduping through thread-local state (which would
     * be fragile under the concurrent test runner). Visible (public) for
     * {@code ConfigServiceTest} in the default package, which exercises it against a
     * fresh EntityManager without disturbing the UnitTest harness's ambient JPA context.
     */
    public static void scheduleRollbackEviction(Session session, String key) {
        session.getTransaction().registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                // no-op: the evict/keep decision is made after completion
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_ROLLEDBACK) {
                    cache.invalidate(key);
                }
            }
        });
    }

    /**
     * Atomically create {@code key} with {@code value} iff no row exists yet.
     * Returns {@code true} when this call inserted the row, {@code false} when a
     * row was already present. Unlike {@link #set} (last-writer-wins via upsert),
     * this closes a check-then-write race: a losing concurrent insert trips the
     * {@code config_key} unique constraint and surfaces as {@code false}, not an
     * overwrite. Used by the first-install credential bootstrap (JCLAW-782).
     */
    public static boolean setIfAbsent(String key, String value) {
        boolean inserted = Tx.run(() -> {
            if (Config.findByKey(key) != null) return false;
            try {
                var config = new Config();
                config.key = key;
                config.value = value;
                config.save(); // flushes; the unique index rejects a concurrent insert
                return true;
            } catch (PersistenceException _) {
                // A concurrent setIfAbsent inserted the same key first. The flush
                // marks the transaction rollback-only, so Tx.run/withTransaction
                // rolls it back cleanly — report the key as already present.
                return false;
            }
        });
        if (inserted) {
            // Mirror set(): seed the cache for read-your-writes, evicting the entry
            // if the surrounding (not-yet-committed) transaction rolls back (JCLAW-832).
            cache.put(key, Optional.ofNullable(value));
            if (JPA.isInsideTransaction()) {
                scheduleRollbackEviction(JPA.em().unwrap(Session.class), key);
            }
        }
        return inserted;
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

        // Operator timezone must be a valid IANA zone id. Reject typos here so
        // the system prompt never injects a bad zone — TimezoneResolver.appZone
        // would silently fall back to the server default, hiding the mistake.
        if (key.equals(TimezoneResolver.APP_CONFIG_KEY)) {
            try {
                ZoneId.of(value == null ? "" : value.trim());
            } catch (Exception _) {
                return "Invalid IANA timezone id '" + value
                        + "'. Use a value from GET /api/timezones (e.g. 'Asia/Kuala_Lumpur').";
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
            ToolRegistrationJob.registerAll();
        }

        // Live-apply LLM dispatcher cap changes from Settings without
        // requiring a restart. HttpFactories.applyDispatcherConfig reads
        // both keys and pushes them into the live OkHttp dispatcher, so
        // the next outbound LLM call uses the new cap.
        if (key.equals("dispatcher.llm.maxRequestsPerHost")
                || key.equals("dispatcher.llm.maxRequests")) {
            HttpFactories.applyDispatcherConfig();
        }

        // Per-logger level overrides apply live: the next log statement on the
        // affected logger uses the new level. The override is layered on top of
        // the file config, so it wins. See LoggerLevelService.
        if (key.startsWith(LoggerLevelService.PREFIX)) {
            LoggerLevelService.apply(key.substring(LoggerLevelService.PREFIX.length()), value);
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
                EventLogger.info("config",
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
            ToolRegistrationJob.registerAll();
        }
        // Deleting a logger override reverts that logger to its inherited level
        // (root → its captured file baseline). See LoggerLevelService.
        if (key.startsWith(LoggerLevelService.PREFIX)) {
            LoggerLevelService.revert(key.substring(LoggerLevelService.PREFIX.length()));
        }
    }

    public static List<Config> listAll() {
        // NB: must stay a lambda, not a method reference — Tx.run is overloaded
        // (Function0<T> vs Runnable) and Config::findAll is ambiguous between
        // them, whereas the explicit lambda resolves cleanly (Sonar S1612 FP).
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
