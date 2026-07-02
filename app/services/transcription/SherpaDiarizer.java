package services.transcription;

import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import play.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Speaker diarization via sherpa-onnx (JCLAW-556): pyannote segmentation-3.0
 * plus WeSpeaker ResNet34-LM embeddings, running in-process over onnxruntime
 * through sherpa's JNI bindings. Produces "who spoke when" segments; pairing
 * them with whisper transcript segments is {@link DiarizedTranscript}'s job.
 *
 * <p>Lifecycle mirrors {@link WhisperJniTranscriber}: the native engine is
 * expensive to construct (two ONNX sessions), so one instance is cached for
 * the JVM's lifetime and released from {@link jobs.ShutdownJob}. Inference
 * mutates native state, so {@link #diarize} serializes under
 * {@link #inferenceLock}. Clustering parameters are the one cheap-to-change
 * part — sherpa applies {@code setConfig} to the live engine reading only the
 * clustering block — so per-call threshold / speaker-count changes don't
 * rebuild the ONNX sessions.
 *
 * <p>The 2026-07-03 spike found sherpa's forced-cluster-count mode can
 * collapse distinct speakers, so the threshold path is primary: default 0.3
 * (config {@code transcription.diarization.threshold}); a positive
 * {@code numSpeakers} is an optional per-call hint.
 */
public final class SherpaDiarizer {

    /** One diarized span: seconds, and a zero-based speaker index. */
    public record SpeakerSegment(double start, double end, int speaker) {}

    private static final Object inferenceLock = new Object();
    // Guarded by inferenceLock, same argument as WhisperJniTranscriber's fields.
    private static OfflineSpeakerDiarization engine = null;
    private static float activeThreshold = Float.NaN;
    private static int activeNumSpeakers = Integer.MIN_VALUE;

    private SherpaDiarizer() {}

    /**
     * Diarize an audio file. Blocking and CPU-bound — run off the request
     * thread. Downloads the two ONNX models on first use (~32 MB total).
     *
     * @param threshold   clustering threshold (cosine dissimilarity, complete
     *                    linkage); lower splits more aggressively
     * @param numSpeakers exact speaker count when known, or any value below 2
     *                    to cluster by threshold alone
     */
    public static List<SpeakerSegment> diarize(Path audioFile, float threshold, int numSpeakers) {
        if (!FfmpegProbe.isAvailable()) {
            throw new TranscriptionException(
                    "ffmpeg is not available on PATH — install ffmpeg to enable diarization");
        }
        var segmentationModel = DiarizationModelManager.ensureAvailable(
                DiarizationModelManager.DiarizationModel.SEGMENTATION);
        var embeddingModel = DiarizationModelManager.ensureAvailable(
                DiarizationModelManager.DiarizationModel.EMBEDDING);

        float[] samples = WhisperJniTranscriber.ffmpegToPcmF32(audioFile);

        synchronized (inferenceLock) {
            ensureEngine(segmentationModel, embeddingModel, threshold, numSpeakers);
            var raw = engine.process(samples);
            var segments = new ArrayList<SpeakerSegment>(raw.length);
            for (var s : raw) {
                segments.add(new SpeakerSegment(s.getStart(), s.getEnd(), s.getSpeaker()));
            }
            return segments;
        }
    }

    /** Free the native engine on JVM shutdown. Wired from {@link jobs.ShutdownJob}. */
    public static void shutdown() {
        synchronized (inferenceLock) {
            if (engine != null) {
                try {
                    engine.release();
                } catch (@SuppressWarnings("java:S1181") Throwable t) {
                    // JNI release can surface native errors; shutdown must continue
                    Logger.warn(t, "SherpaDiarizer: error releasing engine");
                }
                engine = null;
                activeThreshold = Float.NaN;
                activeNumSpeakers = Integer.MIN_VALUE;
            }
        }
    }

    /** Caller must hold {@link #inferenceLock}. */
    private static void ensureEngine(Path segmentationModel, Path embeddingModel,
                                     float threshold, int numSpeakers) {
        if (engine == null) {
            try {
                engine = new OfflineSpeakerDiarization(
                        buildConfig(segmentationModel, embeddingModel, threshold, numSpeakers));
            } catch (RuntimeException e) {
                throw new TranscriptionException(
                        "failed to initialise sherpa-onnx diarization engine: " + e.getMessage(), e);
            } catch (@SuppressWarnings("java:S1181") UnsatisfiedLinkError e) {
                throw new TranscriptionException(
                        "sherpa-onnx native library unavailable on this platform — "
                                + "diarization needs a sherpa-onnx-native-lib jar for this OS/arch", e);
            }
            activeThreshold = threshold;
            activeNumSpeakers = numSpeakers;
            Logger.info("SherpaDiarizer: engine loaded (threshold=%.2f numSpeakers=%d)",
                    threshold, numSpeakers);
            return;
        }
        if (activeThreshold != threshold || activeNumSpeakers != numSpeakers) {
            // sherpa reads only the clustering block on setConfig — model paths
            // in the rebuilt config object are ignored, no session reload.
            engine.setConfig(buildConfig(segmentationModel, embeddingModel, threshold, numSpeakers));
            activeThreshold = threshold;
            activeNumSpeakers = numSpeakers;
        }
    }

    private static OfflineSpeakerDiarizationConfig buildConfig(
            Path segmentationModel, Path embeddingModel, float threshold, int numSpeakers) {
        return OfflineSpeakerDiarizationConfig.builder()
                .setSegmentation(OfflineSpeakerSegmentationModelConfig.builder()
                        .setPyannote(OfflineSpeakerSegmentationPyannoteModelConfig.builder()
                                .setModel(segmentationModel.toString())
                                .build())
                        .build())
                .setEmbedding(SpeakerEmbeddingExtractorConfig.builder()
                        .setModel(embeddingModel.toString())
                        .build())
                .setClustering(FastClusteringConfig.builder()
                        .setNumClusters(numSpeakers >= 2 ? numSpeakers : -1)
                        .setThreshold(threshold)
                        .build())
                .setMinDurationOn(0.2f)
                .setMinDurationOff(0.5f)
                .build();
    }

    /** Test-only: drop the cached engine so tests don't bleed. */
    public static void resetForTest() {
        shutdown();
    }
}
