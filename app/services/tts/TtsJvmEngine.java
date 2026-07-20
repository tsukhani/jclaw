package services.tts;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import okhttp3.Request;
import play.Logger;
import play.Play;
import services.ConfigService;
import utils.HttpFactories;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * The JVM-native TTS engine (JCLAW-793): sherpa-onnx {@code OfflineTts} running
 * ONNX models entirely in-process — no Python, no sidecar. The operator selects
 * it in Settings &gt; Speech ({@code tts.engine=jvm}); the {@link TtsRouter}
 * dispatches here.
 *
 * <p>Validated by the JCLAW-793 spike on Apple silicon (Piper VITS, RTF ~0.03,
 * CPU). The native lib (libsherpa-onnx-jni with ONNX Runtime statically linked)
 * is bundled by the build for the host platform and auto-extracted+loaded by
 * sherpa's {@code LibraryLoader} on the first {@link OfflineTts} construction.
 *
 * <p>Weights are provisioned lazily on first use: the sherpa model archive is
 * downloaded from the k2-fsa releases and extracted under
 * {@code data/tts-models/sherpa/<id>/} (mirroring the sidecar's lazy HF
 * download). Each model's {@code OfflineTts} is cached; synthesis is serialized
 * JVM-wide with a fair lock so concurrent read-aloud requests queue rather than
 * contend — the same discipline as {@link TtsSidecarClient}.
 */
public final class TtsJvmEngine {

    private TtsJvmEngine() {}

    private enum Kind { VITS, KOKORO }

    /** A sherpa model: its release archive, the extracted dir name, the config
     *  kind, and the acoustic-model filename that marks a completed extract. */
    private record Spec(String archiveUrl, String dirName, Kind kind, String onnx) {}

    private static final String RELEASE =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";

    /** Maps a {@link TtsModel} JVM id to its sherpa archive/config (the JVM-side
     *  analogue of synth.py's MLX_REPOS). Adding a voice = one entry + one
     *  {@link TtsModel} enum row. */
    private static final Map<String, Spec> SPECS = Map.of(
            "piper-en_US-amy-low", new Spec(RELEASE + "vits-piper-en_US-amy-low.tar.bz2",
                    "vits-piper-en_US-amy-low", Kind.VITS, "en_US-amy-low.onnx"),
            "kokoro-multi-lang-v1_0", new Spec(RELEASE + "kokoro-multi-lang-v1_0.tar.bz2",
                    "kokoro-multi-lang-v1_0", Kind.KOKORO, "model.onnx"));

    private static final Map<String, OfflineTts> CACHE = new ConcurrentHashMap<>();

    /** Model ids with an in-flight background prefetch — single-flights the
     *  Settings Download button and drives the {@code /api/tts/state} flag. */
    private static final java.util.Set<String> DOWNLOADING = ConcurrentHashMap.newKeySet();

    /** {@code OfflineTts.generate} is CPU-bound and not documented thread-safe;
     *  serialize JVM-wide with a fair lock (mirrors the sidecar client). */
    private static final ReentrantLock LOCK = new ReentrantLock(true);

    static Path modelsRoot() {
        return new File(Play.applicationPath, "data/tts-models/sherpa").toPath();
    }

    /** Whether a model id is one this engine knows how to serve. */
    public static boolean isKnownModel(String modelId) {
        return SPECS.containsKey(modelId);
    }

    /** Whether the model's weights are already extracted on disk (no download
     *  needed). Drives the {@code /api/tts/state} readiness flag. */
    public static boolean isModelPresent(String modelId) {
        var spec = SPECS.get(modelId);
        return spec != null && Files.isRegularFile(modelsRoot().resolve(spec.dirName()).resolve(spec.onnx()));
    }

    /** Whether a background prefetch for this model is currently running. */
    public static boolean isDownloading(String modelId) {
        return DOWNLOADING.contains(modelId);
    }

    /** Provision a model's weights off the request path (the Settings Download
     *  button). Single-flight per id and a no-op once present — safe to call
     *  repeatedly. Does NOT construct the {@code OfflineTts} (that stays lazy on
     *  first synthesis). */
    public static void prefetch(String modelId) {
        var spec = specFor(modelId);
        if (isModelPresent(modelId) || !DOWNLOADING.add(modelId)) return;
        try {
            ensureModel(spec);
        } finally {
            DOWNLOADING.remove(modelId);
        }
    }

    /**
     * Synthesize {@code text} to WAV bytes in-process. First call for a model
     * downloads + extracts its weights, then constructs and caches its
     * {@code OfflineTts}. {@code voice} is an optional speaker index (numeric
     * string; blank/invalid → 0); {@code speed} defaults to 1.0.
     */
    public static byte[] synthesize(String text, String modelId, String voice, Float speed) {
        LOCK.lock();
        try {
            var tts = ensureLoaded(modelId);
            float spd = (speed != null && speed > 0f) ? speed : 1.0f;
            GeneratedAudio audio = tts.generate(text, speakerId(voice), spd);
            return toWav(audio);
        } finally {
            LOCK.unlock();
        }
    }

    private static int speakerId(String voice) {
        if (voice == null || voice.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(voice.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static OfflineTts ensureLoaded(String modelId) {
        return CACHE.computeIfAbsent(modelId, id -> {
            var spec = specFor(id);
            var dir = ensureModel(spec);
            Logger.info("TtsJvmEngine: loading sherpa OfflineTts model '%s' from %s", id, dir);
            return new OfflineTts(buildConfig(spec, dir));
        });
    }

    private static Spec specFor(String modelId) {
        var spec = SPECS.get(modelId);
        if (spec == null) {
            throw new TtsException("unknown JVM-native TTS model '" + modelId
                    + "' (known: " + String.join(", ", SPECS.keySet()) + ")");
        }
        return spec;
    }

    private static OfflineTtsConfig buildConfig(Spec spec, Path dir) {
        var model = new OfflineTtsModelConfig.Builder();
        if (spec.kind() == Kind.VITS) {
            model.setVits(OfflineTtsVitsModelConfig.builder()
                    .setModel(dir.resolve(spec.onnx()).toString())
                    .setTokens(dir.resolve("tokens.txt").toString())
                    .setDataDir(dir.resolve("espeak-ng-data").toString())
                    .build());
        } else {
            var kokoro = OfflineTtsKokoroModelConfig.builder()
                    .setModel(dir.resolve(spec.onnx()).toString())
                    .setVoices(dir.resolve("voices.bin").toString())
                    .setTokens(dir.resolve("tokens.txt").toString())
                    .setDataDir(dir.resolve("espeak-ng-data").toString());
            var lexicons = lexiconFiles(dir);
            if (!lexicons.isBlank()) kokoro.setLexicon(lexicons);
            var dict = dir.resolve("dict");
            if (Files.isDirectory(dict)) kokoro.setDictDir(dict.toString());
            model.setKokoro(kokoro.build());
        }
        model.setNumThreads(ConfigService.getInt("tts.jvm.numThreads", 2))
                .setProvider("cpu")
                .setDebug(false);
        return OfflineTtsConfig.builder().setModel(model.build()).build();
    }

    /** Kokoro multilingual models ship one or more {@code lexicon*.txt} files
     *  (e.g. per-language); sherpa takes them comma-joined. Empty for English-
     *  only voices, which resolve pronunciation via the bundled espeak data. */
    private static String lexiconFiles(Path dir) {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("lexicon") && n.endsWith(".txt"))
                    .sorted()
                    .map(n -> dir.resolve(n).toString())
                    .collect(Collectors.joining(","));
        } catch (IOException e) {
            return "";
        }
    }

    private static Path ensureModel(Spec spec) {
        var dir = modelsRoot().resolve(spec.dirName());
        var marker = dir.resolve(spec.onnx());
        if (Files.isRegularFile(marker)) return dir;
        try {
            Files.createDirectories(modelsRoot());
            var archive = modelsRoot().resolve(spec.dirName() + ".tar.bz2");
            Logger.info("TtsJvmEngine: provisioning '%s' — downloading %s", spec.dirName(), spec.archiveUrl());
            downloadTo(spec.archiveUrl(), archive);
            extractTarBz2(archive, modelsRoot());
            Files.deleteIfExists(archive);
            if (!Files.isRegularFile(marker)) {
                throw new TtsException("sherpa archive for '" + spec.dirName()
                        + "' extracted but " + marker.getFileName() + " is missing");
            }
            return dir;
        } catch (IOException e) {
            throw new TtsException("failed to provision JVM-native TTS model '"
                    + spec.dirName() + "': " + e.getMessage(), e);
        }
    }

    private static void downloadTo(String url, Path dest) throws IOException {
        var client = HttpFactories.general().newBuilder().readTimeout(Duration.ZERO).build();
        var call = client.newCall(new Request.Builder().url(url).get().build());
        call.timeout().timeout(ConfigService.getInt("tts.jvm.downloadTimeoutSeconds", 1800), TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " fetching " + url);
            }
            try (var in = resp.body().byteStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Extract a .tar.bz2 via the host {@code tar} (-j handles bzip2). jclaw's
     *  hosts are POSIX — the sidecars already require {@code uv} — so shelling
     *  to tar avoids pulling commons-compress just for one archive format. */
    private static void extractTarBz2(Path archive, Path targetDir) throws IOException {
        try {
            var pb = new ProcessBuilder("tar", "-xjf",
                    archive.toAbsolutePath().toString(), "-C", targetDir.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            var p = pb.start();
            var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("tar timed out extracting " + archive.getFileName());
            }
            if (p.exitValue() != 0) {
                throw new IOException("tar failed (exit " + p.exitValue() + "): " + out.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted extracting " + archive.getFileName(), e);
        }
    }

    private static byte[] toWav(GeneratedAudio audio) {
        try {
            var tmp = Files.createTempFile("jclaw-tts-", ".wav");
            try {
                if (!audio.save(tmp.toString())) {
                    throw new TtsException("sherpa OfflineTts failed to write WAV output");
                }
                return Files.readAllBytes(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new TtsException("failed to read synthesized audio: " + e.getMessage(), e);
        }
    }

    /** Release all cached engines. Wired into {@code jobs.ShutdownJob}. */
    public static void release() {
        LOCK.lock();
        try {
            CACHE.values().forEach(OfflineTts::release);
            CACHE.clear();
        } finally {
            LOCK.unlock();
        }
    }
}
