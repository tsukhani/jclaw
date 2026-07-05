package services.transcription;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import play.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * <p>Matching (rewritten in JCLAW-606 on the JCLAW-605 evidence that
 * reference quality dominates): every clip of a person is decoded, chunked
 * into 5s pieces, each chunk embedded with sherpa-onnx's
 * {@link SpeakerEmbeddingExtractor} (the same WeSpeaker model the diarizer
 * downloads), and the embeddings AVERAGED into one reference voiceprint;
 * each diarized cluster's speech gets the same chunk-averaged treatment.
 * Assignment is greedy and EXCLUSIVE — one enrolled name claims at most one
 * cluster — which structurally prevents the "every cluster matched the one
 * enrolled voice" collapse; clusters whose top two candidates are within
 * {@link #AMBIGUITY_GAP} stay anonymous rather than guessing.
 *
 * <p>Lifecycle mirrors the diarizer: the native extractor is cached
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

    /** Chunk length for averaged voiceprints (JCLAW-606): embeddings are
     *  computed per 5s chunk and averaged — the JCLAW-605 experiment's
     *  winning reference scheme. */
    static final int CHUNK_SAMPLES = 5 * 16_000;
    /** A cluster whose top two enrolled candidates score within this gap is
     *  left anonymous rather than guessed. */
    static final double AMBIGUITY_GAP = 0.03;

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

    /** The enrollment folder root — where the {@code diarize_audio} tool's
     *  enroll action (JCLAW-561) files reference clips, one subfolder per
     *  person. Honors the test override. */
    public static Path enrollmentRoot() {
        return root;
    }

    /**
     * Match diarized speakers against the enrolled voices. Returns
     * {@code speaker index → display name} for matches only; an empty map
     * when there is no enrollment, no diarized speech, or nothing matched.
     * Blocking and CPU-bound like the rest of the pipeline.
     */
    public static Map<Integer, String> nameSpeakers(
            Path audioFile, List<SpeakerSegment> segments, float threshold) {
        var enrollment = scanEnrollment();
        if (enrollment.isEmpty() || segments.isEmpty()) return Map.of();

        var embeddingModel = DiarizationModelManager.ensureAvailable(
                DiarizationModelManager.DiarizationModel.EMBEDDING);
        float[] samples = WhisperJniTranscriber.ffmpegToPcmF32(audioFile);

        synchronized (lock) {
            ensureExtractor(embeddingModel);
            // Person references: chunk-averaged across ALL enrolled clips.
            var references = new LinkedHashMap<String, float[]>();
            for (var person : enrollment.entrySet()) {
                var chunks = new java.util.ArrayList<float[]>();
                for (var clip : person.getValue()) {
                    try {
                        chunks.addAll(chunksOf(WhisperJniTranscriber.ffmpegToPcmF32(clip)));
                    } catch (TranscriptionException e) {
                        Logger.warn("SpeakerNamer: skipping unreadable enrollment clip %s (%s)",
                                clip, e.getMessage());
                    }
                }
                var avg = averagedEmbedding(chunks);
                if (avg != null) references.put(person.getKey(), avg);
            }
            if (references.isEmpty()) return Map.of();

            // Cluster voiceprints: same chunk-averaged scheme.
            var scoresByCluster = new LinkedHashMap<Integer, Map<String, Double>>();
            for (var e : samplesPerSpeaker(samples, segments).entrySet()) {
                var voiceprint = averagedEmbedding(chunksOf(e.getValue()));
                if (voiceprint == null) continue;
                var scores = new HashMap<String, Double>();
                for (var ref : references.entrySet()) {
                    scores.put(ref.getKey(), OverlapReattributor.cosine(voiceprint, ref.getValue()));
                }
                scoresByCluster.put(e.getKey(), scores);
            }
            var names = assignExclusive(scoresByCluster, threshold, AMBIGUITY_GAP);
            Logger.info("SpeakerNamer: scores %s -> %s (threshold %.2f, ambiguity gap %.2f)",
                    scoresByCluster, names, threshold, AMBIGUITY_GAP);
            return names;
        }
    }

    /**
     * Greedy exclusive assignment (JCLAW-606): all (cluster, person, score)
     * candidates sorted best-first; each person names at most one cluster
     * and each cluster takes at most one name — the structural fix for the
     * collapse where every cluster independently matched the single
     * enrolled voice. Clusters whose top two candidates sit within
     * {@code ambiguityGap} stay anonymous. Public so tests drive the rule
     * table with synthetic scores — no models, no natives.
     */
    public static Map<Integer, String> assignExclusive(
            Map<Integer, Map<String, Double>> scoresByCluster, float threshold, double ambiguityGap) {
        record Candidate(int cluster, String person, double score) {}
        var candidates = new java.util.ArrayList<Candidate>();
        for (var e : scoresByCluster.entrySet()) {
            var scores = e.getValue();
            if (scores.size() >= 2) {
                var sorted = scores.values().stream().sorted(java.util.Comparator.reverseOrder()).toList();
                if (sorted.get(0) - sorted.get(1) < ambiguityGap) continue; // too close to call
            }
            for (var s : scores.entrySet()) {
                if (s.getValue() >= threshold) {
                    candidates.add(new Candidate(e.getKey(), s.getKey(), s.getValue()));
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(Candidate::score).reversed());
        var names = new HashMap<Integer, String>();
        var takenPersons = new java.util.HashSet<String>();
        for (var c : candidates) {
            if (names.containsKey(c.cluster()) || takenPersons.contains(c.person())) continue;
            names.put(c.cluster(), c.person());
            takenPersons.add(c.person());
        }
        return names;
    }

    /** Split samples into {@link #CHUNK_SAMPLES} pieces, dropping a trailing
     *  fragment under 1s. */
    private static java.util.List<float[]> chunksOf(float[] samples) {
        var chunks = new java.util.ArrayList<float[]>();
        for (int at = 0; at < samples.length; at += CHUNK_SAMPLES) {
            int len = Math.min(CHUNK_SAMPLES, samples.length - at);
            if (len < 16_000) break;
            var chunk = new float[len];
            System.arraycopy(samples, at, chunk, 0, len);
            chunks.add(chunk);
        }
        return chunks;
    }

    /** Average of the chunks' embeddings, or null with no usable chunk.
     *  Caller must hold {@link #lock}. */
    private static float[] averagedEmbedding(java.util.List<float[]> chunks) {
        float[] avg = null;
        for (var chunk : chunks) {
            // JCLAW-623: unit-scale each chunk so loud chunks don't dominate.
            var emb = OverlapReattributor.l2normalize(embeddingOf(chunk));
            if (avg == null) avg = new float[emb.length];
            for (int i = 0; i < emb.length; i++) avg[i] += emb[i];
        }
        if (avg == null) return null;
        for (int i = 0; i < avg.length; i++) avg[i] /= chunks.size();
        return avg;
    }

    /**
     * Embed an arbitrary PCM float mono 16 kHz window with the shared
     * WeSpeaker extractor (JCLAW-605: stem attribution in the overlap
     * re-attribution pass). Blocking; ensures the embedding model is on
     * disk first.
     */
    public static float[] embedWindow(float[] samples) {
        var embeddingModel = DiarizationModelManager.ensureAvailable(
                DiarizationModelManager.DiarizationModel.EMBEDDING);
        synchronized (lock) {
            ensureExtractor(embeddingModel);
            return embeddingOf(samples);
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

    /** Concatenate each speaker's diarized sample ranges, capped at
     *  {@link #MAX_SAMPLES_PER_SPEAKER}. */
    private static Map<Integer, float[]> samplesPerSpeaker(
            float[] samples, List<SpeakerSegment> segments) {
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
