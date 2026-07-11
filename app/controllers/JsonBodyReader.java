package controllers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.mvc.Http;
import utils.ApiResponses;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Shared JSON body parsing for API controllers. Previously duplicated as a
 * private static {@code readJsonBody()} in every {@code Api*Controller}.
 */
public final class JsonBodyReader {

    private JsonBodyReader() { /* static-only utility */ }

    /**
     * Parse the current request body as a {@link JsonObject}. Returns
     * {@code null} on any parse failure — callers should follow with
     * {@code badRequest()} when null.
     */
    public static JsonObject readJsonBody() {
        try (var reader = new InputStreamReader(Http.Request.current().body, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Read an optional string field. Returns {@code null} when the key is
     * absent, JSON-null, or resolves to a blank string. When {@code trim} is
     * true the returned value is trimmed (blank-after-trim still collapses to
     * {@code null}); when false the raw value is returned verbatim.
     */
    public static String optString(JsonObject body, String key, boolean trim) {
        if (!body.has(key)) return null;
        var el = body.get(key);
        if (el.isJsonNull()) return null;
        var s = el.getAsString();
        if (s == null || s.isBlank()) return null;
        return trim ? s.trim() : s;
    }

    /**
     * Read a required string field, returning {@code null} (never throwing) when the key is absent,
     * JSON-null, or blank; the value is trimmed otherwise. Callers follow a {@code null} with their own
     * aggregated {@code error()} so a single response can name every missing field.
     */
    public static String requiredString(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) return null;
        String s = body.get(key).getAsString();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Read a required string field, sending a 400 (via {@link ApiResponses#error}) when the key is
     * absent, JSON-null, or blank. Returns the raw (untrimmed) value on success. For callers that fail
     * fast on the first missing field rather than aggregating.
     */
    // Sonar java:S2259: ApiResponses.error() never returns (throws a Play result), so body.get(key)
    // below is non-null — the analyzer can't see the throw.
    @SuppressWarnings("java:S2259")
    public static String requiredOr400(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Field '%s' is required".formatted(key));
        }
        var s = body.get(key).getAsString();
        if (s.isBlank()) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Field '%s' must not be blank".formatted(key));
        return s;
    }
}
