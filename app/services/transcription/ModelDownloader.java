package services.transcription;

import okhttp3.Request;
import okhttp3.Response;
import play.Logger;
import services.EventLogger;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * The one model-download engine (JCLAW-631). The four model managers each
 * carried a copy of the same subtle sequence — HEAD with redirects off
 * (Hugging Face's {@code X-Linked-Etag} SHA256 lives on the 302, not the
 * CDN target) → streaming GET into a {@code .part} temp file with a live
 * digest → verify → atomic rename. ~750 LOC of near-identical code is now
 * this class plus thin per-domain facades that keep their public APIs
 * (enums, paths, availability checks) and delegate the mechanics here.
 */
public final class ModelDownloader {

    private ModelDownloader() {}

    /** Byte-level progress hook: (bytesDownloaded, totalBytes or -1). */
    @FunctionalInterface
    public interface Progress {
        void report(long downloaded, long total);
    }

    public static Path download(String url, Path root, String filename, String what)
            throws IOException {
        return download(url, root, filename, what, null);
    }

    /**
     * Download {@code url} into {@code root/filename}, verifying the
     * streaming SHA256 against the {@code X-Linked-Etag} announced by the
     * origin. Atomic: readers never observe a partial file.
     *
     * @param what human label for logs/events (e.g. "Word-alignment model")
     */
    public static Path download(String url, Path root, String filename, String what,
                                Progress progress) throws IOException {
        Files.createDirectories(root);

        var client = HttpFactories.general();
        var noFollow = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        String expectedSha256;
        try (Response head = noFollow.newCall(new Request.Builder().url(url).head().build()).execute()) {
            if (head.code() >= 400) {
                throw new IOException("HEAD %s failed: %d %s".formatted(url, head.code(), head.message()));
            }
            var etag = head.header("X-Linked-Etag");
            if (etag == null) {
                throw new IOException("HEAD %s missing X-Linked-Etag header".formatted(url));
            }
            expectedSha256 = etag.replace("\"", "").toLowerCase();
        }

        var tmp = root.resolve(filename + ".part");
        var digest = newSha256();
        // Model files run to hundreds of MB: clear the per-call deadline
        // (read/connect timeouts still bound each socket operation).
        var download = client.newBuilder()
                .callTimeout(Duration.ZERO)
                .build();
        try (Response resp = download.newCall(new Request.Builder().url(url).build()).execute();
             var sink = Files.newOutputStream(tmp)) {
            if (!resp.isSuccessful()) {
                throw new IOException("GET %s failed: %d %s".formatted(url, resp.code(), resp.message()));
            }
            long total = resp.body().contentLength();
            long downloaded = 0;
            var src = resp.body().source();
            byte[] buf = new byte[64 * 1024];
            while (true) {
                int n = src.read(buf);
                if (n == -1) break;
                sink.write(buf, 0, n);
                digest.update(buf, 0, n);
                downloaded += n;
                if (progress != null) progress.report(downloaded, total);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        var actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expectedSha256)) {
            Files.deleteIfExists(tmp);
            throw new IOException(
                    "SHA256 mismatch for %s: expected %s, got %s".formatted(filename, expectedSha256, actual));
        }

        var finalPath = root.resolve(filename);
        Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        EventLogger.info("transcription",
                "%s %s downloaded and verified".formatted(what, filename));
        Logger.info("ModelDownloader: %s ready at %s", filename, finalPath);
        return finalPath;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
