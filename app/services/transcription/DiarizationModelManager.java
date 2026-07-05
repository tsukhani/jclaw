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
 * NOT vestigial despite the JCLAW-614 sherpa-diarizer removal: the WeSpeaker
 * embedding model downloaded here backs {@link SpeakerNamer#embedWindow},
 * which powers voice naming, overlap re-attribution and every enrollment
 * purity gate.
 *
 * Downloader for the two ONNX models the sherpa-onnx diarization engine
 * needs (JCLAW-556): the pyannote segmentation-3.0 export and the
 * WeSpeaker VoxCeleb ResNet34-LM speaker-embedding model — the same two
 * models the reference pyannote 3.1 pipeline runs.
 *
 * <p>Same trust chain as {@link WhisperModelManager}: both files are
 * fetched from Hugging Face's resolve endpoint, whose {@code X-Linked-Etag}
 * header carries the Git-LFS SHA256 of the content; we hash the streamed
 * body and compare. Unlike whisper models (up to 1 GB, downloaded in the
 * background with progress polling), these total ~32 MB, so
 * {@link #ensureAvailable} downloads synchronously on first use and the
 * Settings UI needs no per-model progress machinery.
 */
public final class DiarizationModelManager {

    /** The two model files, keyed by their role in the sherpa pipeline. */
    public enum DiarizationModel {
        EMBEDDING(
                "wespeaker_en_voxceleb_resnet34_LM.onnx",
                "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/main/wespeaker_en_voxceleb_resnet34_LM.onnx");

        private final String filename;
        private final String url;

        DiarizationModel(String filename, String url) {
            this.filename = filename;
            this.url = url;
        }

        public String filename() { return filename; }
        public String url() { return url; }
    }

    private static final Path DEFAULT_ROOT = Path.of("data", "diarization-models");
    // Production never writes this field; tests set it before use (same
    // visibility argument as WhisperModelManager.root).
    private static Path root = DEFAULT_ROOT;

    private DiarizationModelManager() {}

    public static Path localPath(DiarizationModel model) {
        return root.resolve(model.filename());
    }

    public static boolean availableLocally(DiarizationModel model) {
        return Files.isRegularFile(localPath(model));
    }

    /**
     * Ensure the model file is on disk and SHA256-verified, downloading
     * synchronously if absent. Callers already run off the request thread
     * (the diarization engine is blocking by contract), so a one-time
     * ~30 MB fetch inline is acceptable. Throws {@link TranscriptionException}
     * with a clear operator-facing message on any failure.
     */
    public static Path ensureAvailable(DiarizationModel model) {
        if (availableLocally(model)) return localPath(model);
        try {
            return doDownload(model, model.url());
        } catch (IOException e) {
            throw new TranscriptionException(
                    "failed to download diarization model %s: %s".formatted(model.filename(), e.getMessage()), e);
        }
    }

    /**
     * HEAD (redirects off — the X-Linked-* headers live on HF's 302, not the
     * CDN target) → streaming GET into a temp file with live SHA256 → atomic
     * rename. Package-visible URL parameter so tests can point at a mock
     * server, mirroring {@link WhisperModelManager#doDownload}.
     */
    public static Path doDownload(DiarizationModel model, String url) throws IOException {
        return ModelDownloader.download(url, root, model.filename(), "Diarization model");
    }

    /** Test-only: redirect the storage root so tests don't pollute {@code data/diarization-models/}. */
    public static void setRootForTest(Path testRoot) {
        root = testRoot == null ? DEFAULT_ROOT : testRoot;
    }
}
