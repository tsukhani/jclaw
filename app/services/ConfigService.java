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

    private static final CachedValue NEGATIVE_HIT = new CachedValue(null, 0);

    public static String get(String key) {
        var cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value();
        }
        // Use compute() to restrict to one DB call per key per TTL window.
        // Without this, N concurrent virtual threads all see isExpired()=true
        // and each independently call Config.findByKey — a cache stampede that
        // multiplies DB reads by the concurrency level on every 60-second expiry.
        var entry = cache.compute(key, (k, existing) -> {
            // Re-check inside the lock: another thread may have refreshed while we waited.
            if (existing != null && !existing.isExpired()) return existing;
            var config = Tx.run(() -> Config.findByKey(k));
            var value = config != null ? config.value : null;
            return new CachedValue(value, System.currentTimeMillis() + CACHE_TTL_MS);
        });
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
