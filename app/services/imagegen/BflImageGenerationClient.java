package services.imagegen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.ConfigService;
import utils.HttpFactories;

import java.io.IOException;

/**
 * Black Forest Labs (Flux) image-generation client (JCLAW-225). BFL's API is asynchronous even on the
 * simplest endpoints: a POST submits the job and returns a polling URL, which is polled until the
 * result is ready, then the produced image is fetched from a short-lived signed URL. Auth is the
 * {@code x-key} header (not Bearer), and the result arrives as a URL rather than inline base64 — so
 * this does NOT extend {@link OpenAiCompatibleImageGenerationClient}.
 *
 * <p>Configuration: {@code provider.bfl.baseUrl} (default {@code https://api.bfl.ai/v1}),
 * {@code provider.bfl.apiKey}, {@code imagegen.cloud.model} (default {@code flux-2-pro}),
 * {@code imagegen.timeoutSeconds} (poll deadline, default 60).
 */
public class BflImageGenerationClient implements ImageGenerationService {

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String KEY_HEADER = "x-key";
    private static final String DEFAULT_MODEL = "flux-2-pro";
    private static final long POLL_INTERVAL_MS = 1000L;

    private final OkHttpClient client;

    public BflImageGenerationClient() {
        this(HttpFactories.llmSingleShot());
    }

    /** Test seam — inject a MockWebServer-backed client. */
    public BflImageGenerationClient(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public GeneratedImage generate(String prompt, String model, Integer width, Integer height) {
        if (prompt == null || prompt.isBlank()) {
            throw new ImageGenerationException("image generation: prompt is required");
        }
        var baseUrl = ConfigService.get("provider.bfl.baseUrl");
        var apiKey = ConfigService.get("provider.bfl.apiKey");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ImageGenerationException("provider.bfl.baseUrl is not configured");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ImageGenerationException("provider.bfl.apiKey is not configured");
        }
        var effModel = firstNonBlank(model, ConfigService.get("imagegen.cloud.model"), DEFAULT_MODEL);
        int w = width != null ? width : 1024;
        int h = height != null ? height : 1024;

        var pollingUrl = submit(trimTrailingSlash(baseUrl), effModel, apiKey, prompt, w, h);
        var sampleUrl = pollUntilReady(pollingUrl, apiKey);
        return new GeneratedImage(fetchBytes(sampleUrl), "image/png", "bfl:" + effModel);
    }

    private String submit(String baseUrl, String model, String apiKey, String prompt, int w, int h) {
        var root = new JsonObject();
        root.addProperty("prompt", prompt);
        root.addProperty("width", w);
        root.addProperty("height", h);
        var request = new Request.Builder()
                .url(baseUrl + "/" + model)
                .header(KEY_HEADER, apiKey)
                .post(RequestBody.create(root.toString(), JSON))
                .build();
        try (var response = client.newCall(request).execute()) {
            var body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new ImageGenerationException("bfl submit failed: HTTP %d %s%s".formatted(
                        response.code(), response.message(), body.isEmpty() ? "" : (" — " + truncate(body, 500))));
            }
            var json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("polling_url") && !json.get("polling_url").isJsonNull()) {
                return json.get("polling_url").getAsString();
            }
            if (json.has("id") && !json.get("id").isJsonNull()) {
                return baseUrl + "/get_result?id=" + json.get("id").getAsString();
            }
            throw new ImageGenerationException("bfl submit response had no polling_url or id");
        } catch (IOException e) {
            throw new ImageGenerationException("bfl submit transport failed: " + e.getMessage(), e);
        }
    }

    private String pollUntilReady(String pollingUrl, String apiKey) {
        long timeoutMs = ConfigService.getInt("imagegen.timeoutSeconds", 60) * 1000L;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            var request = new Request.Builder().url(pollingUrl).header(KEY_HEADER, apiKey).get().build();
            try (var response = client.newCall(request).execute()) {
                var body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new ImageGenerationException("bfl poll failed: HTTP %d %s".formatted(
                            response.code(), response.message()));
                }
                var json = JsonParser.parseString(body).getAsJsonObject();
                var status = json.has("status") && !json.get("status").isJsonNull()
                        ? json.get("status").getAsString() : "";
                if ("Ready".equalsIgnoreCase(status)) {
                    var result = json.getAsJsonObject("result");
                    if (result != null && result.has("sample") && !result.get("sample").isJsonNull()) {
                        return result.get("sample").getAsString();
                    }
                    throw new ImageGenerationException("bfl result Ready but no sample URL");
                }
                if ("Error".equalsIgnoreCase(status) || "Failed".equalsIgnoreCase(status)) {
                    throw new ImageGenerationException("bfl generation failed: " + truncate(body, 500));
                }
                // Pending / Queued / Processing — keep polling until the deadline.
            } catch (IOException e) {
                throw new ImageGenerationException("bfl poll transport failed: " + e.getMessage(), e);
            }
            if (System.nanoTime() >= deadline) {
                throw new ImageGenerationException("bfl generation timed out after " + (timeoutMs / 1000) + "s");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ImageGenerationException("bfl generation interrupted", e);
            }
        }
    }

    private byte[] fetchBytes(String imageUrl) {
        var req = new Request.Builder().url(imageUrl).get().build();
        try (var resp = HttpFactories.general().newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new ImageGenerationException("bfl image fetch failed: HTTP " + resp.code());
            }
            return resp.body().bytes();
        } catch (IOException e) {
            throw new ImageGenerationException("bfl image fetch transport failed: " + e.getMessage(), e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (var v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
