package services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import play.Logger;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.Strings;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers Black Forest Labs' generation models for the Settings model dropdowns, image and video
 * alike. BFL has no list-models endpoint, but its API publishes an unauthenticated OpenAPI spec at
 * {@code /openapi.json} with one {@code POST /v1/{model}} per model, and that path segment is exactly
 * what {@code imagegen.bfl.model} and {@code videogen.bfl.model} store.
 *
 * <p>A model qualifies when its endpoint is tagged {@code Models}, sits directly under the API version
 * (the {@code flux-tools/*} editors need an input image or video) and its request can be satisfied by a
 * prompt alone, which drops fill, expand and the finetune endpoints that {@code generate_image} cannot
 * feed. Endpoints whose name contains {@code video} are the video models; the rest are image models.
 * Image models differ in how they take a size and a reference image, so each one's accepted fields
 * are kept as a {@link RequestShape} for {@code BflImageGenerationClient} to build its request from.
 *
 * <p>The cached {@link #imageModels()} and {@link #videoModels()} are what the controllers call; the
 * uncached {@link #fetch(String)} takes an injectable client so it is testable without the cache.
 */
public final class BflModelCatalog {

    /** One selectable BFL model. {@code slug} is the endpoint name, e.g. {@code flux-2-pro}. */
    public record BflModel(String slug, @Nullable String description) {}

    /** The request fields an image model accepts, and its {@code aspect_ratio} enum when it has one. */
    public record RequestShape(Set<String> fields, List<String> aspectRatios) {}

    /** Both halves of one spec read, so the image and video dropdowns share a fetch. */
    public record Catalog(List<BflModel> image, List<BflModel> video, Map<String, RequestShape> shapes) {
        static final Catalog EMPTY = new Catalog(List.of(), List.of(), Map.of());
    }

    private static final String DEFAULT_BASE = "https://api.bfl.ai/v1";
    private static final Set<String> IMAGE_REQUIRED = Set.of("prompt");
    // A mode-switched endpoint (flux-3-video) names its text-only variant by mode, which the video client sends.
    private static final Set<String> VIDEO_REQUIRED = Set.of("prompt", "mode");
    private static final String REF = "$ref";

    // BFL adds models on the order of weeks; only non-empty reads are cached, so a failed fetch retries.
    private static final Cache<String, Catalog> CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1)
            .build();

    private final OkHttpClient client;

    public BflModelCatalog() {
        this(HttpFactories.generalGuarded());
    }

    /** Test seam — inject a canned transport. */
    public BflModelCatalog(OkHttpClient client) {
        this.client = client;
    }

    public static List<BflModel> imageModels() {
        return cached().image();
    }

    public static List<BflModel> videoModels() {
        return cached().video();
    }

    /** The shape BFL publishes for image model {@code model}; empty when the spec is unreadable or omits it. */
    public static Optional<RequestShape> requestShape(String model) {
        return Optional.ofNullable(cached().shapes().get(model));
    }

    private static Catalog cached() {
        var base = Strings.firstNonBlankOr(DEFAULT_BASE, ConfigService.get("provider.bfl.baseUrl"));
        var hit = CACHE.getIfPresent(base);
        if (hit != null) return hit;
        var fresh = new BflModelCatalog().fetch(base);
        if (!fresh.image().isEmpty() || !fresh.video().isEmpty()) CACHE.put(base, fresh);
        return fresh;
    }

    /** Uncached read of the spec served beside {@code baseUrl}; empty on any failure. */
    public Catalog fetch(String baseUrl) {
        var base = HttpUrl.parse(baseUrl);
        if (base == null) {
            Logger.warn("bfl model discovery: unparseable base URL %s", baseUrl);
            return Catalog.EMPTY;
        }
        var specUrl = base.resolve("/openapi.json");
        if (specUrl == null) return Catalog.EMPTY;
        var req = new Request.Builder().url(specUrl).get().build();
        try (var resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                Logger.warn("bfl model discovery: HTTP %d from %s", resp.code(), specUrl);
                return Catalog.EMPTY;
            }
            return parse(resp.body().string(), Strings.trimTrailingSlash(base.encodedPath()) + "/");
        } catch (IOException | RuntimeException e) {
            Logger.warn(e, "bfl model discovery: could not read %s", specUrl);
            return Catalog.EMPTY;
        }
    }

    private static Catalog parse(String spec, String pathPrefix) {
        var root = JsonParser.parseString(spec).getAsJsonObject();
        var schemas = object(object(root, "components"), "schemas");
        var image = new ArrayList<BflModel>();
        var video = new ArrayList<BflModel>();
        var shapes = new HashMap<String, RequestShape>();
        var paths = object(root, "paths");
        if (paths == null) return Catalog.EMPTY;
        for (var entry : paths.entrySet()) {
            var path = entry.getKey();
            if (!path.startsWith(pathPrefix)) continue;
            var slug = path.substring(pathPrefix.length());
            if (slug.isEmpty() || slug.contains("/")) continue;
            var post = entry.getValue().isJsonObject() ? object(entry.getValue().getAsJsonObject(), "post") : null;
            if (post == null || !hasTag(post, "Models")) continue;
            var body = object(object(object(object(post, "requestBody"), "content"), HttpKeys.APPLICATION_JSON), "schema");
            var isVideo = slug.contains("video");
            if (!promptSuffices(body, schemas, isVideo ? VIDEO_REQUIRED : IMAGE_REQUIRED)) continue;
            var model = new BflModel(slug, string(post, "summary"));
            if (isVideo) {
                video.add(model);
            } else {
                image.add(model);
                var shape = shapeOf(resolve(body, schemas));
                if (shape != null) shapes.put(slug, shape);
            }
        }
        image.sort(Comparator.comparing(BflModel::slug));
        video.sort(Comparator.comparing(BflModel::slug));
        return new Catalog(List.copyOf(image), List.copyOf(video), Map.copyOf(shapes));
    }

    private static @Nullable RequestShape shapeOf(@Nullable JsonObject schema) {
        var properties = object(schema, "properties");
        if (properties == null) return null;
        var ratios = new ArrayList<String>();
        var aspect = object(properties, "aspect_ratio");
        if (aspect != null) {
            var variants = new ArrayList<JsonElement>();
            if (aspect.has("anyOf") && aspect.get("anyOf").isJsonArray()) aspect.getAsJsonArray("anyOf").forEach(variants::add);
            else variants.add(aspect);
            for (var variant : variants) {
                if (!variant.isJsonObject() || !variant.getAsJsonObject().has("enum")) continue;
                for (var value : variant.getAsJsonObject().getAsJsonArray("enum")) ratios.add(value.getAsString());
            }
        }
        return new RequestShape(Set.copyOf(properties.keySet()), List.copyOf(ratios));
    }

    /** True when {@code allowed} covers every required field of the schema, or of any variant of a union. */
    private static boolean promptSuffices(@Nullable JsonObject schema, @Nullable JsonObject schemas, Set<String> allowed) {
        var resolved = resolve(schema, schemas);
        if (resolved == null) return false;
        for (var union : List.of("anyOf", "oneOf")) {
            if (resolved.has(union) && resolved.get(union).isJsonArray()) {
                for (var variant : resolved.getAsJsonArray(union)) {
                    if (variant.isJsonObject() && promptSuffices(variant.getAsJsonObject(), schemas, allowed)) return true;
                }
                return false;
            }
        }
        var properties = object(resolved, "properties");
        if (properties == null || !properties.has("prompt")) return false;
        if (!resolved.has("required") || !resolved.get("required").isJsonArray()) return true;
        for (var field : resolved.getAsJsonArray("required")) {
            if (!field.isJsonPrimitive() || !allowed.contains(field.getAsString())) return false;
        }
        return true;
    }

    private static @Nullable JsonObject resolve(@Nullable JsonObject schema, @Nullable JsonObject schemas) {
        if (schema == null || !schema.has(REF)) return schema;
        var ref = schema.get(REF).getAsString();
        return object(schemas, ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static boolean hasTag(JsonObject operation, String tag) {
        if (!operation.has("tags") || !operation.get("tags").isJsonArray()) return false;
        for (var t : operation.getAsJsonArray("tags")) {
            if (t.isJsonPrimitive() && tag.equals(t.getAsString())) return true;
        }
        return false;
    }

    private static @Nullable JsonObject object(@Nullable JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement child = parent.get(key);
        return child != null && child.isJsonObject() ? child.getAsJsonObject() : null;
    }

    private static @Nullable String string(JsonObject parent, String key) {
        var child = parent.get(key);
        return child != null && child.isJsonPrimitive() ? child.getAsString() : null;
    }
}
