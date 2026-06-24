package services.imagegen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import play.Logger;
import play.Play;
import services.ConfigService;
import services.EventLogger;
import utils.HttpFactories;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Orchestrates download of the local Flux model weights (JCLAW-226). The
 * JCLAW-509 spike chose to have the Python sidecar own the actual fetch (via
 * {@code huggingface_hub}, which knows the exact multi-file diffusers set), with
 * this manager <em>triggering</em> it and <em>relaying</em> progress to the
 * Settings UI. So this is the status/orchestration half of
 * {@link services.transcription.WhisperModelManager}'s pattern — same state
 * machine, single-flight coalescing, and pollable {@link ModelStatus} snapshot —
 * but the bytes move inside the sidecar's {@code POST /pull}, not here.
 *
 * <p>Weights land under {@code data/flux-models/} (jclaw's data/ convention) in
 * the Hugging Face cache layout. {@link #availableLocally} checks that layout so
 * a model pulled in a previous session is recognised without a running sidecar.
 */
public final class FluxModelManager {

    private static final String DEFAULT_MODEL = "black-forest-labs/FLUX.2-klein-4B";
    private static final ConcurrentHashMap<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ModelStatus> statuses = new ConcurrentHashMap<>();

    private FluxModelManager() {}

    /** State machine for the local model's availability. */
    public enum State { ABSENT, DOWNLOADING, AVAILABLE, ERROR }

    /**
     * Snapshot for status polling.
     *
     * @param state           current availability state
     * @param bytesDownloaded bytes pulled so far (0 unless DOWNLOADING)
     * @param totalBytes      expected total (0 when unknown)
     * @param error           error message; non-null only when {@code state == ERROR}
     */
    public record ModelStatus(State state, long bytesDownloaded, long totalBytes, String error) {}

    /** Progress event passed to the optional callback as ndjson lines arrive. */
    public record DownloadProgress(String model, long bytesDownloaded, long totalBytes) {}

    /** The configured local model repo id (Settings-editable). */
    public static String configuredModel() {
        return ConfigService.get("imagegen.local.model", DEFAULT_MODEL);
    }

    /**
     * Heuristic local-presence check: the Hugging Face cache puts a repo at
     * {@code <cache>/models--{org}--{repo}/snapshots/{rev}/...}. We treat a
     * non-empty snapshots dir as present. This is a proxy, not a completeness
     * proof — an interrupted pull can leave a partial snapshot — but the sidecar
     * returns 409 on a genuinely incomplete model and {@code hf_hub} resumes on
     * the next pull, so the proxy is safe.
     */
    public static boolean availableLocally(String model) {
        var snapshots = cacheRoot()
                .resolve("models--" + model.replace("/", "--"))
                .resolve("snapshots");
        if (!Files.isDirectory(snapshots)) return false;
        try (var entries = Files.list(snapshots)) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    static Path cacheRoot() {
        return new File(Play.applicationPath, "data/flux-models").toPath();
    }

    /**
     * Best-effort current status. In-flight DOWNLOADING trusts the in-memory
     * cache (a runtime signal the filesystem can't reflect); otherwise reconciles
     * against the local cache so a previously-pulled model reports AVAILABLE and a
     * deleted one settles back to ABSENT.
     */
    public static ModelStatus status(String model) {
        var live = statuses.get(model);
        if (live != null && live.state() == State.DOWNLOADING) {
            return live;
        }
        if (availableLocally(model)) {
            if (live != null && live.state() == State.AVAILABLE) return live;
            var fresh = new ModelStatus(State.AVAILABLE, 0, 0, null);
            statuses.put(model, fresh);
            return fresh;
        }
        // Absent on disk: drop a stale AVAILABLE entry (deleted out-of-band).
        if (live != null && live.state() == State.AVAILABLE) {
            statuses.remove(model);
        }
        if (live != null && live.state() == State.ERROR) return live;
        return new ModelStatus(State.ABSENT, 0, 0, null);
    }

    /**
     * Ensure the model is downloaded, returning a future that completes when the
     * sidecar's pull finishes. Concurrent callers for the same model coalesce onto
     * one pull. {@code onProgress} may be null; when supplied it is invoked from
     * the pull thread as ndjson progress lines arrive (only the first caller's
     * callback is wired up — others poll {@link #status}).
     */
    public static CompletableFuture<Void> ensureAvailable(String model, Consumer<DownloadProgress> onProgress) {
        if (availableLocally(model)) {
            return CompletableFuture.completedFuture(null);
        }
        return inFlight.computeIfAbsent(model, m -> {
            var future = new CompletableFuture<Void>();
            Thread.ofVirtual().name("flux-pull-" + m).start(() -> {
                try {
                    doPull(m, onProgress);
                    future.complete(null);
                } catch (@SuppressWarnings("java:S1181") Throwable t) {
                    Logger.warn(t, "FluxModelManager: pull failed for %s", m);
                    statuses.put(m, new ModelStatus(
                            State.ERROR, 0, 0, t.getMessage() == null ? t.toString() : t.getMessage()));
                    future.completeExceptionally(t);
                } finally {
                    inFlight.remove(m);
                }
            });
            return future;
        });
    }

    /**
     * Drive the sidecar's {@code POST /pull} and relay its ndjson progress into
     * {@link #statuses} + the optional callback. The sidecar goes silent while
     * downloading a single shard between progress lines, so we read with a long
     * read timeout (a continuously-streaming body the way WhisperModelManager's
     * direct GET does would otherwise let general()'s 30s readTimeout trip).
     */
    private static void doPull(String model, Consumer<DownloadProgress> onProgress) throws IOException {
        var baseUrl = LocalFluxSidecarManager.ensureRunning();
        statuses.put(model, new ModelStatus(State.DOWNLOADING, 0, 0, null));
        var client = HttpFactories.general().newBuilder()
                .readTimeout(Duration.ofMinutes(15))   // tolerate a slow multi-GB shard between lines
                .callTimeout(Duration.ZERO)            // overall pull is unbounded
                .build();
        var request = new Request.Builder()
                .url(baseUrl + "/pull")
                .post(RequestBody.create(new byte[0]))
                .build();
        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("sidecar /pull failed: HTTP " + resp.code());
            }
            BufferedSource src = resp.body().source();
            String line;
            while ((line = src.readUtf8Line()) != null) {
                if (line.isBlank()) continue;
                var json = JsonParser.parseString(line).getAsJsonObject();
                var status = optString(json, "status");
                long done = json.has("bytesDownloaded") ? json.get("bytesDownloaded").getAsLong() : 0;
                long total = json.has("totalBytes") ? json.get("totalBytes").getAsLong() : 0;
                if ("error".equals(status)) {
                    throw new IOException("sidecar pull error: " + optString(json, "error"));
                }
                if ("done".equals(status)) {
                    statuses.put(model, new ModelStatus(State.AVAILABLE, done, total, null));
                } else {
                    statuses.put(model, new ModelStatus(State.DOWNLOADING, done, total, null));
                    if (onProgress != null) {
                        onProgress.accept(new DownloadProgress(model, done, total));
                    }
                }
            }
        }
        EventLogger.info("imagegen", "Flux model %s downloaded".formatted(model));
    }

    private static String optString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    /** Test-only: drop in-memory state so tests don't bleed into each other. */
    public static void resetForTest() {
        inFlight.clear();
        statuses.clear();
    }
}
