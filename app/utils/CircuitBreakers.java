package utils;

import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of named {@link CircuitBreaker}s, so a guarded subsystem
 * that is discovered at runtime — an LLM provider, an MCP server — gets one
 * breaker per name without every call site holding a static field, and so an ops
 * endpoint has something to enumerate.
 *
 * <p>Names are the caller's namespace and must not collide across subsystems;
 * prefix them ({@code "llm:openai"}, {@code "mcp:github"}). {@code config} is
 * applied only when the breaker is first created — a later {@link #get} with
 * different tuning returns the existing breaker unchanged, so tuning belongs
 * wherever the name is first minted.
 *
 * <p>The map is the only shared mutable state here and is a
 * {@link ConcurrentHashMap}. play1 runs test classes concurrently in one JVM, so
 * a test must register under a name unique to itself and {@link #remove} it
 * afterwards rather than clearing the registry.
 */
public final class CircuitBreakers {

    private static final ConcurrentHashMap<String, CircuitBreaker> BREAKERS = new ConcurrentHashMap<>();

    private CircuitBreakers() {}

    /** The breaker registered under {@code name}, created from {@code config} if there is none yet. */
    public static CircuitBreaker get(String name, CircuitBreaker.Config config) {
        return BREAKERS.computeIfAbsent(name, ignored -> new CircuitBreaker(config));
    }

    public static Optional<CircuitBreaker> find(String name) {
        return Optional.ofNullable(BREAKERS.get(name));
    }

    /** @return {@code true} if a breaker was registered under {@code name} and has now been dropped. */
    public static boolean remove(String name) {
        return BREAKERS.remove(name) != null;
    }

    /** Every registered breaker with its current state and window counters, name-ordered. */
    public static SortedMap<String, CircuitBreaker.Stats> snapshot() {
        var out = new TreeMap<String, CircuitBreaker.Stats>();
        BREAKERS.forEach((name, breaker) -> out.put(name, breaker.stats()));
        return out;
    }
}
