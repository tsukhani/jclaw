import channels.WhatsAppCobaltChannel;
import channels.WhatsAppCobaltRunner;
import channels.WhatsAppCobaltSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Behavior coverage for {@link WhatsAppCobaltChannel} (JCLAW-450): the
 * degrade-quietly contract. The channel holds no socket — it resolves the live
 * {@link WhatsAppCobaltSession} from {@link WhatsAppCobaltRunner} per call — so
 * every outbound entry point must return FAILED/false/void (never throw) when no
 * session exists, when a session exists but was never connected, and when a
 * session exists without a Cobalt handle.
 *
 * <p>The "registered but dead" cases seed a bare (never-connected) session
 * directly into the runner's registry via reflection, keyed on synthetic binding
 * ids no other test uses, and remove exactly those keys afterwards — the
 * registry itself is never cleared or reconciled here (per the Slack rule in
 * {@code WhatsAppCobaltSessionTest}: don't race the concurrent lanes on the
 * global set). The connected-session send paths (Jid parsing, media message
 * building) need a live paired {@code it.auties.whatsapp.api.Whatsapp} socket
 * and are out of unit-test reach — see the shim link test in
 * {@code WhatsAppCobaltMediaShimTest} for the media-build dependency.
 */
class WhatsAppCobaltChannelTest extends UnitTest {

    /** Registry keys owned by this test class only. */
    private static final long UNREGISTERED_ID = 9_450_101L;
    private static final long BARE_SESSION_ID = 9_450_102L;

    private static final Field HANDLES_FIELD;
    static {
        try {
            HANDLES_FIELD = WhatsAppCobaltRunner.class.getDeclaredField("HANDLES");
            HANDLES_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        handles().remove(BARE_SESSION_ID);
    }

    // ── binding → channel resolution ──

    @Test
    void forBindingWithNullBindingYieldsNull() {
        assertNull(WhatsAppCobaltChannel.forBinding(null),
                "a null binding must resolve to no channel, not an NPE later");
    }

    @Test
    void channelNameIsWhatsapp() {
        assertEquals("whatsapp", channelFor(UNREGISTERED_ID).channelName(),
                "both transports share the 'whatsapp' channel name");
    }

    // ── degrade-quietly: no live session ──

    @Test
    void trySendFailsWhenNoSessionIsRegistered() {
        assertFalse(channelFor(UNREGISTERED_ID).trySend("447911111111@s.whatsapp.net", "hi").ok(),
                "no session for the binding → FAILED, never a throw");
    }

    @Test
    void trySendFailsForABindingWithoutAnId() {
        // An unsaved binding (id null) can't resolve a session either.
        var binding = new models.WhatsAppBinding();
        assertFalse(WhatsAppCobaltChannel.forBinding(binding)
                        .trySend("447911111111@s.whatsapp.net", "hi").ok(),
                "null binding id → no session → FAILED");
    }

    @Test
    void trySendFailsWhenSessionExistsButNeverConnected() throws Exception {
        var ch = channelWithBareSession();
        assertFalse(ch.trySend("447911111111@s.whatsapp.net", "hi").ok(),
                "a registered but never-connected session must degrade to FAILED");
    }

    @Test
    void sendPhotoFailsWithoutALiveSession() throws Exception {
        Path tmp = Files.createTempFile("wa-cobalt-photo-", ".png");
        Files.write(tmp, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        try {
            assertFalse(channelFor(UNREGISTERED_ID)
                            .sendPhoto("447911111111@s.whatsapp.net", tmp.toFile(), "cap").ok(),
                    "the failure is session-driven — the file itself is readable");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void sendDocumentFailsWithoutALiveSession() throws Exception {
        Path tmp = Files.createTempFile("wa-cobalt-doc-", ".pdf");
        Files.writeString(tmp, "%PDF-1.4 fake");
        try {
            assertFalse(channelFor(UNREGISTERED_ID)
                            .sendDocument("447911111111@s.whatsapp.net", tmp.toFile(), null).ok(),
                    "the failure is session-driven — the file itself is readable");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── reactions ──

    @Test
    void sendReactionIsFalseWhenNoSessionIsRegistered() {
        assertFalse(channelFor(UNREGISTERED_ID).sendReaction("MSG-1", "👍"),
                "no session → false, never a throw");
    }

    @Test
    void sendReactionIsFalseWhenSessionHasNoHandleOrCachedTarget() throws Exception {
        // Session resolves, but it holds no Cobalt handle and the target message
        // was never cached — both gates must yield false without touching a wire.
        var ch = channelWithBareSession();
        assertFalse(ch.sendReaction("MSG-NEVER-SEEN", "👍"));
        assertFalse(ch.sendReaction("MSG-NEVER-SEEN", null),
                "the remove-reaction form degrades the same way");
    }

    // ── presence ──

    @Test
    void startTypingIsASafeNoOpWithoutALiveSession() throws Exception {
        assertDoesNotThrow(() -> channelFor(UNREGISTERED_ID).startTyping("447911111111@s.whatsapp.net"),
                "presence is best-effort: no session must never break the reply flow");
        var ch = channelWithBareSession();
        assertDoesNotThrow(() -> ch.startTyping("447911111111@s.whatsapp.net"),
                "a never-connected session must be equally harmless");
    }

    // ── helpers ──

    private static WhatsAppCobaltChannel channelFor(long bindingId) {
        var binding = new models.WhatsAppBinding();
        binding.id = bindingId;
        return WhatsAppCobaltChannel.forBinding(binding);
    }

    /** Register a bare (never-connected) session under this class's own key and
     *  return a channel bound to it. Removed again in {@link #tearDown()}. */
    private WhatsAppCobaltChannel channelWithBareSession() throws Exception {
        handles().put(BARE_SESSION_ID, new WhatsAppCobaltSession(BARE_SESSION_ID));
        return channelFor(BARE_SESSION_ID);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, WhatsAppCobaltSession> handles() throws Exception {
        return (Map<Long, WhatsAppCobaltSession>) HANDLES_FIELD.get(null);
    }
}
