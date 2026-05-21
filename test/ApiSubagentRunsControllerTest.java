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

    /**
     * Hard-delete sweeps the SubagentRun row along with the child agent
     * it spawned (which cascades to the child conversation, its
     * messages, and the run row itself via AgentService.delete's FK
     * chain). Terminal-status only — RUNNING rows must be killed first.
     */
    @Test
    void deleteOnCompletedRunRemovesRunAndChildAgent() {
        login();
        var data = commitInFreshTx(() -> {
            var p = AgentService.create("api-del-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-del-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var runId = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            return new long[]{runId, c.id};
        });
        var runId = data[0];
        var childAgentId = data[1];

        var resp = DELETE("/api/subagent-runs/" + runId);
        assertIsOk(resp);
        assertContentMatch("\"status\":\"deleted\"", resp);

        // Clear L1 cache so the next finder hits the DB rather than
        // returning the stale pre-delete entity reference.
        JPA.em().clear();
        assertNull(SubagentRun.findById(runId), "run row gone");
        assertNull(Agent.findById(childAgentId),
                "child agent swept by AgentService.delete cascade");
    }

    @Test
    void deleteOnRunningRunReturns409() {
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-del-running-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-del-running-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
        });

        var resp = DELETE("/api/subagent-runs/" + runId);
        assertEquals(409, resp.status.intValue());

        // Row must still be present — the rejection is the whole point
        // (the stream owner still holds a reference to it).
        JPA.em().clear();
        assertNotNull(SubagentRun.findById(runId),
                "RUNNING row left in place when delete is rejected");
    }

    @Test
    void deleteOnUnknownIdReturns404() {
        login();
        var resp = DELETE("/api/subagent-runs/9999999");
        assertEquals(404, resp.status.intValue());
    }

    // ── kill body-reason branch coverage ─────────────────────────────

    @Test
    void killEndpointAcceptsExplicitReason() {
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-kill-reason-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-kill-reason-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
        });
        var resp = POST("/api/subagent-runs/" + runId + "/kill",
                "application/json",
                "{\"reason\":\"operator-supplied reason\"}");
        assertIsOk(resp);
        // The server persists the reason on the SubagentRun row's outcome field.
        JPA.em().clear();
        var fresh = (SubagentRun) SubagentRun.findById(runId);
        assertEquals(SubagentRun.Status.KILLED, fresh.status);
    }

    @Test
    void killEndpointIgnoresBlankReasonAndUsesDefault() {
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-kill-blank-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-kill-blank-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
        });
        var resp = POST("/api/subagent-runs/" + runId + "/kill",
                "application/json",
                "{\"reason\":\"   \"}");
        assertIsOk(resp);
    }

    @Test
    void killEndpointHandlesNullReasonInBody() {
        // body.has("reason") is true but get("reason").isJsonNull() is also
        // true → controller falls back to the default reason.
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-kill-null-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-kill-null-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
        });
        var resp = POST("/api/subagent-runs/" + runId + "/kill",
                "application/json",
                "{\"reason\":null}");
        assertIsOk(resp);
    }

    @Test
    void killEndpointOnAlreadyTerminalRunReportsKilledFalseWithStatus() {
        // Killing a COMPLETED row: SubagentRegistry.kill returns killed=false
        // but finalStatus is set (the existing terminal status), so the
        // controller renders an envelope with killed=false rather than 404.
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-kill-completed-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-kill-completed-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
        });
        var resp = POST("/api/subagent-runs/" + runId + "/kill",
                "application/json", "{}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"killed\":false"),
                "already-terminal kill must return killed=false: " + body);
    }

    // ── collectModesForRuns branch coverage ───────────────────────────

    @Test
    void listSurfacesModeFromMatchingSubagentSpawnEvent() {
        // Happy path through collectModesForRuns: when a SUBAGENT_SPAWN
        // EventLog row carries this run's id and a non-null mode, the
        // listing response must echo that mode for the row.
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-mode-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-mode-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
        });
        commitInFreshTx(() -> {
            EventLogger.recordSubagentSpawn("api-mode-p", "api-mode-c",
                    String.valueOf(runId), "inline", "build the docs");
            EventLogger.flush();
            return null;
        });
        var resp = GET("/api/subagent-runs");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"mode\":\"inline\""),
                "mode from SUBAGENT_SPAWN event must surface on the row: " + body);
    }

    @Test
    void listIgnoresSpawnEventsWithNonNumericRunId() {
        // collectModesForRuns has a NumberFormatException guard for run_id
        // strings that don't parse as Long. Seed an event whose details
        // carry a clearly non-numeric run_id, then list — no mode should
        // be attached to any row.
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-bad-runid-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-bad-runid-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
        });
        commitInFreshTx(() -> {
            EventLogger.recordSubagentSpawn("api-bad-runid-p", "api-bad-runid-c",
                    "not-a-number", "inline", "ctx");
            EventLogger.flush();
            return null;
        });
        var resp = GET("/api/subagent-runs");
        assertIsOk(resp);
        var body = getContent(resp);
        // Row exists but no mode attached — mode field absent or null.
        assertTrue(body.contains("\"id\":" + runId),
                "row should still appear in listing: " + body);
        assertFalse(body.contains("\"mode\":\"inline\""),
                "no mode should attach when run_id isn't parseable: " + body);
    }

    @Test
    void listKeepsOnlyMostRecentModeWhenMultipleSpawnEventsExist() {
        // collectModesForRuns iterates events in DESC timestamp order and
        // takes the FIRST hit per run id (containsKey skip). Two spawn
        // events for the same run with different modes → only the newer
        // mode appears.
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-multi-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-multi-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
        });
        commitInFreshTx(() -> {
            // Older event first.
            EventLogger.recordSubagentSpawn("api-multi-p", "api-multi-c",
                    String.valueOf(runId), "session", "first");
            EventLogger.flush();
            return null;
        });
        commitInFreshTx(() -> {
            // Newer event — its mode should win.
            EventLogger.recordSubagentSpawn("api-multi-p", "api-multi-c",
                    String.valueOf(runId), "inline", "second");
            EventLogger.flush();
            return null;
        });
        var resp = GET("/api/subagent-runs");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"mode\":\"inline\""),
                "most recent mode (inline) should win: " + body);
        assertFalse(body.contains("\"mode\":\"session\""),
                "older mode (session) should not appear: " + body);
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
