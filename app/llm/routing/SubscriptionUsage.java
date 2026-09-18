package llm.routing;

import com.google.gson.JsonParser;
import llm.LlmProvider.LlmException;
import llm.LlmTypes.ProviderConfig;
import llm.ProviderRegistry;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import utils.AppClock;
import utils.HttpFactories;
import utils.HttpKeys;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * How much of each prepaid provider's quota is spent (JCLAW-1222), from two sources.
 *
 * <p><b>Proactive.</b> ollama.com answers {@code GET /api/usage} with the fraction used of each
 * quota window — undocumented, but what its own settings page reads. Verified 2026-09-17 on a Max
 * account: {@code {"limits":{"session":{"usage":0.017,…},"weekly":{"usage":0.631,…}}}}, with no reset
 * timestamps. Every {@code limits.*.usage} is read, so a window Ollama adds later counts too. Routing
 * reads the cached value and never waits on the fetch; a refresh starts in the background once the
 * value is a minute old.
 *
 * <p><b>Reactive.</b> A call that fails for exhausted credit marks what failed as unavailable for a
 * while. On a host with a usage source only the model is marked — Ollama answers 402 for a model
 * whose tier the plan does not include while the plan itself has headroom — and the next route
 * re-reads the usage; elsewhere the whole provider is, since a balance is account-wide.
 */
public final class SubscriptionUsage {

    /** @param windows used fraction of each quota window, in [0, 1], keyed by the provider's window name */
    public record Snapshot(Map<String, Double> windows, Instant fetchedAt) {

        public Snapshot {
            windows = Map.copyOf(windows);
        }

        /** The fullest window decides: whichever runs out first stops the provider. */
        public double pressure() {
            return windows.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        }

        public @Nullable String fullestWindow() {
            return windows.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }
    }

    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration MODEL_QUOTA_COOLDOWN = Duration.ofMinutes(60);
    static final Duration PROVIDER_QUOTA_COOLDOWN = Duration.ofMinutes(15);
    private static final long FETCH_TIMEOUT_MS = 5_000;

    private static final Map<String, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<String, Instant> LAST_ATTEMPT = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Instant> UNAVAILABLE_UNTIL = new ConcurrentHashMap<>();

    private SubscriptionUsage() {}

    public static boolean hasUsageSource(ProviderConfig config) {
        var host = host(config.baseUrl());
        return host != null && (host.equals("ollama.com") || host.endsWith(".ollama.com"));
    }

    /** The last usage read for {@code config}, or null when none exists yet. Never blocks. */
    public static @Nullable Snapshot current(ProviderConfig config) {
        var name = config.name();
        if (hasUsageSource(config) && isStale(name) && IN_FLIGHT.add(name)) {
            Thread.ofVirtual().name("router-usage").start(() -> {
                try {
                    refreshNow(config);
                } finally {
                    IN_FLIGHT.remove(name);
                }
            });
        }
        return SNAPSHOTS.get(name);
    }

    /** {@link #current}, but fetching first when the value is missing or stale — for a caller that can wait on it. */
    public static @Nullable Snapshot fresh(ProviderConfig config) {
        if (!hasUsageSource(config) || !isStale(config.name())) return SNAPSHOTS.get(config.name());
        var fetched = refreshNow(config);
        return fetched != null ? fetched : SNAPSHOTS.get(config.name());
    }

    /** Fetch {@code config}'s usage now. Returns the stored snapshot, or null when the source gave nothing usable. */
    public static @Nullable Snapshot refreshNow(ProviderConfig config) {
        var name = config.name();
        LAST_ATTEMPT.put(name, AppClock.now());
        var origin = origin(config.baseUrl());
        if (origin == null) return null;
        var request = new Request.Builder()
                .url(origin + "/api/usage")
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + config.apiKey())
                .get()
                .build();
        var call = HttpFactories.general().newCall(request);
        call.timeout().timeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try (var response = call.execute()) {
            var body = response.body().string();
            if (response.code() != 200) {
                warnOnce(name, "HTTP " + response.code());
                return null;
            }
            var windows = parseWindows(body);
            if (windows.isEmpty()) {
                warnOnce(name, "the response carried no usage windows");
                return null;
            }
            var snapshot = new Snapshot(windows, AppClock.now());
            SNAPSHOTS.put(name, snapshot);
            WARNED.remove(name);
            return snapshot;
        } catch (IOException | RuntimeException e) {
            warnOnce(name, String.valueOf(e.getMessage()));
            return null;
        }
    }

    /** Every {@code limits.<window>.usage} fraction in an {@code /api/usage} body; empty when there are none. */
    public static Map<String, Double> parseWindows(String body) {
        try {
            var root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return Map.of();
            var limits = root.getAsJsonObject().get("limits");
            if (limits == null || !limits.isJsonObject()) return Map.of();
            var out = new LinkedHashMap<String, Double>();
            for (var window : limits.getAsJsonObject().entrySet()) {
                if (!window.getValue().isJsonObject()) continue;
                var usage = window.getValue().getAsJsonObject().get("usage");
                if (usage == null || !usage.isJsonPrimitive() || !usage.getAsJsonPrimitive().isNumber()) continue;
                var fraction = usage.getAsDouble();
                if (Double.isFinite(fraction)) out.put(window.getKey(), Math.clamp(fraction, 0.0, 1.0));
            }
            return out;
        } catch (RuntimeException _) {
            return Map.of();
        }
    }

    /**
     * Learn from a failed call. Only exhausted credit marks anything; a rate limit on a host with a
     * usage source just makes the next route re-read the usage, since a spent session window
     * surfaces as a 429 before the fraction next refreshes.
     */
    public static void noteFailure(Throwable failure) {
        if (!(failure instanceof LlmException e)) return;
        var f = e.failure();
        if (f == null) return;
        var provider = ProviderRegistry.get(f.provider());
        var usageSource = provider != null && hasUsageSource(provider.config());
        switch (f.remedy()) {
            case QUOTA_EXHAUSTED -> {
                var model = f.model();
                if (usageSource && model != null) {
                    markUnavailable(f.provider() + "/" + model, MODEL_QUOTA_COOLDOWN);
                } else {
                    markUnavailable(f.provider(), PROVIDER_QUOTA_COOLDOWN);
                }
                if (usageSource) LAST_ATTEMPT.remove(f.provider());
            }
            case RATE_LIMITED -> {
                if (usageSource) LAST_ATTEMPT.remove(f.provider());
            }
            default -> {
                // Other failures say nothing about credit.
            }
        }
    }

    /** True while a quota failure has marked {@code provider}, or {@code model} on it, unavailable. */
    public static boolean isMarkedUnavailable(String provider, String model) {
        return activeMark(provider) || activeMark(provider + "/" + model);
    }

    public static Map<String, Snapshot> snapshots() {
        return Map.copyOf(SNAPSHOTS);
    }

    /** Active unavailability marks, keyed {@code provider} or {@code provider/model}. */
    public static Map<String, Instant> unavailableMarks() {
        var now = AppClock.now();
        var out = new LinkedHashMap<String, Instant>();
        UNAVAILABLE_UNTIL.forEach((k, until) -> {
            if (until.isAfter(now)) out.put(k, until);
        });
        return out;
    }

    public static void putSnapshotForTest(String provider, Map<String, Double> windows) {
        SNAPSHOTS.put(provider, new Snapshot(windows, AppClock.now()));
        LAST_ATTEMPT.put(provider, AppClock.now());
    }

    public static void clearForTest(String provider) {
        SNAPSHOTS.remove(provider);
        LAST_ATTEMPT.remove(provider);
        WARNED.remove(provider);
        UNAVAILABLE_UNTIL.keySet().removeIf(k -> k.equals(provider) || k.startsWith(provider + "/"));
    }

    private static boolean isStale(String name) {
        var last = LAST_ATTEMPT.get(name);
        return last == null || !last.plus(TTL).isAfter(AppClock.now());
    }

    private static void markUnavailable(String key, Duration cooldown) {
        var until = AppClock.now().plus(cooldown);
        UNAVAILABLE_UNTIL.put(key, until);
        EventLogger.warn("router", "%s is out of credit: not routing to it until %s".formatted(key, until));
    }

    private static boolean activeMark(String key) {
        var until = UNAVAILABLE_UNTIL.get(key);
        if (until == null) return false;
        if (until.isAfter(AppClock.now())) return true;
        UNAVAILABLE_UNTIL.remove(key, until);
        return false;
    }

    private static void warnOnce(String provider, String why) {
        if (WARNED.add(provider)) {
            EventLogger.warn("router", "Could not read usage for provider '%s' (%s); its budget guard is blind until it can"
                    .formatted(provider, why));
        }
    }

    private static @Nullable String host(String baseUrl) {
        try {
            var h = URI.create(baseUrl.trim()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static @Nullable String origin(String baseUrl) {
        try {
            var uri = URI.create(baseUrl.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
