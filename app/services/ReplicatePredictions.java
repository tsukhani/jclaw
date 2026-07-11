package services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import utils.HttpKeys;

import java.io.IOException;

/**
 * Shared Replicate predictions transport (JCLAW-708). Replicate runs hosted image and video models
 * behind a single async predictions API: {@code POST /v1/models/{owner}/{model}/predictions} creates
 * a prediction (optionally with {@code Prefer: wait} to run it inline for ~60s), and a Bearer-authed
 * {@code GET} on the prediction's {@code urls.get} (or {@code /predictions/{id}}) polls it to a
 * terminal status whose {@code output} is a result URL (or array of URLs).
 *
 * <p>Both {@code ReplicateImageGenerationClient} and {@code ReplicateVideoGenerationClient} wrap this
 * with their media-specific input-building and output-mapping; the create/poll transport, Bearer auth,
 * and output/error extraction live here. HTTP-error and transport failures surface as
 * {@link ReplicateException} carrying the structured detail (code/message/body, or cause) each caller
 * needs to format its own typed, media-specific message.
 */
public final class ReplicatePredictions {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String OUTPUT = "output";
    private static final String ERROR = "error";

    private final OkHttpClient client;

    public ReplicatePredictions(OkHttpClient client) {
        this.client = client;
    }

    /**
     * Create a prediction. {@code input} is wrapped as {@code {"input": …}}; {@code preferWait} adds
     * the {@code Prefer: wait} header (run inline up to ~60s, then fall back to polling). Returns the
     * parsed prediction JSON.
     *
     * @throws ReplicateException on a non-2xx response (code/message/body populated) or a transport failure
     */
    public JsonObject create(String baseUrl, String model, String apiKey, JsonObject input, boolean preferWait) {
        var root = new JsonObject();
        root.add("input", input);
        var builder = new Request.Builder()
                .url(baseUrl + "/models/" + model + "/predictions")
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey);
        if (preferWait) {
            builder.header("Prefer", "wait"); // run inline up to ~60s, then fall back to polling
        }
        return execute(builder.post(RequestBody.create(root.toString(), JSON)).build());
    }

    /**
     * Fetch/poll a prediction at an absolute URL (Bearer-authed GET). Returns the parsed prediction JSON.
     *
     * @throws ReplicateException on a non-2xx response or a transport failure
     */
    public JsonObject get(String url, String apiKey) {
        return execute(new Request.Builder()
                .url(url).header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey).get().build());
    }

    private JsonObject execute(Request request) {
        try (var response = client.newCall(request).execute()) {
            var body = response.body().string();
            if (!response.isSuccessful()) {
                throw new ReplicateException(response.code(), response.message(), body);
            }
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (IOException e) {
            throw new ReplicateException(e);
        }
    }

    /** {@code output} is a result URL string or an array of URL strings — the first, or {@code null}
     *  when absent, JSON-null, or an empty array. Callers that require an output throw on null. */
    public static String firstOutputUrl(JsonObject prediction) {
        if (!prediction.has(OUTPUT) || prediction.get(OUTPUT).isJsonNull()) return null;
        var output = prediction.get(OUTPUT);
        if (output.isJsonArray()) {
            JsonArray arr = output.getAsJsonArray();
            return arr.isEmpty() ? null : arr.get(0).getAsString();
        }
        return output.getAsString();
    }

    /** Build a failure message from a terminal prediction: its {@code error} field when present, else
     *  a generic status message. */
    public static String extractError(JsonObject prediction, String status) {
        if (prediction.has(ERROR) && !prediction.get(ERROR).isJsonNull()) {
            return "replicate " + status + ": " + prediction.get(ERROR).getAsString();
        }
        return "replicate prediction " + status;
    }

    /**
     * Neutral failure from the shared Replicate transport — either an HTTP-error response
     * ({@link #code()}/{@link #statusMessage()}/{@link #body()} populated) or a transport failure
     * ({@link #isTransport()} true, cause populated). Each media client catches it and re-throws its
     * own typed exception with a media-specific message.
     */
    public static final class ReplicateException extends RuntimeException {

        private final int code;
        private final String statusMessage;
        private final String body;

        ReplicateException(int code, String statusMessage, String body) {
            super("replicate HTTP " + code);
            this.code = code;
            this.statusMessage = statusMessage;
            this.body = body;
        }

        ReplicateException(IOException cause) {
            super(cause.getMessage(), cause);
            this.code = 0;
            this.statusMessage = null;
            this.body = null;
        }

        /** True when this wraps a transport (I/O) failure rather than an HTTP-error response. */
        public boolean isTransport() {
            return getCause() != null;
        }

        public int code() {
            return code;
        }

        public String statusMessage() {
            return statusMessage;
        }

        public String body() {
            return body;
        }
    }
}
