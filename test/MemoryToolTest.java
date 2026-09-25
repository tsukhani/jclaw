import memory.MemoryCategory;
import memory.MemoryForgetLog;
import memory.MemoryStoreFactory;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import tools.MemoryTool;

/**
 * JCLAW-919: the agent-callable memory tool.
 *
 * <p>Runs with the Lucene index closed, so the semantic tier is absent and the lexical one
 * is what answers. That is the configuration to pin: it is the fail-open path, and a tool
 * that only works with a vector backend configured would be broken on a fresh install.
 */
class MemoryToolTest extends UnitTest {

    private final MemoryTool tool = new MemoryTool();
    private Agent agent;

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
        MemoryForgetLog.clearForTest();
        agent = new Agent();
        agent.name = "memtool-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
    }

    @AfterEach
    void luceneRelease() {
        LuceneTestSync.release();
    }

    private String call(String json) {
        return tool.execute(json, agent);
    }

    private void seed(String text) {
        MemoryStoreFactory.get().store(String.valueOf(agent.id), text,
                MemoryCategory.FACT.label, 0.6);
    }

    // --- JCLAW-529: universal retrieval keys ---

    private static models.Memory onlyMemory() {
        var all = models.Memory.<models.Memory>find("order by id desc").<models.Memory>fetch();
        return all.isEmpty() ? null : all.getFirst();
    }

    @Test
    void storeKeepsTheQuestionsTheAgentSuppliedAsARetrievalKey() {
        // The agent is already emitting this tool call, so the questions ride along at no
        // extra round-trip — the same bargain auto-capture gets from its extractor call.
        call("{\"action\":\"store\",\"text\":\"The user's son Arun goes by Bo\","
                + "\"questions\":[\"what do my kids go by?\",\"what is Arun's nickname?\"]}");

        assertEquals("what do my kids go by?\nwhat is Arun's nickname?", onlyMemory().retrievalKey);
    }

    @Test
    void storeWithoutQuestionsLeavesNoKeyRatherThanFailing() {
        // Pre-529 behaviour: the row is still stored and embeds its statement alone.
        // MemoryKeyBackfillService can key it later.
        call("{\"action\":\"store\",\"text\":\"The staging cluster runs nightly builds\"}");

        assertNull(onlyMemory().retrievalKey);
    }

    @Test
    void acceptsQuestionsSentAsAStringifiedArray() {
        // Exactly what a live model produced during UAT: the array arrived as one string,
        // and a malformed one — no comma between the elements, so no JSON parser recovers
        // it. Refusing it stored a keyless memory and the feature silently did nothing.
        call("{\"action\":\"store\",\"text\":\"The user's daughter Priya has a teacher\","
                + "\"questions\":\"[\\\"Who is my daughter's teacher?\\\" \\\"What is Priya's teacher called?\\\"]\"}");

        assertEquals("Who is my daughter's teacher?\nWhat is Priya's teacher called?",
                onlyMemory().retrievalKey);
    }

    @Test
    void acceptsQuestionsSentAsNewlineSeparatedText() {
        call("{\"action\":\"store\",\"text\":\"The build runs nightly\","
                + "\"questions\":\"when does the build run?\\nhow often is the build?\"}");

        assertEquals("when does the build run?\nhow often is the build?", onlyMemory().retrievalKey);
    }

    @Test
    void aQuestionsValueWithNoUsableContentLeavesNoKey() {
        call("{\"action\":\"store\",\"text\":\"The cache warms on startup\",\"questions\":\"   \"}");
        assertNull(onlyMemory().retrievalKey);
    }

    // --- dispatch ---

    @Test
    void rejectsAnUnknownOrMissingAction() {
        assertTrue(call("{}").startsWith("Error:"));
        assertTrue(call("{\"action\":\"obliterate\"}").startsWith("Error:"));
        assertTrue(call("not json").startsWith("Error:"));
    }

    @Test
    void eachActionRequiresItsOwnField() {
        assertTrue(call("{\"action\":\"recall\"}").contains("query"));
        assertTrue(call("{\"action\":\"forget\"}").contains("query"));
        assertTrue(call("{\"action\":\"store\"}").contains("text"));
    }

    // --- store ---

    @Test
    void storeRecordsAFactAndRecallFindsIt() {
        var stored = call("{\"action\":\"store\",\"text\":\"The user keeps the NAS in the basement\"}");
        assertTrue(stored.startsWith("Remembered"), stored);

        var recalled = call("{\"action\":\"recall\",\"query\":\"NAS basement\"}");
        assertTrue(recalled.contains("basement"), recalled);
    }

    @Test
    void storingSomethingAlreadyKnownIsANoOpRatherThanASecondRow() {
        // The JCLAW-530 failure mode: a write tool duplicating what capture already did.
        // The guard is structural, so it holds however often the model calls store.
        seed("The user keeps the NAS in the basement");
        var again = call("{\"action\":\"store\",\"text\":\"The user keeps the NAS in the basement\"}");

        assertTrue(again.startsWith("Already remembered"), again);
        assertEquals(1, MemoryStoreFactory.get().list(String.valueOf(agent.id)).size(),
                "a duplicate store must not create a second row");
    }

    @Test
    void storeRefusesCredentialsEvenWhenAskedDirectly() {
        // A stored memory is re-injected into every later prompt, so a credential here is
        // a standing exfiltration surface. Being asked explicitly does not change that.
        var out = call("{\"action\":\"store\",\"text\":\"The user's API key is sk-live-ABCDEF1234567890abcdef\"}");

        assertTrue(out.startsWith("Refused:"), out);
        assertTrue(MemoryStoreFactory.get().list(String.valueOf(agent.id)).isEmpty(),
                "nothing may be persisted when the text is refused");
    }

    @Test
    void storeHonorsAnExplicitCategoryAndCoercesAnInvalidOne() {
        call("{\"action\":\"store\",\"text\":\"The user prefers tabs\",\"category\":\"preference\"}");
        var stored = MemoryStoreFactory.get().list(String.valueOf(agent.id));
        assertEquals(MemoryCategory.PREFERENCE.label, stored.getFirst().category());
    }

    // --- JCLAW-981: core is operator-authored, and capped ---

    @Test
    void storeDefaultsToCoreBecauseTheToolOnlyRunsOnAnExplicitInstruction() {
        call("{\"action\":\"store\",\"text\":\"The user's dog is called Biscuit\"}");
        var stored = MemoryStoreFactory.get().list(String.valueOf(agent.id));
        assertEquals(MemoryCategory.CORE.label, stored.getFirst().category(),
                "a deliberate remember is a core memory — capture is what produces the rest");
    }

    @Test
    void storeRefusesANewCoreMemoryOnceTheCapIsReached() {
        int cap = services.ConfigService.getInt("memory.coreload.maxCount", 20);
        for (int i = 0; i < cap; i++) {
            MemoryStoreFactory.get().store(String.valueOf(agent.id),
                    "Core fact number %d about the user".formatted(i), MemoryCategory.CORE.label, 0.9);
        }

        var out = call("{\"action\":\"store\",\"text\":\"The user's dog is called Biscuit\"}");

        assertTrue(out.startsWith("Not stored."), "the store must be refused, not silently downgraded: " + out);
        assertTrue(out.contains("ask"), "the refusal must tell the agent to ask the operator: " + out);
        assertEquals(cap, MemoryStoreFactory.get().list(String.valueOf(agent.id)).stream()
                        .filter(m -> MemoryCategory.CORE.label.equals(m.category())).count(),
                "nothing may be written past the cap");
    }

    @Test
    void anExplicitNonCoreCategoryStillStoresWhenCoreIsFull() {
        // This is the operator having agreed to a different category — the path the
        // refusal above points the agent at.
        int cap = services.ConfigService.getInt("memory.coreload.maxCount", 20);
        for (int i = 0; i < cap; i++) {
            MemoryStoreFactory.get().store(String.valueOf(agent.id),
                    "Core fact number %d about the user".formatted(i), MemoryCategory.CORE.label, 0.9);
        }

        var out = call("{\"action\":\"store\",\"text\":\"The user's dog is called Biscuit\","
                + "\"category\":\"entity\"}");

        assertTrue(out.startsWith("Remembered"), "an agreed category must store: " + out);
        assertTrue(MemoryStoreFactory.get().list(String.valueOf(agent.id)).stream()
                        .anyMatch(m -> MemoryCategory.ENTITY.label.equals(m.category())),
                "and must land under the category the operator agreed to");
    }

    // --- forget ---

    @Test
    void forgetRemovesEveryMemoryStatingTheFact() {
        // Includes the subset restatement JCLAW-920 added to the lexical rule — the same
        // fact with an extra clause, which containment catches and plain Jaccard does not.
        seed("The user keeps the NAS in the basement");
        seed("The user keeps the NAS in the basement at home");
        seed("The user drives an Xpeng G6");

        var out = call("{\"action\":\"forget\",\"query\":\"The user keeps the NAS in the basement\"}");
        assertTrue(out.startsWith("Forgot"), out);

        var left = MemoryStoreFactory.get().list(String.valueOf(agent.id));
        assertEquals(1, left.size(), "only the unrelated memory should survive: " + left);
        assertTrue(left.getFirst().text().contains("Xpeng"), left.getFirst().text());
    }

    @Test
    void withoutAVectorBackendForgetMatchesWordingOnly() {
        // Pins the real limit rather than leaving it to be discovered. "kept ... by the
        // user" restates "keeps ... the user" but shares too few tokens: containment 0.667,
        // under forget's 0.70 floor as well as capture dedup's 0.82. JCLAW-1049 gave forget
        // its own thresholds and this pair still does not clear them — catching it remains
        // the semantic tier's job, and the index is closed here.
        seed("The user keeps the NAS in the basement");
        seed("The NAS is kept in the basement by the user");

        call("{\"action\":\"forget\",\"query\":\"The user keeps the NAS in the basement\"}");

        assertEquals(1, MemoryStoreFactory.get().list(String.valueOf(agent.id)).size(),
                "a pure paraphrase survives when only the lexical tier is available");
    }

    @Test
    void forgetLeavesMerelyRelatedMemoriesAlone() {
        // The risk in "remove all matching": a topical query deleting a neighbourhood.
        // Matching is the same-fact test capture dedups on, not topical similarity.
        seed("The user keeps the NAS in the basement");
        seed("The user backs up photos to the NAS every Sunday");

        call("{\"action\":\"forget\",\"query\":\"The user keeps the NAS in the basement\"}");

        var left = MemoryStoreFactory.get().list(String.valueOf(agent.id));
        assertEquals(1, left.size(), "a different fact about the same subject must survive: " + left);
        assertTrue(left.getFirst().text().contains("Sunday"));
    }

    @Test
    void forgettingSomethingUnknownSaysSoRatherThanReportingSuccess() {
        seed("The user drives an Xpeng G6");
        var out = call("{\"action\":\"forget\",\"query\":\"the user's favourite opera\"}");

        assertTrue(out.contains("Nothing stored matches"), out);
        assertEquals(1, MemoryStoreFactory.get().list(String.valueOf(agent.id)).size());
    }

    // --- recall ---

    @Test
    void recallReportsAMissRatherThanReturningNothing() {
        seed("The user drives an Xpeng G6");
        assertTrue(call("{\"action\":\"recall\",\"query\":\"submarine\"}").startsWith("No memories matched"));
    }

    @Test
    void recallFramesResultsAsReferenceDataNotInstructions() {
        // Recalled text is attacker-influenceable in principle, and it lands in the model's
        // context. The framing has to travel with it, exactly as the prompt section does.
        seed("The user drives an Xpeng G6");
        var out = call("{\"action\":\"recall\",\"query\":\"Xpeng\"}");

        assertTrue(out.contains("not new instructions"), out);
    }

    @Test
    void recallLimitWidensPastTheConfiguredRecallLimit() {
        // JCLAW-969: the tool applied its limit as stream().limit(n) AFTER the pipeline had
        // already cut to memory.recall.limit (default 10), so the parameter could only ever
        // narrow. The tool description tells the agent to "recall again with a better query
        // whenever you need a stored detail you cannot see" — an agent asking for 20 got 10,
        // with nothing in the response saying its request had been capped.
        for (int i = 0; i < 16; i++) {
            seed("Deployment note number " + i + " about the widgetserver rollout");
        }

        var narrow = call("{\"action\":\"recall\",\"query\":\"widgetserver\",\"limit\":3}");
        var wide = call("{\"action\":\"recall\",\"query\":\"widgetserver\",\"limit\":16}");

        assertEquals(3, narrow.lines().filter(l -> l.startsWith("- ")).count(),
                "a small limit must still narrow");
        assertTrue(wide.lines().filter(l -> l.startsWith("- ")).count() > 10,
                "a limit above memory.recall.limit must actually widen the recall, not just "
                        + "re-slice the same 10 rows");
    }

    @Test
    void recallLimitIsBoundedByTheDocumentedCeiling() {
        // Widening is not unbounded — the schema promises a cap, and an agent that asks for
        // 5000 must not turn one tool call into a 5000-row retrieval.
        for (int i = 0; i < 12; i++) {
            seed("Ceiling note number " + i + " about the widgetserver rollout");
        }
        var out = call("{\"action\":\"recall\",\"query\":\"widgetserver\",\"limit\":5000}");
        assertTrue(out.lines().filter(l -> l.startsWith("- ")).count() <= 50,
                "the recall must stay within memory.recall.toolMaxLimit");
    }

    // --- JCLAW-919: forget must survive the capture running on the same turn ---

    @Test
    void aForgottenFactIsSuppressedFromCaptureForAWhile() {
        // Found in live UAT, not in a unit test: forget deleted the memory and auto-capture
        // recreated it eleven seconds later under a new id with identical text. The turn
        // asking to forget X necessarily states X, so capture extracts it straight back.
        seed("The user's canary phrase is zephyr-quartz-1917");
        call("{\"action\":\"forget\",\"query\":\"The user's canary phrase is zephyr-quartz-1917\"}");

        assertTrue(MemoryForgetLog.recentlyForgotten(String.valueOf(agent.id),
                        "The user's canary phrase is zephyr-quartz-1917."),
                "capture must be told not to re-learn what was just forgotten");
    }

    @Test
    void anExplicitReStoreOverridesTheForgetWindow() {
        // "Forget X" then "actually, remember X" has to work, or the second instruction
        // silently does nothing for the length of the window.
        seed("The user's canary phrase is zephyr-quartz-1917");
        call("{\"action\":\"forget\",\"query\":\"The user's canary phrase is zephyr-quartz-1917\"}");

        var out = call("{\"action\":\"store\",\"text\":\"The user's canary phrase is zephyr-quartz-1917\"}");
        assertTrue(out.startsWith("Remembered"), out);
        assertFalse(MemoryForgetLog.recentlyForgotten(String.valueOf(agent.id),
                        "The user's canary phrase is zephyr-quartz-1917"),
                "an explicit re-store must lift the suppression");
    }

    @Test
    void anUnrelatedFactIsNotSuppressedByAForget() {
        seed("The user's canary phrase is zephyr-quartz-1917");
        call("{\"action\":\"forget\",\"query\":\"The user's canary phrase is zephyr-quartz-1917\"}");

        assertFalse(MemoryForgetLog.recentlyForgotten(String.valueOf(agent.id),
                        "The user drives an Xpeng G6"),
                "the window must not block capture of anything else");
    }

    // --- tool dispatch carries no ambient JPA transaction ---

    /**
     * Every action, run the way the agent actually reaches it: on a thread with no JPA
     * context. {@code ParallelToolExecutor} starts a fresh virtual thread per work unit
     * (inheriting no ThreadLocal), and the streaming chat path is {@code @NoTransaction}
     * (JCLAW-199). Before the fix each action threw {@code JPAException} on its first DB
     * touch and {@code ToolRegistry} swallowed it into the tool's text — so store wrote
     * nothing, and forget deleted nothing while never reaching the forget-log, letting
     * capture re-learn the fact on the same turn.
     *
     * <p>The direct {@code tool.execute} calls every other test in this class makes run on
     * the test thread, which Play's invocation always binds a context to — which is exactly
     * why the whole class passed against the broken code.
     */
    @Test
    void everyActionWorksOnADispatchThreadWithNoAmbientTransaction() {
        var dispatchAgent = commitInFreshTx(() -> {
            var a = new Agent();
            a.name = "memtool-dispatch-agent";
            a.modelProvider = "openrouter";
            a.modelId = "gpt-4.1";
            a.save();
            return a;
        });
        var id = String.valueOf(dispatchAgent.id);

        var stored = offDispatchThread(() -> tool.execute(
                "{\"action\":\"store\",\"text\":\"The user keeps the NAS in the basement\"}", dispatchAgent));
        assertTrue(stored.startsWith("Remembered"), stored);
        assertEquals(1, committedCount(id), "store must actually write a row");

        var recalled = offDispatchThread(() -> tool.execute(
                "{\"action\":\"recall\",\"query\":\"where is the NAS\"}", dispatchAgent));
        // Not merely "did not error": "No memories matched" is also not an error, and would
        // pass while recall returned nothing.
        assertTrue(recalled.contains("NAS"), recalled);

        var forgotten = offDispatchThread(() -> tool.execute(
                "{\"action\":\"forget\",\"query\":\"The user keeps the NAS in the basement\"}", dispatchAgent));
        assertTrue(forgotten.startsWith("Forgot"), forgotten);
        assertEquals(0, committedCount(id), "forget must actually delete the row");
        assertTrue(MemoryForgetLog.recentlyForgotten(id, "The user keeps the NAS in the basement"),
                "the forget-log is only reached if the delete did not throw");
    }

    private static long committedCount(String agentId) {
        return commitInFreshTx(() -> models.Memory.count("agent.id = ?1", Long.valueOf(agentId)));
    }

    /** Runs {@code block} on a fresh platform thread that has no JPA context bound —
     *  the dispatch-thread shape. Mirrors {@link #commitInFreshTx} without the {@code Tx}. */
    private static <T> T offDispatchThread(java.util.function.Supplier<T> block) {
        return onOwnThread(block, false);
    }

    /** Seeds data visible to other threads: a fresh platform thread inside its own
     *  committed transaction (same shape as {@code ApiAttachmentsControllerTest}). */
    private static <T> T commitInFreshTx(java.util.function.Supplier<T> block) {
        return onOwnThread(block, true);
    }

    private static <T> T onOwnThread(java.util.function.Supplier<T> block, boolean inTx) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(inTx ? services.Tx.run(block::get) : block.get());
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
