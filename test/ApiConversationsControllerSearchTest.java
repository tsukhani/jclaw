import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.TaskRunMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConversationService;
import services.Tx;
import services.search.LuceneIndexer;
import services.search.MessageSearchRepository;
import services.search.MessageSearchTestHooks;

import java.io.IOException;
import java.util.List;
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

    @Test
    void searchWithQueryMatchingNothingShortCircuitsToZeroRows() {
        // A conversation with messages exists, but none contains the keyword.
        // The CONVERSATION_MESSAGE scope returns empty, so ftsConversationIds
        // yields an empty list and the controller short-circuits to a zero-row
        // response with X-Total-Count=0.
        var convIds = commitInFreshTx(() -> {
            var agent = AgentService.create("conv-nomatch-agent", "openrouter", "gpt-4.1");
            var id = seedConversationWithMessages(agent, "web", "u-nomatch",
                    new String[]{"nothing relevant here", "still nothing"});
            return new long[]{id};
        });

        var resp = GET("/api/conversations?q=zzznosuchtokenzzz");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp),
                "no FTS match must render an empty array: " + getContent(resp));
        assertEquals("0", resp.getHeader("X-Total-Count"),
                "empty FTS result must report total=0");
        assertFalse(getContent(resp).contains("\"id\":" + convIds[0]),
                "the non-matching seeded conversation must be absent from the zero-row response");
    }

    // ── delete-all scope (the q: filter must narrow the delete too) ──────

    @Test
    void deleteAllWithAKeywordDeletesOnlyTheMatchingConversations() {
        var ids = commitInFreshTx(() -> {
            var agent = AgentService.create("conv-qdel-agent", "openrouter", "gpt-4.1");
            var match = seedConversationWithMessages(agent, "web", "u-match",
                    new String[]{"filler", "carries deletetargettoken here"});
            var spared = seedConversationWithMessages(agent, "web", "u-spared",
                    new String[]{"entirely unrelated prose", "still unrelated"});
            return new long[]{match, spared};
        });

        var resp = deleteWithJsonBody("/api/conversations",
                "{\"filter\": {\"q\": \"deletetargettoken\"}}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":1"), getContent(resp));

        assertTrue(commitInFreshTx(() -> Conversation.findById(ids[0]) == null),
                "the keyword-matching conversation is deleted");
        assertTrue(commitInFreshTx(() -> Conversation.findById(ids[1]) != null),
                "a conversation the keyword never matched must survive");
    }

    /**
     * The regression this guards: a {@code q} that resolves to no ids used to
     * reach {@code deleteByFilter} as "no keyword constraint", so a delete the
     * page had counted as zero rows wiped the table instead.
     */
    @Test
    void deleteAllWithAKeywordThatMatchesNothingDeletesNothing() {
        var ids = commitInFreshTx(() -> {
            var agent = AgentService.create("conv-qdel-none-agent", "openrouter", "gpt-4.1");
            var a = seedConversationWithMessages(agent, "web", "u-a", new String[]{"alpha"});
            var b = seedConversationWithMessages(agent, "web", "u-b", new String[]{"beta"});
            return new long[]{a, b};
        });

        var resp = deleteWithJsonBody("/api/conversations",
                "{\"filter\": {\"q\": \"zzznosuchtokenzzz\"}}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":0"), getContent(resp));

        for (var id : ids) {
            assertTrue(commitInFreshTx(() -> Conversation.findById(id) != null),
                    "conv id=" + id + " must survive a keyword that matched nothing");
        }
    }

    /**
     * The listing endpoint degrades open when the index is unreachable — a wider
     * result set is harmless to look at. The delete must not: "no keyword
     * constraint" would destroy every row the other filters match.
     */
    @Test
    void deleteAllRefusesWhenTheSearchIndexIsUnreachable() throws IOException {
        long id = commitInFreshTx(() -> {
            var agent = AgentService.create("conv-qdel-down-agent", "openrouter", "gpt-4.1");
            return seedConversationWithMessages(agent, "web", "u-down",
                    new String[]{"holds outagetoken somewhere"});
        });

        MessageSearchTestHooks.setRepository(new UnreachableSearchRepository());
        try {
            var resp = deleteWithJsonBody("/api/conversations",
                    "{\"filter\": {\"q\": \"outagetoken\"}}");
            assertEquals(503, resp.status.intValue(), getContent(resp));
        } finally {
            // The repository reference is process-global; this class holds the
            // Lucene lock for its lifecycle, but leaving a throwing backend
            // installed would break every later test in the window.
            MessageSearchTestHooks.setRepository(null);
            services.search.MessageSearch.init();
        }

        assertTrue(commitInFreshTx(() -> Conversation.findById(id) != null),
                "nothing is deleted when the delete's scope cannot be resolved");
    }

    /** A search backend that is present but cannot answer. */
    private static final class UnreachableSearchRepository implements MessageSearchRepository {
        @Override
        public void init() {
            // Already "started"; the failure is per-query, not at startup.
        }

        @Override
        public List<TaskRunMessage> search(String query, int limit) throws IOException {
            throw new IOException("index unreachable");
        }

        @Override
        public List<Long> searchIds(LuceneIndexer.Scope scope, String query, int limit) throws IOException {
            throw new IOException("index unreachable");
        }

        @Override
        public String dialectName() {
            return "unreachable";
        }
    }

    /**
     * Play's {@code DELETE} helper overwrites the request body, so a
     * DELETE-with-payload has to go through {@code makeRequest}. Mirrors the
     * copy in {@code ApiConversationsControllerTest}, which carries the full
     * rationale for the reflection on {@code savedCookies}.
     */
    private static play.mvc.Http.Response deleteWithJsonBody(String url, String json) {
        var req = newRequest();
        req.method = "DELETE";
        req.contentType = "application/json";
        req.url = url;
        req.path = url;
        req.querystring = "";
        req.body = new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            var f = play.test.FunctionalTest.class.getDeclaredField("savedCookies");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cookies = (java.util.Map<String, play.mvc.Http.Cookie>) f.get(null);
            if (cookies != null) req.cookies = cookies;
        } catch (Exception _) {
            // Surfaces as a 401 on the assertions above if it ever shifts.
        }
        return makeRequest(req);
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
    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
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
