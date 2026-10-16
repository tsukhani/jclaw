package services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jspecify.annotations.Nullable;
import utils.HttpKeys;
import utils.Strings;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Shared Black Forest Labs task transport. Every BFL model, image or video, sits behind one async
 * shape: a POST to {@code {baseUrl}/{model}} returns a {@code polling_url}, which is polled with the
 * {@code x-key} header until its status reaches {@code Ready} and {@code result.sample} holds a
 * short-lived signed URL. {@code BflImageGenerationClient} and {@code BflVideoGenerationClient} wrap
 * this with their request bodies and map {@link BflException} onto their own typed exceptions.
 */
public final class BflTasks {

    private static final MediaType JSON = MediaType.parse(HttpKeys.APPLICATION_JSON);
    private static final String KEY_HEADER = "x-key";
    private static final String POLLING_URL = "polling_url";
    private static final String STATUS = "status";
    private static final String SAMPLE = "sample";

    // Terminal statuses short of Ready. Anything else (Pending, Reasoning, Generating, Task not found)
    // keeps polling until the caller's deadline.
    private static final Set<String> FAILED_STATUSES =
            Set.of("error", "failed", "request moderated", "content moderated");

    public enum State { PENDING, READY, FAILED }

    /**
     * One poll outcome. {@code sampleUrl} is set only on {@link State#READY}, and may still be null
     * when BFL reports Ready without one; {@code error} is set only on {@link State#FAILED}.
     */
    public record Poll(State state, @Nullable String sampleUrl, @Nullable String error) {}

    /** A submit or poll request that failed at the transport or HTTP level, with a caller-ready message. */
    public static final class BflException extends RuntimeException {
        private final String detail;

        BflException(String detail) {
            super(detail);
            this.detail = detail;
        }

        BflException(String detail, Throwable cause) {
            super(detail, cause);
            this.detail = detail;
        }

        /** Narrows {@link Throwable#getMessage()}: every BflException carries one. */
        @Override
        public String getMessage() {
            return detail;
        }
    }

    private final OkHttpClient client;

    public BflTasks(OkHttpClient client) {
        this.client = client;
    }

    /**
     * Submit a task and return the URL to poll for it.
     *
     * @throws BflException on a non-2xx response, a transport failure, or a response naming no task
     */
    public String submit(String baseUrl, String model, String apiKey, JsonObject body) {
        var request = new Request.Builder()
                .url(baseUrl + "/" + model)
                .header(KEY_HEADER, apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (var response = client.newCall(request).execute()) {
            var text = response.body().string();
            if (!response.isSuccessful()) {
                throw new BflException("bfl submit failed: HTTP %d %s%s".formatted(
                        response.code(), response.message(),
                        text.isEmpty() ? "" : (" — " + Strings.truncate(text, Strings.ERROR_SNIPPET_MAX_CHARS))));
            }
            var json = JsonParser.parseString(text).getAsJsonObject();
            if (json.has(POLLING_URL) && !json.get(POLLING_URL).isJsonNull()) {
                return json.get(POLLING_URL).getAsString();
            }
            if (json.has("id") && !json.get("id").isJsonNull()) {
                return baseUrl + "/get_result?id=" + json.get("id").getAsString();
            }
            throw new BflException("bfl submit response had no polling_url or id");
        } catch (IOException e) {
            throw new BflException("bfl submit transport failed: " + e.getMessage(), e);
        }
    }

    /**
     * Poll a task once.
     *
     * @throws BflException on a non-2xx response or a transport failure; a failed generation is a
     *                      {@link State#FAILED} result instead
     */
    public Poll poll(String pollingUrl, String apiKey) {
        var request = new Request.Builder().url(pollingUrl).header(KEY_HEADER, apiKey).get().build();
        try (var response = client.newCall(request).execute()) {
            var text = response.body().string();
            if (!response.isSuccessful()) {
                throw new BflException("bfl poll failed: HTTP %d %s".formatted(response.code(), response.message()));
            }
            var json = JsonParser.parseString(text).getAsJsonObject();
            var status = json.has(STATUS) && !json.get(STATUS).isJsonNull()
                    ? json.get(STATUS).getAsString() : "";
            if ("Ready".equalsIgnoreCase(status)) {
                return new Poll(State.READY, sampleUrl(json), null);
            }
            if (FAILED_STATUSES.contains(status.toLowerCase(Locale.ROOT))) {
                return new Poll(State.FAILED, null,
                        "bfl generation failed: " + Strings.truncate(text, Strings.ERROR_SNIPPET_MAX_CHARS));
            }
            return new Poll(State.PENDING, null, null);
        } catch (IOException e) {
            throw new BflException("bfl poll transport failed: " + e.getMessage(), e);
        }
    }

    private static @Nullable String sampleUrl(JsonObject json) {
        var result = json.has("result") && json.get("result").isJsonObject() ? json.getAsJsonObject("result") : null;
        return result != null && result.has(SAMPLE) && !result.get(SAMPLE).isJsonNull()
                ? result.get(SAMPLE).getAsString() : null;
    }
}
