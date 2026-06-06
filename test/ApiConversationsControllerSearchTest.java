import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import services.AgentService;
import services.ConversationService;
import services.Tx;
import services.search.LuceneIndexer;

import java.util.function.Supplier;

/**
 * JCLAW-328: acceptance coverage for the {@code q:} keyword path on
 * {@code GET /api/conversations}. Sibling of {@link ApiTasksControllerSearchTest}'s
 * Lucene live-trigger pattern, scoped to the
 * {@link LuceneIndexer.Scope#CONVERSATION_MESSAGE} index that JCLAW-304
 * wired through {@link Message}'s {@code @PostPersist} hook.
 *
 * <p>Two cases pinned, mirroring the ticket's AC:
 * <ul>
 *   <li>{@link #searchByQueryFindsContentAcrossMessages} — a keyword in
 *       any message position (not just the preview) surfaces the
 *       conversation in the list, proving the FTS path beats the legacy
 *       LIKE-on-preview behavior.</li>
 *   <li>{@link #searchIntersectsWithChannelFilter} — {@code q=} plus
 *       {@code channel=} together is AND-semantics on the controller's
 *       JpqlFilter, not a substitution.</li>
 * </ul>
 */
class ApiConversationsControllerSearchTest extends FunctionalTest {

    @BeforeEach
    void setup() throws Exception {
        // JCLAW-428: serialize against other Lucene tests and open a clean
        // index at the %test path (data/jclaw-lucene-test). openForTest()
        // replaces the old close + MessageSearch.init + wipeIndex dance.
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
        services.search.MessageSearch.init();
    }

    @AfterEach
    void luceneRelease() {
        LuceneTestSync.release();
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    void searchByQueryFindsContentAcrossMessages() {
        // Seed three conversations: keyword appears in message 1 of A,
        // message 3 of B, and message 5 of C. The legacy LIKE-on-preview
        // path would only find A (since preview = first message). FTS
        // walks every message in the conversation, so all three surface.
        var convIds = commitInFreshTx(() -> {
            var agent = AgentService.create("conv-search-agent", "openrouter", "gpt-4.1");
            var convA = seedConversationWithMessages(agent, "web", "u-a",
                    new String[]{"keywordmorningtoken in turn 1", "filler", "filler"});
            var convB = seedConversationWithMessages(agent, "web", "u-b",
                    new String[]{"filler", "filler", "keywordmorningtoken in turn 3", "filler", "filler"});
            var convC = seedConversationWithMessages(agent, "web", "u-c",
                    new String[]{"filler", "filler", "filler", "filler", "keywordmorningtoken in turn 5"});
            return new long[]{convA, convB, convC};
        });

        var resp = GET("/api/conversations?q=keywordmorningtoken");
        assertIsOk(resp);
        var body = getContent(resp);
        for (var id : convIds) {
            assertTrue(body.contains("\"id\":" + id),
                    "conv id=" + id + " must appear in q-results body: " + body);
        }
    }

    @Test
    void searchIntersectsWithChannelFilter() {
        // Seed two conversations: both contain the keyword in a message,
        // one on web and one on telegram. q + channel=web returns only
        // the web row — proves AND-semantics rather than the q result
        // overriding the channel predicate.
        var convIds = commitInFreshTx(() -> {
            var agent = AgentService.create("conv-search-agent-2", "openrouter", "gpt-4.1");
            var webId = seedConversationWithMessages(agent, "web", "u-web",
                    new String[]{"contains intersecttoken plain prose"});
            var tgId = seedConversationWithMessages(agent, "telegram", "12345",
                    new String[]{"also contains intersecttoken in a different channel"});
            return new long[]{webId, tgId};
        });

        var resp = GET("/api/conversations?q=intersecttoken&channel=web");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + convIds[0]),
                "web conversation must be present: " + body);
        assertFalse(body.contains("\"id\":" + convIds[1]),
                "telegram conversation must be filtered out by channel=web: " + body);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Create a conversation and append N messages to it inside the
     * caller's Tx. Returns the Conversation.id. Each message is a
     * USER-role row so the @PostPersist hook indexes it on the same
     * commit.
     */
    private static long seedConversationWithMessages(Agent agent, String channel,
                                                       String peerId, String[] contents) {
        var conv = ConversationService.create(agent, channel, peerId);
        for (var content : contents) {
            var m = new Message();
            m.conversation = conv;
            m.role = MessageRole.USER.value;
            m.content = content;
            m.save();
        }
        // Bump conv.preview to something distinct from the FTS keyword
        // so the test can prove the FTS path — not the legacy LIKE path
        // — is what surfaces the match. ConversationService.create sets
        // preview from the first message it's given, but here we wrote
        // messages directly so preview stays null/empty by default.
        return conv.id;
    }

    /**
     * Commit on a VT-tx so the @PostUpdate hook's commit is visible to
     * the inline FunctionalTest HTTP handler. The shared FunctionalTest
     * carrier sits inside an ambient tx that doesn't commit until the
     * test returns; without this, the seeded rows are uncommitted when
     * the GET hits the index. Mirrors the existing helper in
     * {@link ApiSubagentRunsControllerTest}.
     */
    private static long[] commitInFreshTx(Supplier<long[]> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<long[]>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try { t.join(); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
