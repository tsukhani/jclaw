import models.Agent;
import models.Conversation;
import models.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import slash.Commands;

import java.time.Instant;

/**
 * JCLAW-26 coverage: slash-command parsing, execution side effects,
 * findByAgentChannelPeer ordering, and contextSince filtering.
 *
 * <p>Not covered here: per-controller SSE / Telegram dispatch wiring —
 * those are thin adapters around {@link Commands#execute}. The behavior
 * that actually matters (new row creation, reset watermark, history
 * filter) lives in this test class.
 */
public class SlashCommandsTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = AgentService.create("slash-agent", "openrouter", "gpt-4.1");
    }

    // ── parse ──────────────────────────────────────────────────────────

    @Test
    public void parseRecognizesAllThreeCommands() {
        assertEquals(Commands.Command.NEW, Commands.parse("/new").orElseThrow());
        assertEquals(Commands.Command.RESET, Commands.parse("/reset").orElseThrow());
        assertEquals(Commands.Command.HELP, Commands.parse("/help").orElseThrow());
    }

    @Test
    public void parseIsCaseInsensitive() {
        assertEquals(Commands.Command.NEW, Commands.parse("/NEW").orElseThrow());
        assertEquals(Commands.Command.RESET, Commands.parse("/Reset").orElseThrow());
    }

    @Test
    public void parseTrimsLeadingAndTrailingWhitespace() {
        assertEquals(Commands.Command.HELP, Commands.parse("   /help\n").orElseThrow());
    }

    @Test
    public void parseIgnoresArgumentsAfterCommand() {
        // Commands don't take arguments yet — extra tokens are discarded
        // rather than rejecting the whole input.
        assertEquals(Commands.Command.NEW, Commands.parse("/new please start fresh").orElseThrow());
    }

    @Test
    public void parseReturnsEmptyForUnknownSlashCommand() {
        assertTrue(Commands.parse("/tarun").isEmpty(),
                "unknown slash commands must pass through so LLM sees them");
        assertTrue(Commands.parse("/start").isEmpty(),
                "/start is Telegram's auto-onboarding — reserved for JCLAW-97");
    }

    @Test
    public void parseReturnsEmptyForNonSlashInput() {
        assertTrue(Commands.parse("hello").isEmpty());
        assertTrue(Commands.parse("").isEmpty());
        assertTrue(Commands.parse(null).isEmpty());
        assertTrue(Commands.parse("   ").isEmpty());
        // Must start with a slash — midline slashes don't count.
        assertTrue(Commands.parse("see /help for info").isEmpty());
    }

    // ── /new ───────────────────────────────────────────────────────────

    @Test
    public void newCreatesFreshConversationAndIgnoresCurrent() {
        var existing = ConversationService.findOrCreate(agent, "telegram", "user-123");
        ConversationService.appendUserMessage(existing, "earlier message");

        var result = Commands.execute(
                Commands.Command.NEW, agent, "telegram", "user-123", existing);

        assertNotNull(result.conversation(),
                "/new must return a non-null new conversation");
        assertNotEquals(existing.id, result.conversation().id,
                "/new must create a fresh Conversation row, not reuse the current one");
        assertEquals(Commands.NEW_TEXT, result.responseText());

        // The new conversation has exactly the canned assistant message.
        var msgs = ConversationService.loadRecentMessages(result.conversation());
        assertEquals(1, msgs.size());
        assertEquals("assistant", msgs.getFirst().role);
        assertEquals(Commands.NEW_TEXT, msgs.getFirst().content);

        // The existing conversation is untouched.
        var existingMsgs = ConversationService.loadRecentMessages(existing);
        assertEquals(1, existingMsgs.size());
        assertEquals("earlier message", existingMsgs.getFirst().content);
    }

    @Test
    public void findByAgentChannelPeerReturnsMostRecentAfterNew() {
        // Critical for the /new handler to "stick" on Telegram: subsequent
        // findOrCreate calls must resolve to the new row, not the old one.
        // Before JCLAW-26 the JPQL had no ORDER BY — first() could return
        // either in insertion order, which would route the next Telegram
        // message back to the pre-/new conversation.
        var first = ConversationService.findOrCreate(agent, "telegram", "user-456");
        Commands.execute(Commands.Command.NEW, agent, "telegram", "user-456", first);

        var resolvedAfterNew = ConversationService.findOrCreate(agent, "telegram", "user-456");
        assertNotEquals(first.id, resolvedAfterNew.id,
                "findOrCreate must return the most recent row after /new creates a new one");
    }

    // ── /reset ─────────────────────────────────────────────────────────

    @Test
    public void resetStampsContextSinceOnCurrentConversation() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        var before = Instant.now();

        var result = Commands.execute(
                Commands.Command.RESET, agent, "web", "admin", convo);

        assertNotNull(result.conversation());
        assertEquals(convo.id, result.conversation().id,
                "/reset must keep the same conversation row");
        assertNotNull(result.conversation().contextSince,
                "/reset must stamp a contextSince watermark");
        assertFalse(result.conversation().contextSince.isBefore(before),
                "contextSince must be set after the /reset call started");
    }

    @Test
    public void resetExcludesPriorMessagesFromLlmContext() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "pre-reset question");
        ConversationService.appendAssistantMessage(convo, "pre-reset answer", null);

        Commands.execute(Commands.Command.RESET, agent, "web", "admin", convo);

        // Simulate a subsequent user turn.
        var reloaded = ConversationService.findById(convo.id);
        ConversationService.appendUserMessage(reloaded, "post-reset question");

        var llmContext = ConversationService.loadRecentMessages(reloaded);
        // /reset doesn't persist any new Message (the ack is transient —
        // delivered via the channel's response mechanism only). So the only
        // message strictly newer than contextSince is the post-reset turn.
        assertEquals(1, llmContext.size(),
                "LLM context must exclude pre-reset messages");
        assertEquals("post-reset question", llmContext.getFirst().content);
    }

    @Test
    public void resetDoesNotPersistAck() {
        // /reset is a control signal — the ack text is delivered to the
        // user via the channel's response path (SSE complete frame / sink
        // seal) but must not enter Message history. Persisting it created
        // a clock-alignment bug on Linux where the ack.createdAt and
        // contextSince rounded to the same TIMESTAMP and the ">" filter
        // flaked depending on driver rounding direction. The invariant
        // that prevents it: no new rows from /reset at all.
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "first");
        ConversationService.appendAssistantMessage(convo, "second", null);
        var countBefore = Message.count("conversation = ?1", convo);

        Commands.execute(Commands.Command.RESET, agent, "web", "admin", convo);

        var countAfter = Message.count("conversation = ?1", convo);
        assertEquals(countBefore, countAfter,
                "/reset must not persist any new Message row");
    }

    @Test
    public void resetWithoutCurrentConversationIsSafe() {
        // Defensive: caller may pass a null conversation (e.g. channel hasn't
        // resolved one yet). Should not throw; returns a fallback response.
        var result = Commands.execute(
                Commands.Command.RESET, agent, "web", "admin", null);
        assertNull(result.conversation());
        assertNotNull(result.responseText());
    }

    // ── /help ──────────────────────────────────────────────────────────

    @Test
    public void helpReturnsHelpTextAndDoesNotMutateState() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "prior message");

        var result = Commands.execute(
                Commands.Command.HELP, agent, "web", "admin", convo);

        assertEquals(Commands.HELP_TEXT, result.responseText());
        assertEquals(convo.id, result.conversation().id);
        // Conversation still has contextSince = null — /help is not a reset.
        var reloaded = ConversationService.findById(convo.id);
        assertNull(reloaded.contextSince);
    }

    @Test
    public void helpTextListsAllRecognizedCommands() {
        // If a new command is added, HELP_TEXT must be updated — this test
        // fails loudly when /new, /reset, or /help are missing from the
        // listing (a cheap guardrail against silent drift).
        assertTrue(Commands.HELP_TEXT.contains("/new"));
        assertTrue(Commands.HELP_TEXT.contains("/reset"));
        assertTrue(Commands.HELP_TEXT.contains("/help"));
    }

    // ── handle() convenience ───────────────────────────────────────────

    @Test
    public void handleReturnsEmptyForNonSlashInput() {
        var current = ConversationService.findOrCreate(agent, "web", "admin");
        var result = Commands.handle("regular message", agent, "web", "admin", current);
        assertTrue(result.isEmpty(),
                "handle() must return empty so the caller proceeds to the LLM");
    }

    @Test
    public void handleDispatchesRecognizedCommand() {
        var current = ConversationService.findOrCreate(agent, "web", "admin");
        var result = Commands.handle("/help", agent, "web", "admin", current);
        assertTrue(result.isPresent());
        assertEquals(Commands.Command.HELP, result.get().command());
    }
}
