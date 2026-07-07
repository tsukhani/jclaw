import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import models.SessionCompaction;
import services.AgentService;
import services.AttachmentService;
import services.ConfigService;
import services.ConversationService;

import java.time.Instant;
import java.util.List;

/**
 * Service-layer tests for {@link ConversationService}: CRUD, message-append
 * mechanics, the recent-message loader (with {@code contextSince} +
 * {@code compactionSince} watermark interaction), bulk delete cascade, and
 * the conversation-scoped model-override setters.
 */
class ConversationServiceTest extends UnitTest {

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
    void findOrCreateReturnsExistingForRepeatTuple() {
        var agent = newAgent("conv-find-or-create-1");
        var first = ConversationService.findOrCreate(agent, "web", "user-42");
        var second = ConversationService.findOrCreate(agent, "web", "user-42");
        assertEquals(first.id, second.id,
                "findOrCreate must return the same row for an identical tuple");
    }

    @Test
    void findOrCreateKeysGroupMembersOntoOneSharedConversation() {
        // JCLAW-370: a group's composite peer key is the chat id (sender-
        // independent), so two members posting in the same chat resolve to ONE
        // shared conversation owned by the binding's JClaw peer — not one per
        // member. The runner passes the chat id straight through as peerId.
        var agent = newAgent("conv-group-shared");
        var groupKey = "-100"; // telegramConversationPeerId(owner, "group", "-100", null)
        var fromMemberA = ConversationService.findOrCreate(agent, "telegram", groupKey);
        var fromMemberB = ConversationService.findOrCreate(agent, "telegram", groupKey);
        assertEquals(fromMemberA.id, fromMemberB.id,
                "two members in the same group must share one chat-id-keyed conversation");
    }

    @Test
    void findOrCreateKeysForumTopicsOntoSeparateConversations() {
        // JCLAW-370: the ":topic:<threadId>" suffix scopes each forum topic to
        // its own conversation within the same chat, and a member in one topic
        // shares that topic's conversation with another member in the same topic.
        var agent = newAgent("conv-group-topics");
        var topicOne = "-100:topic:1";
        var topicTwo = "-100:topic:2";
        var inTopicOne = ConversationService.findOrCreate(agent, "telegram", topicOne);
        var inTopicTwo = ConversationService.findOrCreate(agent, "telegram", topicTwo);
        assertNotEquals(inTopicOne.id, inTopicTwo.id,
                "distinct forum topics in the same chat must be separate conversations");

        var alsoTopicOne = ConversationService.findOrCreate(agent, "telegram", topicOne);
        assertEquals(inTopicOne.id, alsoTopicOne.id,
                "a second member in the same topic must share that topic's conversation");
    }

    @Test
    void findOrCreateKeysDmOntoBindingOwner() {
        // JCLAW-370: a DM's composite peer key stays the binding owner id, so DM
        // conversations are byte-for-byte unchanged from the pre-JCLAW-370 path.
        var agent = newAgent("conv-dm-owner");
        var ownerKey = "42"; // telegramConversationPeerId("42", "private", "42", null)
        var first = ConversationService.findOrCreate(agent, "telegram", ownerKey);
        var second = ConversationService.findOrCreate(agent, "telegram", ownerKey);
        assertEquals(first.id, second.id,
                "a DM must remain a single owner-keyed conversation");
    }

    @Test
    void findOrCreateCreatesNewRowWhenTupleAbsent() {
        var agent = newAgent("conv-find-or-create-2");
        var conv = ConversationService.findOrCreate(agent, "web", "first-user");
        assertNotNull(conv.id);
        assertEquals("web", conv.channelType);
        assertEquals("first-user", conv.peerId);
        assertEquals(0, conv.messageCount, "new conversation starts with messageCount=0");
        assertNull(conv.preview, "new conversation has no preview");
    }

    @Test
    void createPersistsChannelAndPeer() {
        var agent = newAgent("conv-create-direct");
        var conv = ConversationService.create(agent, "telegram", "tg-42");
        assertNotNull(conv.id);
        assertEquals("telegram", conv.channelType);
        assertEquals("tg-42", conv.peerId);
        assertEquals(agent.id, conv.agent.id);
    }

    @Test
    void createWithChatTypePersistsIt() {
        // JCLAW-387 B4 follow-up: the 4-arg create stamps chat.type on the row.
        var agent = newAgent("conv-create-chattype");
        var conv = ConversationService.create(agent, "telegram", "tg-99", "supergroup");
        assertEquals("supergroup", conv.chatType);
        Conversation refreshed = Conversation.findById(conv.id);
        assertEquals("supergroup", refreshed.chatType,
                "chat_type must be persisted on the row");
    }

    @Test
    void createWithoutChatTypeLeavesItNull() {
        // The 3-arg create (every non-Telegram caller) leaves chat_type null.
        var agent = newAgent("conv-create-no-chattype");
        var conv = ConversationService.create(agent, "web", "u-1");
        assertNull(conv.chatType, "non-Telegram create must leave chat_type null");
        Conversation refreshed = Conversation.findById(conv.id);
        assertNull(refreshed.chatType);
    }

    @Test
    void createWithNullChatTypeLeavesItNull() {
        // Passing an explicit null chatType must not write anything.
        var agent = newAgent("conv-create-explicit-null");
        var conv = ConversationService.create(agent, "telegram", "tg-7", null);
        assertNull(conv.chatType);
    }

    @Test
    void findOrCreateStampsChatTypeOnlyOnCreation() {
        // First call creates + stamps; a later call returns the existing row and
        // never re-stamps (even with a different chatType).
        var agent = newAgent("conv-foc-chattype");
        var first = ConversationService.findOrCreate(agent, "telegram", "tg-555", "group");
        assertEquals("group", first.chatType);
        var second = ConversationService.findOrCreate(agent, "telegram", "tg-555", "private");
        assertEquals(first.id, second.id, "same tuple must resolve to one row");
        assertEquals("group", second.chatType,
                "chat type is stamped once at creation, never overwritten");
    }

    @Test
    void findByIdReturnsConversationOrNull() {
        var agent = newAgent("conv-find-by-id");
        var conv = ConversationService.create(agent, "web", "p-1");
        assertNotNull(ConversationService.findById(conv.id));
        assertNull(ConversationService.findById(99_999_999L));
    }

    // =====================
    // appendMessage helpers
    // =====================

    @Test
    void appendUserMessageIncrementsCountAndSeedsPreview() {
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
    void appendUserMessagePreviewTruncatesAt100Characters() {
        var agent = newAgent("conv-append-truncate");
        var conv = ConversationService.create(agent, "web", "u");
        var longText = "x".repeat(250);
        ConversationService.appendUserMessage(conv, longText);
        assertEquals(100, conv.preview.length(),
                "preview must be capped at 100 chars");
    }

    @Test
    void appendUserMessageSecondTimeDoesNotOverwritePreview() {
        var agent = newAgent("conv-append-no-overwrite");
        var conv = ConversationService.create(agent, "web", "u");
        ConversationService.appendUserMessage(conv, "first");
        ConversationService.appendUserMessage(conv, "second");
        assertEquals("first", conv.preview,
                "preview is only set when the prior preview is null");
    }

    @Test
    void appendAssistantMessageStoresRoleAndContent() {
        var agent = newAgent("conv-append-assistant");
        var conv = ConversationService.create(agent, "web", "u");
        var msg = ConversationService.appendAssistantMessage(conv, "Hi back!", null);
        assertEquals(MessageRole.ASSISTANT.value, msg.role);
        assertEquals("Hi back!", msg.content);
        assertNull(msg.usageJson, "no usageJson when not provided");
        assertNull(msg.reasoning, "no reasoning when not provided");
    }

    @Test
    void appendAssistantMessageThreadsUsageAndReasoning() {
        var agent = newAgent("conv-append-assistant-extras");
        var conv = ConversationService.create(agent, "web", "u");
        var msg = ConversationService.appendAssistantMessage(
                conv, "considered.", "[]", "{\"tokens\":42}", "thinking out loud");
        assertEquals("{\"tokens\":42}", msg.usageJson);
        assertEquals("thinking out loud", msg.reasoning);
    }

    @Test
    void appendToolResultStoresToolCallIdInToolResults() {
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
    void loadRecentMessagesReturnsAscendingOrder() throws Exception {
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
    void loadRecentMessagesHonorsContextSinceWatermark() throws Exception {
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
    void loadRecentMessagesUsesMaxOfContextSinceAndCompactionSince() throws Exception {
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
    void loadRecentMessagesReturnsEmptyWhenNoMessages() {
        var agent = newAgent("conv-load-empty");
        var conv = ConversationService.create(agent, "web", "u");
        var loaded = ConversationService.loadRecentMessages(conv);
        assertTrue(loaded.isEmpty());
    }

    // =====================
    // effectiveHistoryLimit — per-chat-type history caps (JCLAW-387 B4)
    // =====================

    @Test
    void effectiveHistoryLimitDefaultsToFiftyWhenNothingSet() {
        var agent = newAgent("conv-hist-default");
        var conv = ConversationService.create(agent, "web", "u");
        assertEquals(50, ConversationService.effectiveHistoryLimit(conv),
                "with no keys set, the global default (50) applies");
    }

    @Test
    void effectiveHistoryLimitWebUsesGlobalMax() {
        var agent = newAgent("conv-hist-web-global");
        var conv = ConversationService.create(agent, "web", "u");
        ConfigService.set("chat.maxContextMessages", "30");
        assertEquals(30, ConversationService.effectiveHistoryLimit(conv),
                "non-Telegram channels follow the global cap");
    }

    @Test
    void effectiveHistoryLimitWebIgnoresPerTypeKeys() {
        // Per-type keys must never leak onto a non-Telegram channel.
        var agent = newAgent("conv-hist-web-ignores");
        var conv = ConversationService.create(agent, "web", "u");
        ConfigService.set("chat.maxContextMessages", "40");
        ConfigService.set("groupChat.historyLimit", "5");
        ConfigService.set("dmHistoryLimit", "7");
        assertEquals(40, ConversationService.effectiveHistoryLimit(conv),
                "web conversations stay on the global cap regardless of per-type keys");
    }

    @Test
    void effectiveHistoryLimitTelegramPrivateUsesDmLimit() {
        // A stored chatType="private" resolves to the DM per-type key.
        var agent = newAgent("conv-hist-dm");
        var conv = ConversationService.create(agent, "telegram", "42", "private");
        ConfigService.set("chat.maxContextMessages", "50");
        ConfigService.set("dmHistoryLimit", "8");
        assertEquals(8, ConversationService.effectiveHistoryLimit(conv),
                "a private (DM) conversation must respect dmHistoryLimit");
    }

    @Test
    void effectiveHistoryLimitTelegramPrivateFallsBackToGlobal() {
        // dmHistoryLimit unset → DM falls back to the global value.
        var agent = newAgent("conv-hist-dm-fallback");
        var conv = ConversationService.create(agent, "telegram", "42", "private");
        ConfigService.set("chat.maxContextMessages", "27");
        assertEquals(27, ConversationService.effectiveHistoryLimit(conv),
                "DM falls back to chat.maxContextMessages when dmHistoryLimit is unset");
    }

    @Test
    void effectiveHistoryLimitTelegramStoredGroupUsesGroupLimit() {
        // A stored chatType="group" resolves to the group per-type key even
        // without a forum-topic suffix on the peerId.
        var agent = newAgent("conv-hist-stored-group");
        var conv = ConversationService.create(agent, "telegram", "-100", "group");
        ConfigService.set("chat.maxContextMessages", "50");
        ConfigService.set("groupChat.historyLimit", "6");
        ConfigService.set("dmHistoryLimit", "9");
        assertEquals(6, ConversationService.effectiveHistoryLimit(conv),
                "a stored-group conversation must respect groupChat.historyLimit");
    }

    @Test
    void effectiveHistoryLimitTelegramStoredSupergroupUsesGroupLimit() {
        var agent = newAgent("conv-hist-stored-supergroup");
        var conv = ConversationService.create(agent, "telegram", "-100", "supergroup");
        ConfigService.set("chat.maxContextMessages", "50");
        ConfigService.set("groupChat.historyLimit", "4");
        assertEquals(4, ConversationService.effectiveHistoryLimit(conv),
                "a stored-supergroup conversation must respect groupChat.historyLimit");
    }

    @Test
    void effectiveHistoryLimitStoredChatTypeBeatsPlainPeerFallthrough() {
        // The whole point of the column: a plain (no :topic:) peerId that WOULD
        // fall through to global now resolves correctly when chatType is stored.
        var agentDm = newAgent("conv-hist-stored-dm-vs-plain");
        var dm = ConversationService.create(agentDm, "telegram", "777", "private");
        var agentGrp = newAgent("conv-hist-stored-grp-vs-plain");
        var grp = ConversationService.create(agentGrp, "telegram", "-777", "group");
        ConfigService.set("chat.maxContextMessages", "50");
        ConfigService.set("dmHistoryLimit", "11");
        ConfigService.set("groupChat.historyLimit", "3");
        assertEquals(11, ConversationService.effectiveHistoryLimit(dm),
                "stored private chatType must resolve DM limit despite a plain peerId");
        assertEquals(3, ConversationService.effectiveHistoryLimit(grp),
                "stored group chatType must resolve group limit despite a plain peerId");
    }

    @Test
    void effectiveHistoryLimitTelegramGroupTopicUsesGroupLimit() {
        // A forum-topic peerId carries the ":topic:" suffix — an unambiguous
        // group marker — so groupChat.historyLimit applies.
        var agent = newAgent("conv-hist-group-topic");
        var conv = ConversationService.create(agent, "telegram", "-100:topic:1");
        ConfigService.set("chat.maxContextMessages", "50");
        ConfigService.set("groupChat.historyLimit", "10");
        assertEquals(10, ConversationService.effectiveHistoryLimit(conv),
                "a forum-topic conversation must respect groupChat.historyLimit");
    }

    @Test
    void effectiveHistoryLimitTelegramGroupTopicFallsBackToGlobal() {
        // groupChat.historyLimit unset → falls back to the global value.
        var agent = newAgent("conv-hist-group-fallback");
        var conv = ConversationService.create(agent, "telegram", "-100:topic:1");
        ConfigService.set("chat.maxContextMessages", "25");
        assertEquals(25, ConversationService.effectiveHistoryLimit(conv),
                "group falls back to chat.maxContextMessages when groupChat.historyLimit is unset");
    }

    @Test
    void effectiveHistoryLimitTelegramPlainPeerUsesGlobal() {
        // A plain Telegram peerId (DM or group, indistinguishable on the row)
        // resolves to the global cap rather than guessing chat type.
        var agent = newAgent("conv-hist-plain-tg");
        var conv = ConversationService.create(agent, "telegram", "42");
        ConfigService.set("chat.maxContextMessages", "33");
        ConfigService.set("groupChat.historyLimit", "5");
        ConfigService.set("dmHistoryLimit", "7");
        assertEquals(33, ConversationService.effectiveHistoryLimit(conv),
                "a plain Telegram peerId falls through to the global cap (chat type not derivable)");
    }

    @Test
    void loadRecentMessagesAppliesGroupHistoryLimit() throws Exception {
        // End-to-end through loadRecentMessages: a tight group limit caps the
        // number of returned rows on a forum-topic conversation.
        var agent = newAgent("conv-load-group-cap");
        var conv = ConversationService.create(agent, "telegram", "-100:topic:9");
        ConfigService.set("chat.maxContextMessages", "50");
        ConfigService.set("groupChat.historyLimit", "2");
        ConversationService.appendUserMessage(conv, "one");
        Thread.sleep(2);
        ConversationService.appendUserMessage(conv, "two");
        Thread.sleep(2);
        ConversationService.appendUserMessage(conv, "three");

        var loaded = ConversationService.loadRecentMessages(conv);
        assertEquals(2, loaded.size(),
                "group history limit (2) must cap loadRecentMessages to the 2 most recent");
        assertEquals("two", loaded.get(0).content);
        assertEquals("three", loaded.get(1).content);
    }

    // =====================
    // setModelOverride / clearModelOverride
    // =====================

    @Test
    void setModelOverrideWritesBothColumnsAtomically() {
        var agent = newAgent("conv-override-set");
        var conv = ConversationService.create(agent, "web", "u");

        ConversationService.setModelOverride(conv, "ollama", "llama3.1");

        Conversation refreshed = Conversation.findById(conv.id);
        assertEquals("ollama", refreshed.modelProviderOverride);
        assertEquals("llama3.1", refreshed.modelIdOverride);
    }

    @Test
    void clearModelOverrideNullsBothColumns() {
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
    void deleteByIdsReturnsZeroForEmptyList() {
        assertEquals(0, ConversationService.deleteByIds(List.of()));
    }

    @Test
    void deleteByIdsReturnsZeroForNullList() {
        assertEquals(0, ConversationService.deleteByIds(null));
    }

    @Test
    void deleteByIdsRemovesMessagesAndCompactionsAndConversations() {
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
    void deleteByIdsSweepsAttachmentsBeforeMessages() {
        // Regression: FK2HSKR8CN6X9CEMKJWMA86XIIE from chat_message_attachment.message_id
        // to message.id was violating when a bulk DELETE FROM Message ran while
        // any attachment still pointed at one of the messages being deleted.
        // deleteByIds must sweep attachments first so the FK holds.
        var agent = newAgent("conv-delete-with-attachment");
        var conv = ConversationService.create(agent, "web", "p");
        ConversationService.appendUserMessage(conv, "msg-with-file");

        var msg = Message.find("conversation.id = ?1", conv.id).<Message>first();
        var att = new MessageAttachment();
        att.message = msg;
        att.uuid = "test-uuid-" + conv.id;
        att.originalFilename = "photo.png";
        att.storagePath = "test-agent/attachments/" + conv.id + "/photo.png";
        att.mimeType = "image/png";
        att.sizeBytes = 1024;
        att.kind = MessageAttachment.KIND_IMAGE;
        att.save();

        assertEquals(1L, MessageAttachment.count("message.conversation.id = ?1", conv.id),
                "precondition: attachment must exist before delete");

        var deleted = ConversationService.deleteByIds(List.of(conv.id));
        assertEquals(1, deleted, "must report 1 conversation deleted");

        assertEquals(0L, MessageAttachment.count("message.conversation.id = ?1", conv.id),
                "attachment rows must be swept");
        assertEquals(0L, Message.count("conversation.id = ?1", conv.id),
                "message rows must be swept");
        assertEquals(0L, Conversation.count("id = ?1", conv.id),
                "conversation row must be deleted");
    }

    @Test
    void deleteByIdsRemovesAttachmentFilesFromDisk() throws Exception {
        // JCLAW-209: deleting a conversation must free its on-disk attachment bytes,
        // not just the DB rows — otherwise generated images and uploads are orphaned
        // in the workspace forever.
        var agent = newAgent("conv-delete-files");
        try {
            var conv = ConversationService.create(agent, "web", "p");
            ConversationService.appendUserMessage(conv, "draw me something");
            var msg = Message.find("conversation.id = ?1", conv.id).<Message>first();

            var bytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 5, 6, 7};
            var att = AttachmentService.persistGeneratedImage(
                    agent, msg, bytes, "image/png", "{\"prompt\":\"x\"}");

            // The conversation's attachment directory exists with the bytes in it.
            var dir = AgentService.acquireWorkspacePath(agent.name, "attachments/" + conv.id);
            assertTrue(java.nio.file.Files.isDirectory(dir), "precondition: attachment dir must exist");
            assertArrayEquals(bytes, AttachmentService.readBytes(att), "precondition: bytes on disk");

            ConversationService.deleteByIds(List.of(conv.id));

            // Both the on-disk directory and the rows are gone.
            assertFalse(java.nio.file.Files.exists(dir), "attachment directory must be swept from disk");
            assertEquals(0L, MessageAttachment.count("uuid = ?1", att.uuid), "attachment row must be swept");
            assertEquals(0L, Conversation.count("id = ?1", conv.id), "conversation row must be deleted");
        } finally {
            // Best-effort: remove anything this test planted in the test workspace.
            try {
                var root = AgentService.workspacePath(agent.name);
                if (java.nio.file.Files.exists(root)) {
                    try (var s = java.nio.file.Files.walk(root)) {
                        s.sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception _) {} });
                    }
                }
            } catch (Exception _) { /* best-effort */ }
        }
    }

    @Test
    void deleteByIdsHandlesMultipleConversations() {
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

}
