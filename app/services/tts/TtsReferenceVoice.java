package services.tts;

import org.jspecify.annotations.Nullable;
import play.Play;
import services.ConfigService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

/**
 * Storage for the reference clip a cloning TTS model copies its speaker from
 * (JCLAW-865).
 *
 * <p>Chatterbox and Qwen3-TTS have no named voices — {@link TtsVoiceCatalog}
 * returns an empty list for them, which is why their Voice picker renders blank.
 * A reference clip is their voice selection, and without one they are fixed on
 * whatever speaker the model defaults to. The sidecar has always accepted
 * {@code ref_audio}; nothing on the JVM side ever sent it.
 *
 * <p>One clip, replacing whatever was there — the setting it backs
 * ({@code tts.sidecar.refAudio}) is a single global voice, so keeping older
 * uploads would accumulate files nothing can reference. The stored path is
 * absolute because the reader is the sidecar process, which runs with its own
 * working directory.
 */
public final class TtsReferenceVoice {

    private TtsReferenceVoice() {}

    /** Config key holding the absolute path of the active reference clip. */
    public static final String CONFIG_KEY_PREFIX = "tts.";
    public static final String CONFIG_KEY_SUFFIX = ".refAudio";

    /**
     * Formats worth accepting. Deliberately narrow: these are what the engines'
     * audio loaders handle without extra codecs, and a clip the model cannot read
     * fails deep inside Python where the operator cannot act on it.
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("wav", "mp3", "flac", "m4a", "ogg");

    /**
     * Size ceiling. Cloning wants a few seconds of clean speech — a long file is
     * not better input, it is a mistake (a whole podcast episode, the wrong file),
     * and rejecting it here is far more useful than a failure mid-synthesis.
     */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /** Where clips live, alongside the other TTS runtime artifacts under data/. */
    static Path refsDir() {
        return new File(Play.applicationPath, "data/tts-models/refs").toPath();
    }

    private static String configKey(TtsEngine engine) {
        return CONFIG_KEY_PREFIX + engine.id() + CONFIG_KEY_SUFFIX;
    }

    /** The active clip's absolute path, or null when none is set or the file has
     *  gone missing underneath us (a stale config row must not be handed to the
     *  sidecar, which would fail the synthesis rather than the setting). */
    public static @Nullable String activePath(TtsEngine engine) {
        var configured = ConfigService.get(configKey(engine));
        if (configured == null || configured.isBlank()) return null;
        return Files.isRegularFile(Path.of(configured)) ? configured : null;
    }

    /** Rejection reason for {@code filename}/{@code size}, or null when acceptable. */
    public static @Nullable String validate(String filename, long sizeBytes) {
        var ext = extensionOf(filename);
        if (ext == null || !ALLOWED_EXTENSIONS.contains(ext)) {
            return "Reference clip must be one of: " + String.join(", ", ALLOWED_EXTENSIONS);
        }
        if (sizeBytes <= 0) return "Reference clip is empty";
        if (sizeBytes > MAX_BYTES) {
            return "Reference clip must be under %d MB — cloning wants a few seconds of clean speech"
                    .formatted(MAX_BYTES / (1024 * 1024));
        }
        return null;
    }

    /**
     * Replace the active clip with {@code source} and point the config at it.
     * Returns the stored absolute path.
     *
     * <p>Clears prior clips first so the directory holds exactly the one file the
     * config names; a differing extension would otherwise leave an orphan behind.
     */
    public static String store(File source, String originalFilename, TtsEngine engine) throws IOException {
        var dir = refsDir();
        Files.createDirectories(dir);
        clearFiles();

        var ext = extensionOf(originalFilename);
        var target = dir.resolve("reference." + (ext == null ? "wav" : ext));
        Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);

        var absolute = target.toAbsolutePath().toString();
        ConfigService.set(configKey(engine), absolute);
        return absolute;
    }

    /** Drop the active clip and its config row, returning to the model default. */
    public static void clear(TtsEngine engine) throws IOException {
        clearFiles();
        ConfigService.delete(configKey(engine));
    }

    private static void clearFiles() throws IOException {
        var dir = refsDir();
        if (!Files.isDirectory(dir)) return;
        try (var entries = Files.list(dir)) {
            for (var p : entries.toList()) Files.deleteIfExists(p);
        }
    }

    private static @Nullable String extensionOf(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
