package utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Size-bounded LRU cache for compiled regex patterns.
 *
 * <p>The backing {@link LinkedHashMap} is unguarded on its own; the public
 * methods here synchronize on the cache's monitor for atomic LRU updates.
 * The previous implementation also wrapped the map in {@link
 * java.util.Collections#synchronizedMap}, which doubled the locking on
 * every access without making {@code computeIfAbsent} any more atomic
 * (the wrapper does not lift the remapping function into the lock).
 */
public final class BoundedPatternCache {

    private final int cap;
    private final Map<String, Pattern> store;

    public BoundedPatternCache(int cap) {
        if (cap <= 0) throw new IllegalArgumentException("cap must be positive");
        this.cap = cap;
        this.store = new LinkedHashMap<>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                return size() > BoundedPatternCache.this.cap;
            }
        };
    }

    public Pattern computeIfAbsent(String key, Function<String, Pattern> compiler) {
        synchronized (store) {
            return store.computeIfAbsent(key, compiler);
        }
    }

    public int size() {
        synchronized (store) {
            return store.size();
        }
    }
}
