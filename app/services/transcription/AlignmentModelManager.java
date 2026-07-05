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
 * Downloader for the CTC forced-alignment model (JCLAW-603): the int8 ONNX
 * export of {@code facebook/wav2vec2-base-960h} (Apache-2.0), a
 * character-level CTC acoustic model. It does not transcribe anything —
 * whisper keeps that job (and its Malay support) — it only answers "when was
 * each already-known character spoken", which is what lets
 * {@link SegmentWordSplitter} split a whisper segment at a diarization
 * boundary without guessing. Char-level alignment holds up on Latin-script
 * Malay even though the acoustic model is English-trained.
 *
 * <p>Same trust chain as {@link DiarizationModelManager}: fetched from
 * Hugging Face's resolve endpoint, whose {@code X-Linked-Etag} header
 * carries the Git-LFS SHA256 of the content; we hash the streamed body and
 * compare. ~95 MB, downloaded synchronously on first use — the aligner only
 * runs when a segment actually straddles a speaker change, minutes into the
 * blocking diarization pipeline.
 */
public final class AlignmentModelManager {

    public static final String FILENAME = "wav2vec2-base-960h-ctc-quantized.onnx";
    static final String URL =
            "https://huggingface.co/onnx-community/wav2vec2-base-960h-ONNX"
                    + "/resolve/main/onnx/model_quantized.onnx";

    private static final Path DEFAULT_ROOT = Path.of("data", "alignment-models");
    // Production never writes this field; tests set it before use (same
    // visibility argument as DiarizationModelManager.root).
    private static Path root = DEFAULT_ROOT;

    private AlignmentModelManager() {}

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
                    "failed to download word-alignment model %s: %s".formatted(FILENAME, e.getMessage()), e);
        }
    }

    /**
     * HEAD (redirects off — the X-Linked-* headers live on HF's 302, not the
     * CDN target) → streaming GET into a temp file with live SHA256 → atomic
     * rename. URL parameter so tests can point at a mock server, mirroring
     * {@link DiarizationModelManager#doDownload}.
     */
    public static Path doDownload(String url) throws IOException {
        return ModelDownloader.download(url, root, FILENAME, "Word-alignment model");
    }

    /** Test-only: redirect the storage root so tests don't pollute {@code data/alignment-models/}. */
    public static void setRootForTest(Path testRoot) {
        root = testRoot == null ? DEFAULT_ROOT : testRoot;
    }
}
