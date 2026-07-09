import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConversationService;
import services.Tx;
import services.search.DirectLuceneMessageSearchRepository;
import services.search.LuceneIndexer;

import java.util.List;

/**
 * JCLAW-135 Part C: deleting a conversation must also evict its messages'
 * CONVERSATION_MESSAGE full-text docs. The Conversation delete cascades the
 * message rows at the DB level (ON DELETE CASCADE), but a cascade / bulk delete
 * never fires {@code Message.@PostRemove}, so {@code deleteByIds} evicts the
 * docs explicitly. This test drives the JPA round-trip: the @PostPersist hook
 * indexes each message, then {@code deleteByIds} must drop them from the index.
 *
 * <p>Uses {@code LuceneTestSync} to serialize against the JVM-global index and
 * distinctive concatenated tokens (immune to StandardAnalyzer word-boundary
 * splitting) so the assertions can't be muddied by another scope's docs.
 */
class ConversationServiceIndexCleanupTest extends UnitTest {

    private DirectLuceneMessageSearchRepository repo;

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        repo = new DirectLuceneMessageSearchRepository();
    }

    @AfterEach
    void teardown() {
        LuceneTestSync.release();
    }

    /** Run {@code block} in a committed transaction on a fresh VT so the JPA
     *  lifecycle hooks fire and their Lucene writes are visible to the next
     *  {@code maybeRefresh} — see DirectLuceneMessageSearchRepositoryTest. */
    private static long commitInFreshTx(java.util.function.Supplier<Long> block) {
        var ref = new java.util.concurrent.atomic.AtomicLong(0);
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    @Test
    void deleteByIdsEvictsConversationMessageDocs() throws Exception {
        var token = "convmsgcleanuptoken";
        var ids = new long[2];
        long convoId = commitInFreshTx(() -> {
            var a = new Agent();
            a.name = "idx-cleanup-agent-" + System.nanoTime();
            a.modelProvider = "test-provider";
            a.modelId = "test-model";
            a.enabled = true;
            a.save();

            var convo = ConversationService.create(a, "web", "u-" + System.nanoTime());

            var m1 = new Message();
            m1.conversation = convo;
            m1.role = MessageRole.USER.value;
            m1.content = "first " + token;
            m1.save();
            ids[0] = m1.id;

            var m2 = new Message();
            m2.conversation = convo;
            m2.role = MessageRole.ASSISTANT.value;
            m2.content = "second " + token;
            m2.save();
            ids[1] = m2.id;

            return convo.id;
        });

        // Both messages are indexed by their @PostPersist hook.
        var before = repo.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE, token, 10);
        assertEquals(2, before.size(), "both messages must be indexed before delete");

        // Delete the conversation via the service in a committed tx.
        commitInFreshTx(() -> (long) ConversationService.deleteByIds(List.of(convoId)));

        var after = repo.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE, token, 10);
        assertTrue(after.isEmpty(),
                "deleting the conversation must evict its messages' CONVERSATION_MESSAGE docs");
    }
}
