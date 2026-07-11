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
import utils.Strings;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers Replicate's curated <b>text-to-image</b> models for the Settings "Model" dropdown — the
 * image-gen analogue of {@link services.videogen.ReplicateVideoModelCatalog}. Replicate maintains a
 * {@code text-to-image} collection; {@code GET /v1/collections/text-to-image} returns its models, each an
 * {@code owner/name} slug — exactly what {@code imagegen.replicate.model} stores.
 *
 * <p>JCLAW-700: {@code image-to-image} (Kontext) models live in a different collection and would
 * otherwise be unselectable, so a curated set is appended (flagged {@code imageToImage=true}) — the
 * dropdown groups the two by capability. Kontext takes an optional {@code input_image} (JCLAW-696),
 * so those models serve both modes.
 *
 * <p>The cached {@link #availableModels()} entry point is what the controller calls; the uncached
 * {@link #fetch()} instance method takes an injectable client so it's unit-testable against a
 * MockWebServer without touching the process-global cache.
 */
public final class ReplicateImageModelCatalog {

    /** One selectable Replicate model. {@code slug} is the {@code owner/name} for
     *  imagegen.replicate.model. {@code imageToImage} true means it accepts an uploaded reference
     *  (Kontext / style transfer) — the Settings dropdown groups by this flag (JCLAW-700). */
    public record ImageModel(String slug, String name, String description, boolean imageToImage) {}

    /**
     * JCLAW-700: curated image-to-image (Kontext) models. These live in Replicate's image-editing
     * collection, not the {@code text-to-image} one the dropdown fetches, so they'd otherwise be
     * unselectable. Kontext accepts an optional {@code input_image}, so each also does plain
     * text-to-image — selecting one covers both modes. Offered only when a Replicate key is set.
     */
    private static final List<ImageModel> KONTEXT_MODELS = List.of(
            new ImageModel("black-forest-labs/flux-kontext-pro", "flux-kontext-pro",
                    "Image editing & style transfer (Kontext); also text-to-image.", true),
            new ImageModel("black-forest-labs/flux-kontext-max", "flux-kontext-max",
                    "Highest-quality Kontext image editing / style transfer; also text-to-image.", true),
            new ImageModel("black-forest-labs/flux-kontext-dev", "flux-kontext-dev",
                    "Open-weight Kontext image editing (non-commercial license).", true));

    private static final String DEFAULT_BASE = "https://api.replicate.com/v1";
    private static final String COLLECTION = "text-to-image";
    private static final String MODELS = "models";

    // Replicate's curated collection changes on the order of days, so a short TTL keeps the Settings
    // dropdown responsive without an outbound call on every page load. Only successful, non-empty
    // results are cached (see availableModels), so a missing-key or transient-error state re-fetches.
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
     * Cached entry point for the controller: the discovered text-to-image models plus the curated
     * image-to-image (Kontext) set, for the Settings dropdown. Returns an empty list when no
     * Replicate API key is configured — the dropdown degrades to "no models discovered" — since
     * every model here (Kontext included) runs on Replicate and needs the key. With a key set, the
     * fetched text-to-image list is cached (empties aren't, so a transient error re-fetches) and
     * the static Kontext models are always appended.
     */
    public static List<ImageModel> availableModels() {
        var apiKey = ConfigService.get("provider.replicate.apiKey");
        if (apiKey == null || apiKey.isBlank()) return List.of();
        var cached = CACHE.getIfPresent(COLLECTION);
        var textToImage = cached != null ? cached : new ReplicateImageModelCatalog().fetch();
        if (cached == null && !textToImage.isEmpty()) CACHE.put(COLLECTION, textToImage);
        var out = new ArrayList<ImageModel>(textToImage);
        out.addAll(KONTEXT_MODELS);
        return out;
    }

    /** Uncached fetch + parse of the {@code text-to-image} collection — directly unit-testable. */
    public List<ImageModel> fetch() {
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
                out.add(new ImageModel(owner + "/" + name, name, asString(m, "description"), false));
            }
        }
        return out;
    }

    private static String asString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
