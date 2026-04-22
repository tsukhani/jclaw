import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SessionCompaction;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;

import java.time.Instant;
import java.util.List;

/**
 * Service-layer tests for {@link ConversationService}: CRUD, message-append
 * mechanics, the recent-message loader (with {@code contextSince} +
 * {@code compactionSince} watermark interaction), bulk delete cascade, the
 * conversation-scoped model-override setters, and the title-generation
 * gating predicate.
 *
 * <p>{@code generateTitleAsync} itself is not tested here — it spins a
 * virtual thread and depends on a configured LLM provider, both of which
 * make assertions flaky in a unit-test setting.
 */
public class ConversationServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    private Agent newAgent(String name) {
        return AgentService.create(name, "openrouter", "gpt-4.1");
    }

    // =====================
    // findOrCreate / create
    // =====================

    @Test
    public void findOrCreateReturnsExistingForRepeatTuple() {
        var agent = newAgent("conv-find-or-create-1");
        var first = ConversationService.findOrCreate(agent, "web", "user-42");
        var second = ConversationService.findOrCreate(agent, "web", "user-42");
        assertEquals(first.id, second.id,
                "findOrCreate must return the same row for an identical tuple");
    }

    @Test
    public void findOrCreateCreatesNewRowWhenTupleAbsent() {
        var agent = newAgent("conv-find-or-create-2");
        var conv = ConversationService.findOrCreate(agent, "web", "first-user");
        assertNotNull(conv.id);
        assertEquals("web", conv.channelType);
        assertEquals("first-user", conv.peerId);
        assertEquals(0, conv.messageCount, "new conversation starts with messageCount=0");
        assertNull(conv.preview, "new conversation has no preview");
    }

    @Test
    public void createPersistsChannelAndPeer() {
        var agent = newAgent("conv-create-direct");
        var conv = ConversationService.create(agent, "telegram", "tg-42");
        assertNotNull(conv.id);
        assertEquals("telegram", conv.channelType);
        assertEquals("tg-42", conv.peerId);
        assertEquals(agent.id, conv.agent.id);
    }

    @Test
    public void findByIdReturnsConversationOrNull() {
        var agent = newAgent("conv-find-by-id");
        var conv = ConversationService.create(agent, "web", "p-1");
        assertNotNull(ConversationService.findById(conv.id));
        assertNull(ConversationService.findById(99_999_999L));
    }

    // =====================
    // appendMessage helpers
    // =====================

    @Test
    public void appendUserMessageIncrementsCountAndSeedsPreview() {
        var agent = newAgent("conv-append-user");
        var conv = ConversationService.create(agent, "web", "u");
        var msg = ConversationService.appendUserMessage(conv, "Hello, world.");
        assertNotNull(msg.id);
        assertEquals(MessageRole.USER.value, msg.role);
        assertEquals("Hello, world.", msg.content);
        assertEquals(1, conv.messageCount, "user append must bump messageCount to 1");
        assertEquals("Hello, world.", conv.preview,
                "first user message must populate preview");
    }

    @Test
    public void appendUserMessagePreviewTruncatesAt100Characters() {
        var agent = newAgent("conv-append-truncate");
        var conv = ConversationService.create(agent, "web", "u");
        var longText = "x".repeat(250);
        ConversationService.appendUserMessage(conv, longText);
        assertEquals(100, conv.preview.length(),
                "preview must be capped at 100 chars");
    }

    @Test
    public void appendUserMessageSecondTimeDoesNotOverwritePreview() {
        var agent = newAgent("conv-append-no-overwrite");
        var conv = ConversationService.create(agent, "web", "u");
        ConversationService.appendUserMessage(conv, "first");
        ConversationService.appendUserMessage(conv, "second");
        assertEquals("first", conv.preview,
                "preview is only set when the prior preview is null");
    }

    @Test
    public void appendAssistantMessageStoresRoleAndContent() {
        var agent = newAgent("conv-append-assistant");
        var conv = ConversationService.create(agent, "web", "u");
        var msg = ConversationService.appendAssistantMessage(conv, "Hi back!", null);
        assertEquals(MessageRole.ASSISTANT.value, msg.role);
        assertEquals("Hi back!", msg.content);
        assertNull(msg.usageJson, "no usageJson when not provided");
        assertNull(msg.reasoning, "no reasoning when not provided");
    }

    @Test
    public void appendAssistantMessageThreadsUsageAndReasoning() {
        var agent = newAgent("conv-append-assistant-extras");
        var conv = ConversationService.create(agent, "web", "u");
        var msg = ConversationService.appendAssistantMessage(
                conv, "considered.", "[]", "{\"tokens\":42}", "thinking out loud");
        assertEquals("{\"tokens\":42}", msg.usageJson);
        assertEquals("thinking out loud", msg.reasoning);
    }

    @Test
    public void appendToolResultStoresToolCallIdInToolResults() {
        var agent = newAgent("conv-append-tool");
        var conv = ConversationService.create(agent, "web", "u");
        var msg = ConversationService.appendToolResult(conv, "call-7", "result body");
        assertEquals(MessageRole.TOOL.value, msg.role);
        assertEquals("result body", msg.content);
        assertEquals("call-7", msg.toolResults,
                "appendToolResult routes the call id to the toolResults column");
    }

    // =====================
    // loadRecentMessages — watermarks + ordering
    // =====================

    @Test
    public void loadRecentMessagesReturnsAscendingOrder() throws Exception {
        var agent = newAgent("conv-load-asc");
        var conv = ConversationService.create(agent, "web", "u");
        ConversationService.appendUserMessage(conv, "first");
        Thread.sleep(2);
        ConversationService.appendAssistantMessage(conv, "second", null);
        Thread.sleep(2);
        ConversationService.appendUserMessage(conv, "third");

        var loaded = ConversationService.loadRecentMessages(conv);
        assertEquals(3, loaded.size());
        assertEquals("first", loaded.get(0).content);
        assertEquals("second", loaded.get(1).content);
        assertEquals("third", loaded.get(2).content);
    }

    @Test
    public void loadRecentMessagesHonorsContextSinceWatermark() throws Exception {
        var agent = newAgent("conv-load-context-since");
        var conv = ConversationService.create(agent, "web", "u");
        ConversationService.appendUserMessage(conv, "pre-reset");
        Thread.sleep(5);
        var watermark = Instant.now();
        Thread.sleep(5);
        ConversationService.appendUserMessage(conv, "post-reset");

        conv.contextSince = watermark;
        conv.save();

        var loaded = ConversationService.loadRecentMessages(conv);
        assertEquals(1, loaded.size(),
                "only post-watermark messages must be returned");
        assertEquals("post-reset", loaded.getFirst().content);
    }

    @Test
    public void loadRecentMessagesUsesMaxOfContextSinceAndCompactionSince() throws Exception {
        // The tighter watermark wins: when compactionSince is later than
        // contextSince, only post-compaction messages are returned.
        var agent = newAgent("conv-load-max-watermark");
        var conv = ConversationService.create(agent, "web", "u");
        ConversationService.appendUserMessage(conv, "ancient");
        Thread.sleep(5);
        var earlier = Instant.now();
        Thread.sleep(5);
        ConversationService.appendUserMessage(conv, "middle");
        Thread.sleep(5);
        var later = Instant.now();
        Thread.sleep(5);
        ConversationService.appendUserMessage(conv, "recent");

        conv.contextSince = earlier;
        conv.compactionSince = later;
        conv.save();

        var loaded = ConversationService.loadRecentMessages(conv);
        assertEquals(1, loaded.size(),
                "compactionSince (later) must beat contextSince (earlier)");
        assertEquals("recent", loaded.getFirst().content);
    }

    @Test
    public void loadRecentMessagesReturnsEmptyWhenNoMessages() {
        var agent = newAgent("conv-load-empty");
        var conv = ConversationService.create(agent, "web", "u");
        var loaded = ConversationService.loadRecentMessages(conv);
        assertTrue(loaded.isEmpty());
    }

    // =====================
    // setModelOverride / clearModelOverride
    // =====================

    @Test
    public void setModelOverrideWritesBothColumnsAtomically() {
        var agent = newAgent("conv-override-set");
        var conv = ConversationService.create(agent, "web", "u");

        ConversationService.setModelOverride(conv, "ollama", "llama3.1");

        Conversation refreshed = Conversation.findById(conv.id);
        assertEquals("ollama", refreshed.modelProviderOverride);
        assertEquals("llama3.1", refreshed.modelIdOverride);
    }

    @Test
    public void clearModelOverrideNullsBothColumns() {
        var agent = newAgent("conv-override-clear");
        var conv = ConversationService.create(agent, "web", "u");
        ConversationService.setModelOverride(conv, "ollama", "llama3.1");

        ConversationService.clearModelOverride(conv);

        Conversation refreshed = Conversation.findById(conv.id);
        assertNull(refreshed.modelProviderOverride);
        assertNull(refreshed.modelIdOverride);
    }

    // =====================
    // deleteByIds — bulk cascade
    // =====================

    @Test
    public void deleteByIdsReturnsZeroForEmptyList() {
        assertEquals(0, ConversationService.deleteByIds(List.of()));
    }

    @Test
    public void deleteByIdsReturnsZeroForNullList() {
        assertEquals(0, ConversationService.deleteByIds(null));
    }

    @Test
    public void deleteByIdsRemovesMessagesAndCompactionsAndConversations() {
        var agent = newAgent("conv-delete-cascade");
        var conv = ConversationService.create(agent, "web", "p");
        ConversationService.appendUserMessage(conv, "msg-1");
        ConversationService.appendAssistantMessage(conv, "msg-2", null);

        // Add a session-compaction row directly (no public service helper exists).
        var sc = new SessionCompaction();
        sc.conversation = conv;
        sc.turnCount = 2;
        sc.summaryTokens = 10;
        sc.model = "openrouter/gpt-4.1";
        sc.summary = "test compaction";
        sc.compactedAt = Instant.now();
        sc.save();

        // Pre-conditions
        assertEquals(2L, Message.count("conversation.id = ?1", conv.id));
        assertEquals(1L, SessionCompaction.count("conversation.id = ?1", conv.id));

        var deleted = ConversationService.deleteByIds(List.of(conv.id));
        assertEquals(1, deleted, "must report 1 conversation deleted");

        assertEquals(0L, Message.count("conversation.id = ?1", conv.id),
                "messages must be swept");
        assertEquals(0L, SessionCompaction.count("conversation.id = ?1", conv.id),
                "session_compaction rows must be swept");
        assertEquals(0L, Conversation.count("id = ?1", conv.id),
                "conversation row itself must be deleted");
    }

    @Test
    public void deleteByIdsHandlesMultipleConversations() {
        var agent = newAgent("conv-delete-multi");
        var c1 = ConversationService.create(agent, "web", "p1");
        var c2 = ConversationService.create(agent, "web", "p2");
        var c3 = ConversationService.create(agent, "web", "p3");
        ConversationService.appendUserMessage(c1, "in c1");
        ConversationService.appendUserMessage(c2, "in c2");
        // c3 has no messages

        var deleted = ConversationService.deleteByIds(List.of(c1.id, c2.id, c3.id));
        assertEquals(3, deleted, "all three conversations must be reported deleted");
        assertEquals(0L, Conversation.count("id IN (?1, ?2, ?3)", c1.id, c2.id, c3.id));
    }

    // =====================
    // requestTitleGeneration — gating predicate only
    // =====================

    @Test
    public void requestTitleGenerationReturnsFalseWhenAtMaxGenerations() {
        var agent = newAgent("conv-title-maxed");
        var conv = ConversationService.create(agent, "web", "u");
        ConversationService.appendUserMessage(conv, "would-trigger");
        conv.titleGenerationCount = Conversation.MAX_TITLE_GENERATIONS;
        conv.save();
        assertFalse(ConversationService.requestTitleGeneration(conv),
                "must short-circuit when titleGenerationCount has hit the cap");
    }

    @Test
    public void requestTitleGenerationReturnsFalseWithNoUserMessages() {
        var agent = newAgent("conv-title-no-user");
        var conv = ConversationService.create(agent, "web", "u");
        // Only an assistant message — no user content to summarize.
        ConversationService.appendAssistantMessage(conv, "I have nothing to summarize", null);
        assertFalse(ConversationService.requestTitleGeneration(conv),
                "must return false when there are no user messages");
    }
}
