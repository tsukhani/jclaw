package services;

import models.Config;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ConfigService {

    private static final long CACHE_TTL_MS = 60_000;

    private record CachedValue(String value, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private static final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    public static String get(String key) {
        // Fast path: read without any lock.
        var cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        // Slow path: do the DB call outside any CHM lock to avoid holding a
        // per-bucket lock during I/O (which serializes unrelated keys in the
        // same bucket and can cascade into thread starvation under load).
        // Two threads may both execute the query for the same key — that is
        // acceptable (idempotent read) and far cheaper than the contention.
        var config = Tx.run(() -> Config.findByKey(key));
        var value = config != null ? config.value : null;
        var fresh = new CachedValue(value, System.currentTimeMillis() + CACHE_TTL_MS);
        // Install only if no other thread refreshed with a newer value while
        // we were querying. merge() holds the bucket lock briefly (no I/O).
        var entry = cache.merge(key, fresh, (existing, candidate) ->
                existing.isExpired() ? candidate : existing);
        return entry.value();
    }

    public static String get(String key, String defaultValue) {
        var value = get(key);
        return value != null ? value : defaultValue;
    }

    public static void set(String key, String value) {
        Tx.run(() -> Config.upsert(key, value));
        cache.put(key, new CachedValue(value, System.currentTimeMillis() + CACHE_TTL_MS));
    }

    public static void delete(String key) {
        Tx.run(() -> {
            var config = Config.findByKey(key);
            if (config != null) {
                config.delete();
            }
        });
        cache.remove(key);
    }

    public static List<Config> listAll() {
        return Tx.run(() -> Config.<Config>findAll());
    }

    public static void clearCache() {
        cache.clear();
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
