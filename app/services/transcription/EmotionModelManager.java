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
import java.util.HexFormat;

/**
 * Downloader for the speech-emotion-recognition model (JCLAW-563): the
 * int8-quantized ONNX export of the wav2vec2-base SER fine-tune
 * (DunnBC22/wav2vec2-base-Speech_Emotion_Recognition, Apache-2.0 — the same
 * wav2vec2 family as SpeechBrain's IEMOCAP classifier, but pre-exported to
 * ONNX so it runs in-process with no Python sidecar).
 *
 * <p>Same trust chain as {@link DiarizationModelManager}: fetched from
 * Hugging Face's resolve endpoint, whose {@code X-Linked-Etag} header
 * carries the Git-LFS SHA256 of the content; we hash the streamed body and
 * compare. At ~95 MB the download runs synchronously on first use — callers
 * are already minutes into blocking native inference by the time emotion
 * analysis starts.
 */
public final class EmotionModelManager {

    public static final String FILENAME = "wav2vec2-base-ser-quantized.onnx";
    static final String URL =
            "https://huggingface.co/onnx-community/wav2vec2-base-Speech_Emotion_Recognition-ONNX"
                    + "/resolve/main/onnx/model_quantized.onnx";

    private static final Path DEFAULT_ROOT = Path.of("data", "emotion-models");
    // Production never writes this field; tests set it before use (same
    // visibility argument as DiarizationModelManager.root).
    private static Path root = DEFAULT_ROOT;

    private EmotionModelManager() {}

    public static Path localPath() {
        return root.resolve(FILENAME);
    }

    public static boolean availableLocally() {
        return Files.isRegularFile(localPath());
    }

    /**
     * Ensure the model file is on disk and SHA256-verified, downloading
     * synchronously if absent. Throws {@link TranscriptionException} with a
     * clear operator-facing message on any failure.
     */
    public static Path ensureAvailable() {
        if (availableLocally()) return localPath();
        try {
            return doDownload(URL);
        } catch (IOException e) {
            throw new TranscriptionException(
                    "failed to download emotion recognition model %s: %s".formatted(FILENAME, e.getMessage()), e);
        }
    }

    /**
     * HEAD (redirects off — the X-Linked-* headers live on HF's 302, not the
     * CDN target) → streaming GET into a temp file with live SHA256 → atomic
     * rename. URL parameter so tests can point at a mock server, mirroring
     * {@link DiarizationModelManager#doDownload}.
     */
    public static Path doDownload(String url) throws IOException {
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

        var tmp = root.resolve(FILENAME + ".part");
        var digest = newSha256();
        // ~95 MB: too big for general()'s default 60s call deadline on slow
        // links — clear the per-call timeout like the whisper downloads do
        // (read/connect timeouts still bound each socket operation).
        var download = client.newBuilder()
                .callTimeout(java.time.Duration.ZERO)
                .build();
        try (Response resp = download.newCall(new Request.Builder().url(url).build()).execute();
             var sink = Files.newOutputStream(tmp)) {
            if (!resp.isSuccessful()) {
                throw new IOException("GET %s failed: %d %s".formatted(url, resp.code(), resp.message()));
            }
            var src = resp.body().source();
            byte[] buf = new byte[64 * 1024];
            while (true) {
                int n = src.read(buf);
                if (n == -1) break;
                sink.write(buf, 0, n);
                digest.update(buf, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        var actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expectedSha256)) {
            Files.deleteIfExists(tmp);
            throw new IOException(
                    "SHA256 mismatch for %s: expected %s, got %s".formatted(FILENAME, expectedSha256, actual));
        }

        var finalPath = localPath();
        Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        EventLogger.info("transcription",
                "Emotion recognition model %s downloaded and verified".formatted(FILENAME));
        Logger.info("EmotionModelManager: %s ready at %s", FILENAME, finalPath);
        return finalPath;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    /** Test-only: redirect the storage root so tests don't pollute {@code data/emotion-models/}. */
    public static void setRootForTest(Path testRoot) {
        root = testRoot == null ? DEFAULT_ROOT : testRoot;
    }
}
