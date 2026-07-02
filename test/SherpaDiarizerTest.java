import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.DiarizationModelManager;
import services.transcription.DiarizationModelManager.DiarizationModel;
import services.transcription.FfmpegProbe;
import services.transcription.SherpaDiarizer;
import services.transcription.TranscriptionException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JCLAW-556: integration coverage for the sherpa-onnx diarization engine.
 * Mirrors {@link WhisperJniTranscriberTest}'s discipline: the inference
 * test needs ffmpeg on PATH plus both ONNX models on disk (~32 MB, not
 * fetched by tests) and skips via Assumptions when either is absent, so a
 * fresh clone still passes {@code play autotest}. The ffmpeg-missing
 * error branch runs everywhere.
 */
class SherpaDiarizerTest extends UnitTest {

    private Path tempWav;

    @BeforeEach
    void setUp() throws Exception {
        tempWav = Files.createTempFile("diarizer-test-", ".wav");
        writeSilentWav(tempWav, 16000, 2);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempWav);
        SherpaDiarizer.resetForTest();
    }

    @Test
    void diarize_returnsSegments_whenModelsAndFfmpegPresent() {
        assumeTrue(FfmpegProbe.probe().available(),
                "ffmpeg not on PATH — skipping integration test");
        assumeTrue(DiarizationModelManager.availableLocally(DiarizationModel.SEGMENTATION)
                        && DiarizationModelManager.availableLocally(DiarizationModel.EMBEDDING),
                "diarization models not downloaded — skipping integration test");

        // Two seconds of silence: the diarizer must complete without
        // throwing and return a (typically empty) non-null segment list.
        // We don't assert emptiness — VAD behavior on synthetic silence is
        // model-defined, not a contract.
        var segments = SherpaDiarizer.diarize(tempWav, 0.3f, -1);
        assertNotNull(segments, "diarize must return a non-null list even on silent input");
    }

    @Test
    void diarize_throws_whenFfmpegMissing() {
        FfmpegProbe.setForTest(new FfmpegProbe.ProbeResult(false, "forced-missing-for-test"));
        try {
            var ex = assertThrows(TranscriptionException.class,
                    () -> SherpaDiarizer.diarize(tempWav, 0.3f, -1));
            assertTrue(ex.getMessage().toLowerCase().contains("ffmpeg"),
                    "error must explicitly mention ffmpeg: " + ex.getMessage());
        } finally {
            FfmpegProbe.setForTest(null);
        }
    }

    /** Same hand-rolled silent WAV as WhisperJniTranscriberTest. */
    private static void writeSilentWav(Path out, int sampleRate, int seconds) throws IOException {
        int numSamples = sampleRate * seconds;
        int byteRate = sampleRate * 2;
        int dataSize = numSamples * 2;
        var hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put("RIFF".getBytes());
        hdr.putInt(36 + dataSize);
        hdr.put("WAVE".getBytes());
        hdr.put("fmt ".getBytes());
        hdr.putInt(16);
        hdr.putShort((short) 1);
        hdr.putShort((short) 1);
        hdr.putInt(sampleRate);
        hdr.putInt(byteRate);
        hdr.putShort((short) 2);
        hdr.putShort((short) 16);
        hdr.put("data".getBytes());
        hdr.putInt(dataSize);
        try (OutputStream os = Files.newOutputStream(out)) {
            os.write(hdr.array());
            os.write(new byte[dataSize]);
        }
    }
}
