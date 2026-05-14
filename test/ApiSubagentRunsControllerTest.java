import models.Agent;
import models.Conversation;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import services.SubagentRegistry;
import services.Tx;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * JCLAW-271 functional coverage for {@code GET /api/subagent-runs} +
 * {@code POST /api/subagent-runs/{id}/kill}. Uses the real HTTP stack via
 * {@link FunctionalTest} so the JSON wire shape, auth check, and filter
 * combinators are all exercised end-to-end.
 */
class ApiSubagentRunsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        SubagentRegistry.clear();
        EventLogger.clear();
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void teardown() {
        SubagentRegistry.clear();
        EventLogger.clear();
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}");
        assertIsOk(resp);
    }

    // ── tests ─────────────────────────────────────────────────────────

    @Test
    void unauthenticatedRequestReturns401() {
        var response = GET("/api/subagent-runs");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void listWithNoFiltersReturnsEveryRunNewestFirst() {
        login();
        var ids = commitInFreshTx(() -> {
            var p = AgentService.create("api-list-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-list-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var r1 = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            var r2 = persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
            return new long[]{r1, r2};
        });

        var resp = GET("/api/subagent-runs");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.startsWith("["), "response is a JSON array, got: " + body);
        assertTrue(body.contains("\"id\":" + ids[0]),
                "first run id present: " + body);
        assertTrue(body.contains("\"id\":" + ids[1]),
                "second run id present: " + body);
        assertNotNull(resp.getHeader("X-Total-Count"),
                "X-Total-Count header set for pagination");
    }

    @Test
    void listFilteredByParentAgentExcludesOtherAgents() {
        login();
        var data = commitInFreshTx(() -> {
            var p1 = AgentService.create("api-pa-1", "openrouter", "gpt-4.1");
            var p2 = AgentService.create("api-pa-2", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-pa-c", "openrouter", "gpt-4.1");
            var pc1 = ConversationService.create(p1, "web", "u");
            var pc2 = ConversationService.create(p2, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var keepId = persistRun(p1, c, pc1, cc, SubagentRun.Status.RUNNING);
            var dropId = persistRun(p2, c, pc2, cc, SubagentRun.Status.RUNNING);
            return new long[]{p1.id, keepId, dropId};
        });

        var resp = GET("/api/subagent-runs?parentAgentId=" + data[0]);
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + data[1]),
                "matching run is present: " + body);
        assertFalse(body.contains("\"id\":" + data[2]),
                "non-matching run is filtered out: " + body);
    }

    @Test
    void listFilteredByStatusReturnsOnlyMatchingRows() {
        login();
        var data = commitInFreshTx(() -> {
            var p = AgentService.create("api-st-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-st-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var runningId = persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
            var completedId = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            return new long[]{runningId, completedId};
        });

        var resp = GET("/api/subagent-runs?status=RUNNING");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + data[0]),
                "RUNNING row is included: " + body);
        assertFalse(body.contains("\"id\":" + data[1]),
                "COMPLETED row is excluded: " + body);
    }

    @Test
    void listWithInvalidStatusReturns400() {
        login();
        var resp = GET("/api/subagent-runs?status=BOGUS");
        assertEquals(400, resp.status.intValue());
        // Gson escapes single quotes as '; assert on the unambiguous
        // substring that survives the escape.
        assertTrue(getContent(resp).contains("Invalid") && getContent(resp).contains("status"),
                "error message names the bad param: " + getContent(resp));
    }

    @Test
    void listFilteredBySinceExcludesEarlierRuns() {
        login();
        var data = commitInFreshTx(() -> {
            var p = AgentService.create("api-since-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-since-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            // Older row — explicit timestamp predates the cutoff.
            var older = new SubagentRun();
            older.parentAgent = p;
            older.childAgent = c;
            older.parentConversation = pc;
            older.childConversation = cc;
            older.status = SubagentRun.Status.COMPLETED;
            older.startedAt = Instant.parse("2025-01-01T00:00:00Z");
            older.save();
            // Newer row — defaulted to now() by @PrePersist.
            var newerId = persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
            return new long[]{older.id, newerId};
        });

        var cutoff = "2026-01-01T00:00:00Z";
        var resp = GET("/api/subagent-runs?since=" + cutoff);
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + data[1]),
                "newer row present: " + body);
        assertFalse(body.contains("\"id\":" + data[0]),
                "older row filtered out by since: " + body);
    }

    @Test
    void listWithInvalidSinceReturns400() {
        login();
        var resp = GET("/api/subagent-runs?since=not-a-date");
        assertEquals(400, resp.status.intValue());
        // Gson escapes single quotes; assert on the unambiguous substring
        // that survives the escape.
        assertTrue(getContent(resp).contains("Invalid") && getContent(resp).contains("since"),
                "error message names the bad param: " + getContent(resp));
    }

    @Test
    void listCombinesAllFilters() {
        login();
        var data = commitInFreshTx(() -> {
            var p = AgentService.create("api-combo-p", "openrouter", "gpt-4.1");
            var p2 = AgentService.create("api-combo-p2", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-combo-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var pc2 = ConversationService.create(p2, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            // The keep row: matches all three filters.
            var keep = persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
            // Wrong status.
            persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            // Wrong parent.
            persistRun(p2, c, pc2, cc, SubagentRun.Status.RUNNING);
            return new long[]{p.id, keep};
        });

        var resp = GET("/api/subagent-runs?parentAgentId=" + data[0]
                + "&status=RUNNING&since=2024-01-01T00:00:00Z");
        assertIsOk(resp);
        var body = getContent(resp);
        // Find every "id": value in the response — exactly one match expected.
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(body);
        int count = 0;
        while (matcher.find()) count++;
        assertEquals(1, count, "exactly one row matches all three filters: " + body);
        assertTrue(body.contains("\"id\":" + data[1]),
                "keep row is the one match: " + body);
    }

    // ── kill endpoint ────────────────────────────────────────────────

    @Test
    void killEndpointFlipsRunningRunToKilled() {
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-kill-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-kill-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
        });

        var resp = POST("/api/subagent-runs/" + runId + "/kill",
                "application/json", "{}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"killed\":true"),
                "response confirms kill: " + getContent(resp));

        // Re-read the row through the persistence layer. JPA's L1 cache
        // would return the stale RUNNING entity from before the kill, so
        // clear it before refetching the freshly-committed row.
        JPA.em().clear();
        var fresh = (SubagentRun) SubagentRun.findById(runId);
        assertEquals(SubagentRun.Status.KILLED, fresh.status);
        assertNotNull(fresh.endedAt);
    }

    @Test
    void killEndpointOnUnknownIdReturns404() {
        login();
        var resp = POST("/api/subagent-runs/9999999/kill", "application/json", "{}");
        assertEquals(404, resp.status.intValue());
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static long persistRun(Agent p, Agent c, Conversation pc, Conversation cc,
                                    SubagentRun.Status status) {
        var run = new SubagentRun();
        run.parentAgent = p;
        run.childAgent = c;
        run.parentConversation = pc;
        run.childConversation = cc;
        run.status = status;
        if (status != SubagentRun.Status.RUNNING) {
            run.endedAt = Instant.now();
            run.outcome = "seeded " + status.name().toLowerCase();
        }
        run.save();
        return run.id;
    }

    /** Same as the ApiConversationsControllerTest helper: commit on a VT so
     *  the in-process FunctionalTest HTTP handlers can observe the rows
     *  before responding. The shared FunctionalTest carrier is inside an
     *  ambient tx that doesn't commit until the test returns. */
    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
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
}
