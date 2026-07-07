package services.transcription;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Logger;
import services.ConfigService;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for the local ASR sidecar (JCLAW-565 lineage; ASR-only since
 * JCLAW-654): plain transcription plus the Settings page's ASR model
 * status/prefetch. Requests carry the audio by absolute path — both
 * processes share the host. A JVM-wide fair lock serializes calls so
 * concurrent conversations queue instead of thrashing the GPU.
 */
public class AsrSidecarClient {


    private static final MediaType JSON = MediaType.get("application/json");

    /** JCLAW-620: the sidecar is one-inference-at-a-time by design (HTTP
     *  409 when busy). Serialize all sidecar calls JVM-wide with a FAIR
     *  lock so concurrent conversations queue instead of surfacing a
     *  retryable busy condition as a user-facing failure. */
    private static final java.util.concurrent.locks.ReentrantLock SIDECAR_LOCK =
            new java.util.concurrent.locks.ReentrantLock(true);

    private final String baseUrlOverride;
    private final OkHttpClient client;

    public AsrSidecarClient() {
        // Derived from the shared general client (reuses its pool/dispatcher)
        // but with the per-read socket timeout lifted: the sidecar's first
        // /diarize lazily loads the pipeline (gated download + torch import)
        // and legitimately sends nothing for minutes. The JCLAW-565 UAT
        // caught general()'s 30s read timeout firing mid-load while the
        // sidecar went on to succeed. DELIBERATE TRADEOFF (JCLAW-626): with
        // readTimeout=0, a genuinely hung socket is bounded ONLY by the
        // 1800s per-call callTimeout set on every request below — do not
        // "fix" the zero without replacing that bound.
        this(null, HttpFactories.general().newBuilder()
                .readTimeout(java.time.Duration.ZERO)
                .build());
    }

    /** Test seam: fixed base URL (no sidecar spawn) + injected client. */
    public AsrSidecarClient(String baseUrlOverride, OkHttpClient client) {
        this.baseUrlOverride = baseUrlOverride;
        this.client = client;
    }












    /**
     * GPU ASR via the sidecar (JCLAW-627): mlx-whisper on Apple silicon,
     * faster-whisper elsewhere — same weights as whisper.cpp, 5-20x faster.
     * First call may build the script env and download weights.
     */
    public List<WhisperTranscriber.Segment> transcribe(Path audioFile, String model,
                                                          String language) {
        SIDECAR_LOCK.lock();
        try {
            return transcribeLocked(audioFile, model, language);
        } finally {
            SIDECAR_LOCK.unlock();
        }
    }

    private List<WhisperTranscriber.Segment> transcribeLocked(Path audioFile, String model,
                                                                 String language) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : AsrSidecarManager.ensureRunning();
        var body = new JsonObject();
        body.addProperty("audio_path", audioFile.toAbsolutePath().toString());
        body.addProperty("model", model);
        if (language != null && !language.isBlank()) body.addProperty("language", language);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/transcribe")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        call.timeout().timeout(ConfigService.getInt(
                AsrSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException(
                        "pyannote sidecar transcribe failed: HTTP %d — %s".formatted(
                                resp.code(), truncate(text)));
            }
            var root = JsonParser.parseString(text).getAsJsonObject();
            var segments = new ArrayList<WhisperTranscriber.Segment>();
            for (var el : root.getAsJsonArray("segments")) {
                var o = el.getAsJsonObject();
                segments.add(new WhisperTranscriber.Segment(
                        o.get("startMs").getAsLong(), o.get("endMs").getAsLong(),
                        o.get("text").getAsString(),
                        o.has("noSpeechProb") ? o.get("noSpeechProb").getAsDouble() : 0.0,
                        o.has("avgLogprob") ? o.get("avgLogprob").getAsDouble() : 0.0,
                        o.has("compressionRatio") ? o.get("compressionRatio").getAsDouble() : 1.0));
            }
            return segments;
        } catch (IOException e) {
            throw new TranscriptionException(
                    "pyannote sidecar unreachable: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            if (e instanceof TranscriptionException te) throw te;
            throw new TranscriptionException(
                    "pyannote sidecar returned an unparseable transcribe response: " + e.getMessage(), e);
        }
    }


    /**
     * JCLAW-656: local audio-LLM generation — audio paths + prompt to the
     * sidecar's Qwen2-Audio worker, text back. Serialized on the JVM-wide
     * sidecar lock like every other inference.
     */
    public String qwenGenerate(java.util.List<Path> audios, String prompt, int maxTokens) {
        SIDECAR_LOCK.lock();
        try {
            var baseUrl = baseUrlOverride != null ? baseUrlOverride : AsrSidecarManager.ensureRunning();
            var body = new com.google.gson.JsonObject();
            var arr = new com.google.gson.JsonArray();
            for (var a : audios) arr.add(a.toAbsolutePath().toString());
            body.add("audios", arr);
            body.addProperty("prompt", prompt);
            body.addProperty("max_tokens", maxTokens);
            var call = client.newCall(new okhttp3.Request.Builder()
                    .url(baseUrl + "/qwen")
                    .post(okhttp3.RequestBody.create(body.toString(), JSON))
                    .build());
            try (var response = call.execute()) {
                var text = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new TranscriptionException("local audio model failed (HTTP %d): %s"
                            .formatted(response.code(), text.substring(0, Math.min(300, text.length()))));
                }
                return com.google.gson.JsonParser.parseString(text).getAsJsonObject()
                        .get("text").getAsString();
            }
        } catch (java.io.IOException e) {
            throw new TranscriptionException("local audio model unreachable: " + e.getMessage(), e);
        } finally {
            SIDECAR_LOCK.unlock();
        }
    }

    /** JCLAW-656: local audio-LLM install status for the Settings page —
     *  filesystem-only on the sidecar side, no model load. */
    public com.google.gson.JsonObject qwenStatus() {
        try {
            var baseUrl = baseUrlOverride != null ? baseUrlOverride : AsrSidecarManager.ensureRunning();
            var call = client.newCall(new okhttp3.Request.Builder()
                    .url(baseUrl + "/qwen/status").get().build());
            try (var response = call.execute()) {
                var text = response.body() != null ? response.body().string() : "{}";
                return com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            }
        } catch (java.io.IOException | RuntimeException e) {
            var o = new com.google.gson.JsonObject();
            o.addProperty("installed", false);
            o.addProperty("error", e.getMessage());
            return o;
        }
    }

    /** JCLAW-650: host-relevant ASR artifact status (raw JSON body). */
    public String asrModels(String commaSeparatedIds) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : AsrSidecarManager.ensureRunning();
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/asr/models?ids=" + commaSeparatedIds).get().build());
        call.timeout().timeout(30, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException("asr status failed: HTTP %d — %s"
                        .formatted(resp.code(), truncate(text)));
            }
            return text;
        } catch (IOException e) {
            throw new TranscriptionException("pyannote sidecar unreachable: " + e.getMessage(), e);
        }
    }

    /** JCLAW-650: download the host engine's weights for a model id.
     *  Synchronous; AsrModelStore wraps it in an async single-flight. */
    public String asrPrefetch(String modelId) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : AsrSidecarManager.ensureRunning();
        var body = new JsonObject();
        body.addProperty("model", modelId);
        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/asr/prefetch")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        call.timeout().timeout(ConfigService.getInt(
                AsrSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException("asr prefetch failed: HTTP %d — %s"
                        .formatted(resp.code(), truncate(text)));
            }
            return text;
        } catch (IOException e) {
            throw new TranscriptionException("pyannote sidecar unreachable: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        var oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}
