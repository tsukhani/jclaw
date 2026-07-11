package services.videogen;

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
import utils.Strings;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers Replicate's curated <b>text-to-video</b> models for the Settings "Model" dropdown
 * (JCLAW-236). Replicate maintains a {@code text-to-video} collection; {@code GET
 * /v1/collections/text-to-video} returns its models, each an {@code owner/name} slug — exactly what
 * {@code videogen.cloud.model} stores. The {@code generate_video} tool sends only a prompt, so
 * text-to-video is the compatible set ({@code image-to-video} models need an input frame the tool
 * doesn't provide).
 *
 * <p>The cached {@link #textToVideoModels()} entry point is what the controller calls; the uncached
 * {@link #fetch()} instance method takes an injectable client so it's unit-testable against a
 * MockWebServer without touching the process-global cache.
 */
public final class ReplicateVideoModelCatalog {

    /** One curated text-to-video model. {@code slug} is the {@code owner/name} for videogen.cloud.model. */
    public record VideoModel(String slug, String name, String description) {}

    private static final String DEFAULT_BASE = "https://api.replicate.com/v1";
    private static final String COLLECTION = "text-to-video";
    private static final String MODELS = "models";

    // Replicate's curated collection changes on the order of days, so a short TTL keeps the Settings
    // dropdown responsive without an outbound call on every page load. Only successful, non-empty
    // results are cached (see textToVideoModels), so a missing-key or transient-error state re-fetches.
    private static final Cache<String, List<VideoModel>> CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1)
            .build();

    private final OkHttpClient client;

    public ReplicateVideoModelCatalog() {
        this(HttpFactories.general());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public ReplicateVideoModelCatalog(OkHttpClient client) {
        this.client = client;
    }

    /**
     * Cached entry point for the controller. Returns an empty list when no Replicate API key is
     * configured or on a transport/HTTP error — the dropdown degrades to "no models discovered".
     * Empties are not cached so the list repopulates once a key is set or the error clears.
     */
    public static List<VideoModel> textToVideoModels() {
        var cached = CACHE.getIfPresent(COLLECTION);
        if (cached != null) return cached;
        var fresh = new ReplicateVideoModelCatalog().fetch();
        if (!fresh.isEmpty()) CACHE.put(COLLECTION, fresh);
        return fresh;
    }

    /** Uncached fetch + parse of the {@code text-to-video} collection — directly unit-testable. */
    public List<VideoModel> fetch() {
        var apiKey = ConfigService.get("provider.replicate.apiKey");
        if (apiKey == null || apiKey.isBlank()) return List.of();
        var base = Strings.firstNonBlank(ConfigService.get("provider.replicate.baseUrl"), DEFAULT_BASE);
        var url = Strings.trimTrailingSlash(base) + "/collections/" + COLLECTION;
        var req = new Request.Builder()
                .url(url)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .get().build();
        try (var resp = client.newCall(req).execute()) {
            var body = resp.body().string();
            if (!resp.isSuccessful()) {
                Logger.warn("videogen model discovery: HTTP %d from %s", resp.code(), url);
                return List.of();
            }
            return parse(body);
        } catch (IOException e) {
            Logger.warn(e, "videogen model discovery: transport error");
            return List.of();
        }
    }

    private static List<VideoModel> parse(String body) {
        var out = new ArrayList<VideoModel>();
        var root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has(MODELS) || !root.get(MODELS).isJsonArray()) return out;
        for (var el : root.getAsJsonArray(MODELS)) {
            if (!el.isJsonObject()) continue;
            var m = el.getAsJsonObject();
            var owner = asString(m, "owner");
            var name = asString(m, "name");
            if (owner == null || owner.isBlank() || name == null || name.isBlank()) continue;
            out.add(new VideoModel(owner + "/" + name, name, asString(m, "description")));
        }
        return out;
    }

    private static String asString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
