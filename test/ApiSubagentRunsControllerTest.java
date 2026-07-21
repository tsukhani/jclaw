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
    void listJoinFetchesToOneFksAndPreservesViewShape() {
        // JCLAW-806: the list query now JOIN FETCHes the four optional=false
        // to-one FKs that toView dereferences (parent/child agent + parent/child
        // conversation). Verify the eager join didn't drop the row or scramble
        // the emitted view fields — every id/name toView reads must round-trip.
        login();
        var ids = commitInFreshTx(() -> {
            var p = AgentService.create("api-jf-parent", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-jf-child", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var runId = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            return new long[]{runId, p.id, c.id, pc.id, cc.id};
        });

        var resp = GET("/api/subagent-runs");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + ids[0]), "run row present: " + body);
        assertTrue(body.contains("\"parentAgentId\":" + ids[1]), "parentAgentId present: " + body);
        assertTrue(body.contains("\"parentAgentName\":\"api-jf-parent\""),
                "parent agent name present: " + body);
        assertTrue(body.contains("\"childAgentId\":" + ids[2]), "childAgentId present: " + body);
        assertTrue(body.contains("\"childAgentName\":\"api-jf-child\""),
                "child agent name present: " + body);
        assertTrue(body.contains("\"parentConversationId\":" + ids[3]),
                "parentConversationId present: " + body);
        assertTrue(body.contains("\"childConversationId\":" + ids[4]),
                "childConversationId present: " + body);
        assertTrue(body.contains("\"status\":\"COMPLETED\""), "status present: " + body);
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
    void listFilteredByParentConversationExcludesOtherConversations() {
        login();
        var data = commitInFreshTx(() -> {
            var p = AgentService.create("api-pc-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-pc-c", "openrouter", "gpt-4.1");
            // Two separate parent conversations under the same parent agent —
            // the filter must narrow to one and exclude the other.
            var pc1 = ConversationService.create(p, "web", "u-pc1");
            var pc2 = ConversationService.create(p, "web", "u-pc2");
            var cc = ConversationService.create(c, "subagent", null);
            var keepId = persistRun(p, c, pc1, cc, SubagentRun.Status.RUNNING);
            var dropId = persistRun(p, c, pc2, cc, SubagentRun.Status.RUNNING);
            return new long[]{pc1.id, keepId, dropId};
        });

        var resp = GET("/api/subagent-runs?parentConversationId=" + data[0]);
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + data[1]),
                "matching run is present: " + body);
        assertFalse(body.contains("\"id\":" + data[2]),
                "run from another conversation is filtered out: " + body);
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

    // ── server-side sort ─────────────────────────────────────────────

    @Test
    void sortsByParentAgentNameServerSide() {
        login();
        var data = commitInFreshTx(() -> {
            var pz = AgentService.create("sort-zeta", "openrouter", "gpt-4.1");
            var pa = AgentService.create("sort-alpha", "openrouter", "gpt-4.1");
            var c = AgentService.create("sort-child", "openrouter", "gpt-4.1");
            var pzc = ConversationService.create(pz, "web", "u");
            var pac = ConversationService.create(pa, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            persistRun(pz, c, pzc, cc, SubagentRun.Status.COMPLETED);
            persistRun(pa, c, pac, cc, SubagentRun.Status.COMPLETED);
            return new long[]{pa.id, pz.id};
        });
        assertNotNull(data);

        var asc = getContent(GET("/api/subagent-runs?sort=parent&dir=asc"));
        assertTrue(asc.indexOf("sort-alpha") < asc.indexOf("sort-zeta"),
                "parent asc: sort-alpha before sort-zeta, got: " + asc);

        var desc = getContent(GET("/api/subagent-runs?sort=parent&dir=desc"));
        assertTrue(desc.indexOf("sort-zeta") < desc.indexOf("sort-alpha"),
                "parent desc: sort-zeta before sort-alpha, got: " + desc);
    }

    @Test
    void unknownSortColumnFallsBackToDefaultOrderNot400() {
        login();
        commitInFreshTx(() -> {
            var p = AgentService.create("sort-fallback-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("sort-fallback-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            return null;
        });
        // A bogus sort column must not 400 — it falls back to startedAt DESC.
        var resp = GET("/api/subagent-runs?sort=bogus&dir=sideways");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("sort-fallback-c"), "row still returned under fallback order");
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
        assertContentMatch("\"status\":\"ok\"", resp);

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

    // ── bulk delete (DELETE /api/subagent-runs) ──────────────────────

    /**
     * Bulk delete by explicit ids sweeps terminal rows and silently skips
     * RUNNING ones — a "delete selected" over a mixed set leaves live runs
     * intact rather than 409-ing the whole batch. Each removed row cascades
     * through AgentService.delete on its child agent.
     */
    @Test
    void deleteBulkByIdsRemovesTerminalRunsAndSkipsRunning() {
        login();
        var data = commitInFreshTx(() -> {
            var p = AgentService.create("api-bulk-ids-p", "openrouter", "gpt-4.1");
            var c1 = AgentService.create("api-bulk-ids-c1", "openrouter", "gpt-4.1");
            var c2 = AgentService.create("api-bulk-ids-c2", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc1 = ConversationService.create(c1, "subagent", null);
            var cc2 = ConversationService.create(c2, "subagent", null);
            var doneId = persistRun(p, c1, pc, cc1, SubagentRun.Status.COMPLETED);
            var runningId = persistRun(p, c2, pc, cc2, SubagentRun.Status.RUNNING);
            return new long[]{doneId, runningId, c1.id};
        });

        var resp = deleteWithJsonBody("/api/subagent-runs",
                "{\"ids\":[" + data[0] + "," + data[1] + "]}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":1"),
                "only the terminal row counts as deleted, got: " + getContent(resp));

        JPA.em().clear();
        assertNull(SubagentRun.findById(data[0]), "COMPLETED run swept");
        assertNull(Agent.findById(data[2]), "child agent of deleted run swept by cascade");
        assertNotNull(SubagentRun.findById(data[1]), "RUNNING run left intact");
    }

    /**
     * Bulk delete by filter mirrors the list view's WHERE clause: only rows
     * matching parentAgentId are removed, and a RUNNING match is skipped.
     */
    @Test
    void deleteBulkByFilterScopedToParentAgentDeletesOnlyMatchingTerminalRows() {
        login();
        var data = commitInFreshTx(() -> {
            var target = AgentService.create("api-bulk-filter-target", "openrouter", "gpt-4.1");
            var other = AgentService.create("api-bulk-filter-other", "openrouter", "gpt-4.1");
            var tc = ConversationService.create(target, "web", "u");
            var oc = ConversationService.create(other, "web", "u");
            // Each run owns a DISTINCT child agent — a subagent spawns a fresh
            // child per run. Sharing one child agent would make the first
            // AgentService.delete cascade sweep every run pointing at it,
            // masking the RUNNING-skip the test is asserting.
            var cDone = AgentService.create("api-bulk-filter-c-done", "openrouter", "gpt-4.1");
            var cRunning = AgentService.create("api-bulk-filter-c-running", "openrouter", "gpt-4.1");
            var cOther = AgentService.create("api-bulk-filter-c-other", "openrouter", "gpt-4.1");
            var ccDone = ConversationService.create(cDone, "subagent", null);
            var ccRunning = ConversationService.create(cRunning, "subagent", null);
            var ccOther = ConversationService.create(cOther, "subagent", null);
            var targetDone = persistRun(target, cDone, tc, ccDone, SubagentRun.Status.COMPLETED);
            var targetRunning = persistRun(target, cRunning, tc, ccRunning, SubagentRun.Status.RUNNING);
            var otherDone = persistRun(other, cOther, oc, ccOther, SubagentRun.Status.COMPLETED);
            return new long[]{target.id, targetDone, targetRunning, otherDone};
        });

        var resp = deleteWithJsonBody("/api/subagent-runs",
                "{\"filter\":{\"parentAgentId\":" + data[0] + "}}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":1"),
                "only the target agent's terminal row removed, got: " + getContent(resp));

        JPA.em().clear();
        assertNull(SubagentRun.findById(data[1]), "target COMPLETED run swept");
        assertNotNull(SubagentRun.findById(data[2]), "target RUNNING run skipped");
        assertNotNull(SubagentRun.findById(data[3]), "other agent's run untouched by scoped filter");
    }

    @Test
    void deleteBulkWithNeitherIdsNorFilterReturns400() {
        login();
        var resp = deleteWithJsonBody("/api/subagent-runs", "{}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void deleteBulkWithEmptyIdsArrayReportsZeroDeleted() {
        login();
        var resp = deleteWithJsonBody("/api/subagent-runs", "{\"ids\":[]}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":0"),
                "empty ids array is a no-op, got: " + getContent(resp));
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

    @Test
    void listEnrichesEachPageRunWithItsOwnMode() {
        // JCLAW-400: the per-run scan is now bounded to the page's run-ids
        // via per-id details-LIKE predicates. Assert the bounded query still
        // attaches the correct mode to EACH distinct run on the page (not
        // just one), so the LIKE-narrowing didn't cross-wire run→mode.
        login();
        var ids = commitInFreshTx(() -> {
            var p = AgentService.create("api-each-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-each-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var inlineId = persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
            var sessionId = persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
            return new long[]{inlineId, sessionId};
        });
        commitInFreshTx(() -> {
            EventLogger.recordSubagentSpawn("api-each-p", "api-each-c",
                    String.valueOf(ids[0]), "inline", "ctx-inline");
            EventLogger.recordSubagentSpawn("api-each-p", "api-each-c",
                    String.valueOf(ids[1]), "session", "ctx-session");
            EventLogger.flush();
            return null;
        });
        var resp = GET("/api/subagent-runs");
        assertIsOk(resp);
        var body = getContent(resp);
        // Each run object must carry its own mode. Parse out the per-id slice
        // and confirm the mode that immediately follows each id matches.
        assertModeForRun(body, ids[0], "inline");
        assertModeForRun(body, ids[1], "session");
    }

    @Test
    void listResolvesModeWhenSpawnHistoryExceedsOldFloor() {
        // JCLAW-400 regression: previously the scan fetched at most ~500
        // SUBAGENT_SPAWN rows and the target run's event could be crowded
        // out once history grew past the floor. Seed > 500 unrelated spawn
        // events plus one for the listed run; the bounded LIKE query must
        // still find this run's mode regardless of history depth.
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-floor-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-floor-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            return persistRun(p, c, pc, cc, SubagentRun.Status.RUNNING);
        });
        commitInFreshTx(() -> {
            // The real spawn event for the listed run, recorded FIRST so it
            // becomes the OLDEST by timestamp — i.e. would sort last and be
            // dropped by a 500-row DESC slice. The bounded query ignores age.
            EventLogger.recordSubagentSpawn("api-floor-p", "api-floor-c",
                    String.valueOf(runId), "inline", "real");
            // 600 newer, unrelated spawn events for run ids that aren't on
            // the page (offset far past any real run id to avoid collision).
            for (int i = 0; i < 600; i++) {
                EventLogger.recordSubagentSpawn("other-p", "other-c",
                        String.valueOf(900_000L + i), "session", "noise");
            }
            EventLogger.flush();
            return null;
        });
        var resp = GET("/api/subagent-runs");
        assertIsOk(resp);
        var body = getContent(resp);
        assertModeForRun(body, runId, "inline");
    }

    /**
     * Assert that the run object with {@code id} in the JSON array carries
     * {@code "mode":"<expected>"}. Locates the id token then scans forward
     * to that object's mode field, so it can't be fooled by another run's
     * matching mode elsewhere in the array.
     */
    private static void assertModeForRun(String body, long id, String expectedMode) {
        var marker = "\"id\":" + id + ",";
        int at = body.indexOf(marker);
        assertTrue(at >= 0, "run id " + id + " present in body: " + body);
        int modeAt = body.indexOf("\"mode\":", at);
        assertTrue(modeAt >= 0, "mode field after id " + id + ": " + body);
        assertTrue(body.startsWith("\"mode\":\"" + expectedMode + "\"", modeAt),
                "run " + id + " should carry mode '" + expectedMode + "': " + body);
    }

    // ── helpers ───────────────────────────────────────────────────────

    /**
     * Play 1.x's {@link FunctionalTest#DELETE} helper overwrites the request
     * body with an empty stream, so a DELETE that carries a JSON payload has
     * to drive {@code makeRequest} directly. Mirrors the identical helper in
     * ApiConversationsControllerTest — the two bulk-delete endpoints share the
     * ids-or-filter body shape.
     */
    private static play.mvc.Http.Response deleteWithJsonBody(String url, String json) {
        var req = newRequest();
        req.method = "DELETE";
        req.contentType = "application/json";
        var qIdx = url.indexOf('?');
        req.url = url;
        req.path = qIdx >= 0 ? url.substring(0, qIdx) : url;
        req.querystring = qIdx >= 0 ? url.substring(qIdx + 1) : "";
        req.body = new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            var f = FunctionalTest.class.getDeclaredField("savedCookies");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cookies = (java.util.Map<String, play.mvc.Http.Cookie>) f.get(null);
            if (cookies != null) req.cookies = cookies;
        } catch (Exception _) {
            // savedCookies field may shift across play versions; an
            // unauthenticated DELETE surfaces as 401.
        }
        return makeRequest(req);
    }

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
}
