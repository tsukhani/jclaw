package utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Size-bounded LRU cache for compiled regex patterns.
 *
 * Fixes the CHM-clear-inside-computeIfAbsent anti-pattern previously used
 * in ApiConversationsController: mutating a ConcurrentHashMap from inside
 * its own remapping function has undefined semantics, and a full-cache
 * clear on overflow thrashes every call beyond the cap.
 */
public final class BoundedPatternCache {

    private final int cap;
    private final Map<String, Pattern> store;

    public BoundedPatternCache(int cap) {
        if (cap <= 0) throw new IllegalArgumentException("cap must be positive");
        this.cap = cap;
        this.store = Collections.synchronizedMap(new LinkedHashMap<>(cap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                return size() > BoundedPatternCache.this.cap;
            }
        });
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
