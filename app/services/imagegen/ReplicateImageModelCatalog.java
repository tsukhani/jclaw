package services.imagegen;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import play.Logger;
import services.ConfigService;
import utils.HttpFactories;
import utils.HttpKeys;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers Replicate's curated <b>text-to-image</b> models for the Settings "Model" dropdown — the
 * image-gen analogue of {@link services.videogen.ReplicateVideoModelCatalog}. Replicate maintains a
 * {@code text-to-image} collection; {@code GET /v1/collections/text-to-image} returns its models, each an
 * {@code owner/name} slug — exactly what {@code imagegen.replicate.model} stores. The {@code generate_image}
 * tool sends only a prompt (plus optional dimensions), so text-to-image is the compatible set
 * ({@code image-to-image} models need an input image the tool doesn't provide).
 *
 * <p>The cached {@link #textToImageModels()} entry point is what the controller calls; the uncached
 * {@link #fetch()} instance method takes an injectable client so it's unit-testable against a
 * MockWebServer without touching the process-global cache.
 */
public final class ReplicateImageModelCatalog {

    /** One curated text-to-image model. {@code slug} is the {@code owner/name} for imagegen.replicate.model. */
    public record ImageModel(String slug, String name, String description) {}

    private static final String DEFAULT_BASE = "https://api.replicate.com/v1";
    private static final String COLLECTION = "text-to-image";
    private static final String MODELS = "models";

    // Replicate's curated collection changes on the order of days, so a short TTL keeps the Settings
    // dropdown responsive without an outbound call on every page load. Only successful, non-empty
    // results are cached (see textToImageModels), so a missing-key or transient-error state re-fetches.
    private static final Cache<String, List<ImageModel>> CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1)
            .build();

    private final OkHttpClient client;

    public ReplicateImageModelCatalog() {
        this(HttpFactories.general());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public ReplicateImageModelCatalog(OkHttpClient client) {
        this.client = client;
    }

    /**
     * Cached entry point for the controller. Returns an empty list when no Replicate API key is
     * configured or on a transport/HTTP error — the dropdown degrades to "no models discovered".
     * Empties are not cached so the list repopulates once a key is set or the error clears.
     */
    public static List<ImageModel> textToImageModels() {
        var cached = CACHE.getIfPresent(COLLECTION);
        if (cached != null) return cached;
        var fresh = new ReplicateImageModelCatalog().fetch();
        if (!fresh.isEmpty()) CACHE.put(COLLECTION, fresh);
        return fresh;
    }

    /** Uncached fetch + parse of the {@code text-to-image} collection — directly unit-testable. */
    public List<ImageModel> fetch() {
        var apiKey = ConfigService.get("provider.replicate.apiKey");
        if (apiKey == null || apiKey.isBlank()) return List.of();
        var base = firstNonBlank(ConfigService.get("provider.replicate.baseUrl"), DEFAULT_BASE);
        var url = trimTrailingSlash(base) + "/collections/" + COLLECTION;
        var req = new Request.Builder()
                .url(url)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .get().build();
        try (var resp = client.newCall(req).execute()) {
            var body = resp.body().string();
            if (!resp.isSuccessful()) {
                Logger.warn("imagegen model discovery: HTTP %d from %s", resp.code(), url);
                return List.of();
            }
            return parse(body);
        } catch (IOException e) {
            Logger.warn(e, "imagegen model discovery: transport error");
            return List.of();
        }
    }

    private static List<ImageModel> parse(String body) {
        var out = new ArrayList<ImageModel>();
        var root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has(MODELS) || !root.get(MODELS).isJsonArray()) return out;
        for (var el : root.getAsJsonArray(MODELS)) {
            if (!el.isJsonObject()) continue;
            var m = el.getAsJsonObject();
            var owner = asString(m, "owner");
            var name = asString(m, "name");
            if (owner != null && !owner.isBlank() && name != null && !name.isBlank()) {
                out.add(new ImageModel(owner + "/" + name, name, asString(m, "description")));
            }
        }
        return out;
    }

    private static String asString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
