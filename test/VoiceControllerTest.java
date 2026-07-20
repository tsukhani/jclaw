import controllers.VoiceController;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSWSH guard for the voice-mode WebSocket (JCLAW-791): the handshake's
 * browser-set Origin must match our own host, else a cross-site page could open
 * a cookie-authenticated voice socket and drive the agent as the victim. Covers
 * the pure Origin-vs-host comparison; the WS wiring itself is exercised manually
 * (Play's FunctionalTest can't perform a WS upgrade).
 */
class VoiceControllerTest extends UnitTest {

    @Test
    void sameOriginIsAccepted() {
        assertTrue(VoiceController.originMatchesHost("http://localhost:9000", "localhost:9000"));
        assertTrue(VoiceController.originMatchesHost("https://jclaw.example.com", "jclaw.example.com"));
        // Scheme is ignored (http page → wss socket is same-origin); authority is what matters.
        assertTrue(VoiceController.originMatchesHost("https://HOST.example.com", "host.example.com"));
    }

    @Test
    void crossOriginIsRejected() {
        assertFalse(VoiceController.originMatchesHost("https://evil.example.com", "jclaw.example.com"));
        // Same host, different port is a different origin.
        assertFalse(VoiceController.originMatchesHost("http://localhost:3000", "localhost:9000"));
    }

    @Test
    void missingOrMalformedOriginIsRejected() {
        assertFalse(VoiceController.originMatchesHost(null, "localhost:9000"));
        assertFalse(VoiceController.originMatchesHost("", "localhost:9000"));
        assertFalse(VoiceController.originMatchesHost("   ", "localhost:9000"));
        assertFalse(VoiceController.originMatchesHost("not a url", "localhost:9000"));
        assertFalse(VoiceController.originMatchesHost("http://localhost:9000", null));
        assertFalse(VoiceController.originMatchesHost("http://localhost:9000", ""));
    }
}
