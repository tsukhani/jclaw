package services.decision;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import utils.CircuitBreaker;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The operator's {@code laya-serve} as a decision provider (JCLAW-1447): Laya's checkpoints answer
 * {@code POST /v1/systemone} with the body JEV takes, through {@link JevApi} under their own breaker.
 * The operator runs the server and JClaw dials its address; nothing here starts a process.
 */
public final class LayaDecision {

    /** The router's classifier provider id. */
    public static final String PROVIDER = "laya";
    public static final String BASE_URL_KEY = "decision.laya.baseUrl";
    /** Sent as a bearer token; a server started without {@code LAYA_API_KEY} needs none. */
    public static final String API_KEY = "decision.laya.apiKey";
    public static final String BREAKER_NAME = "decision:laya";
    /**
     * {@code laya-serve}'s own default port, on this machine. {@code LayaDecisionTest} holds it equal to
     * {@code LAYA_DEFAULT_ADDRESS} in {@code frontend/utils/decision-models.ts}.
     */
    public static final String DEFAULT_BASE_URL = "http://localhost:8000";

    /**
     * The models {@code laya-serve} names. {@code LayaDecisionTest} holds them equal to the names in
     * {@code LAYA_CHECKPOINTS} in {@code frontend/utils/decision-models.ts}, which also gives each one's context.
     */
    public static final List<String> CHECKPOINTS = List.of("english", "multilingual", "typed-decisions");

    private static final long HEALTH_TIMEOUT_SECONDS = 5;
    private static final String NOT_LAYA = "/health did not answer as a Laya server";

    private LayaDecision() {}

    /**
     * Whether the server answers, and what it says of itself.
     *
     * @param loaded the checkpoints in memory; empty when the server wants a key it was not sent
     * @param device where they compute; null when the server does not say
     */
    public record Status(String baseUrl, boolean reachable, @Nullable String error, List<String> loaded,
                         @Nullable String device) {}

    /** The operator's address, else {@link #DEFAULT_BASE_URL}. */
    public static String baseUrl() {
        var own = ConfigService.get(BASE_URL_KEY);
        return own == null || own.isBlank() ? DEFAULT_BASE_URL : trimSlash(own.strip());
    }

    /** The key the server was started with; null when unset. */
    public static @Nullable String apiKey() {
        var key = ConfigService.get(API_KEY);
        return key == null || key.isBlank() ? null : key.trim();
    }

    /**
     * The server at {@code baseUrl}, as a {@link JevApi} target on the SSRF-guarded client.
     *
     * @param apiKey sent as a bearer token; null sends none
     * @throws SecurityException for an address {@link SsrfGuard#assertProviderUrlSafe} refuses
     */
    public static JevApi.Target target(String baseUrl, @Nullable String apiKey) {
        // The guarded client's DNS screen never sees an IP literal, and a stored row can predate the write check.
        SsrfGuard.assertProviderUrlSafe(baseUrl);
        return unscreened(baseUrl, apiKey);
    }

    /** The provider's one breaker, whatever the address or checkpoint. */
    public static CircuitBreaker breaker() {
        return JevApi.breaker(unscreened(DEFAULT_BASE_URL, null));
    }

    private static JevApi.Target unscreened(String baseUrl, @Nullable String apiKey) {
        return new JevApi.Target("Laya", "Laya", "the Laya server", trimSlash(baseUrl) + "/v1/systemone",
                apiKey, BREAKER_NAME, true, _ -> false);
    }

    /**
     * {@code GET /health} at {@code baseUrl}. A server started with a key tells a caller without it only that it
     * is up, so {@code apiKey} is sent when there is one.
     */
    public static Status status(String baseUrl, @Nullable String apiKey) {
        var base = trimSlash(baseUrl);
        try {
            SsrfGuard.assertProviderUrlSafe(base);
        } catch (SecurityException e) {
            return unreachable(base, e.getMessage());
        }
        try {
            var request = new Request.Builder().url(base + "/health").header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON).get();
            if (apiKey != null) request.header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey);
            var call = HttpFactories.generalGuarded().newCall(request.build());
            call.timeout().timeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try (var response = call.execute()) {
                // Status only: the body comes from a server whose address the operator chose (JCLAW-778).
                if (!response.isSuccessful()) return unreachable(base, "HTTP " + response.code() + " from /health");
                var health = JsonParser.parseString(response.body().string());
                if (!health.isJsonObject() || !"ok".equals(text(health.getAsJsonObject().get("status")))) {
                    return unreachable(base, NOT_LAYA);
                }
                var body = health.getAsJsonObject();
                return new Status(base, true, null, texts(body.get("loaded")), text(body.get("device")));
            }
        } catch (IllegalArgumentException _) {
            // OkHttp refuses an address the write check let through, such as a port above 65535.
            return unreachable(base, "not a usable address");
        } catch (JsonParseException _) {
            return unreachable(base, NOT_LAYA);
        } catch (IOException e) {
            return unreachable(base, "not reachable (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static @Nullable String text(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString() : null;
    }

    private static List<String> texts(@Nullable JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        var out = new ArrayList<String>();
        for (var entry : element.getAsJsonArray()) {
            var name = text(entry);
            if (name != null) out.add(name);
        }
        return List.copyOf(out);
    }

    private static Status unreachable(String base, @Nullable String error) {
        return new Status(base, false, error, List.of(), null);
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
