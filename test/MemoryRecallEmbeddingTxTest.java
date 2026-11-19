import agents.SystemPromptAssembler;
import memory.JpaMemoryStore;
import memory.MemoryStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-960: the recall query's embedding is a blocking HTTP round-trip and must not run
 * with a pooled DB connection checked out.
 *
 * <p>{@code AgentPromptPreparer} deliberately folds the whole prompt prologue into ONE
 * {@code Tx.run} — its javadoc calls it "one round-trip to the connection pool" — and
 * {@code SystemPromptAssembler.assemble} runs inside it, with {@code renderCoreMemories}
 * having already issued {@code Memory.findCore}. So the connection is checked out before
 * recall's embedding call even starts. This is the read-path twin of the defect JCLAW-807
 * removed from the write path, and it violates the store's own written contract.
 *
 * <p>Both arms below run the SAME recall inside the SAME transaction shape. The only
 * difference is whether the embedding was precomputed outside it, so the control arm is
 * what proves the assertion can actually detect the defect.
 */
class MemoryRecallEmbeddingTxTest extends UnitTest {

    private static final String FACT = "The operator keeps the NAS in the basement";
    private static final String QUERY = "where does the NAS live";

    /** Embedder invocations, sampled around each transaction. */
    private final AtomicInteger embedCalls = new AtomicInteger();

    @BeforeEach
    void setup() {
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        embedCalls.set(0);
        JpaMemoryStore.setEmbedderForTest(_ -> {
            embedCalls.incrementAndGet();
            return new float[] {1f, 0f, 0f, 0f};
        });
        MemoryStoreFactory.setForTest(new JpaMemoryStore(true, false));
    }

    @AfterEach
    void teardown() {
        JpaMemoryStore.setEmbedderForTest(null);
        MemoryStoreFactory.reset();
        LuceneTestSync.release();
    }

    private String seedAgentWithMemory() {
        var a = new models.Agent();
        a.name = "recall-tx-" + System.nanoTime();
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        var id = String.valueOf(a.id);
        MemoryStoreFactory.get().store(id, FACT, "fact", 0.6);
        return id;
    }

    @Test
    void aPrecomputedQueryEmbeddingIsNotRegeneratedInsideTheRecallTransaction() {
        var agent = seedAgentWithMemory();

        // Production shape: embed FIRST, with no transaction open...
        var queryEmbedding = MemoryStoreFactory.get().embedQuery(QUERY);
        assertNotNull(queryEmbedding, "precondition: the store must produce a query embedding");

        // ...then search and hydrate inside the transaction.
        embedCalls.set(0);
        var result = Tx.run(() -> SystemPromptAssembler.recall(agent, QUERY, Set.of(), queryEmbedding));

        assertEquals(0, embedCalls.get(),
                "the precomputed embedding must be used as-is — any call here is a blocking "
                        + "HTTP round-trip made with a pooled connection checked out");
        assertFalse(result.selected().isEmpty(), "recall must still return the seeded memory");
    }

    @Test
    void withoutAPrecomputedEmbeddingTheCallHappensInsideTheTransaction() {
        // Control arm. This is the pre-960 behavior, still reachable through the three-arg
        // recall — correct for the operator introspection and eval endpoints, which own no
        // transaction boundary to hoist the call out of. Its value here is proving the
        // assertion above is not vacuous.
        var agent = seedAgentWithMemory();

        embedCalls.set(0);
        var result = Tx.run(() -> SystemPromptAssembler.recall(agent, QUERY, Set.of()));

        assertEquals(1, embedCalls.get(),
                "control: the three-arg form embeds inside the transaction, which is exactly "
                        + "what the precomputed path exists to avoid");
        assertFalse(result.selected().isEmpty(), "recall must still return the seeded memory");
    }

    @Test
    void embedQueryIsANoOpWhenThereIsNothingToEmbed() {
        // Null flows through search() unchanged and degrades to the keyword leg, which is the
        // same result a missing provider would have produced anyway.
        assertNull(MemoryStoreFactory.get().embedQuery(null));
        assertNull(MemoryStoreFactory.get().embedQuery("   "));
        assertEquals(0, embedCalls.get(), "a blank query must not reach the provider");
    }
}
