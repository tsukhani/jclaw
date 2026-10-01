package services.decision;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import play.Logger;
import services.ConfigService;
import services.discovery.ModelCatalogParser;
import utils.CircuitBreaker;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
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
    /** Asked only after a timeout, on the turn's own thread; a healthy server answers in microseconds. */
    private static final long PS_TIMEOUT_SECONDS = 1;
    /** Until Ollama restarts: the classifier is consulted on every routed turn, and a cold tev1 took 19 s. */
    private static final String DEFAULT_KEEP_ALIVE = "-1";
    private static final Pattern DURATION = Pattern.compile("-1|0|\\d+(\\.\\d+)?[smh]");
    private static final Pattern NO_RESIDENCY = Pattern.compile("0+(\\.0+)?[smh]?");
    /** A cold load from disk took 19 s; the pin must outlast it, unlike the classifier's own call. */
    private static final long PIN_TIMEOUT_SECONDS = 120;
    /** Ollama answered {@code ollama stop}'s unload in 8 ms; the slack covers one queued behind a load. */
    private static final long UNLOAD_TIMEOUT_SECONDS = 30;
    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);
    private static final ConcurrentHashMap<String, CompletableFuture<Boolean>> PINNING = new ConcurrentHashMap<>();

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
     * Asks the server at {@code baseUrl} to load {@code model} and keep it as {@link #KEEP_ALIVE_KEY} says, without
     * waiting. Ollama cancels a load when the request that started it is cancelled (v0.35.0), so a classifier call cut
     * off at its timeout can leave a cold model unloaded on every turn; this request has its own, longer timeout. It
     * is no decision, so it stays outside the provider's breaker.
     *
     * @return whether the model loaded: already false when nothing was sent, and the running pin's outcome while one
     *         for the same server and model is in flight
     */
    public static CompletableFuture<Boolean> pin(String baseUrl, String model) {
        var body = new JsonObject();
        body.addProperty("model", model);
        addKeepAlive(body);
        // A keep_alive of zero unloads the model as soon as it has loaded.
        if (NO_RESIDENCY.matcher(body.get("keep_alive").getAsString()).matches()) return CompletableFuture.completedFuture(false);
        var base = trimSlash(baseUrl);
        try {
            SsrfGuard.assertProviderUrlSafe(base);
        } catch (SecurityException _) {
            return CompletableFuture.completedFuture(false);
        }
        var key = base + " " + model;
        var pinning = new CompletableFuture<Boolean>();
        var running = PINNING.putIfAbsent(key, pinning);
        if (running != null) return running;

        var request = new Request.Builder().url(base + "/api/generate").post(RequestBody.create(body.toString(), JSON)).build();
        var call = HttpFactories.generalGuarded().newCall(request);
        call.timeout().timeout(PIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        call.enqueue(new Callback() {
            @Override
            public void onResponse(Call done, Response response) {
                try (response) {
                    // Debug, as for the embedding pin: a stopped server would otherwise warn on every renewal.
                    if (!response.isSuccessful()) Logger.debug("[decision] loading Ollama %s returned HTTP %d", model, response.code());
                    finish(response.isSuccessful());
                }
            }

            @Override
            public void onFailure(Call failed, IOException e) {
                Logger.debug("[decision] loading Ollama %s failed: %s", model, e.getClass().getSimpleName());
                finish(false);
            }

            // Out of the map first, so a pin that has finished is never handed to a later caller.
            private void finish(boolean loaded) {
                PINNING.remove(key, pinning);
                pinning.complete(loaded);
            }
        });
        return pinning;
    }

    /**
     * The server at {@code baseUrl}, as a {@link JevApi} target on the SSRF-guarded client.
     *
     * @throws SecurityException for an address {@link SsrfGuard#assertProviderUrlSafe} refuses
     */
    public static JevApi.Target target(String baseUrl, String model) {
        // The guarded client's DNS screen never sees an IP literal, and a stored row can predate the write check.
        SsrfGuard.assertProviderUrlSafe(baseUrl);
        var base = trimSlash(baseUrl);
        // A cold load outlasts the classifier's timeout without the server being any less healthy (JCLAW-1338).
        return unscreened(base, outage -> outage.timedOut() && stillLoading(base, model));
    }

    /** The provider's one breaker, whatever the address. */
    public static CircuitBreaker breaker() {
        return JevApi.breaker(unscreened(DEFAULT_BASE_URL, _ -> false));
    }

    private static JevApi.Target unscreened(String baseUrl, Predicate<JevException.Outage> uncounted) {
        return new JevApi.Target("Ollama", "Ollama", "the Ollama server", trimSlash(baseUrl) + "/v1/systemone",
                null, BREAKER_NAME, true, uncounted);
    }

    /**
     * Whether the server at {@code base} answers {@code GET /api/ps} without {@code model} among its loaded models, so
     * a call that timed out was waiting on a load. False when it cannot tell, which leaves the timeout counted.
     */
    static boolean stillLoading(String base, String model) {
        try {
            return !loadedModels(base, PS_TIMEOUT_SECONDS).contains(tagged(model));
        } catch (IOException | JsonParseException | IllegalStateException | ClassCastException _) {
            return false;
        }
    }

    /**
     * Waits for a pin of {@code model} already in flight, then pins it afresh and waits for that: an unload sent while
     * the earlier pin ran may have undone it.
     *
     * @return whether the fresh pin loaded the model
     */
    public static boolean pinAfresh(String baseUrl, String model) {
        try {
            var running = PINNING.get(trimSlash(baseUrl) + " " + model);
            if (running != null) running.get(PIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return pin(baseUrl, model).get(PIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException _) {
            return false;
        }
    }

    /**
     * Unloads every decision model loaded on the server at {@code baseUrl} other than {@code keep}: loaded per
     * {@code /api/ps} and listing {@code decision} among its capabilities per {@code /api/tags} (JCLAW-1339). Sends
     * nothing when either cannot be read. Like {@link #pin}, it is no decision, so it stays outside the breaker.
     *
     * @param keep the router's classifier model, or null when the router uses none on this server
     * @return the models Ollama accepted an unload for
     */
    public static List<String> unloadUnused(String baseUrl, @Nullable String keep) {
        var base = trimSlash(baseUrl);
        try {
            SsrfGuard.assertProviderUrlSafe(base);
        } catch (SecurityException _) {
            return List.of();
        }
        List<String> loaded;
        var decision = new HashSet<String>();
        try {
            loaded = loadedModels(base, TAGS_TIMEOUT_SECONDS);
            if (loaded.isEmpty()) return List.of();
            for (var name : decisionModels(JsonParser.parseString(get(base, "/api/tags", TAGS_TIMEOUT_SECONDS)))) {
                decision.add(tagged(name));
            }
        } catch (IOException | JsonParseException | IllegalStateException | ClassCastException e) {
            Logger.debug("[decision] reading Ollama's loaded models failed: %s", e.getClass().getSimpleName());
            return List.of();
        }
        var kept = keep == null ? null : tagged(keep);
        var unloaded = new ArrayList<String>();
        for (var name : loaded) {
            if (decision.contains(name) && !name.equals(kept) && unload(base, name)) unloaded.add(name);
        }
        return unloaded;
    }

    /** What {@code ollama stop} sends: the model and a zero keep_alive, no prompt. */
    private static boolean unload(String base, String model) {
        var body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("keep_alive", 0);
        var request = new Request.Builder().url(base + "/api/generate").post(RequestBody.create(body.toString(), JSON)).build();
        var call = HttpFactories.generalGuarded().newCall(request);
        call.timeout().timeout(UNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var response = call.execute()) {
            if (!response.isSuccessful()) {
                Logger.debug("[decision] unloading Ollama %s returned HTTP %d", model, response.code());
                return false;
            }
            Logger.info("[decision] unloaded Ollama %s, which the router no longer uses", model);
            return true;
        } catch (IOException e) {
            Logger.debug("[decision] unloading Ollama %s failed: %s", model, e.getClass().getSimpleName());
            return false;
        }
    }

    /** The {@link #tagged} names {@code GET /api/ps} lists as loaded. */
    private static List<String> loadedModels(String base, long timeoutSeconds) throws IOException {
        var loaded = JsonParser.parseString(get(base, "/api/ps", timeoutSeconds)).getAsJsonObject().getAsJsonArray("models");
        if (loaded == null) throw new IOException("no models in /api/ps");
        var out = new ArrayList<String>();
        for (var entry : loaded) {
            var name = entry.isJsonObject() ? entry.getAsJsonObject().get("name") : null;
            if (name != null && name.isJsonPrimitive()) out.add(tagged(name.getAsString()));
        }
        return out;
    }

    private static String get(String base, String path, long timeoutSeconds) throws IOException {
        var request = new Request.Builder().url(base + path).header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON).get().build();
        var call = HttpFactories.generalGuarded().newCall(request);
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS);
        try (var response = call.execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " from " + path);
            return response.body().string();
        }
    }

    /** Ollama names a model by its tag, and a bare name means {@code :latest}. */
    private static String tagged(String model) {
        return model.contains(":") ? model : model + ":latest";
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
