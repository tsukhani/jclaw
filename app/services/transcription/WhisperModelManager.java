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
    private static volatile Path root = DEFAULT_ROOT;

    private static final ConcurrentHashMap<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ModelStatus> statuses = new ConcurrentHashMap<>();

    private WhisperModelManager() {}

    /** State machine for a single model's local availability. */
    public enum State { ABSENT, DOWNLOADING, VERIFYING, AVAILABLE, ERROR }

    /** Snapshot for status polling. {@code error} is non-null only when {@code state == ERROR}. */
    public record ModelStatus(State state, long bytesDownloaded, long totalBytes, String error) {}

    /** Streaming progress event passed to the optional callback on every chunk. */
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
                } catch (Throwable t) {
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
    /** Visible to tests so they can drive the download against a MockWebServer URL. */
    public static Path doDownload(WhisperModel model, Consumer<DownloadProgress> onProgress) throws IOException {
        return doDownload(model, model.downloadUrl(), onProgress);
    }

    public static Path doDownload(WhisperModel model, String url, Consumer<DownloadProgress> onProgress) throws IOException {
        Files.createDirectories(root);

        var client = HttpFactories.general();
        // HEAD with redirects disabled: HF's resolve endpoint replies 302 and
        // the X-Linked-Etag / X-Linked-Size headers are set on the 302 itself,
        // not on the CDN it redirects to. Following the redirect (the OkHttp
        // default) lands at cas-bridge.xethub.hf.co with a bare 200 that
        // carries neither header — exactly the symptom that read as "HF
        // stopped exposing the SHA256."
        var noFollow = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        String expectedSha256;
        long expectedSize;
        try (Response head = noFollow.newCall(new Request.Builder().url(url).head().build()).execute()) {
            // Accept 200 (small non-LFS files served direct from HF) AND any
            // 3xx redirect (LFS-managed files that bounce to a CDN); both
            // shapes carry the X-Linked-* headers we need. Reject only 4xx/5xx.
            if (head.code() >= 400) {
                throw new IOException("HEAD %s failed: %d %s".formatted(url, head.code(), head.message()));
            }
            var etag = head.header("X-Linked-Etag");
            var size = head.header("X-Linked-Size");
            if (etag == null || size == null) {
                throw new IOException("HEAD %s missing X-Linked-Etag / X-Linked-Size headers".formatted(url));
            }
            expectedSha256 = etag.replace("\"", "").toLowerCase();
            expectedSize = Long.parseLong(size);
        }

        statuses.put(model.id(), new ModelStatus(State.DOWNLOADING, 0, expectedSize, null));

        var tmp = root.resolve(model.filename() + ".part");
        var digest = newSha256();
        long downloaded = 0;
        try (Response resp = client.newCall(new Request.Builder().url(url).build()).execute();
             var sink = Files.newOutputStream(tmp)) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("GET %s failed: %d %s".formatted(url, resp.code(), resp.message()));
            }
            BufferedSource src = resp.body().source();
            byte[] buf = new byte[64 * 1024];
            while (true) {
                int n = src.read(buf);
                if (n == -1) break;
                sink.write(buf, 0, n);
                digest.update(buf, 0, n);
                downloaded += n;
                statuses.put(model.id(), new ModelStatus(State.DOWNLOADING, downloaded, expectedSize, null));
                if (onProgress != null) {
                    onProgress.accept(new DownloadProgress(model.id(), downloaded, expectedSize));
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        statuses.put(model.id(), new ModelStatus(State.VERIFYING, downloaded, expectedSize, null));
        var actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expectedSha256)) {
            Files.deleteIfExists(tmp);
            throw new IOException(
                    "SHA256 mismatch for %s: expected %s, got %s".formatted(model.id(), expectedSha256, actual));
        }

        var finalPath = localPath(model);
        Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        statuses.put(model.id(), new ModelStatus(State.AVAILABLE, downloaded, expectedSize, null));
        EventLogger.info("transcription",
                "Whisper model %s downloaded and verified (%d bytes)".formatted(model.id(), downloaded));
        return finalPath;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
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
