package services.transcription;

import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import play.Logger;
import services.EventLogger;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lazy-downloader for whisper.cpp GGML model files.
 *
 * <p>Files land at {@code data/whisper-models/{filename}}. Verification reads
 * the SHA256 from Hugging Face's {@code X-Linked-Etag} header on the resolve
 * endpoint — Git LFS exposes the file content's SHA256 there, so we don't
 * carry a hand-maintained manifest. The trust chain is HF's TLS endpoint
 * asserting the hash, then we hash the streamed body and compare.
 *
 * <p>Concurrent {@link #ensureAvailable} calls for the same model coalesce
 * onto a single in-flight download via {@link ConcurrentHashMap#computeIfAbsent}.
 * Status is exposed both via the returned future AND a polling-friendly
 * {@link #status} snapshot used by the Settings UI (separate story).
 */
public final class WhisperModelManager {

    private static final Path DEFAULT_ROOT = Path.of("data", "whisper-models");
    // Production never writes this field. Test setups call setRootForTest
    // before any virtual threads start, so Thread.start()'s happens-before
    // relationship covers visibility — no need for volatile or AtomicReference.
    private static Path root = DEFAULT_ROOT;

    private static final ConcurrentHashMap<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ModelStatus> statuses = new ConcurrentHashMap<>();

    private WhisperModelManager() {}

    /** State machine for a single model's local availability. */
    public enum State { ABSENT, DOWNLOADING, VERIFYING, AVAILABLE, ERROR }

    /**
     * Snapshot for status polling.
     *
     * @param state            current state in the local-availability state
     *                         machine
     * @param bytesDownloaded  bytes downloaded so far (0 when not in
     *                         {@link State#DOWNLOADING})
     * @param totalBytes       expected total bytes (0 when unknown)
     * @param error            error message; non-null only when
     *                         {@code state == ERROR}
     */
    public record ModelStatus(State state, long bytesDownloaded, long totalBytes, String error) {}

    /**
     * Streaming progress event passed to the optional callback on every
     * chunk.
     *
     * @param modelId         which model the progress event belongs to
     * @param bytesDownloaded bytes downloaded so far
     * @param totalBytes      expected total bytes
     */
    public record DownloadProgress(String modelId, long bytesDownloaded, long totalBytes) {}

    public static Path localPath(WhisperModel model) {
        return root.resolve(model.filename());
    }

    public static boolean availableLocally(WhisperModel model) {
        return Files.isRegularFile(localPath(model));
    }

    /**
     * Best-effort current status for a model. In-flight states
     * ({@link State#DOWNLOADING}, {@link State#VERIFYING}) trust the in-memory
     * cache because those are runtime signals the filesystem can't reflect.
     * Terminal states reconcile against the filesystem so the Settings UI
     * picks up two scenarios without needing a backend restart:
     *
     * <ul>
     *   <li>An operator deleted a model file from {@code data/whisper-models/}
     *       — the cache's stale AVAILABLE entry is dropped and the row
     *       reports ABSENT, re-enabling the Download button.</li>
     *   <li>An operator dropped a model file in out-of-band (e.g. copied
     *       from another machine) — the row flips to AVAILABLE without
     *       requiring a download click.</li>
     * </ul>
     */
    public static ModelStatus status(WhisperModel model) {
        var live = statuses.get(model.id());
        if (live != null
                && (live.state() == State.DOWNLOADING || live.state() == State.VERIFYING)) {
            return live;
        }

        boolean fileExists = availableLocally(model);
        if (fileExists) {
            if (live != null && live.state() == State.AVAILABLE) return live;
            try {
                var size = Files.size(localPath(model));
                var fresh = new ModelStatus(State.AVAILABLE, size, size, null);
                statuses.put(model.id(), fresh);
                return fresh;
            } catch (IOException e) {
                return new ModelStatus(State.ERROR, 0, 0, e.getMessage());
            }
        }

        // File is absent. A stale AVAILABLE cache entry means the file was
        // deleted externally — drop it so the row settles on ABSENT and
        // future polls don't keep returning the lie.
        if (live != null && live.state() == State.AVAILABLE) {
            statuses.remove(model.id());
        }
        if (live != null && live.state() == State.ERROR) return live;
        return new ModelStatus(State.ABSENT, 0, 0, null);
    }

    /**
     * Ensure the model file is on disk and SHA256-verified. Returns a future
     * that completes with the local path on success. Concurrent callers for
     * the same model share one download.
     *
     * <p>{@code onProgress} may be null. When supplied, it is invoked from
     * the download thread on every chunk; only the first caller's callback
     * is wired up — subsequent callers must poll {@link #status}.
     */
    public static CompletableFuture<Path> ensureAvailable(
            WhisperModel model, Consumer<DownloadProgress> onProgress) {
        if (availableLocally(model)) {
            return CompletableFuture.completedFuture(localPath(model));
        }
        return inFlight.computeIfAbsent(model.id(), id -> {
            var future = new CompletableFuture<Path>();
            Thread.ofVirtual().name("whisper-download-" + id).start(() -> {
                try {
                    var path = doDownload(model, onProgress);
                    future.complete(path);
                } catch (@SuppressWarnings("java:S1181") Throwable t) {
                    // Top-level VT — any failure (incl. native/Error) must reach the future as completeExceptionally
                    Logger.warn(t, "WhisperModelManager: download failed for %s", model.id());
                    statuses.put(model.id(), new ModelStatus(
                            State.ERROR, 0, 0, t.getMessage() == null ? t.toString() : t.getMessage()));
                    future.completeExceptionally(t);
                } finally {
                    inFlight.remove(id);
                }
            });
            return future;
        });
    }

    /**
     * HEAD → expected SHA256 + size → streaming GET into a temp file with
     * live SHA256 + progress accounting → atomic rename on success.
     *
     * <p>Package-private so {@link WhisperModelManagerTest} can hit it
     * directly with a mocked HTTP source.
     */
    public static Path doDownload(WhisperModel model, Consumer<DownloadProgress> onProgress) throws IOException {
        return doDownload(model, model.downloadUrl(), onProgress);
    }

    public static Path doDownload(WhisperModel model, String url, Consumer<DownloadProgress> onProgress) throws IOException {
        return ModelDownloader.download(url, root, model.filename(), "Whisper model",
                onProgress == null ? null
                        : (downloaded, total) -> onProgress.accept(
                                new DownloadProgress(model.id(), downloaded, total)));
    }

    /** Test-only: drop in-memory state so tests don't bleed into each other. */
    public static void resetForTest() {
        inFlight.clear();
        statuses.clear();
        root = DEFAULT_ROOT;
    }

    /** Test-only: redirect the storage root so tests don't pollute {@code data/whisper-models/}. */
    public static void setRootForTest(Path testRoot) {
        root = testRoot == null ? DEFAULT_ROOT : testRoot;
    }

    /** Test-only: pre-seed the in-flight map so a test can verify the single-flight contract. */
    public static void putInFlightForTest(String modelId, CompletableFuture<Path> future) {
        inFlight.put(modelId, future);
    }
}
