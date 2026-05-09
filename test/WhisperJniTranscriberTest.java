import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.transcription.FfmpegProbe;
import services.transcription.WhisperJniTranscriber;
import services.transcription.WhisperModel;
import services.transcription.WhisperModelManager;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
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
public class WhisperJniTranscriberTest extends UnitTest {

    private Path tempWav;

    @BeforeEach
    public void setUp() throws Exception {
        tempWav = Files.createTempFile("whisper-test-", ".wav");
        writeSilentWav(tempWav, 16000, 1); // 1 second of silent 16 kHz mono PCM16
    }

    @AfterEach
    public void tearDown() throws Exception {
        Files.deleteIfExists(tempWav);
        WhisperJniTranscriber.resetForTest();
    }

    @Test
    public void transcribe_returnsString_whenModelAndFfmpegPresent() {
        assumeTrue(FfmpegProbe.probe().available(),
                "ffmpeg not on PATH — skipping integration test");
        var model = WhisperModel.DEFAULT;
        assumeTrue(WhisperModelManager.availableLocally(model),
                "Whisper model %s not downloaded — skipping integration test"
                        .formatted(model.id()));

        // Returns whatever whisper.cpp says about a second of silence —
        // typically empty or a single short token. Either way, the call
        // must complete without throwing and must not return null.
        var result = WhisperJniTranscriber.transcribe(tempWav, model);
        assertNotNull(result, "transcribe must return a non-null string even on silent input");
    }

    @Test
    public void transcribe_throws_whenFfmpegMissing() {
        FfmpegProbe.setForTest(new FfmpegProbe.ProbeResult(false, "forced-missing-for-test"));
        try {
            var ex = assertThrows(WhisperJniTranscriber.TranscriptionException.class,
                    () -> WhisperJniTranscriber.transcribe(tempWav, WhisperModel.DEFAULT));
            assertTrue(ex.getMessage().toLowerCase().contains("ffmpeg"),
                    "error must explicitly mention ffmpeg: " + ex.getMessage());
        } finally {
            FfmpegProbe.setForTest(null); // restore the un-probed sentinel
        }
    }

    @Test
    public void transcribe_throws_whenModelNotDownloaded() {
        assumeTrue(FfmpegProbe.probe().available(),
                "ffmpeg not on PATH — skipping (this branch needs FfmpegProbe to pass first)");
        // Pick a model that almost certainly isn't downloaded in dev — the
        // multilingual medium variant, ~514 MB. The "downloaded" check uses
        // file existence under WhisperModelManager.localPath, so we don't
        // need to clear the manager state.
        var unlikelyDownloaded = WhisperModel.MEDIUM_MULTILINGUAL;
        assumeTrue(!WhisperModelManager.availableLocally(unlikelyDownloaded),
                "MEDIUM_MULTILINGUAL is downloaded — skipping the not-downloaded branch test");
        var ex = assertThrows(WhisperJniTranscriber.TranscriptionException.class,
                () -> WhisperJniTranscriber.transcribe(tempWav, unlikelyDownloaded));
        assertTrue(ex.getMessage().toLowerCase().contains("not downloaded"),
                "error must explain the model is not downloaded: " + ex.getMessage());
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
