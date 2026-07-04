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
 * HTTP client for the pyannote diarization sidecar (JCLAW-565). Sends the
 * audio file <b>by absolute path</b> — both processes share a filesystem, so
 * streaming megabytes through the localhost socket would be waste — and maps
 * the response into the same {@link SherpaDiarizer.SpeakerSegment} shape the
 * rest of the pipeline consumes, so {@link DiarizedTranscript},
 * {@link SpeakerNamer} and the emotion annotator are backend-agnostic.
 */
public class PyannoteDiarizationClient {

    private static final MediaType JSON = MediaType.get("application/json");

    private final String baseUrlOverride;
    private final OkHttpClient client;

    public PyannoteDiarizationClient() {
        // Derived from the shared general client (reuses its pool/dispatcher)
        // but with the per-read socket timeout lifted: the sidecar's first
        // /diarize lazily loads the pipeline (gated download + torch import)
        // and legitimately sends nothing for minutes. The JCLAW-565 UAT
        // caught general()'s 30s read timeout firing mid-load and falling
        // back to sherpa while the sidecar went on to succeed. The per-call
        // callTimeout below still bounds the whole request.
        this(null, HttpFactories.general().newBuilder()
                .readTimeout(java.time.Duration.ZERO)
                .build());
    }

    /** Test seam: fixed base URL (no sidecar spawn) + injected client. */
    public PyannoteDiarizationClient(String baseUrlOverride, OkHttpClient client) {
        this.baseUrlOverride = baseUrlOverride;
        this.client = client;
    }

    /**
     * Diarize {@code audioFile}. Blocking; ensures the sidecar is running
     * first (which may pay the one-time env/model download). Throws
     * {@link TranscriptionException} on any sidecar or protocol failure —
     * {@link DiarizationRouter} decides whether that falls back to sherpa.
     *
     * @param numSpeakers exact speaker count when known, or any value below 2
     *                    to let the pipeline find the count itself
     */
    public List<SherpaDiarizer.SpeakerSegment> diarize(Path audioFile, int numSpeakers) {
        var baseUrl = baseUrlOverride != null ? baseUrlOverride : PyannoteSidecarManager.ensureRunning();

        var body = new JsonObject();
        body.addProperty("audio_path", audioFile.toAbsolutePath().toString());
        if (numSpeakers >= 2) body.addProperty("num_speakers", numSpeakers);

        var call = client.newCall(new Request.Builder()
                .url(baseUrl + "/diarize")
                .post(RequestBody.create(body.toString(), JSON))
                .build());
        // Diarization is ~18x realtime on MPS but ~0.2x on CPU — the deadline
        // must cover an hour-long recording on a CPU-only host.
        call.timeout().timeout(ConfigService.getInt(
                PyannoteSidecarManager.CONFIG_PREFIX + ".timeoutSeconds", 1800), TimeUnit.SECONDS);

        try (var resp = call.execute()) {
            var text = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new TranscriptionException(
                        "pyannote sidecar diarize failed: HTTP %d — %s".formatted(
                                resp.code(), truncate(text)));
            }
            return parseSegments(text);
        } catch (IOException e) {
            throw new TranscriptionException(
                    "pyannote sidecar unreachable: " + e.getMessage(), e);
        }
    }

    /** Parse the sidecar's {@code {segments:[{start,end,speaker}...]}} response.
     *  Public (like WhisperJniTranscriber.applyLanguage) so tests reach it from
     *  the default package. */
    public static List<SherpaDiarizer.SpeakerSegment> parseSegments(String json) {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            var device = root.has("device") && !root.get("device").isJsonNull()
                    ? root.get("device").getAsString() : "?";
            var arr = root.getAsJsonArray("segments");
            var segments = new ArrayList<SherpaDiarizer.SpeakerSegment>(arr.size());
            for (var el : arr) {
                var o = el.getAsJsonObject();
                segments.add(new SherpaDiarizer.SpeakerSegment(
                        o.get("start").getAsDouble(),
                        o.get("end").getAsDouble(),
                        o.get("speaker").getAsInt()));
            }
            Logger.info("PyannoteDiarizationClient: %d segments (device=%s)", segments.size(), device);
            return segments;
        } catch (RuntimeException e) {
            throw new TranscriptionException(
                    "pyannote sidecar returned an unparseable response: " + truncate(json), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        var oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }
}
