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
 * Downloader for the two speech-emotion-recognition artifacts (JCLAW-564):
 * the emotion2vec+ base ONNX export and its external linear classifier
 * head. emotion2vec+ (ACL 2024, {@code emotion2vec/emotion2vec_plus_base}
 * upstream) is self-supervised on large-scale multilingual speech and
 * fine-tuned on ~40k hours of pseudo-labeled emotion data, which is what
 * makes its prosody reading hold up on non-English speech — the
 * English-only wav2vec2-base model it replaces produced incoherent labels
 * on Malay turns (the JCLAW-564 evidence). License: FunASR Model Open
 * Source License Agreement (free to use/modify/share with attribution);
 * the operator downloads directly from Hugging Face at runtime, JClaw
 * redistributes nothing.
 *
 * <p>The ONNX graph ends at the 768-d frame embeddings — emotion2vec's
 * classifier head is exported separately ({@code classifier.bin}, a raw
 * 9×768 linear layer) because the graph-internal masking logic doesn't
 * survive ONNX export upstream. {@link EmotionRecognizer} applies the
 * head in plain Java.
 *
 * <p>Same trust chain as {@link DiarizationModelManager}: fetched from
 * Hugging Face's resolve endpoint, whose {@code X-Linked-Etag} header
 * carries the Git-LFS SHA256 of the content; we hash the streamed body
 * and compare. At ~356 MB the download runs synchronously on first use —
 * callers are already minutes into blocking native inference by the time
 * emotion analysis starts.
 */
public final class EmotionModelManager {

    private static final String REPO =
            "https://huggingface.co/ykevinc/emotion2vec-plus-base-onnx/resolve/main";

    /** The two artifacts, keyed by their role in the emotion pipeline. */
    public enum EmotionModel {
        EMBEDDING("emotion2vec-plus-base.onnx", REPO + "/model.onnx"),
        CLASSIFIER("emotion2vec-classifier.bin", REPO + "/classifier.bin");

        private final String filename;
        private final String url;

        EmotionModel(String filename, String url) {
            this.filename = filename;
            this.url = url;
        }

        public String filename() { return filename; }
        public String url() { return url; }
    }

    private static final Path DEFAULT_ROOT = Path.of("data", "emotion-models");
    // Production never writes this field; tests set it before use (same
    // visibility argument as DiarizationModelManager.root).
    private static Path root = DEFAULT_ROOT;

    private EmotionModelManager() {}

    public static Path localPath(EmotionModel model) {
        return root.resolve(model.filename());
    }

    public static boolean availableLocally(EmotionModel model) {
        return Files.isRegularFile(localPath(model));
    }

    /**
     * Ensure the artifact is on disk and SHA256-verified, downloading
     * synchronously if absent. Throws {@link TranscriptionException} with a
     * clear operator-facing message on any failure.
     */
    public static Path ensureAvailable(EmotionModel model) {
        if (availableLocally(model)) return localPath(model);
        try {
            return doDownload(model, model.url());
        } catch (IOException e) {
            throw new TranscriptionException(
                    "failed to download emotion recognition model %s: %s"
                            .formatted(model.filename(), e.getMessage()), e);
        }
    }

    /**
     * HEAD (redirects off — the X-Linked-* headers live on HF's 302, not the
     * CDN target) → streaming GET into a temp file with live SHA256 → atomic
     * rename. URL parameter so tests can point at a mock server, mirroring
     * {@link DiarizationModelManager#doDownload}.
     */
    public static Path doDownload(EmotionModel model, String url) throws IOException {
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

        var tmp = root.resolve(model.filename() + ".part");
        var digest = newSha256();
        // ~356 MB: too big for general()'s default 60s call deadline on slow
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
            throw new IOException("SHA256 mismatch for %s: expected %s, got %s"
                    .formatted(model.filename(), expectedSha256, actual));
        }

        var finalPath = localPath(model);
        Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        EventLogger.info("transcription",
                "Emotion recognition model %s downloaded and verified".formatted(model.filename()));
        Logger.info("EmotionModelManager: %s ready at %s", model.filename(), finalPath);
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
