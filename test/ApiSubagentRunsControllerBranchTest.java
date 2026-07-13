import models.Agent;
import models.Conversation;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import services.SubagentRegistry;
import services.Tx;

import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * JCLAW-707 branch coverage for {@code GET /api/subagent-runs} and
 * {@code DELETE /api/subagent-runs}. Complements
 * {@link ApiSubagentRunsControllerTest} (filters, kill, single/bulk delete
 * happy-paths) and {@link ApiSubagentRunsControllerSearchTest} (the {@code q}
 * FTS union) by pinning the paging window, the {@code X-Total-Count} header
 * contract, the server-side sort arms that weren't otherwise exercised, and
 * the bulk-delete request-validation 400s.
 *
 * <p>Every case asserts observable behavior over the real HTTP stack: page-row
 * counts, header values, ordering of rows in the JSON array, and status codes.
 */
class ApiSubagentRunsControllerBranchTest extends FunctionalTest {

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
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    // ── pagination window + X-Total-Count ─────────────────────────────

    @Test
    void listCapsPageToLimitButReportsFullTotalInHeader() {
        login();
        commitInFreshTx(() -> {
            var p = AgentService.create("pg-cap-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("pg-cap-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            return null;
        });

        var resp = GET("/api/subagent-runs?limit=1");
        assertIsOk(resp);
        assertEquals(1, countRows(getContent(resp)),
                "limit=1 must return a single-row page: " + getContent(resp));
        assertEquals("3", resp.getHeader("X-Total-Count"),
                "X-Total-Count reflects the full match count, not the page size");
    }

    @Test
    void listOffsetSkipsLeadingRows() {
        login();
        commitInFreshTx(() -> {
            var p = AgentService.create("pg-off-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("pg-off-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            return null;
        });

        // offset=2 over 3 rows leaves exactly one row on the page.
        var resp = GET("/api/subagent-runs?limit=2&offset=2");
        assertIsOk(resp);
        assertEquals(1, countRows(getContent(resp)),
                "offset=2 over 3 rows yields one row: " + getContent(resp));
        assertEquals("3", resp.getHeader("X-Total-Count"));
    }

    @Test
    void listWithNegativeLimitAndOffsetFallsBackToDefaults() {
        login();
        var ids = commitInFreshTx(() -> {
            var p = AgentService.create("pg-neg-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("pg-neg-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var a = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            var b = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            return new long[]{a, b};
        });

        // Negative values are rejected by the guards and fall through to the
        // default page (limit 100, offset 0) — every row is returned, not zero.
        var resp = GET("/api/subagent-runs?limit=-5&offset=-3");
        assertIsOk(resp);
        var body = getContent(resp);
        assertEquals(2, countRows(body), "both rows returned under default page: " + body);
        assertTrue(body.contains("\"id\":" + ids[0]) && body.contains("\"id\":" + ids[1]),
                "both seeded ids present: " + body);
        assertEquals("2", resp.getHeader("X-Total-Count"));
    }

    // ── server-side sort arms ─────────────────────────────────────────

    @Test
    void listSortsByIdAscAndDesc() {
        login();
        var ids = commitInFreshTx(() -> {
            var p = AgentService.create("srt-id-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("srt-id-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var first = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            var second = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            return new long[]{first, second};
        });

        var asc = getContent(GET("/api/subagent-runs?sort=id&dir=asc"));
        assertTrue(asc.indexOf("\"id\":" + ids[0]) < asc.indexOf("\"id\":" + ids[1]),
                "id asc: lower id first, got: " + asc);

        var desc = getContent(GET("/api/subagent-runs?sort=id&dir=desc"));
        assertTrue(desc.indexOf("\"id\":" + ids[1]) < desc.indexOf("\"id\":" + ids[0]),
                "id desc: higher id first, got: " + desc);
    }

    @Test
    void listSortsByChildAgentNameServerSide() {
        login();
        commitInFreshTx(() -> {
            var p = AgentService.create("srt-child-p", "openrouter", "gpt-4.1");
            var cA = AgentService.create("aaa-child", "openrouter", "gpt-4.1");
            var cZ = AgentService.create("zzz-child", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var ccA = ConversationService.create(cA, "subagent", null);
            var ccZ = ConversationService.create(cZ, "subagent", null);
            persistRun(p, cA, pc, ccA, SubagentRun.Status.COMPLETED);
            persistRun(p, cZ, pc, ccZ, SubagentRun.Status.COMPLETED);
            return null;
        });

        var asc = getContent(GET("/api/subagent-runs?sort=child&dir=asc"));
        assertTrue(asc.indexOf("aaa-child") < asc.indexOf("zzz-child"),
                "child asc: aaa-child before zzz-child, got: " + asc);

        var desc = getContent(GET("/api/subagent-runs?sort=child&dir=desc"));
        assertTrue(desc.indexOf("zzz-child") < desc.indexOf("aaa-child"),
                "child desc: zzz-child before aaa-child, got: " + desc);
    }

    @Test
    void listSortsByStartedAtServerSide() {
        login();
        var ids = commitInFreshTx(() -> {
            var p = AgentService.create("srt-started-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("srt-started-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var older = persistRunStartedAt(p, c, pc, cc, Instant.parse("2025-01-01T00:00:00Z"));
            var newer = persistRunStartedAt(p, c, pc, cc, Instant.parse("2026-01-01T00:00:00Z"));
            return new long[]{older, newer};
        });

        var asc = getContent(GET("/api/subagent-runs?sort=started&dir=asc"));
        assertTrue(asc.indexOf("\"id\":" + ids[0]) < asc.indexOf("\"id\":" + ids[1]),
                "started asc: older run first, got: " + asc);

        var desc = getContent(GET("/api/subagent-runs?sort=started&dir=desc"));
        assertTrue(desc.indexOf("\"id\":" + ids[1]) < desc.indexOf("\"id\":" + ids[0]),
                "started desc: newer run first, got: " + desc);
    }

    // ── bulk-delete request validation ────────────────────────────────

    @Test
    void deleteBulkWithUnparseableBodyReturns400() {
        login();
        // JsonBodyReader.readJsonBody() returns null on a parse failure, so the
        // controller's "Missing request body." guard fires — distinct from the
        // "{}" body that reaches the neither-ids-nor-filter guard.
        var resp = deleteWithJsonBody("/api/subagent-runs", "not-json-at-all");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("Missing request body"),
                "null body must be reported as a missing body: " + getContent(resp));
    }

    @Test
    void deleteBulkFilterWithInvalidStatusReturns400() {
        login();
        var resp = deleteWithJsonBody("/api/subagent-runs",
                "{\"filter\":{\"status\":\"BOGUS\"}}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("Invalid") && getContent(resp).contains("status"),
                "invalid status in a filter must 400: " + getContent(resp));
    }

    @Test
    void deleteBulkFilterWithInvalidSinceReturns400() {
        login();
        var resp = deleteWithJsonBody("/api/subagent-runs",
                "{\"filter\":{\"since\":\"not-a-date\"}}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("Invalid") && getContent(resp).contains("since"),
                "invalid since in a filter must 400: " + getContent(resp));
    }

    // ── helpers ───────────────────────────────────────────────────────

    /** Count run objects in the JSON array by the once-per-row {@code startedAt} field. */
    private static int countRows(String body) {
        var m = Pattern.compile("\"startedAt\":").matcher(body);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static long persistRun(Agent p, Agent c, Conversation pc, Conversation cc,
                                    SubagentRun.Status status) {
        return persistRunStartedAt(p, c, pc, cc, null, status);
    }

    private static long persistRunStartedAt(Agent p, Agent c, Conversation pc, Conversation cc,
                                             Instant startedAt) {
        return persistRunStartedAt(p, c, pc, cc, startedAt, SubagentRun.Status.COMPLETED);
    }

    private static long persistRunStartedAt(Agent p, Agent c, Conversation pc, Conversation cc,
                                             Instant startedAt, SubagentRun.Status status) {
        var run = new SubagentRun();
        run.parentAgent = p;
        run.childAgent = c;
        run.parentConversation = pc;
        run.childConversation = cc;
        run.status = status;
        if (startedAt != null) run.startedAt = startedAt;
        if (status != SubagentRun.Status.RUNNING) {
            run.endedAt = Instant.now();
            run.outcome = "seeded " + status.name().toLowerCase();
        }
        run.save();
        return run.id;
    }

    /**
     * Play 1.x's {@link FunctionalTest#DELETE} helper overwrites the request
     * body with an empty stream, so a DELETE that carries a JSON payload has to
     * drive {@code makeRequest} directly. Mirrors the identical helper in
     * {@link ApiSubagentRunsControllerTest}.
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
