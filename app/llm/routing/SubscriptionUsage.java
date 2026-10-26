package llm.routing;

import com.google.gson.JsonObject;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * How much of each prepaid provider's quota is spent (JCLAW-1222), from two sources.
 *
 * <p><b>Proactive.</b> ollama.com answers {@code GET /api/usage}, undocumented, with what its own
 * settings page reads. On 2026-09-17 that was the fraction used of each quota window,
 * {@code {"limits":{"session":{"usage":0.017,…},"weekly":{"usage":0.631,…}}}}; on 2026-10-15 it was
 * request counts only, {@code {"range":"7d","totals":{"request_count":8341},"buckets":[…]}}, and
 * {@code range} takes {@code 24h}, {@code 7d} or {@code 30d} (JCLAW-1449). Every {@code limits.*.usage}
 * is read whenever a body carries it, so the budget guard returns by itself if the fractions do.
 * Routing reads the cached value and never waits on the fetch; a refresh starts in the background
 * once the value is a minute old, or an hour old while it carries no windows, since a read can then
 * only learn whether they returned. ollama.com answers that unparameterized read for range {@code 7d},
 * so one fetch fills both it and the 7d entry.
 *
 * <p><b>Reactive.</b> A call that fails for exhausted credit marks what failed as unavailable for a
 * while. On a host with a usage source only the model is marked — Ollama answers 402 for a model
 * whose tier the plan does not include while the plan itself has headroom — and, while the guard is
 * on, the next route re-reads the usage; elsewhere the whole provider is, since a balance is account-wide.
 */
public final class SubscriptionUsage {

    /** One span of request history, as {@code /api/usage} reports it; the newest may still be running. */
    public record Bucket(Instant from, Instant until, long requestCount, boolean partial) {}

    /**
     * @param windows used fraction of each quota window, in [0, 1], keyed by the provider's window name
     * @param range   the history span the provider reported, null when the body carried no history
     * @param total   requests over that span
     * @param buckets request counts over that span, oldest first
     */
    public record Snapshot(Map<String, Double> windows, @Nullable String range, long total, List<Bucket> buckets,
                           Instant fetchedAt) {

        public Snapshot {
            windows = Map.copyOf(windows);
            buckets = List.copyOf(buckets);
        }

        public Snapshot(Map<String, Double> windows, Instant fetchedAt) {
            this(windows, null, 0, List.of(), fetchedAt);
        }

        /** The fullest window decides: whichever runs out first stops the provider. */
        public double pressure() {
            return windows.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        }

        public @Nullable String fullestWindow() {
            return windows.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }
    }

    /** A history span {@code /api/usage?range=} accepts; ollama.com answers anything else with a 400. */
    public enum Range {
        DAY("24h"), WEEK("7d"), MONTH("30d");

        public final String param;

        Range(String param) {
            this.param = param;
        }

        public static @Nullable Range parse(@Nullable String param) {
            for (var r : values()) {
                if (r.param.equals(param)) return r;
            }
            return null;
        }
    }

    /** One cache entry: the router's own read ({@code range} null, no query parameter) or a requested span. */
    private record Key(String provider, @Nullable Range range) {}

    static final Duration TTL = Duration.ofSeconds(60);
    /** How long the router's own read stands while its provider reports no quota windows. */
    static final Duration GUARD_OFF_RECHECK = Duration.ofHours(1);
    static final Duration MODEL_QUOTA_COOLDOWN = Duration.ofMinutes(60);
    static final Duration PROVIDER_QUOTA_COOLDOWN = Duration.ofMinutes(15);
    private static final long FETCH_TIMEOUT_MS = 5_000;

    private static final Map<Key, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<Key, Instant> LAST_ATTEMPT = new ConcurrentHashMap<>();
    private static final Set<Key> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Map<Key, Object> FETCH_LOCKS = new ConcurrentHashMap<>();
    private static final Set<Key> WARNED = ConcurrentHashMap.newKeySet();
    private static final Map<String, Instant> UNAVAILABLE_UNTIL = new ConcurrentHashMap<>();
    /** Providers whose last router read carried no quota windows. */
    private static final Set<String> GUARD_OFF = ConcurrentHashMap.newKeySet();
    /** Providers already warned about it; never cleared, so the warning is once per process. */
    private static final Set<String> GUARD_OFF_WARNED = ConcurrentHashMap.newKeySet();

    private SubscriptionUsage() {}

    public static boolean hasUsageSource(ProviderConfig config) {
        var host = host(config.baseUrl());
        return host != null && (host.equals("ollama.com") || host.endsWith(".ollama.com"));
    }

    /** The last usage read for {@code config}, or null when none exists yet. Never blocks. */
    public static @Nullable Snapshot current(ProviderConfig config) {
        var key = new Key(config.name(), null);
        if (hasUsageSource(config) && isStale(key) && IN_FLIGHT.add(key)) {
            Thread.ofVirtual().name("router-usage").start(() -> {
                try {
                    refreshNow(config);
                } finally {
                    IN_FLIGHT.remove(key);
                }
            });
        }
        return SNAPSHOTS.get(key);
    }

    /** {@link #current}, but fetching first when the value is missing or stale — for a caller that can wait on it. */
    public static @Nullable Snapshot fresh(ProviderConfig config) {
        return fresh(config, null);
    }

    private static @Nullable Snapshot fresh(ProviderConfig config, @Nullable Range range) {
        var key = new Key(config.name(), range);
        if (!hasUsageSource(config)) return SNAPSHOTS.get(key);
        // A reader arriving mid-fetch would otherwise find the attempt stamped and no new snapshot yet.
        synchronized (FETCH_LOCKS.computeIfAbsent(key, _ -> new Object())) {
            if (!isStale(key)) return SNAPSHOTS.get(key);
            var fetched = refreshNow(config, range);
            return fetched != null ? fetched : SNAPSHOTS.get(key);
        }
    }

    /**
     * {@code provider}'s request history over {@code range}, read at most {@link #TTL} ago through the
     * same cache as the router's own read. Null when there is no such reading: the provider has no key
     * or is not on ollama.com, or ollama.com refused, failed or did not answer within that minute.
     */
    public static @Nullable Snapshot history(String provider, Range range) {
        var registered = ProviderRegistry.get(provider);
        if (registered == null) {
            warnOnce(new Key(provider, range), "no API key is set");
            return null;
        }
        return history(registered.config(), range);
    }

    /** {@link #history(String, Range)} for a provider already resolved. */
    public static @Nullable Snapshot history(ProviderConfig config, Range range) {
        if (!hasUsageSource(config)) {
            warnOnce(new Key(config.name(), range), "its base URL is not on ollama.com");
            return null;
        }
        var snapshot = fresh(config, range);
        return snapshot != null && snapshot.fetchedAt().plus(TTL).isAfter(AppClock.now()) ? snapshot : null;
    }

    /** Fetch {@code config}'s usage now. Returns the stored snapshot, or null when the source gave nothing usable. */
    public static @Nullable Snapshot refreshNow(ProviderConfig config) {
        return refreshNow(config, null);
    }

    private static @Nullable Snapshot refreshNow(ProviderConfig config, @Nullable Range range) {
        var key = new Key(config.name(), range);
        var attempted = AppClock.now();
        LAST_ATTEMPT.put(key, attempted);
        var origin = origin(config.baseUrl());
        if (origin == null) return null;
        try {
            // OkHttp refuses a key with a character a header cannot carry; that is a failed read, not a 500.
            var request = new Request.Builder()
                    .url(origin + "/api/usage" + (range != null ? "?range=" + range.param : ""))
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + config.apiKey())
                    .get()
                    .build();
            var call = HttpFactories.general().newCall(request);
            call.timeout().timeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try (var response = call.execute()) {
                var body = response.body().string();
                if (response.code() != 200) {
                    warnOnce(key, "HTTP " + response.code());
                    return null;
                }
                var root = jsonObject(body);
                var windows = root != null ? windows(root) : Map.<String, Double>of();
                var history = root != null ? requestHistory(root) : null;
                if (windows.isEmpty() && history == null) {
                    warnOnce(key, "the response carried neither usage windows nor request history");
                    return null;
                }
                var now = AppClock.now();
                var snapshot = history == null
                        ? new Snapshot(windows, now)
                        : new Snapshot(windows, history.range(), history.total(), history.buckets(), now);
                SNAPSHOTS.put(key, snapshot);
                WARNED.remove(key);
                var twin = twin(key, snapshot);
                if (twin != null) {
                    // Snapshot before stamp: a reader finding the stamp fresh must find the snapshot too.
                    SNAPSHOTS.put(twin, snapshot);
                    LAST_ATTEMPT.put(twin, attempted);
                    WARNED.remove(twin);
                }
                // The guard follows whatever the router's entry now holds, a 7d read's windows included.
                if (range == null || twin != null) noteGuard(config.name(), !windows.isEmpty());
                return snapshot;
            }
        } catch (IOException | RuntimeException e) {
            // An exception message is not ours to vouch for: keep the key out of the log whatever it says.
            var message = String.valueOf(e.getMessage());
            var apiKey = config.apiKey();
            warnOnce(key, e.getClass().getSimpleName() + ": "
                    + (apiKey.isEmpty() ? message : message.replace(apiKey, "****")));
            return null;
        }
    }

    /** The router's entry for a 7d read, or the 7d entry for the router's, when the body reports 7d; else null. */
    private static @Nullable Key twin(Key key, Snapshot snapshot) {
        if (!Range.WEEK.param.equals(snapshot.range())) return null;
        if (key.range() == null) return new Key(key.provider(), Range.WEEK);
        return key.range() == Range.WEEK ? new Key(key.provider(), null) : null;
    }

    private record History(@Nullable String range, long total, List<Bucket> buckets) {}

    /** The body's request history, or null when it carries no {@code buckets}. */
    private static @Nullable History requestHistory(JsonObject root) {
        var buckets = buckets(root);
        return buckets == null ? null : new History(string(root, "range"), total(root, buckets), buckets);
    }

    /** Every {@code limits.<window>.usage} fraction in an {@code /api/usage} body; empty when there are none. */
    public static Map<String, Double> parseWindows(String body) {
        var root = jsonObject(body);
        return root != null ? windows(root) : Map.of();
    }

    private static Map<String, Double> windows(JsonObject root) {
        var limits = root.get("limits");
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
    }

    /** The body's {@code buckets}, skipping any entry that is not a well-formed span; null when it has none. */
    private static @Nullable List<Bucket> buckets(JsonObject root) {
        var buckets = root.get("buckets");
        if (buckets == null || !buckets.isJsonArray()) return null;
        var out = new ArrayList<Bucket>();
        for (var element : buckets.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            var bucket = element.getAsJsonObject();
            var from = instant(bucket, "from");
            var until = instant(bucket, "until");
            var count = count(bucket);
            if (from == null || until == null || count == null) continue;
            var partial = bucket.get("partial");
            out.add(new Bucket(from, until, count,
                    partial != null && partial.isJsonPrimitive() && partial.getAsJsonPrimitive().isBoolean()
                            && partial.getAsBoolean()));
        }
        return out;
    }

    /** {@code totals.request_count}, or the buckets' sum when the body carries no total. */
    private static long total(JsonObject root, List<Bucket> buckets) {
        var totals = root.get("totals");
        var total = totals != null && totals.isJsonObject() ? count(totals.getAsJsonObject()) : null;
        return total != null ? total : buckets.stream().mapToLong(Bucket::requestCount).sum();
    }

    private static @Nullable Long count(JsonObject object) {
        var count = object.get("request_count");
        if (count == null || !count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber()) return null;
        var n = count.getAsDouble();
        return Double.isFinite(n) && n >= 0 ? (long) n : null;
    }

    private static @Nullable Instant instant(JsonObject object, String field) {
        var value = string(object, field);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private static @Nullable String string(JsonObject object, String field) {
        var value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static @Nullable JsonObject jsonObject(String body) {
        try {
            var root = JsonParser.parseString(body);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (RuntimeException _) {
            return null;
        }
    }

    /**
     * Learn from a failed call. Only exhausted credit marks anything; a rate limit on a host with a
     * usage source just makes the next route re-read the usage, since a spent session window
     * surfaces as a 429 before the fraction next refreshes. With the guard off there is no fraction
     * to re-read, so neither failure hastens the hourly read.
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
                if (usageSource) hastenRead(f.provider());
            }
            case RATE_LIMITED -> {
                if (usageSource) hastenRead(f.provider());
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

    /** The router's own readings, by provider. */
    public static Map<String, Snapshot> snapshots() {
        var out = new LinkedHashMap<String, Snapshot>();
        SNAPSHOTS.forEach((k, snapshot) -> {
            if (k.range() == null) out.put(k.provider(), snapshot);
        });
        return out;
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
        var key = new Key(provider, null);
        SNAPSHOTS.put(key, new Snapshot(windows, AppClock.now()));
        LAST_ATTEMPT.put(key, AppClock.now());
    }

    public static void clearForTest(String provider) {
        SNAPSHOTS.keySet().removeIf(k -> k.provider().equals(provider));
        LAST_ATTEMPT.keySet().removeIf(k -> k.provider().equals(provider));
        WARNED.removeIf(k -> k.provider().equals(provider));
        GUARD_OFF.remove(provider);
        GUARD_OFF_WARNED.remove(provider);
        UNAVAILABLE_UNTIL.keySet().removeIf(k -> k.equals(provider) || k.startsWith(provider + "/"));
        FETCH_LOCKS.keySet().removeIf(k -> k.provider().equals(provider));
    }

    private static boolean isStale(Key key) {
        var last = LAST_ATTEMPT.get(key);
        var maxAge = key.range() == null && GUARD_OFF.contains(key.provider()) ? GUARD_OFF_RECHECK : TTL;
        return last == null || !last.plus(maxAge).isAfter(AppClock.now());
    }

    private static void hastenRead(String provider) {
        if (!GUARD_OFF.contains(provider)) LAST_ATTEMPT.remove(new Key(provider, null));
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

    private static void warnOnce(Key key, String why) {
        if (!WARNED.add(key)) return;
        var range = key.range();
        if (range == null) {
            EventLogger.warn("router", "Could not read usage for provider '%s' (%s); its budget guard is blind until it can"
                    .formatted(key.provider(), why));
        } else {
            EventLogger.warn("router", "Could not read the %s request history for provider '%s' (%s)"
                    .formatted(range.param, key.provider(), why));
        }
    }

    /**
     * Track whether the router's read still carries quota windows. Going without them warns once per
     * process, however many refreshes follow; their return is logged on every recovery.
     */
    private static void noteGuard(String provider, boolean hasWindows) {
        if (hasWindows) {
            if (GUARD_OFF.remove(provider)) {
                EventLogger.info("router", "Usage for provider '%s' carries quota windows again; its budget guard is back on"
                        .formatted(provider));
            }
        } else if (GUARD_OFF.add(provider) && GUARD_OFF_WARNED.add(provider)) {
            EventLogger.warn("router", ("Usage for provider '%s' reports no quota windows, so its budget guard is off; "
                    + "the router relies on 402 and 429 replies to bench it").formatted(provider));
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
