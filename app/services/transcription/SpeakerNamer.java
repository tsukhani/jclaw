package services.transcription;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager;
import play.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Names diarized speakers by matching them against enrolled reference audio
 * (JCLAW-558). Operators drop short clips under {@code data/speaker-voices/},
 * one subfolder per person — the folder name is the display name:
 *
 * <pre>
 * data/speaker-voices/
 *   Alice/monday-standup.ogg
 *   Bob/voicenote.m4a
 *   Bob/interview-clip.wav
 * </pre>
 *
 * <p>Each clip is embedded with sherpa-onnx's {@link SpeakerEmbeddingExtractor}
 * (the same WeSpeaker ResNet34-LM model the diarizer already downloads) and
 * registered in a {@link SpeakerEmbeddingManager}; each diarized speaker's
 * speech is then embedded and searched against the enrolled set. Cosine
 * similarity at or above the threshold renames the speaker; anything else
 * keeps its anonymous SPEAKER_NN label.
 *
 * <p>Lifecycle mirrors {@link SherpaDiarizer}: the native extractor is cached
 * for the JVM's lifetime, inference is serialized under {@link #lock}, and
 * {@link jobs.ShutdownJob} releases it. Enrollment embeddings are recomputed
 * per call — enrollment folders are a handful of short clips, and the cost is
 * noise next to the whisper pass that precedes any naming.
 */
public final class SpeakerNamer {

    private static final Path DEFAULT_ROOT = Path.of("data", "speaker-voices");
    // Production never writes this field; tests set it before use (same
    // visibility argument as WhisperModelManager.root).
    private static Path root = DEFAULT_ROOT;

    /** Cap on per-speaker audio fed to the embedding model. WeSpeaker
     *  embeddings saturate well before this; capping keeps naming O(seconds)
     *  even for hour-long single-speaker stretches. */
    private static final int MAX_SAMPLES_PER_SPEAKER = 20 * 16_000;

    private static final Object lock = new Object();
    // Guarded by lock.
    private static SpeakerEmbeddingExtractor extractor = null;

    private SpeakerNamer() {}

    /**
     * Whether any enrollment exists: at least one non-hidden subfolder of the
     * voices root containing at least one non-hidden regular file. Cheap —
     * callers use it to skip naming (and its model/native loading) entirely.
     */
    public static boolean enrollmentPresent() {
        return !scanEnrollment().isEmpty();
    }

    /**
     * Match diarized speakers against the enrolled voices. Returns
     * {@code speaker index → display name} for matches only; an empty map
     * when there is no enrollment, no diarized speech, or nothing matched.
     * Blocking and CPU-bound like the rest of the pipeline.
     */
    public static Map<Integer, String> nameSpeakers(
            Path audioFile, List<SherpaDiarizer.SpeakerSegment> segments, float threshold) {
        var enrollment = scanEnrollment();
        if (enrollment.isEmpty() || segments.isEmpty()) return Map.of();

        var embeddingModel = DiarizationModelManager.ensureAvailable(
                DiarizationModelManager.DiarizationModel.EMBEDDING);
        float[] samples = WhisperJniTranscriber.ffmpegToPcmF32(audioFile);

        synchronized (lock) {
            ensureExtractor(embeddingModel);
            var manager = new SpeakerEmbeddingManager(extractor.getDim());
            try {
                int enrolled = enroll(manager, enrollment);
                if (enrolled == 0) return Map.of();

                var names = new HashMap<Integer, String>();
                for (var e : samplesPerSpeaker(samples, segments).entrySet()) {
                    var name = manager.search(embeddingOf(e.getValue()), threshold);
                    if (name != null && !name.isEmpty()) {
                        names.put(e.getKey(), name);
                    }
                }
                return names;
            } finally {
                manager.release();
            }
        }
    }

    /** Free the native extractor on JVM shutdown. Wired from {@link jobs.ShutdownJob}. */
    public static void shutdown() {
        synchronized (lock) {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (@SuppressWarnings("java:S1181") Throwable t) {
                    // JNI release can surface native errors; shutdown must continue
                    Logger.warn(t, "SpeakerNamer: error releasing extractor");
                }
                extractor = null;
            }
        }
    }

    /** {@code display name → reference clips}, hidden entries ignored. */
    private static Map<String, List<Path>> scanEnrollment() {
        if (!Files.isDirectory(root)) return Map.of();
        var enrollment = new HashMap<String, List<Path>>();
        try (Stream<Path> people = Files.list(root)) {
            for (var personDir : people.filter(Files::isDirectory).toList()) {
                if (personDir.getFileName().toString().startsWith(".")) continue;
                try (Stream<Path> clips = Files.list(personDir)) {
                    var files = clips.filter(Files::isRegularFile)
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .toList();
                    if (!files.isEmpty()) {
                        enrollment.put(personDir.getFileName().toString(), files);
                    }
                }
            }
        } catch (IOException e) {
            Logger.warn(e, "SpeakerNamer: failed to scan %s", root);
            return Map.of();
        }
        return enrollment;
    }

    /** Embed every readable clip and register it under the person's name.
     *  A clip ffmpeg can't decode is skipped with a warning rather than
     *  failing the whole transcription. Returns the number of people
     *  successfully enrolled. Caller must hold {@link #lock}. */
    private static int enroll(SpeakerEmbeddingManager manager, Map<String, List<Path>> enrollment) {
        int enrolled = 0;
        for (var person : enrollment.entrySet()) {
            var embeddings = new ArrayList<float[]>();
            for (var clip : person.getValue()) {
                try {
                    embeddings.add(embeddingOf(WhisperJniTranscriber.ffmpegToPcmF32(clip)));
                } catch (TranscriptionException e) {
                    Logger.warn("SpeakerNamer: skipping unreadable enrollment clip %s (%s)",
                            clip, e.getMessage());
                }
            }
            if (!embeddings.isEmpty()
                    && manager.add(person.getKey(), embeddings.toArray(float[][]::new))) {
                enrolled++;
            }
        }
        return enrolled;
    }

    /** Concatenate each speaker's diarized sample ranges, capped at
     *  {@link #MAX_SAMPLES_PER_SPEAKER}. */
    private static Map<Integer, float[]> samplesPerSpeaker(
            float[] samples, List<SherpaDiarizer.SpeakerSegment> segments) {
        var chunks = new HashMap<Integer, List<float[]>>();
        var lengths = new HashMap<Integer, Integer>();
        for (var seg : segments) {
            int have = lengths.getOrDefault(seg.speaker(), 0);
            if (have >= MAX_SAMPLES_PER_SPEAKER) continue;
            int from = Math.clamp(Math.round(seg.start() * 16_000), 0, samples.length);
            int to = Math.clamp(Math.round(seg.end() * 16_000), from, samples.length);
            int take = Math.min(to - from, MAX_SAMPLES_PER_SPEAKER - have);
            if (take <= 0) continue;
            var chunk = new float[take];
            System.arraycopy(samples, from, chunk, 0, take);
            chunks.computeIfAbsent(seg.speaker(), _ -> new ArrayList<>()).add(chunk);
            lengths.merge(seg.speaker(), take, Integer::sum);
        }

        var voice = new HashMap<Integer, float[]>();
        for (var e : chunks.entrySet()) {
            var joined = new float[lengths.get(e.getKey())];
            int at = 0;
            for (var chunk : e.getValue()) {
                System.arraycopy(chunk, 0, joined, at, chunk.length);
                at += chunk.length;
            }
            voice.put(e.getKey(), joined);
        }
        return voice;
    }

    /** Caller must hold {@link #lock}. */
    private static float[] embeddingOf(float[] samples) {
        var stream = extractor.createStream();
        try {
            stream.acceptWaveform(samples, 16_000);
            stream.inputFinished();
            return extractor.compute(stream);
        } finally {
            stream.release();
        }
    }

    /** Caller must hold {@link #lock}. */
    private static void ensureExtractor(Path embeddingModel) {
        if (extractor != null) return;
        try {
            extractor = new SpeakerEmbeddingExtractor(
                    SpeakerEmbeddingExtractorConfig.builder()
                            .setModel(embeddingModel.toString())
                            .build());
            Logger.info("SpeakerNamer: embedding extractor loaded (dim=%d)", extractor.getDim());
        } catch (RuntimeException e) {
            throw new TranscriptionException(
                    "failed to initialise sherpa-onnx speaker-embedding extractor: " + e.getMessage(), e);
        } catch (@SuppressWarnings("java:S1181") UnsatisfiedLinkError e) {
            throw new TranscriptionException(
                    "sherpa-onnx native library unavailable on this platform — "
                            + "speaker naming needs a sherpa-onnx-native-lib jar for this OS/arch", e);
        }
    }

    /** Test-only: redirect the enrollment root. */
    public static void setRootForTest(Path testRoot) {
        root = testRoot == null ? DEFAULT_ROOT : testRoot;
    }

    /** Test-only: drop the cached extractor so tests don't bleed. */
    public static void resetForTest() {
        shutdown();
        root = DEFAULT_ROOT;
    }
}
