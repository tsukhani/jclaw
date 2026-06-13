import channels.WhatsAppCobaltRunner;
import channels.WhatsAppCobaltSession;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * Unit coverage for the pure, side-effect-free seams of the Cobalt session +
 * runner (JCLAW-448/449). Per the Slack rule, {@code WhatsAppCobaltRunner.reconcile()}
 * is NOT tested here — it mutates a process-global registry that races the
 * concurrent play1 test lanes. Only deterministic helpers and read-only lookups
 * (which never mutate the live set) are exercised.
 */
class WhatsAppCobaltSessionTest extends UnitTest {

    @Test
    void sessionUuidIsDeterministicPerBinding() {
        var a1 = new WhatsAppCobaltSession(42L);
        var a2 = new WhatsAppCobaltSession(42L);
        // Same binding id → same on-disk session UUID across restarts, so resume
        // always finds the same serialized store.
        assertEquals(a1.sessionUuid(), a2.sessionUuid());
    }

    @Test
    void sessionUuidDiffersAcrossBindings() {
        var a = new WhatsAppCobaltSession(1L);
        var b = new WhatsAppCobaltSession(2L);
        assertNotSame(a.sessionUuid(), b.sessionUuid());
        assertFalse(a.sessionUuid().equals(b.sessionUuid()));
    }

    @Test
    void sessionDirIsPerBinding() {
        var dir7 = new WhatsAppCobaltSession(7L).sessionDir();
        var dir8 = new WhatsAppCobaltSession(8L).sessionDir();
        // Leaf is the binding id so two bindings never share a session dir.
        assertEquals("7", dir7.getFileName().toString());
        assertEquals("8", dir8.getFileName().toString());
        assertTrue(dir7.toString().contains("whatsapp-cobalt"));
        // The dir resolves under the app root (absolute in production; the test
        // harness may anchor a relative app path — either way it's per-binding).
        assertNotEquals(dir7, dir8);
    }

    @Test
    void freshSessionIsNotConnectedAndHasNoOwner() {
        var session = new WhatsAppCobaltSession(99L);
        assertFalse(session.isConnected());
        assertNull(session.ownerJid());
        assertNull(session.whatsapp());
        assertNull(session.recentMessage("anything"));
        assertEquals(Long.valueOf(99L), session.bindingId());
    }

    @Test
    void runnerLookupsForUnknownBindingAreEmpty() {
        // Read-only lookups against a binding the runner has never opened: must be
        // null/false without mutating the global registry.
        long unknown = -987654321L;
        assertNull(WhatsAppCobaltRunner.pendingQr(unknown));
        assertNull(WhatsAppCobaltRunner.session(unknown));
        assertFalse(WhatsAppCobaltRunner.isPaired(unknown));
        // null binding id resolves to null defensively.
        assertNull(WhatsAppCobaltRunner.session(null));
    }
}
