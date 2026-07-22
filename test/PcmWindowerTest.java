import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.voice.PcmWindower;

import java.util.ArrayList;
import java.util.List;

/**
 * PCM16 → float window reframing (JCLAW-799): windows must fall out at exactly
 * the right size regardless of how the byte stream is chopped, including a
 * sample split across two frames.
 */
class PcmWindowerTest extends UnitTest {

    private static byte[] le16(int... samples) {
        var b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            b[i * 2] = (byte) (samples[i] & 0xFF);
            b[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return b;
    }

    @Test
    void emitsFullWindowsAndBuffersTheRemainder() {
        var w = new PcmWindower(4);
        List<float[]> got = new ArrayList<>();
        w.accept(le16(0, 16384, -16384, 32767, 100), 10, got::add); // 5 samples, window=4
        assertEquals(1, got.size());
        var win = got.get(0);
        assertEquals(0f, win[0], 1e-6);
        assertEquals(0.5f, win[1], 1e-4);   // 16384/32768
        assertEquals(-0.5f, win[2], 1e-4);
        assertTrue(win[3] > 0.99f);          // 32767/32768
        // The 5th sample is still buffered; two more complete the next window.
        w.accept(le16(200, 300), 4, got::add);
        assertEquals(1, got.size()); // only 3 of 4 buffered — no new window yet
        w.accept(le16(400), 2, got::add);
        assertEquals(2, got.size());
    }

    @Test
    void reassemblesASampleSplitAcrossFrames() {
        var w = new PcmWindower(2);
        List<float[]> got = new ArrayList<>();
        var full = le16(1000, 2000); // 4 bytes = 2 samples = 1 window
        // Feed 3 bytes, then the 4th — the second sample straddles the boundary.
        w.accept(new byte[]{ full[0], full[1], full[2] }, 3, got::add);
        assertEquals(0, got.size());
        w.accept(new byte[]{ full[3] }, 1, got::add);
        assertEquals(1, got.size());
        assertEquals(2000 / 32768f, got.get(0)[1], 1e-6);
    }

    @Test
    void resetClearsPartialState() {
        var w = new PcmWindower(4);
        List<float[]> got = new ArrayList<>();
        w.accept(le16(1, 2, 3), 6, got::add); // 3 of 4 buffered
        w.reset();
        w.accept(le16(10, 20, 30, 40), 8, got::add); // a clean window
        assertEquals(1, got.size());
        assertEquals(10 / 32768f, got.get(0)[0], 1e-6);
    }
}
