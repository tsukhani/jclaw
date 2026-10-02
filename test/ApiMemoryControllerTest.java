import memory.MemoryStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class ApiMemoryControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        // Seeding memories triggers Memory @PostPersist Lucene indexing; close the
        // index (and hold the lock) so the q LIKE-fallback path is deterministic
        // and we don't clash with the search-mode tests.
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        MemoryStoreFactory.reset();
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(resp);
    }

    // Run a block in a fresh committed Tx on a separate thread so the HTTP handler
    // (its own Tx) sees the committed rows.
    private static <T> T fetchInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
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

    // Memory is partitioned on the immutable agent id (JCLAW-531), and the
    // controller resolves that id back to the agent's name for display + filtering,
    // so a real Agent must exist. Create it once per name (reused across seeds).
    private String seedMemory(String agentName, String text, String category, double importance) {
        return fetchInFreshTx(() -> {
            models.Agent agent = models.Agent.find("name = ?1", agentName).first();
            if (agent == null) {
                agent = services.AgentService.create(agentName, "openrouter", "gpt-4.1");
            }
            return MemoryStoreFactory.get().store(String.valueOf(agent.id), text, category, importance);
        });
    }

    private long agentIdFor(String name) {
        return fetchInFreshTx(() -> {
            models.Agent a = models.Agent.find("name = ?1", name).first();
            return a.id;
        });
    }

    private String recall(long agentId, String query) {
        return getContent(POST("/api/memories/recall", "application/json",
                "{\"agentId\":\"%d\",\"query\":\"%s\"}".formatted(agentId, query)));
    }

    // ─── Recall introspection (JCLAW-937) ────────────────────────────────────

    @Test
    void recallRequiresAuth() {
        assertEquals(401, POST("/api/memories/recall", "application/json",
                "{\"agentId\":\"1\",\"query\":\"anything\"}").status.intValue());
    }

    @Test
    void recallRejectsAMissingQueryOrAgent() {
        login();
        assertEquals(400, POST("/api/memories/recall", "application/json",
                "{\"agentId\":\"1\"}").status.intValue());
        assertEquals(400, POST("/api/memories/recall", "application/json",
                "{\"query\":\"x\"}").status.intValue());
    }

    @Test
    void recallReturns404ForAnUnknownAgent() {
        login();
        assertEquals(404, POST("/api/memories/recall", "application/json",
                "{\"agentId\":\"999999\",\"query\":\"x\"}").status.intValue());
    }

    @Test
    void recallReportsCandidatesTheirScoresAndWhatWasSelected() {
        seedMemory("recall-agent", "The user prefers dark mode in every editor", "preference", 0.7);
        var agent = agentIdFor("recall-agent");
        login();

        var body = recall(agent, "dark mode");

        assertTrue(body.contains("dark mode in every editor"), body);
        assertTrue(body.contains("\"selected\":true"), "the match must be marked selected: " + body);
        // The settings that shaped the result travel with it, so a run is attributable.
        assertTrue(body.contains("\"relevanceWeight\""), body);
        assertTrue(body.contains("\"vectorBackend\""), body);
        assertTrue(body.contains("\"score\""), body);
    }

    @Test
    void recallDoesNotStampLastAccessedAt() {
        // The point of the endpoint is measurement. Stamping the decay anchor would let
        // an eval move the very signal it is measuring, and make a repeated run score
        // differently from the first.
        var id = seedMemory("recall-notouch", "The user prefers dark mode in every editor", "preference", 0.7);
        var agent = agentIdFor("recall-notouch");
        login();
        assertNull(fetchInFreshTx(() ->
                ((models.Memory) models.Memory.findById(Long.parseLong(id))).lastAccessedAt),
                "precondition: never recalled yet");

        recall(agent, "dark mode");

        assertNull(fetchInFreshTx(() ->
                ((models.Memory) models.Memory.findById(Long.parseLong(id))).lastAccessedAt),
                "inspecting recall must not count as an access");
    }

    // ─── Auth gate ───────────────────────────────────────────────────────────

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/memories").status.intValue());
    }

    @Test
    void updateRequiresAuth() {
        var resp = PUT("/api/memories/1", "application/json", "{\"importance\":0.9}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        assertEquals(401, DELETE("/api/memories/1").status.intValue());
    }

    // ─── List + filters ──────────────────────────────────────────────────────

    @Test
    void listsMemoriesAcrossAgentsWithAgentName() {
        seedMemory("alice", "The user prefers dark mode", "preference", 0.7);
        seedMemory("bob", "Operator is the sole admin", "core", 0.9);
        login();

        var body = getContent(GET("/api/memories"));
        assertTrue(body.contains("dark mode"), "alice's memory text present");
        assertTrue(body.contains("Operator is the sole admin"), "bob's memory text present");
        assertTrue(body.contains("alice"), "agent name alice present");
        assertTrue(body.contains("bob"), "agent name bob present");
    }

    @Test
    void filtersByAgent() {
        seedMemory("alice", "alice only fact", "fact", 0.5);
        seedMemory("bob", "bob only fact", "fact", 0.5);
        login();

        var body = getContent(GET("/api/memories?agent=alice"));
        assertTrue(body.contains("alice only fact"), "alice's memory present");
        assertFalse(body.contains("bob only fact"), "bob's memory excluded");
    }

    @Test
    void filtersByCategory() {
        seedMemory("alice", "a core memory", "core", 0.9);
        seedMemory("alice", "a plain fact memory", "fact", 0.5);
        login();

        var body = getContent(GET("/api/memories?category=core"));
        assertTrue(body.contains("a core memory"), "core memory present");
        assertFalse(body.contains("a plain fact memory"), "fact excluded");
    }

    // NB: free-text `q` search is intentionally not asserted here. It routes
    // through MessageSearch.searchIds(MEMORY, ...) (Lucene), whose behavior under
    // the concurrent test runner depends on JVM-global search-backend state that
    // leaks from the dedicated *SearchTest classes — making a deterministic q
    // assertion in this (closed-index) controller test infeasible. The Lucene
    // scope itself is covered by the search-infra tests; q is verified live in
    // the Chrome UAT. The agent / category / importance filters below use plain
    // JPQL and are fully deterministic.

    @Test
    void filtersByImportanceThreshold() {
        seedMemory("alice", "high importance memory", "core", 0.9);
        seedMemory("alice", "boundary importance memory", "fact", 0.8);
        seedMemory("alice", "low importance memory", "fact", 0.4);
        login();

        // ">0.8" is strict: the 0.9 row stays; the 0.8 boundary and 0.4 row drop.
        var q = URLEncoder.encode(">0.8", StandardCharsets.UTF_8);
        var body = getContent(GET("/api/memories?importance=" + q));
        assertTrue(body.contains("high importance memory"), "above-threshold present");
        assertFalse(body.contains("boundary importance memory"), "strict > excludes the boundary value");
        assertFalse(body.contains("low importance memory"), "below-threshold excluded");
    }

    @Test
    void statusFilterSplitsActiveAndSupersededViews() {
        // JCLAW-557: the default view matches recall (active only); the
        // JCLAW-525 supersession trail is opt-in via status=superseded / all.
        var oldId = seedMemory("alice", "The user lives in Berlin", "fact", 0.7);
        var newId = seedMemory("alice", "The user lives in Porto", "fact", 0.7);
        fetchInFreshTx(() -> {
            models.Memory.<models.Memory>findById(Long.valueOf(oldId)).supersede(Long.valueOf(newId));
            return null;
        });
        login();

        var active = getContent(GET("/api/memories"));
        assertTrue(active.contains("Porto"), "active memory in the default view");
        assertFalse(active.contains("Berlin"), "superseded row hidden by default (matches recall)");

        var superseded = getContent(GET("/api/memories?status=superseded"));
        assertTrue(superseded.contains("Berlin"), "superseded view surfaces the trail");
        assertFalse(superseded.contains("Porto"), "active row excluded from the superseded view");
        assertTrue(superseded.contains("\"supersededAt\""), "DTO carries the supersession timestamp");
        assertTrue(superseded.contains("\"supersededById\":\"" + newId + "\""),
                "DTO carries the superseding memory id");

        var all = getContent(GET("/api/memories?status=all"));
        assertTrue(all.contains("Berlin") && all.contains("Porto"), "status=all shows both");
    }

    // ─── Pagination (X-Total-Count) ──────────────────────────────────────────

    @Test
    void listSetsTotalCountHeaderReflectingFullMatchNotThePage() {
        seedMemory("alice", "mem one", "fact", 0.5);
        seedMemory("alice", "mem two", "fact", 0.5);
        seedMemory("bob", "mem three", "core", 0.9);
        login();

        // limit=2 caps the body to a page, but the header must report all 3.
        var resp = GET("/api/memories?limit=2");
        assertIsOk(resp);
        assertEquals("3", resp.getHeader("X-Total-Count"),
                "X-Total-Count reports the full match count, not the page size");
        assertEquals(2, countOccurrences(getContent(resp), "\"agentName\""),
                "body is capped to the requested page size");
    }

    @Test
    void listTotalCountHonorsTheFilter() {
        seedMemory("alice", "alice one", "fact", 0.5);
        seedMemory("alice", "alice two", "fact", 0.5);
        seedMemory("bob", "bob one", "core", 0.9);
        login();

        var resp = GET("/api/memories?agent=alice");
        assertIsOk(resp);
        assertEquals("2", resp.getHeader("X-Total-Count"),
                "count reflects the agent filter, not the whole table");
    }

    @Test
    void listTotalCountIsZeroForUnknownAgent() {
        seedMemory("alice", "alice one", "fact", 0.5);
        login();

        // Unknown agent → the resolve short-circuits to empty; count must be 0
        // (not a bare COUNT that would return the whole table).
        var resp = GET("/api/memories?agent=ghost");
        assertIsOk(resp);
        assertEquals("0", resp.getHeader("X-Total-Count"), "no rows for an unknown agent");
        assertFalse(getContent(resp).contains("alice one"), "no rows leaked");
    }

    // ─── Server-side sort ────────────────────────────────────────────────────

    @Test
    void sortsByImportanceServerSide() {
        seedMemory("alice", "lowimp", "fact", 0.2);
        seedMemory("alice", "highimp", "fact", 0.9);
        seedMemory("alice", "midimp", "fact", 0.5);
        login();

        var asc = getContent(GET("/api/memories?sort=importance&dir=asc"));
        assertTrue(asc.indexOf("lowimp") < asc.indexOf("midimp") && asc.indexOf("midimp") < asc.indexOf("highimp"),
                "ascending by importance is low<mid<high, got: " + asc);

        var desc = getContent(GET("/api/memories?sort=importance&dir=desc"));
        assertTrue(desc.indexOf("highimp") < desc.indexOf("midimp") && desc.indexOf("midimp") < desc.indexOf("lowimp"),
                "descending by importance is high<mid<low, got: " + desc);
    }

    @Test
    void sortsByAgentNameServerSide() {
        seedMemory("zeta", "z-mem", "fact", 0.5);
        seedMemory("alpha", "a-mem", "fact", 0.5);
        login();

        var asc = getContent(GET("/api/memories?sort=agent&dir=asc"));
        assertTrue(asc.indexOf("alpha") < asc.indexOf("zeta"),
                "ascending by agent name puts alpha before zeta, got: " + asc);
    }

    @Test
    void unknownSortColumnFallsBackToDefaultOrderNot400() {
        seedMemory("alice", "only-one", "fact", 0.5);
        login();
        // A bogus sort column must not error — it falls back to the recency default.
        var resp = GET("/api/memories?sort=bogus&dir=sideways");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("only-one"), "row still returned under fallback order");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ─── Update / Delete (by memory id) ──────────────────────────────────────

    @Test
    void updatesImportance() {
        var memId = seedMemory("alice", "Tweak me", "fact", 0.4);
        login();

        var resp = PUT("/api/memories/" + memId, "application/json", "{\"importance\":0.95}");
        assertIsOk(resp);

        var stored = fetchInFreshTx(() ->
                models.Memory.<models.Memory>findById(Long.parseLong(memId)).importance);
        assertEquals(0.95, stored, 1e-9);
    }

    @Test
    void rejectsNonFiniteImportance() {
        // JCLAW-970: the body parser is lenient, so a bare NaN token arrives as a number and
        // passes both range comparisons. The @PreUpdate backstop would silently reset it —
        // the operator has to be told their value was refused, not have it quietly changed.
        var memId = seedMemory("alice", "x", "fact", 0.4);
        login();
        var resp = PUT("/api/memories/" + memId, "application/json", "{\"importance\":NaN}");
        assertEquals(400, resp.status.intValue());
        // Assert the message, not just the status: a bare badRequest() from a FAILED parse is
        // also 400, and would let this test pass without ever reaching the guard under test.
        assertTrue(getContent(resp).contains("importance must be between"),
                "must be rejected by the range guard, not by the body parser: " + getContent(resp));

        var stored = fetchInFreshTx(() ->
                models.Memory.<models.Memory>findById(Long.parseLong(memId)).importance);
        assertEquals(0.4, stored, 1e-9, "a refused edit must leave the stored value untouched");
    }

    @Test
    void rejectsOutOfRangeImportance() {
        var memId = seedMemory("alice", "x", "fact", 0.4);
        login();
        var resp = PUT("/api/memories/" + memId, "application/json", "{\"importance\":1.5}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void rejectsACategoryOutsideTheTaxonomy() {
        // JCLAW-927: capture coerces an invented label because the alternative is losing
        // the memory, but an operator typing a category deliberately must be told, not
        // silently given something else behind a 200.
        var memId = seedMemory("alice", "Tweak me", "fact", 0.4);
        login();

        var resp = PUT("/api/memories/" + memId, "application/json", "{\"category\":\"opinion\"}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("category"), "the error should name the field: " + getContent(resp));

        var stored = fetchInFreshTx(() ->
                models.Memory.<models.Memory>findById(Long.parseLong(memId)).category);
        assertEquals("fact", stored, "a rejected update must not have been applied");
    }

    @Test
    void acceptsEveryCanonicalCategory() {
        // Guards against the validation being over-tight — a rule that rejects the six it
        // is meant to allow would make the category field unusable.
        var memId = seedMemory("alice", "Tweak me", "fact", 0.4);
        login();
        for (var label : memory.MemoryCategory.labels()) {
            var resp = PUT("/api/memories/" + memId, "application/json",
                    "{\"category\":\"" + label + "\"}");
            assertIsOk(resp);
        }
    }

    @Test
    void unknownMemoryUpdateIs404() {
        login();
        var resp = PUT("/api/memories/999999", "application/json", "{\"importance\":0.9}");
        assertEquals(404, resp.status.intValue());
    }

    // ─── Provenance (JCLAW-1318) ─────────────────────────────────────────────

    private static com.google.gson.JsonObject provenanceOf(String listBody, String memId) {
        for (var e : com.google.gson.JsonParser.parseString(listBody).getAsJsonArray()) {
            var o = e.getAsJsonObject();
            if (memId.equals(o.get("id").getAsString())) return o.getAsJsonObject("provenance");
        }
        throw new AssertionError("memory " + memId + " not listed: " + listBody);
    }

    private long verificationCount(String memId, models.MemoryVerification.Kind kind) {
        return fetchInFreshTx(() -> models.MemoryVerification.count(
                "memory.id = ?1 AND kind = ?2", Long.parseLong(memId), kind));
    }

    @Test
    void verifyRecordsAnOperatorConfirmationAndReturnsTheReviewedDto() {
        var memId = seedMemory("alice", "Confirm me", "fact", 0.5);
        login();

        var resp = POST("/api/memories/" + memId + "/verify", "application/json", "{}");
        assertIsOk(resp);
        var prov = com.google.gson.JsonParser.parseString(getContent(resp))
                .getAsJsonObject().getAsJsonObject("provenance");
        assertEquals("HUMAN_REVIEWED", prov.get("trustTier").getAsString(), prov.toString());
        assertEquals("FIRM", prov.get("graphTier").getAsString(), prov.toString());
        var v = prov.getAsJsonArray("verifications").get(0).getAsJsonObject();
        assertEquals("human:operator", v.get("actor").getAsString());
        assertEquals("CONFIRMED", v.get("kind").getAsString());
        assertEquals(1, verificationCount(memId, models.MemoryVerification.Kind.CONFIRMED));
    }

    @Test
    void verifyOfAnUnknownMemoryIs404() {
        login();
        assertEquals(404, POST("/api/memories/999999/verify", "application/json", "{}").status.intValue());
    }

    @Test
    void aValidEditRecordsAnEditedEventAndAnInvalidOneRecordsNone() {
        var memId = seedMemory("alice", "Tweak me", "fact", 0.4);
        login();

        assertEquals(400, PUT("/api/memories/" + memId, "application/json", "{\"importance\":1.5}")
                .status.intValue());
        assertEquals(0, verificationCount(memId, models.MemoryVerification.Kind.EDITED));

        assertIsOk(PUT("/api/memories/" + memId, "application/json", "{\"importance\":0.9}"));
        assertEquals(1, verificationCount(memId, models.MemoryVerification.Kind.EDITED));
    }

    @Test
    void aLegacyRowListsAsUnattributedUnverifiedAndTentative() {
        var memId = seedMemory("alice", "Written before provenance", "fact", 0.5);
        login();

        var prov = provenanceOf(getContent(GET("/api/memories")), memId);
        assertEquals("UNATTRIBUTED", prov.get("authorType").getAsString(), prov.toString());
        assertTrue(prov.get("actor") == null || prov.get("actor").isJsonNull(), prov.toString());
        assertTrue(prov.get("sourceConversationId") == null || prov.get("sourceConversationId").isJsonNull(),
                prov.toString());
        assertEquals("UNVERIFIED", prov.get("trustTier").getAsString());
        assertEquals("TENTATIVE", prov.get("graphTier").getAsString());
        assertEquals(0, prov.get("corroborationCount").getAsInt());
    }

    @Test
    void aDerivedMemoryListsItsInputsTurnRefs() {
        var ids = fetchInFreshTx(() -> {
            var agent = services.AgentService.create("deriver", "openrouter", "gpt-4.1");
            var aid = String.valueOf(agent.id);
            var store = MemoryStoreFactory.get();
            var a = store.storeDeferred(aid, "Input A", "fact", 0.5, null,
                    memory.MemoryProvenance.extractor("m1").withSource(11L, 111L));
            var b = store.storeDeferred(aid, "Input B", "fact", 0.5, null,
                    memory.MemoryProvenance.extractor("m1").withSource(22L, 222L));
            var d = store.storeDeferred(aid, "Derived from A and B", "fact", 0.5, null,
                    new memory.MemoryProvenance(null, null, memory.MemoryProvenance.process("consolidation"),
                            models.MemoryAuthorType.CONSOLIDATION_DERIVED,
                            java.util.List.of(Long.parseLong(a), Long.parseLong(b))));
            return java.util.List.of(a, b, d);
        });
        login();

        var prov = provenanceOf(getContent(GET("/api/memories")), ids.get(2));
        assertTrue(prov.get("derived").getAsBoolean(), prov.toString());
        assertEquals("CONSOLIDATION_DERIVED", prov.get("authorType").getAsString());
        assertEquals("process:consolidation", prov.get("actor").getAsString());
        var links = prov.getAsJsonArray("derivations");
        assertEquals(2, links.size(), prov.toString());
        var turns = new java.util.HashSet<String>();
        for (var l : links) {
            var o = l.getAsJsonObject();
            turns.add(o.get("inputConversationId").getAsLong() + "/" + o.get("inputMessageId").getAsLong());
        }
        assertEquals(java.util.Set.of("11/111", "22/222"), turns);

        var source = provenanceOf(getContent(GET("/api/memories")), ids.get(0));
        assertEquals(11L, source.get("sourceConversationId").getAsLong());
        assertEquals(111L, source.get("sourceMessageId").getAsLong());
        assertEquals("extractor/m1", source.get("actor").getAsString());
        assertEquals("HUMAN_TURN", source.get("authorType").getAsString());
    }

    @Test
    void recallCandidatesCarryTheSameProvenanceAsTheList() {
        var memId = seedMemory("recall-prov", "The user prefers dark mode in every editor", "preference", 0.7);
        var agent = agentIdFor("recall-prov");
        login();
        assertIsOk(POST("/api/memories/" + memId + "/verify", "application/json", "{}"));

        var listed = provenanceOf(getContent(GET("/api/memories")), memId);
        var recalled = com.google.gson.JsonParser.parseString(recall(agent, "dark mode")).getAsJsonObject();
        com.google.gson.JsonObject candidate = null;
        for (var c : recalled.getAsJsonArray("candidates")) {
            if (c.getAsJsonObject().get("id").getAsLong() == Long.parseLong(memId)) candidate = c.getAsJsonObject();
        }
        assertNotNull(candidate, recalled.toString());
        assertEquals(listed, candidate.getAsJsonObject("provenance"));
    }

    @Test
    void deletesMemory() {
        var memId = seedMemory("alice", "Delete me", "fact", 0.5);
        login();

        var resp = DELETE("/api/memories/" + memId);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""), "canonical ack: " + getContent(resp));
        var remaining = fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(memId)));
        assertNull(remaining);
    }

    // ─── Bulk delete (Memories-page Delete / Delete all) ─────────────────────

    @Test
    void bulkDeletesByIds() {
        var keep = seedMemory("alice", "keep me", "fact", 0.5);
        var goOne = seedMemory("alice", "bulk one", "fact", 0.5);
        var goTwo = seedMemory("bob", "bulk two", "core", 0.9);
        login();

        var resp = deleteWithJsonBody("/api/memories",
                "{\"ids\": [" + goOne + ", " + goTwo + "]}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":2"));
        assertNull(fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(goOne))));
        assertNull(fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(goTwo))));
        assertNotNull(fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(keep))));
    }

    @Test
    void bulkDeletesByFilterRespectingPredicates() {
        seedMemory("alice", "alice fact", "fact", 0.5);
        seedMemory("bob", "bob core", "core", 0.9);
        login();

        var resp = deleteWithJsonBody("/api/memories",
                "{\"filter\": {\"agent\": \"alice\"}}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":1"));
        var left = fetchInFreshTx(() -> models.Memory.count());
        assertEquals(1L, left.longValue());
    }

    /**
     * Mirrors the live store shape (JCLAW-1013): both agents carry a supersession
     * trail, so an agent-filtered delete has to leave the other agent's active
     * *and* superseded rows alone.
     */
    @Test
    void bulkDeleteByAgentLeavesOtherAgentsIntact() {
        var keepOld = seedMemory("main", "main old", "fact", 0.5);
        var keepNew = seedMemory("main", "main new", "fact", 0.5);
        fetchInFreshTx(() -> {
            models.Memory.<models.Memory>findById(Long.valueOf(keepOld)).supersede(Long.valueOf(keepNew));
            return null;
        });
        var goOld = seedMemory("__loadtest__", "loadtest old", "fact", 0.5);
        var goNew = seedMemory("__loadtest__", "loadtest new", "fact", 0.5);
        fetchInFreshTx(() -> {
            models.Memory.<models.Memory>findById(Long.valueOf(goOld)).supersede(Long.valueOf(goNew));
            return null;
        });
        login();

        var resp = deleteWithJsonBody("/api/memories",
                "{\"filter\": {\"agent\": \"__loadtest__\"}}");
        assertIsOk(resp);

        assertNotNull(fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(keepOld))),
                "other agent's superseded row must survive an agent-filtered delete");
        assertNotNull(fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(keepNew))),
                "other agent's active row must survive an agent-filtered delete");
        assertNull(fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(goNew))),
                "the filtered agent's active row is deleted");
    }

    @Test
    void bulkDeleteEmptyBodyIs400() {
        login();
        var resp = deleteWithJsonBody("/api/memories", "{}");
        assertEquals(400, resp.status.intValue());
    }

    /**
     * A failing free-text search must narrow to nothing, never to everything.
     *
     * <p>{@code resolveFtsIds} encodes "no id constraint" as {@code Optional.empty()} and
     * "ran, matched nothing" as present-but-empty. Returning the former on {@code IOException}
     * dropped the caller's {@code q} from the WHERE clause entirely, so this bulk delete
     * paged over the whole corpus and removed every row the other predicates allowed.
     */
    @Test
    void aFailedFreeTextSearchDeletesNothingRatherThanEverything() {
        seedMemory("alice", "alice fact", "fact", 0.5);
        seedMemory("bob", "bob core", "core", 0.9);
        seedMemory("carol", "carol lesson", "lesson", 0.6);
        login();

        services.search.MessageSearchTestHooks.setRepository(new FailingSearchRepository());
        try {
            var resp = deleteWithJsonBody("/api/memories", "{\"filter\": {\"q\": \"anything\"}}");
            assertIsOk(resp);
            assertTrue(getContent(resp).contains("\"deleted\":0"), getContent(resp));
            assertEquals(3L, fetchInFreshTx(() -> models.Memory.count()).longValue(),
                    "a search failure must not be read as an empty filter");

            var listed = GET("/api/memories?q=anything");
            assertIsOk(listed);
            assertFalse(getContent(listed).contains("alice fact"),
                    "the list path must not render the whole corpus as search results");
        } finally {
            services.search.MessageSearchTestHooks.setRepository(null);
        }
    }

    // ─── Eval generation / scoring (JCLAW-529) ───────────────────────────────

    @Test
    void evalEndpointsRequireAuth() {
        assertEquals(401, POST("/api/memories/evals/generate", "application/json", "{}").status.intValue());
        assertEquals(401, POST("/api/memories/evals/run", "application/json", "{}").status.intValue());
        assertEquals(401, GET("/api/memories/reembed").status.intValue());
        assertEquals(401, POST("/api/memories/reembed", "application/json", "{}").status.intValue());
    }

    @Test
    void evalGenerateWithoutAnAgentIdIsRejected() {
        login();
        var resp = POST("/api/memories/evals/generate", "application/json", "{}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("agentId"), getContent(resp));
    }

    @Test
    void evalGenerateForAnUnknownAgentIs404() {
        login();
        var resp = POST("/api/memories/evals/generate", "application/json", "{\"agentId\":\"99999\"}");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void aDryRunReportsClusterSizesWithoutGeneratingAnyQuestion() {
        // The threshold is the one number in coverage generation that has to be measured
        // rather than picked, and sweeping it through the full generator would spend a model
        // call per surviving cluster per sweep point. dryRun is that sweep, so it must not
        // need a provider at all.
        seedMemory("alice", "Shared subject sailing concerns the mainsail rigging", "fact", 0.5);
        seedMemory("alice", "Shared subject sailing concerns the keel ballast", "fact", 0.5);
        login();
        var agentId = fetchInFreshTx(() -> models.Agent.find("name = ?1", "alice").<models.Agent>first().id);

        var resp = POST("/api/memories/evals/generate", "application/json",
                "{\"agentId\":\"" + agentId + "\",\"dryRun\":true,\"clusterBy\":\"lexical\",\"clusterThreshold\":0.3}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("lexical"), body);
        assertTrue(body.contains("0.3"), body);
    }

    @Test
    void evalGenerateIs409WhenTheAgentHasNoUsableProvider() {
        // A refusal rather than a later call failure: without a provider there is nothing
        // to write the questions with, and a half-written suite is worse than none.
        seedMemory("alice", "Some fact about kayaking rivers", "fact", 0.5);
        login();
        var agentId = fetchInFreshTx(() -> {
            var a = models.Agent.find("name = ?1", "alice").<models.Agent>first();
            a.modelProvider = "no-such-provider";
            a.save();
            return a.id;
        });

        var resp = POST("/api/memories/evals/generate", "application/json",
                "{\"agentId\":\"" + agentId + "\"}");
        assertEquals(409, resp.status.intValue());
        assertTrue(getContent(resp).contains("provider"), getContent(resp));
    }

    @Test
    void evalRunForAMissingSuiteSaysGenerateOneFirst() {
        seedMemory("alice", "Some fact about welding steel", "fact", 0.5);
        login();
        var agentId = fetchInFreshTx(() -> models.Agent.find("name = ?1", "alice").<models.Agent>first().id);

        var resp = POST("/api/memories/evals/run", "application/json",
                "{\"agentId\":\"" + agentId + "\",\"suiteId\":\"no-such-suite\"}");
        assertEquals(404, resp.status.intValue());
        assertTrue(getContent(resp).contains("generate"), getContent(resp));
    }

    @Test
    void evalRunScoresAGeneratedSuiteAgainstLiveRecall() throws Exception {
        // The point of the harness: the score has to come from the same recall pipeline the
        // system prompt uses, not a reimplementation, or it measures the harness instead.
        var memId = seedMemory("alice", "The user keeps the NAS in the basement", "fact", 0.7);
        login();
        var agentId = fetchInFreshTx(() -> models.Agent.find("name = ?1", "alice").<models.Agent>first().id);

        services.evals.MemoryEvalPaths.ensureLocalDir();
        var suiteJson = ("{\"id\":\"uatsuite\",\"description\":\"d\",\"corpusFingerprint\":\"1:0\","
                // The query must be a SUBSTRING of the memory: this class runs with the index
                // closed, so recall degrades to Memory.likeFallback, which matches the whole
                // query as one substring. "basement NAS" retrieved nothing and the original
                // assertion (contains("mrr")) passed anyway.
                + "\"cases\":[{\"id\":\"c1\",\"query\":\"NAS\",\"goldGroups\":[[" + memId + "]]}]}");
        java.nio.file.Files.writeString(services.evals.MemoryEvalPaths.suiteFile("uatsuite"), suiteJson);

        var resp = POST("/api/memories/evals/run", "application/json",
                "{\"agentId\":\"" + agentId + "\",\"suiteId\":\"uatsuite\",\"scope\":\"candidates\"}");
        assertIsOk(resp);
        var body = getContent(resp);
        // Assert the SCORE, not the field names. "mrr" and "c1" are both structural — the
        // Report record always emits mrr, and c1 appears in missed[] on a total failure — so
        // a stubbed-out recall returning List.of() would satisfy a contains() on either.
        assertTrue(body.contains("\"mrr\":1.0"), "the seeded memory must be retrieved at rank 1: " + body);
        assertTrue(body.contains("\"missed\":[]"), "nothing should be missed: " + body);
    }

    // ─── Re-embed (JCLAW-933) ────────────────────────────────────────────────

    @Test
    void reembedStatusIsSafeToPollBeforeAnyRebuildHasRun() {
        login();
        var resp = GET("/api/memories/reembed");
        assertIsOk(resp);
        assertTrue(getContent(resp).trim().startsWith("{"), getContent(resp));
    }

    @Test
    void reembedRefusesToStartWhenItCouldNotFinish() {
        // The rebuild wipes the index before writing, so starting it with no usable vector
        // backend would leave nothing behind — a 409 refusal, not a warning.
        login();
        var resp = POST("/api/memories/reembed", "application/json", "{}");
        assertEquals(409, resp.status.intValue());
        assertFalse(getContent(resp).isBlank(), "a refusal has to say why");
    }

    /** Stands in for a Lucene backend that is present but erroring — the only way to reach
     *  the IOException arm, since a closed index reports dialect "none" and takes the LIKE
     *  path instead. Safe to install: the class holds the LuceneTestSync lock throughout. */
    private static final class FailingSearchRepository implements services.search.MessageSearchRepository {
        @Override
        public java.util.List<Long> searchIds(services.search.LuceneIndexer.Scope scope, String query, int limit)
                throws java.io.IOException {
            throw new java.io.IOException("index unavailable");
        }

        @Override
        public java.util.List<models.TaskRunMessage> search(String query, int limit) throws java.io.IOException {
            throw new java.io.IOException("index unavailable");
        }

        @Override
        public void init() {
            // Already "initialized" — this stub exists only to fail searches.
        }

        @Override
        public String dialectName() {
            return "h2";
        }
    }

    /** DELETE-with-body helper — same makeRequest workaround as
     *  ApiConversationsControllerTest (Play's DELETE helper drops the body). */
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
            // fall through — an unauthenticated DELETE surfaces as 401
        }
        return makeRequest(req);
    }
}
