package channels;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.Strings;

import java.util.concurrent.TimeUnit;

/**
 * Verifies a WhatsApp Cloud-API binding's credentials by probing Meta's Graph
 * API (JCLAW-445). A successful probe is proof the {@code phoneNumberId} names a
 * real, verified WhatsApp Business (WABA) number and that the {@code accessToken}
 * is authorized for it — so the CRUD controller can reject bad credentials at
 * save time rather than silently persisting a binding that will never deliver.
 *
 * <p>The probe is a single {@code GET https://graph.facebook.com/v21.0/{phoneNumberId}}
 * with {@code fields=verified_name,code_verification_status,display_phone_number}
 * and an {@code Authorization: Bearer {accessToken}} header. A 200 carrying a
 * {@code verified_name} is the success signal; any 4xx (or a 200 without a
 * verified name) is a {@link Failed} with the Graph error message surfaced
 * verbatim so the operator can act on it.
 *
 * <p>This is a fixed, operator-configured endpoint (graph.facebook.com), not an
 * LLM-supplied URL, so it rides the shared {@link HttpFactories#general()} client
 * with no SSRF guard — same trust boundary as {@link WhatsAppChannel}'s send path.
 */
public final class WhatsAppCloudApiProbe {

    private WhatsAppCloudApiProbe() {}

    private static final String API_BASE = "https://graph.facebook.com/v21.0/";
    private static final String FIELDS = "verified_name,code_verification_status,display_phone_number";
    private static final long PROBE_TIMEOUT_MS = 15_000L;

    /** The Graph error envelope's top-level key ({@code {"error":{"message":...}}}). */
    private static final String FIELD_ERROR = "error";

    /** Outcome of a credential probe. */
    public sealed interface Result permits Verified, Failed {}

    /**
     * The number is a verified WABA number this token can act on.
     *
     * @param verifiedName  Meta's verified display name for the business number
     * @param displayNumber the human-readable {@code display_phone_number} (may
     *                      be null if Meta omitted it)
     */
    public record Verified(String verifiedName, String displayNumber) implements Result {}

    /** The probe failed — bad token, wrong number id, unverified number, or a
     *  network error. {@code reason} is the Graph error message (or a transport
     *  description) suitable for surfacing to the operator. */
    public record Failed(String reason) implements Result {}

    /**
     * Test override: when non-null, {@link #probe(String, String)} returns this
     * function's result instead of hitting the Graph API — so functional CRUD tests
     * exercise the controller's 422/cache wiring without a network round-trip
     * (mirrors {@code TelegramChannel.installForTest}). Set via
     * {@link #installForTest}/cleared via {@link #clearForTest}. The
     * {@code apiBase} overload is unaffected (HTTP-level probe tests use it directly).
     */
    private static volatile java.util.function.BiFunction<String, String, Result> testOverride;

    /** Install a canned probe result for tests. */
    public static void installForTest(java.util.function.BiFunction<String, String, Result> override) {
        testOverride = override;
    }

    /** Remove the test override, restoring live Graph probing. */
    public static void clearForTest() {
        testOverride = null;
    }

    /**
     * Probe {@code phoneNumberId} with {@code accessToken} against the live Graph
     * API. Never throws — transport failures map to {@link Failed}. Honours a
     * {@link #installForTest} override when one is set.
     */
    public static Result probe(String phoneNumberId, String accessToken) {
        var override = testOverride;
        if (override != null) {
            return override.apply(phoneNumberId, accessToken);
        }
        return probe(phoneNumberId, accessToken, API_BASE);
    }

    /**
     * Overload exposing the API base URL for tests (mock HTTP server). Production
     * callers use {@link #probe(String, String)}. Public because jclaw tests live
     * in the default package and can't see package-private channel methods.
     */
    public static Result probe(String phoneNumberId, String accessToken, String apiBase) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) {
            return new Failed("phoneNumberId is required");
        }
        if (accessToken == null || accessToken.isBlank()) {
            return new Failed("accessToken is required");
        }

        var url = apiBase + phoneNumberId + "?fields=" + FIELDS;
        var request = new okhttp3.Request.Builder()
                .url(url)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + accessToken)
                .get()
                .build();
        var call = HttpFactories.general().newCall(request);
        call.timeout().timeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try (var response = call.execute()) {
            var body = response.body().string();
            if (response.code() == 200) {
                return parseOk(body);
            }
            return new Failed(graphError(body, response.code()));
        } catch (Exception e) {
            return new Failed("probe failed: " + e.getMessage());
        }
    }

    /**
     * Parse a 200 body. A verified WABA number carries a non-blank
     * {@code verified_name}; its absence means the number exists but isn't a
     * verified business number, which we treat as a failure (the operator must
     * complete Meta's verification first).
     */
    private static Result parseOk(String body) {
        JsonObject json;
        try {
            json = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception _) {
            return new Failed("unexpected Graph response: " + Strings.truncate(body, 200));
        }
        String verifiedName = optString(json, "verified_name");
        if (verifiedName == null) {
            return new Failed("number is not a verified WhatsApp Business number "
                    + "(no verified_name returned)");
        }
        return new Verified(verifiedName, optString(json, "display_phone_number"));
    }

    /**
     * Extract the human-readable message from a Graph error envelope
     * ({@code {"error":{"message":...}}}), falling back to the HTTP status when
     * the body isn't the expected shape.
     */
    private static String graphError(String body, int httpCode) {
        try {
            var json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has(FIELD_ERROR) && json.get(FIELD_ERROR).isJsonObject()) {
                var err = json.getAsJsonObject(FIELD_ERROR);
                var msg = optString(err, "message");
                if (msg != null) {
                    return msg;
                }
            }
        } catch (Exception _) {
            // Fall through to the generic message below.
        }
        return "Graph API HTTP " + httpCode + ": " + Strings.truncate(body, 200);
    }

    private static String optString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        var s = json.get(key).getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }
}
