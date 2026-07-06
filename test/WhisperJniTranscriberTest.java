import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.FfmpegProbe;
import services.transcription.TranscriptionException;
import services.transcription.WhisperJniTranscriber;
import services.transcription.WhisperModel;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link WhisperJniTranscriber}. Requires both
 * {@code ffmpeg} on PATH and the default whisper model file on disk —
 * skips via {@link org.junit.jupiter.api.Assumptions} when either is
 * absent so a fresh clone with no setup still passes {@code play autotest}.
 *
 * <p>Test driver shape: write a 1-second silent 16 kHz mono PCM WAV to
 * a tempfile, run it through the transcriber, assert the call returns
 * (a non-null String) without exceptions. We don't assert on the
 * transcribed text itself because silence's "transcript" is whisper-
 * implementation-defined (typically the empty string or a hallucinated
 * filler word) and not a stable contract.
 */
class WhisperJniTranscriberTest extends UnitTest {

    private Path tempWav;

    @BeforeEach
    void setUp() throws Exception {
        tempWav = Files.createTempFile("whisper-test-", ".wav");
        writeSilentWav(tempWav, 16000, 1); // 1 second of silent 16 kHz mono PCM16
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempWav);
    }

    @Test
    void transcribeSegments_failsFastWithSetupInstructions_withoutUv() {
        // JCLAW-650: sidecar-or-error — no whisper.cpp fallback exists.
        services.UvProbe.setForTest(new services.UvProbe.ProbeResult(false, "forced off"));
        try {
            var ex = assertThrows(TranscriptionException.class,
                    () -> WhisperJniTranscriber.transcribeSegments(tempWav, WhisperModel.DEFAULT, null));
            assertTrue(ex.getMessage().contains("uv"), ex.getMessage());
            assertTrue(ex.getMessage().contains("setup"), ex.getMessage());
        } finally {
            services.UvProbe.setForTest(null);
        }
    }

    /**
     * Write a minimal WAV (PCM16 little-endian, mono) of pure silence. Hand-
     * rolled so we don't depend on {@code javax.sound.sampled} which may
     * behave unpredictably in headless test environments.
     */
    private static void writeSilentWav(Path out, int sampleRate, int seconds) throws IOException {
        int numSamples = sampleRate * seconds;
        int byteRate = sampleRate * 2; // mono PCM16
        int dataSize = numSamples * 2;
        var hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put("RIFF".getBytes());
        hdr.putInt(36 + dataSize);
        hdr.put("WAVE".getBytes());
        hdr.put("fmt ".getBytes());
        hdr.putInt(16);                 // fmt chunk size
        hdr.putShort((short) 1);        // PCM
        hdr.putShort((short) 1);        // num channels (mono)
        hdr.putInt(sampleRate);
        hdr.putInt(byteRate);
        hdr.putShort((short) 2);        // block align (mono PCM16 = 2 bytes/frame)
        hdr.putShort((short) 16);       // bits per sample
        hdr.put("data".getBytes());
        hdr.putInt(dataSize);
        try (OutputStream os = Files.newOutputStream(out)) {
            os.write(hdr.array());
            os.write(new byte[dataSize]); // silent samples
        }
    }
}
