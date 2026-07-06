package services.transcription;

import play.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Production speaker-embedding implementation (JCLAW-630): stages PCM
 * windows as temp WAVs and runs one batched sidecar /embed call — the same
 * WeSpeaker ONNX and feature pipeline the retired JNI stack used, so every
 * calibrated threshold keeps its meaning. Implements both Embedder seams;
 * loops that were restructured to {@code embedAll} pay one HTTP round trip
 * per batch instead of one per window.
 *
 * <p>Only reachable inside sidecar-gated flows (diarization is
 * sidecar-or-error since JCLAW-614), so no JNI fallback exists or is
 * needed.
 */
public final class SidecarEmbedder
        implements VoiceMath.Embedder, SpeakerClipExtractor.Embedder {

    public static final SidecarEmbedder INSTANCE = new SidecarEmbedder();

    private SidecarEmbedder() {}

    @Override
    public float[] embed(float[] samples) {
        return embedAll(List.of(samples)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<float[]> windows) {
        if (windows.isEmpty()) return List.of();
        var model = DiarizationModelManager.ensureAvailable(
                DiarizationModelManager.DiarizationModel.EMBEDDING);
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("jclaw-embed-");
            var wavs = new ArrayList<Path>(windows.size());
            for (int i = 0; i < windows.size(); i++) {
                var wav = tmpDir.resolve("w" + i + ".wav");
                Files.write(wav, SpeakerClipExtractor.toWavPcm16(windows.get(i)));
                wavs.add(wav);
            }
            return new PyannoteDiarizationClient().embedBatch(wavs, model);
        } catch (IOException e) {
            throw new TranscriptionException("failed to stage embedding windows: " + e.getMessage(), e);
        } finally {
            if (tmpDir != null) deleteRecursive(tmpDir);
        }
    }

    private static void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) {
                        Logger.warn("SidecarEmbedder: temp cleanup failed for %s", p); } });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
