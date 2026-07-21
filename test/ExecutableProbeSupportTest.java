import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ExecutableProbeSupport;

/**
 * {@link ExecutableProbeSupport#probeCapturing} must classify + capture output
 * under a bounded wait, so a binary that accepts the invocation but never exits
 * yields an unavailable result instead of hanging the caller (the synchronous
 * boot probe {@link jobs.TesseractProbeJob} via {@link services.OcrHealthProbe}).
 */
class ExecutableProbeSupportTest extends UnitTest {

    @Test
    void capturesOutputAndReportsAvailableForARunningBinary() {
        // `echo hello` prints its arg and exits 0 on every supported platform.
        var r = ExecutableProbeSupport.probeCapturing("echo", "hello", "");
        assertTrue(r.available(), "echo exits 0 -> available");
        assertEquals("available", r.reason());
        assertEquals("hello", r.output().trim(), "combined stdout is captured for the caller");
    }

    @Test
    void reportsUnavailableWhenBinaryIsAbsentFromPath() {
        var r = ExecutableProbeSupport.probeCapturing(
                "jclaw-no-such-binary-xyz", "--version", " — install it");
        assertFalse(r.available());
        assertTrue(r.reason().contains("not found on PATH"),
                "IOException from start() maps to a not-found reason, not a thrown exception");
        assertTrue(r.reason().contains("install it"), "the not-found hint is appended");
    }

    @Test
    void boundsAHangingBinaryInsteadOfBlockingForever() {
        // `sleep 300` accepts the invocation but never exits within the probe
        // window; the bounded waitFor + destroyForcibly must return promptly.
        long start = System.currentTimeMillis();
        var r = ExecutableProbeSupport.probeCapturing("sleep", "300", "");
        long elapsedSeconds = (System.currentTimeMillis() - start) / 1000;

        assertFalse(r.available(), "a binary that never exits is not available");
        assertTrue(r.reason().contains("did not exit within"),
                "the timeout branch classifies the hang");
        assertTrue(elapsedSeconds < 60,
                "must return near the probe timeout, not block on the child's full runtime");
    }
}
