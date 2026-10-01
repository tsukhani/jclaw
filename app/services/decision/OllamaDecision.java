package services.decision;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.discovery.ModelCatalogParser;
import utils.CircuitBreaker;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The operator's Ollama server as a decision provider (JCLAW-1336): its System One models answer
 * {@code POST /v1/systemone} with the body JEV takes, through {@link JevApi} under their own breaker.
 * Ollama refuses a cloud model there ("System One requires a local Nimble or Tev model", v0.35.0), so
 * the server is always the operator's own, on this machine or the LAN.
 */
public final class OllamaDecision {

    /** The router's classifier provider id; not {@code ollama-local}, which names the LLM provider. */
    public static final String PROVIDER = "ollama-decision";
    public static final String BASE_URL_KEY = "decision.ollama.baseUrl";
    /** A JSON array of the model names the operator selected, such as {@code ["tev1"]}. */
    public static final String MODELS_KEY = "decision.ollama.models";
    /** How long Ollama keeps the model loaded after a decision request; not {@code provider.ollama-local.keepAlive}. */
    public static final String KEEP_ALIVE_KEY = "decision.ollama.keepAlive";
    public static final String BREAKER_NAME = "decision:ollama";
    /** The capability Ollama's {@code /api/tags} lists for a System One model. */
    static final String DECISION_CAPABILITY = "decision";

    private static final String LOCAL_BASE_URL_KEY = "provider.ollama-local.baseUrl";
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final long TAGS_TIMEOUT_SECONDS = 5;
    /** Until Ollama restarts: the classifier is consulted on every routed turn, and a cold tev1 took 19 s. */
    private static final String DEFAULT_KEEP_ALIVE = "-1";
    private static final Pattern DURATION = Pattern.compile("-1|0|\\d+(\\.\\d+)?[smh]");

    private OllamaDecision() {}

    /** The server's installed decision models, or why they could not be read. */
    public record Status(String baseUrl, boolean reachable, @Nullable String error, List<String> models) {}

    /** The operator's address, else the Ollama Local LLM provider's server without its {@code /v1}. */
    public static String baseUrl() {
        var own = ConfigService.get(BASE_URL_KEY);
        if (own != null && !own.isBlank()) return trimSlash(own.strip());
        var local = ConfigService.get(LOCAL_BASE_URL_KEY);
        return local == null || local.isBlank() ? DEFAULT_BASE_URL : ModelCatalogParser.stripV1Suffix(local.strip());
    }

    public static boolean customized() {
        var own = ConfigService.get(BASE_URL_KEY);
        return own != null && !own.isBlank();
    }

    /** The models the operator selected, in the order stored; empty when none or unreadable. */
    public static List<String> selectedModels() {
        var parsed = parseModels(ConfigService.get(MODELS_KEY));
        return parsed == null ? List.of() : parsed;
    }

    /** Whether {@code value} is an Ollama duration this key accepts: -1, 0, or a number followed by s, m or h. */
    static boolean isKeepAlive(String value) {
        return DURATION.matcher(value).matches();
    }

    /** Adds {@link #KEEP_ALIVE_KEY}, or -1 when it is unset or unreadable, as {@code keep_alive} on {@code body}. */
    public static void addKeepAlive(JsonObject body) {
        var stored = ConfigService.get(KEEP_ALIVE_KEY);
        var value = stored != null && isKeepAlive(stored.strip()) ? stored.strip() : DEFAULT_KEEP_ALIVE;
        // Ollama parses a bare "-1" string as a duration missing its unit; a number is seconds.
        if (value.equals("-1") || value.equals("0")) {
            body.addProperty("keep_alive", Integer.parseInt(value));
        } else {
            body.addProperty("keep_alive", value);
        }
    }

    /**
     * The server at {@code baseUrl}, as a {@link JevApi} target on the SSRF-guarded client.
     *
     * @throws SecurityException for an address {@link SsrfGuard#assertProviderUrlSafe} refuses
     */
    public static JevApi.Target target(String baseUrl) {
        // The guarded client's DNS screen never sees an IP literal, and a stored row can predate the write check.
        SsrfGuard.assertProviderUrlSafe(baseUrl);
        return unscreened(baseUrl);
    }

    /** The provider's one breaker, whatever the address. */
    public static CircuitBreaker breaker() {
        return JevApi.breaker(unscreened(DEFAULT_BASE_URL));
    }

    private static JevApi.Target unscreened(String baseUrl) {
        return new JevApi.Target("Ollama", "Ollama", "the Ollama server", trimSlash(baseUrl) + "/v1/systemone",
                null, BREAKER_NAME, true);
    }

    /** {@code GET /api/tags} at {@code baseUrl}, keeping only models whose capabilities include {@code decision}. */
    public static Status status(String baseUrl) {
        var base = trimSlash(baseUrl);
        try {
            SsrfGuard.assertProviderUrlSafe(base);
        } catch (SecurityException e) {
            return unreachable(base, e.getMessage());
        }
        var request = new Request.Builder().url(base + "/api/tags").header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                .get().build();
        var call = HttpFactories.generalGuarded().newCall(request);
        call.timeout().timeout(TAGS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var response = call.execute()) {
            // Status only: the body comes from a server whose address the operator chose (JCLAW-778).
            if (!response.isSuccessful()) return unreachable(base, "HTTP " + response.code() + " from /api/tags");
            return new Status(base, true, null, decisionModels(JsonParser.parseString(response.body().string())));
        } catch (JsonParseException | IllegalStateException | ClassCastException _) {
            return unreachable(base, "/api/tags did not answer with Ollama's model list");
        } catch (IOException e) {
            return unreachable(base, "not reachable (" + e.getClass().getSimpleName() + ")");
        }
    }

    static List<String> decisionModels(JsonElement tags) {
        var models = tags.getAsJsonObject().getAsJsonArray("models");
        var out = new ArrayList<String>();
        if (models == null) return out;
        for (var entry : models) {
            if (!entry.isJsonObject()) continue;
            var model = entry.getAsJsonObject();
            var name = model.get("name");
            if (name != null && name.isJsonPrimitive() && hasDecision(model.get("capabilities"))) {
                out.add(name.getAsString());
            }
        }
        return out;
    }

    private static boolean hasDecision(@Nullable JsonElement capabilities) {
        if (capabilities == null || !capabilities.isJsonArray()) return false;
        for (var c : capabilities.getAsJsonArray()) {
            if (c.isJsonPrimitive() && DECISION_CAPABILITY.equals(c.getAsString())) return true;
        }
        return false;
    }

    /** The stored selection, or null when {@code value} is not a JSON array of non-blank names. */
    static @Nullable List<String> parseModels(@Nullable String value) {
        if (value == null || value.isBlank()) return List.of();
        JsonArray array;
        try {
            var root = JsonParser.parseString(value);
            if (!root.isJsonArray()) return null;
            array = root.getAsJsonArray();
        } catch (JsonParseException _) {
            return null;
        }
        var out = new LinkedHashSet<String>();
        for (var entry : array) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || entry.getAsString().isBlank()) {
                return null;
            }
            out.add(entry.getAsString().strip());
        }
        return List.copyOf(out);
    }

    private static Status unreachable(String base, @Nullable String error) {
        return new Status(base, false, error, List.of());
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
