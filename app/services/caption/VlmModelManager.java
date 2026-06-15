package services.caption;

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
 * Lazy-downloader for local VLM model files (JCLAW-213) — the vision counterpart of
 * {@code WhisperModelManager}. A model is several files (encoder/decoder ONNX + tokenizer), so each
 * lands in a per-model directory {@code data/vlm-models/{id}/} and is verified against the model's
 * <b>pinned SHA256 manifest</b> ({@link VlmModel.FileSpec}) — distinct from the whisper manager,
 * which trusts HF's {@code X-Linked-Etag} (a single LFS file). Concurrent {@link #ensureAvailable}
 * calls for the same model coalesce onto one in-flight download; {@link #status} is poll-friendly for
 * the Settings UI (JCLAW-214).
 */
public final class VlmModelManager {

    private static final String CATEGORY = "caption";
    private static final Path DEFAULT_ROOT = Path.of("data", "vlm-models");
    private static Path root = DEFAULT_ROOT;

    private static final ConcurrentHashMap<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ModelStatus> statuses = new ConcurrentHashMap<>();

    private VlmModelManager() {}

    public enum State { ABSENT, DOWNLOADING, VERIFYING, AVAILABLE, ERROR }

    public record ModelStatus(State state, long bytesDownloaded, long totalBytes, String error) {}

    public record DownloadProgress(String modelId, long bytesDownloaded, long totalBytes) {}

    /** Per-model cache directory holding the manifest's files (encoder.onnx, decoder.onnx, tokenizer.json). */
    public static Path localDir(VlmModel model) {
        return root.resolve(model.id());
    }

    /** True iff every file in the model's manifest is present on disk. */
    public static boolean availableLocally(VlmModel model) {
        var dir = localDir(model);
        for (var f : model.files()) {
            if (!Files.isRegularFile(dir.resolve(f.localName()))) return false;
        }
        return true;
    }

    /**
     * Current status, reconciling the in-memory cache against the filesystem (so an out-of-band
     * file drop or deletion is reflected without a restart) — same contract as the whisper manager,
     * adapted to "all manifest files present" = AVAILABLE.
     */
    public static ModelStatus status(VlmModel model) {
        var live = statuses.get(model.id());
        if (live != null && (live.state() == State.DOWNLOADING || live.state() == State.VERIFYING)) {
            return live;
        }
        if (availableLocally(model)) {
            if (live != null && live.state() == State.AVAILABLE) return live;
            var fresh = new ModelStatus(State.AVAILABLE, 0, 0, null);
            statuses.put(model.id(), fresh);
            return fresh;
        }
        if (live != null && live.state() == State.AVAILABLE) {
            statuses.remove(model.id());
        }
        if (live != null && live.state() == State.ERROR) return live;
        return new ModelStatus(State.ABSENT, 0, 0, null);
    }

    /**
     * Ensure all of the model's files are on disk and SHA256-verified. Returns a future completing
     * with the model's cache dir. Concurrent callers for the same model share one download.
     */
    public static CompletableFuture<Path> ensureAvailable(VlmModel model, Consumer<DownloadProgress> onProgress) {
        if (availableLocally(model)) {
            return CompletableFuture.completedFuture(localDir(model));
        }
        return inFlight.computeIfAbsent(model.id(), id -> {
            var future = new CompletableFuture<Path>();
            Thread.ofVirtual().name("vlm-download-" + id).start(() -> {
                try {
                    future.complete(doDownloadAll(model, onProgress));
                } catch (@SuppressWarnings("java:S1181") Throwable t) {
                    Logger.warn(t, "VlmModelManager: download failed for %s", model.id());
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

    /** Download every missing file in the manifest into the model's cache dir. */
    static Path doDownloadAll(VlmModel model, Consumer<DownloadProgress> onProgress) throws IOException {
        var dir = localDir(model);
        Files.createDirectories(dir);
        statuses.put(model.id(), new ModelStatus(State.DOWNLOADING, 0, 0, null));
        for (var f : model.files()) {
            var dest = dir.resolve(f.localName());
            if (Files.isRegularFile(dest)) continue;
            doDownloadFile(model.id(), f.url(), f.sha256(), dest, onProgress);
        }
        statuses.put(model.id(), new ModelStatus(State.AVAILABLE, 0, 0, null));
        EventLogger.info(CATEGORY, "VLM model %s downloaded and verified".formatted(model.id()));
        return dir;
    }

    /**
     * Stream one file → temp {@code .part} with a live SHA256 + progress → verify against the pinned
     * hash → atomic rename. Package-visible so the manager test can drive it with a mock HTTP source.
     */
    public static Path doDownloadFile(String modelId, String url, String expectedSha256,
                                      Path dest, Consumer<DownloadProgress> onProgress) throws IOException {
        Files.createDirectories(dest.getParent());
        var client = HttpFactories.general();
        var tmp = dest.resolveSibling(dest.getFileName() + ".part");
        var digest = newSha256();
        long downloaded = 0;
        // Model files run tens-to-hundreds of MB; clear the per-call deadline so the body read isn't
        // bounded by general()'s 60s callTimeout (mirrors WhisperModelManager); readTimeout bounds progress.
        var getCall = client.newCall(new Request.Builder().url(url).build());
        getCall.timeout().clearTimeout();
        try (Response resp = getCall.execute();
             var sink = Files.newOutputStream(tmp)) {
            if (!resp.isSuccessful()) {
                throw new IOException("GET %s failed: %d %s".formatted(url, resp.code(), resp.message()));
            }
            long total = Math.max(resp.body().contentLength(), 0);
            BufferedSource src = resp.body().source();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = src.read(buf)) != -1) {
                sink.write(buf, 0, n);
                digest.update(buf, 0, n);
                downloaded += n;
                statuses.put(modelId, new ModelStatus(State.DOWNLOADING, downloaded, total, null));
                if (onProgress != null) {
                    onProgress.accept(new DownloadProgress(modelId, downloaded, total));
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        statuses.put(modelId, new ModelStatus(State.VERIFYING, downloaded, downloaded, null));
        var actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            Files.deleteIfExists(tmp);
            throw new IOException("SHA256 mismatch for %s: expected %s, got %s"
                    .formatted(dest.getFileName(), expectedSha256, actual));
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    /**
     * Remove a downloaded model's files (JCLAW-214) so the operator can reclaim disk or disable the
     * local fallback. Deletes the per-model directory and clears any cached status; a no-op when the
     * model was never downloaded. Returns true if anything was removed.
     */
    public static boolean delete(VlmModel model) throws IOException {
        var dir = localDir(model);
        statuses.remove(model.id());
        if (!Files.isDirectory(dir)) return false;
        try (var paths = Files.walk(dir)) {
            // Deepest-first so directories are emptied before removal.
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        EventLogger.info(CATEGORY, "VLM model %s removed".formatted(model.id()));
        return true;
    }

    /** Test-only: drop in-memory state so tests don't bleed into each other. */
    public static void resetForTest() {
        inFlight.clear();
        statuses.clear();
        root = DEFAULT_ROOT;
    }

    /** Test-only: redirect the storage root so tests don't pollute {@code data/vlm-models/}. */
    public static void setRootForTest(Path testRoot) {
        root = testRoot == null ? DEFAULT_ROOT : testRoot;
    }

    /** Test-only: pre-seed the in-flight map to verify the single-flight contract. */
    public static void putInFlightForTest(String modelId, CompletableFuture<Path> future) {
        inFlight.put(modelId, future);
    }
}
