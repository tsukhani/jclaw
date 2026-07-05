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
        return ModelDownloader.download(url, root, model.filename(), "Emotion model");
    }

    /** Test-only: redirect the storage root so tests don't pollute {@code data/emotion-models/}. */
    public static void setRootForTest(Path testRoot) {
        root = testRoot == null ? DEFAULT_ROOT : testRoot;
    }
}
