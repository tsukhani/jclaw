package channels;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jspecify.annotations.Nullable;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.JsonArgs;
import utils.Strings;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Whether the Meta app a Cloud API binding's token belongs to is subscribed to
 * the number's WhatsApp Business Account, and the operation that subscribes it
 * (JCLAW-1410). Without that subscription Meta delivers no webhook at all, even
 * though the credential probe and webhook verification both pass.
 *
 * <p>The phone number node has no field naming its account, so both the WABA id
 * and the app id come from {@code GET {phoneNumberId}?fields=health_status}.
 * Shares {@link WhatsAppCloudApiProbe}'s base URL, timeout and error parser.
 */
public final class WhatsAppSubscription {

    private WhatsAppSubscription() {}

    /** Outcome of a subscription check. */
    public sealed interface State permits Subscribed, NotSubscribed, Unknown {}

    /** {@code subscribed_apps} lists the app. */
    public record Subscribed(String wabaId, String appId) implements State {}

    /** {@code subscribed_apps} answered 200 and the app is absent. */
    public record NotSubscribed(String wabaId, String appId) implements State {}

    /** The check could not reach a verdict; {@code reason} never carries the token. */
    public record Unknown(String reason) implements State {}

    /** Outcome of a subscribe call. */
    public sealed interface SubscribeResult permits Done, Failed {}

    /** Meta answered 200 with {@code "success": true}. */
    public record Done(String wabaId) implements SubscribeResult {}

    /** Meta's error message, or a transport description; never carries the token. */
    public record Failed(String reason) implements SubscribeResult {}

    /** {@code appId} is null when health_status names no APP entity. */
    record Ids(String wabaId, @Nullable String appId) {}

    sealed interface Lookup permits Found, NotFound {}

    record Found(Ids ids) implements Lookup {}

    record NotFound(String reason) implements Lookup {}

    record GraphResponse(int code, String body) {}

    private static final String NO_APP = "Graph named no app for this access token";

    /**
     * Test overrides honoured by the two-argument entries. Not replaceable by
     * {@code HttpFactories.runWith}: the callers are {@code FunctionalTest}s driving
     * a controller, and a {@code ScopedValue} binding does not reach Play's request thread.
     */
    private static volatile @Nullable BiFunction<String, String, State> checkOverride;
    private static volatile @Nullable BiFunction<String, String, SubscribeResult> subscribeOverride;

    /** Install canned check and subscribe results for tests. */
    public static void installForTest(BiFunction<String, String, State> check,
                                      BiFunction<String, String, SubscribeResult> subscribe) {
        checkOverride = check;
        subscribeOverride = subscribe;
    }

    /** Remove the test overrides, restoring live Graph calls. */
    public static void clearForTest() {
        checkOverride = null;
        subscribeOverride = null;
    }

    /** Check against the live Graph API, honouring an {@link #installForTest} override. Never throws. */
    public static State check(String phoneNumberId, String accessToken) {
        var override = checkOverride;
        if (override != null) {
            return override.apply(phoneNumberId, accessToken);
        }
        return check(phoneNumberId, accessToken, WhatsAppCloudApiProbe.API_BASE);
    }

    /** Check against {@code apiBase}; never consults the test override. Never throws. */
    public static State check(@Nullable String phoneNumberId, @Nullable String accessToken, String apiBase) {
        if (phoneNumberId == null || phoneNumberId.isBlank()
                || accessToken == null || accessToken.isBlank()) {
            return new Unknown("phoneNumberId and accessToken are required");
        }
        var lookup = resolveIds(phoneNumberId, accessToken, apiBase);
        if (lookup instanceof NotFound(String reason)) {
            return new Unknown(reason);
        }
        var ids = ((Found) lookup).ids();
        var appId = ids.appId();
        if (appId == null) {
            return new Unknown(NO_APP);
        }
        GraphResponse response;
        try {
            response = send(get(apiBase + ids.wabaId() + "/subscribed_apps", accessToken));
        } catch (Exception e) {
            return new Unknown(transportError(e));
        }
        if (response.code() != 200) {
            return new Unknown(WhatsAppCloudApiProbe.graphError(response.body(), response.code()));
        }
        JsonElement data;
        try {
            data = JsonParser.parseString(response.body()).getAsJsonObject().get("data");
        } catch (Exception _) {
            return new Unknown(unexpected(response.body()));
        }
        if (data == null || !data.isJsonArray()) {
            return new Unknown(unexpected(response.body()));
        }
        for (var entry : data.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            var api = entry.getAsJsonObject().get("whatsapp_business_api_data");
            if (api != null && api.isJsonObject()
                    && appId.equals(JsonArgs.optNonBlankString(api.getAsJsonObject(), "id"))) {
                return new Subscribed(ids.wabaId(), appId);
            }
        }
        return new NotSubscribed(ids.wabaId(), appId);
    }

    /** Subscribe against the live Graph API, honouring an {@link #installForTest} override. Never throws. */
    public static SubscribeResult subscribe(String phoneNumberId, String accessToken) {
        var override = subscribeOverride;
        if (override != null) {
            return override.apply(phoneNumberId, accessToken);
        }
        return subscribe(phoneNumberId, accessToken, WhatsAppCloudApiProbe.API_BASE);
    }

    /** Subscribe against {@code apiBase}; never consults the test override. Never throws. */
    public static SubscribeResult subscribe(@Nullable String phoneNumberId, @Nullable String accessToken,
                                            String apiBase) {
        if (phoneNumberId == null || phoneNumberId.isBlank()
                || accessToken == null || accessToken.isBlank()) {
            return new Failed("phoneNumberId and accessToken are required");
        }
        var lookup = resolveIds(phoneNumberId, accessToken, apiBase);
        if (lookup instanceof NotFound(String reason)) {
            return new Failed(reason);
        }
        var ids = ((Found) lookup).ids();
        if (ids.appId() == null) {
            return new Failed(NO_APP);
        }
        var wabaId = ids.wabaId();
        GraphResponse response;
        try {
            response = send(authorized(apiBase + wabaId + "/subscribed_apps", accessToken)
                    .post(RequestBody.create(new byte[0]))
                    .build());
        } catch (Exception e) {
            return new Failed(transportError(e));
        }
        if (response.code() != 200) {
            return new Failed(WhatsAppCloudApiProbe.graphError(response.body(), response.code()));
        }
        try {
            var success = JsonParser.parseString(response.body()).getAsJsonObject().get("success");
            if (success != null && success.isJsonPrimitive() && success.getAsJsonPrimitive().isBoolean()
                    && success.getAsBoolean()) {
                return new Done(wabaId);
            }
        } catch (Exception _) {
            // Falls through to the failure below.
        }
        return new Failed(unexpected(response.body()));
    }

    /**
     * The first WABA and APP entities with a non-blank id in {@code health_status.entities};
     * {@link NotFound} without a WABA. The one WABA lookup, shared with {@link WhatsAppUsage}.
     */
    static Lookup resolveIds(String phoneNumberId, String accessToken, String apiBase) {
        GraphResponse response;
        try {
            response = send(get(apiBase + phoneNumberId + "?fields=health_status", accessToken));
        } catch (Exception e) {
            return new NotFound(transportError(e));
        }
        if (response.code() != 200) {
            return new NotFound(WhatsAppCloudApiProbe.graphError(response.body(), response.code()));
        }
        JsonElement entities;
        try {
            var health = JsonParser.parseString(response.body()).getAsJsonObject().get("health_status");
            entities = health != null && health.isJsonObject()
                    ? health.getAsJsonObject().get("entities") : null;
        } catch (Exception _) {
            return new NotFound(unexpected(response.body()));
        }
        if (entities == null || !entities.isJsonArray()) {
            return new NotFound("Graph returned no health_status entities for this number");
        }
        String wabaId = null;
        String appId = null;
        for (var entity : entities.getAsJsonArray()) {
            if (!entity.isJsonObject()) continue;
            JsonObject obj = entity.getAsJsonObject();
            var type = JsonArgs.optNonBlankString(obj, "entity_type");
            var id = JsonArgs.optNonBlankString(obj, "id");
            if (id == null) continue;
            if ("WABA".equals(type) && wabaId == null) wabaId = id;
            else if ("APP".equals(type) && appId == null) appId = id;
        }
        if (wabaId == null) {
            return new NotFound("Graph named no WhatsApp Business Account for this number");
        }
        return new Found(new Ids(wabaId, appId));
    }

    private static Request.Builder authorized(String url, String accessToken) {
        return new Request.Builder()
                .url(url)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + accessToken);
    }

    static Request get(String url, String accessToken) {
        return authorized(url, accessToken).get().build();
    }

    static GraphResponse send(Request request) throws IOException {
        var call = HttpFactories.general().newCall(request);
        call.timeout().timeout(WhatsAppCloudApiProbe.PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try (var response = call.execute()) {
            return new GraphResponse(response.code(), response.body().string());
        }
    }

    // OkHttp exception messages name the host or the failure, never a request header.
    static String transportError(Exception e) {
        return "Graph request failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    static String unexpected(String body) {
        return "unexpected Graph response: " + Strings.truncate(body, 200);
    }
}
