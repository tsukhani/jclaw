import agents.TurnCancellation;
import controllers.VoiceController;
import jobs.VoiceConversationSweepJob;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.voice.VoiceTurnSpeaker;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Voice-mode WebSocket coverage. Two concerns, both testable without a socket —
 * Play's FunctionalTest can't perform a WS upgrade, so the wiring itself is
 * exercised manually.
 *
 * <ol>
 *   <li>CSWSH guard (JCLAW-791): the handshake's browser-set Origin must match
 *       our own host, else a cross-site page could open a cookie-authenticated
 *       voice socket and drive the agent as the victim.</li>
 *   <li>Per-session conversation (JCLAW-862): each session gets a fresh row, so
 *       reopening voice mode starts clean and the operator's typed chat is never
 *       touched.</li>
 * </ol>
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

    // =====================
    // Per-session conversation — JCLAW-862
    // =====================

    /** Invoke the private session-binding factory the init frame uses, returning
     *  the opaque binding so the discard path can be driven with it. */
    private static Object openVoiceSession(Agent agent, String username) throws Exception {
        Method m = VoiceController.class.getDeclaredMethod("newSessionBinding", Agent.class, String.class);
        m.setAccessible(true);
        return m.invoke(null, agent, username);
    }

    /** Invoke the private discard the socket's finally block runs on close. The
     *  parameter type is read off the binding itself — VoiceBinding is a private
     *  nested record, so it can't be named here. */
    private static void closeVoiceSession(Object binding) throws Exception {
        Method m = VoiceController.class.getDeclaredMethod("discardSessionConversation", binding.getClass());
        m.setAccessible(true);
        m.invoke(null, binding);
    }

    private static Agent freshAgent() {
        // play1 runs test classes concurrently, so the name must not collide.
        return AgentService.create("voice-session-" + System.nanoTime(), "openrouter", "gpt-4.1");
    }

    @Test
    void eachVoiceSessionGetsItsOwnConversation() throws Exception {
        // The reset the operator asked for is achieved by NOT reusing a row:
        // opening voice mode twice must leave two conversations, so the second
        // session starts with no history rather than resuming the first.
        var agent = freshAgent();
        openVoiceSession(agent, "admin");
        openVoiceSession(agent, "admin");

        long voiceConvs = Conversation.count("agent = ?1 and channelType = ?2", agent, "voice");
        assertEquals(2L, voiceConvs,
                "two sessions must produce two conversations, not one reused row");
    }

    @Test
    void voiceSessionsDoNotTouchTheWebConversation() throws Exception {
        // The whole point of giving voice its own row: the operator's typed chat
        // must survive untouched. Resetting by clearing the shared conversation
        // would have deleted it.
        var agent = freshAgent();
        var web = ConversationService.findOrCreate(agent, "web", "admin");
        var webId = web.id;

        openVoiceSession(agent, "admin");
        openVoiceSession(agent, "admin");

        var stillThere = ConversationService.findById(webId);
        assertNotNull(stillThere, "the web conversation must survive a voice session");
        assertEquals("web", stillThere.channelType,
                "the web conversation must not be re-channelled by voice");
        assertEquals(1L, Conversation.count("agent = ?1 and channelType = ?2", agent, "web"),
                "voice must not create or replace web conversations");
    }

    @Test
    void sessionConversationsAreIndividuallyAddressable() throws Exception {
        // Peer ids carry a random suffix so two sessions never collide on the same
        // key — otherwise findByAgentChannelPeer would be left choosing between
        // rows that look identical.
        var agent = freshAgent();
        openVoiceSession(agent, "admin");
        openVoiceSession(agent, "admin");

        // Deliberately NOT streamed as Conversation: Play's find().fetch() hands
        // back a raw list, so a typed stream compiles and then fails at runtime.
        // Cast each element explicitly instead.
        List<?> rows = Conversation.find("agent = ?1 and channelType = ?2", agent, "voice").fetch();
        var peers = new HashSet<String>();
        for (Object row : rows) peers.add(((Conversation) row).peerId);

        assertEquals(2, peers.size(), "each session's conversation needs a distinct peer id");
    }

    // =====================
    // Ephemeral sessions — JCLAW-864
    // =====================

    @Test
    void closingASessionDiscardsItsConversation() throws Exception {
        // Voice interactions are one-off, and there is no conversation retention
        // job, so the row goes with the dialog rather than accumulating.
        var agent = freshAgent();
        var binding = openVoiceSession(agent, "admin");
        assertEquals(1L, Conversation.count("agent = ?1 and channelType = ?2", agent, "voice"),
                "precondition: the session created a conversation");

        closeVoiceSession(binding);

        assertEquals(0L, Conversation.count("agent = ?1 and channelType = ?2", agent, "voice"),
                "closing the dialog must leave no voice conversation behind");
    }

    @Test
    void discardingIsSafeWhenThereIsNoSession() throws Exception {
        // The socket's finally runs even when init never landed (rejected agent,
        // handshake dropped), so a null binding must be a no-op rather than an NPE
        // that masks whatever actually ended the session.
        Method m = VoiceController.class.getDeclaredMethod(
                "discardSessionConversation", Class.forName("controllers.VoiceController$VoiceBinding"));
        m.setAccessible(true);
        assertDoesNotThrow(() -> m.invoke(null, new Object[]{null}));
    }

    @Test
    void bootSweepRemovesAbandonedVoiceConversationsAndSparesWebOnes() {
        // A hard kill skips the socket's finally, so rows can survive a crash. No
        // voice session outlives a restart, which is what makes "delete them all"
        // exact rather than a heuristic — but it must not touch the typed chat.
        var agent = freshAgent();
        ConversationService.create(agent, "voice", "admin#aaaaaaaa");
        ConversationService.create(agent, "voice", "admin#bbbbbbbb");
        var web = ConversationService.findOrCreate(agent, "web", "admin");

        new VoiceConversationSweepJob().doJob();

        assertEquals(0L, Conversation.count("agent = ?1 and channelType = ?2", agent, "voice"),
                "abandoned voice conversations must not survive a boot");
        assertNotNull(ConversationService.findById(web.id),
                "the web conversation must be untouched by the voice sweep");
    }

    // =====================
    // The operator's Stop — JF-197b
    // =====================

    /** The overlay's socket: every frame sent to it, in order. */
    private static final class Overlay implements VoiceTurnSpeaker.Sink {
        final List<Map<String, Object>> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(2);

        @Override public boolean isOpen() {
            return true;
        }

        @Override public void send(Map<String, Object> frame) {
            frames.add(frame);
            ended.countDown();
        }
    }

    @Test
    void aStopOfTheOverlaysTurnFlushesItsPlaybackAndEndsTheTurn() throws Exception {
        var cancel = new AtomicBoolean();
        var current = new AtomicReference<>(cancel);
        var overlay = new Overlay();

        try (var _ = VoiceTurnSpeaker.endOnStop(current, cancel, 3, overlay)) {
            TurnCancellation.cancel(cancel);
            assertTrue(overlay.ended.await(10, TimeUnit.SECONDS), "the overlay got " + overlay.frames);
        }

        assertEquals(List.of(Map.of("type", "flush"), Map.of("type", "turn_complete", "turn", 3)), overlay.frames);
        assertNull(current.get(), "the stopped turn is no longer the overlay's");
    }

    @Test
    void aBargeInCloseOrByeTakesTheTurnFromTheOverlayFirstSoItsCancelSendsNothing() throws Exception {
        var cancel = new AtomicBoolean();
        var current = new AtomicReference<>(cancel);
        var overlay = new Overlay();

        try (var _ = VoiceTurnSpeaker.endOnStop(current, cancel, 3, overlay)) {
            // As cancelCurrent does for each of them: the slot is cleared before the flag is cancelled.
            TurnCancellation.cancel(current.getAndSet(null));
            assertFalse(overlay.ended.await(500, TimeUnit.MILLISECONDS), "the overlay got " + overlay.frames);
        }

        assertEquals(List.of(), overlay.frames);
    }
}
