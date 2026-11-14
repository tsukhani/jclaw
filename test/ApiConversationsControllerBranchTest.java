import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import models.SessionCompaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConversationService;
import services.Tx;

import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * JCLAW-707 branch coverage for {@code GET /api/conversations},
 * {@code GET /api/conversations/{id}/messages}, and
 * {@code GET /api/conversations/channels}. Complements
 * {@link ApiConversationsControllerTest} (CRUD, delete-by-filter, model
 * override) and {@link ApiConversationsControllerSearchTest} (the {@code q}
 * FTS path) by pinning the list {@code name}/{@code agentId} filters, the
 * paging window + {@code X-Total-Count} contract, the {@code compactionCount}
 * bulk-fetch, the {@code /messages} success path and its optional row-shape
 * fields (reasoning / usage / subagentRunId / messageKind+metadata /
 * truncated / attachments), and the distinct-channels projection.
 *
 * <p>Every case asserts observable behavior over the real HTTP stack: row
 * counts, header values, ordering, and the emitted JSON wire shape.
 */
class ApiConversationsControllerBranchTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    // ── list filters ──────────────────────────────────────────────────

    @Test
    void listFilteredByNameMatchesPreviewSubstringCaseInsensitively() {
        login();
        var ids = commitInFreshTx(() -> {
            var agent = newAgent("name-filter-agent");
            var deploy = newConv(agent, "web", "u-a", "Deployment logs for prod", 0);
            var grocery = newConv(agent, "web", "u-b", "grocery shopping list", 0);
            return new long[]{deploy, grocery};
        });

        // Substring, lower-cased on both sides: "deploy" matches "Deployment".
        var resp = GET("/api/conversations?name=deploy");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + ids[0]),
                "preview matching 'deploy' must be present: " + body);
        assertFalse(body.contains("\"id\":" + ids[1]),
                "non-matching preview must be excluded: " + body);
    }

    @Test
    void listFilteredByAgentIdExcludesOtherAgents() {
        login();
        var data = commitInFreshTx(() -> {
            var keep = newAgent("agentfilter-keep");
            var other = newAgent("agentfilter-other");
            var keepConv = newConv(keep, "web", "u-keep", "keep me", 0);
            var otherConv = newConv(other, "web", "u-other", "drop me", 0);
            return new long[]{keep.id, keepConv, otherConv};
        });

        var resp = GET("/api/conversations?agentId=" + data[0]);
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + data[1]),
                "conversation under the filtered agent is present: " + body);
        assertFalse(body.contains("\"id\":" + data[2]),
                "conversation under another agent is filtered out: " + body);
    }

    // ── pagination window + X-Total-Count ─────────────────────────────

    @Test
    void listCapsPageToLimitButReportsFullTotalInHeader() {
        login();
        commitInFreshTx(() -> {
            var agent = newAgent("pg-cap-agent");
            newConv(agent, "web", "u1", "one", 0);
            newConv(agent, "web", "u2", "two", 0);
            newConv(agent, "web", "u3", "three", 0);
            return null;
        });

        var resp = GET("/api/conversations?limit=1");
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
            var agent = newAgent("pg-off-agent");
            newConv(agent, "web", "u1", "one", 0);
            newConv(agent, "web", "u2", "two", 0);
            newConv(agent, "web", "u3", "three", 0);
            return null;
        });

        var resp = GET("/api/conversations?limit=2&offset=2");
        assertIsOk(resp);
        assertEquals(1, countRows(getContent(resp)),
                "offset=2 over 3 rows yields one row: " + getContent(resp));
        assertEquals("3", resp.getHeader("X-Total-Count"));
    }

    @Test
    void listWithNegativeLimitAndOffsetFallsBackToDefaults() {
        login();
        commitInFreshTx(() -> {
            var agent = newAgent("pg-neg-agent");
            newConv(agent, "web", "u1", "one", 0);
            newConv(agent, "web", "u2", "two", 0);
            return null;
        });

        var resp = GET("/api/conversations?limit=-5&offset=-3");
        assertIsOk(resp);
        assertEquals(2, countRows(getContent(resp)),
                "negative params fall back to the default page — all rows returned: " + getContent(resp));
        assertEquals("2", resp.getHeader("X-Total-Count"));
    }

    // ── compactionCount bulk-fetch ────────────────────────────────────

    @Test
    void listSurfacesCompactionCountForConversationsWithCompactions() {
        login();
        commitInFreshTx(() -> {
            var agent = newAgent("compaction-count-agent");
            var conv = ConversationService.create(agent, "web", "u-compact");
            seedCompaction(conv);
            seedCompaction(conv);
            return null;
        });

        var resp = GET("/api/conversations");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"compactionCount\":2"),
                "the GROUP BY bulk-fetch must surface the two-compaction count: " + body);
    }

    @Test
    void listAndSingleGetCountOnlyUserRowsAsPrompts() {
        login();
        var ids = commitInFreshTx(() -> {
            var agent = newAgent("prompt-count-agent");
            var busy = ConversationService.create(agent, "web", "u-busy");
            ConversationService.appendUserMessage(busy, "first");
            ConversationService.appendAssistantMessage(busy, "reply one", null);
            ConversationService.appendAssistantMessage(busy, "reply two", null);
            ConversationService.appendUserMessage(busy, "second");
            var quiet = ConversationService.create(agent, "web", "u-quiet");
            ConversationService.appendAssistantMessage(quiet, "unprompted", null);
            return new long[] {busy.id, quiet.id};
        });

        var rows = com.google.gson.JsonParser.parseString(getContent(GET("/api/conversations"))).getAsJsonArray();
        var byId = new java.util.HashMap<Long, com.google.gson.JsonObject>();
        for (var row : rows) byId.put(row.getAsJsonObject().get("id").getAsLong(), row.getAsJsonObject());
        assertEquals(2, byId.get(ids[0]).get("userPromptCount").getAsInt());
        assertEquals(4, byId.get(ids[0]).get("messageRowCount").getAsInt());
        assertEquals(0, byId.get(ids[1]).get("userPromptCount").getAsInt());
        assertEquals(1, byId.get(ids[1]).get("messageRowCount").getAsInt());

        var single = com.google.gson.JsonParser.parseString(getContent(GET("/api/conversations/" + ids[0])))
                .getAsJsonObject();
        assertEquals(2, single.get("userPromptCount").getAsInt());
        assertEquals(4, single.get("messageRowCount").getAsInt());
    }

    @Test
    void listAndSingleGetCountTheRowsSinceTheLaterFloorAndTheRowsASummaryNeeds() {
        login();
        var ids = commitInFreshTx(() -> {
            var agent = newAgent("since-summary-agent");
            var base = Instant.parse("2026-11-14T10:00:00Z");
            var summarized = sixRows(ConversationService.create(agent, "web", "u-summarized"), base);
            summarized.compactionSince = base.plusSeconds(2);
            summarized.save();
            // A /reset after the summary moves the floor past it.
            var reset = sixRows(ConversationService.create(agent, "web", "u-reset"), base);
            reset.compactionSince = base.plusSeconds(2);
            reset.contextSince = base.plusSeconds(4);
            reset.save();
            var fresh = ConversationService.create(agent, "web", "u-fresh");
            ConversationService.appendUserMessage(fresh, "hello");
            return new long[] {summarized.id, reset.id, fresh.id};
        });

        var rows = com.google.gson.JsonParser.parseString(getContent(GET("/api/conversations"))).getAsJsonArray();
        var byId = new java.util.HashMap<Long, com.google.gson.JsonObject>();
        for (var row : rows) byId.put(row.getAsJsonObject().get("id").getAsLong(), row.getAsJsonObject());
        assertEquals(4, byId.get(ids[0]).get("rowsSinceSummary").getAsInt(), "the row at the floor counts");
        assertEquals(2, byId.get(ids[1]).get("rowsSinceSummary").getAsInt());
        assertEquals(1, byId.get(ids[2]).get("rowsSinceSummary").getAsInt(), "no floor counts every row");
        assertEquals(20, byId.get(ids[0]).get("rowsToSummarize").getAsInt());
        assertEquals(6, byId.get(ids[0]).get("rowsToCompact").getAsInt());

        for (int i = 0; i < 3; i++) {
            var single = com.google.gson.JsonParser.parseString(getContent(GET("/api/conversations/" + ids[i])))
                    .getAsJsonObject();
            assertEquals(byId.get(ids[i]).get("rowsSinceSummary").getAsInt(), single.get("rowsSinceSummary").getAsInt(),
                    "the single get counts as the list does, conversation " + i);
        }
    }

    @Test
    void compactionsListEverySummaryInTheOrderWrittenWithWhetherThePromptStillCarriesIt() {
        login();
        var base = Instant.parse("2026-11-14T10:00:00Z");
        var ids = commitInFreshTx(() -> {
            var agent = newAgent("audit-agent");
            var conv = ConversationService.create(agent, "web", "u-audit");
            var beforeReset = segment(conv, base, base.minusSeconds(60), base.minusSeconds(30), 4, "SET ASIDE");
            var first = segment(conv, base.plusSeconds(100), base, base.plusSeconds(50), 10, "FIRST");
            var second = segment(conv, base.plusSeconds(200), base.plusSeconds(51), base.plusSeconds(150), 12, "SECOND");
            var merged = segment(conv, base.plusSeconds(300), base, base.plusSeconds(150), 22, "MERGED");
            first.mergedIntoId = merged.id;
            first.save();
            second.mergedIntoId = merged.id;
            second.save();
            // A /reset after the first summary and before the rest.
            conv.contextSince = base.plusSeconds(10);
            conv.save();
            return new long[] {conv.id, beforeReset.id, first.id, second.id, merged.id};
        });

        var response = GET("/api/conversations/" + ids[0] + "/compactions");
        assertIsOk(response);
        var rows = com.google.gson.JsonParser.parseString(getContent(response)).getAsJsonArray();

        assertEquals(4, rows.size());
        var order = new java.util.ArrayList<Long>();
        for (var row : rows) order.add(row.getAsJsonObject().get("id").getAsLong());
        assertEquals(java.util.List.of(ids[1], ids[2], ids[3], ids[4]), order, "oldest written first");
        var setAside = rows.get(0).getAsJsonObject();
        assertEquals("reset", setAside.get("status").getAsString());
        assertEquals("SET ASIDE", setAside.get("summary").getAsString());
        var first = rows.get(1).getAsJsonObject();
        assertEquals("merged", first.get("status").getAsString());
        assertEquals(ids[4], first.get("mergedIntoId").getAsLong());
        assertEquals(10, first.get("rows").getAsInt());
        assertEquals(base.toString(), first.get("coversFrom").getAsString());
        assertEquals(base.plusSeconds(50).toString(), first.get("coversTo").getAsString());
        assertEquals("openrouter/gpt-4.1", first.get("model").getAsString());
        var merged = rows.get(3).getAsJsonObject();
        assertEquals("live", merged.get("status").getAsString());
        assertTrue(!merged.has("mergedIntoId") || merged.get("mergedIntoId").isJsonNull());
        assertEquals("MERGED", merged.get("summary").getAsString());
    }

    @Test
    void compactionsOfAConversationWithNoneIsAnEmptyListAndOfAMissingOneNotFound() {
        login();
        var id = commitInFreshTx(() -> ConversationService.create(newAgent("no-summary-agent"), "web", "u-none").id);

        var none = GET("/api/conversations/" + id + "/compactions");
        assertIsOk(none);
        assertEquals("[]", getContent(none));
        assertIsNotFound(GET("/api/conversations/999999/compactions"));
    }

    /** One summary written at {@code at} of the rows from {@code from} to {@code to}. */
    private static SessionCompaction segment(Conversation conv, Instant at, Instant from, Instant to, int rows,
                                             String text) {
        var sc = new SessionCompaction();
        sc.conversation = conv;
        sc.turnCount = rows;
        sc.summaryTokens = text.length() / 4;
        sc.model = "openrouter/gpt-4.1";
        sc.summary = text;
        sc.compactedAt = at;
        sc.coversFrom = from;
        sc.coversTo = to;
        sc.save();
        return sc;
    }

    /** Six alternating rows a second apart from {@code base}, the first a user row. */
    private static Conversation sixRows(Conversation conv, Instant base) {
        for (int i = 0; i < 6; i++) {
            var m = i % 2 == 0 ? ConversationService.appendUserMessage(conv, "q" + i)
                    : ConversationService.appendAssistantMessage(conv, "a" + i, null);
            // createdAt is updatable = false, so only a bulk update can date the row.
            JPA.em().createQuery("UPDATE Message m SET m.createdAt = :at WHERE m.id = :id")
                    .setParameter("at", base.plusSeconds(i)).setParameter("id", m.id).executeUpdate();
        }
        return conv;
    }

    @Test
    void messageRowCountSeesRowsTheStoredCounterMissed() {
        login();
        var id = commitInFreshTx(() -> {
            var agent = newAgent("row-count-agent");
            var conv = ConversationService.create(agent, "web", "u-rows");
            ConversationService.appendUserMessage(conv, "go");
            // A tool round: rows persisted, counter bumped only in memory, as in a real turn.
            ConversationService.appendToolResult(conv, "call-1", "out");
            ConversationService.appendToolResult(conv, "call-2", "out");
            conv.messageCount = 1;
            conv.save();
            return conv.id;
        });

        var single = com.google.gson.JsonParser.parseString(getContent(GET("/api/conversations/" + id)))
                .getAsJsonObject();
        assertEquals(1, single.get("messageCount").getAsInt());
        assertEquals(3, single.get("messageRowCount").getAsInt());
    }

    @Test
    void listAndSingleGetCarryAStoredChecklistAsAnArray() {
        login();
        var id = commitInFreshTx(() -> {
            var agent = newAgent("checklist-row-agent");
            var withList = ConversationService.create(agent, "web", "u-list");
            withList.checklist = "[{\"content\":\"A\",\"status\":\"pending\",\"activeForm\":\"Doing A\"}]";
            withList.save();
            ConversationService.create(agent, "web", "u-none");
            return withList.id;
        });

        var list = getContent(GET("/api/conversations"));
        assertEquals(1, count(list, "\"checklist\":[{"), "only the row with a list carries the key: " + list);
        var single = getContent(GET("/api/conversations/" + id));
        assertTrue(single.contains("\"checklist\":[{\"content\":\"A\""), single);
    }

    // ── server-side sort arms ─────────────────────────────────────────

    @Test
    void listSortsByAgentNameServerSide() {
        login();
        commitInFreshTx(() -> {
            var zeta = newAgent("zeta-agent");
            var alpha = newAgent("alpha-agent");
            newConv(zeta, "web", "u-z", "z", 0);
            newConv(alpha, "web", "u-a", "a", 0);
            return null;
        });

        var asc = getContent(GET("/api/conversations?sort=agentName&dir=asc"));
        assertTrue(asc.indexOf("alpha-agent") < asc.indexOf("zeta-agent"),
                "agentName asc: alpha-agent before zeta-agent, got: " + asc);

        var desc = getContent(GET("/api/conversations?sort=agentName&dir=desc"));
        assertTrue(desc.indexOf("zeta-agent") < desc.indexOf("alpha-agent"),
                "agentName desc: zeta-agent before alpha-agent, got: " + desc);
    }

    @Test
    void listSortsByMessageCountServerSide() {
        login();
        commitInFreshTx(() -> {
            var agent = newAgent("msgcount-sort-agent");
            newConv(agent, "web", "u-low", "low count", 2);
            newConv(agent, "web", "u-high", "high count", 9);
            return null;
        });

        var asc = getContent(GET("/api/conversations?sort=messageCount&dir=asc"));
        assertTrue(asc.indexOf("\"messageCount\":2") < asc.indexOf("\"messageCount\":9"),
                "messageCount asc: 2 before 9, got: " + asc);

        var desc = getContent(GET("/api/conversations?sort=messageCount&dir=desc"));
        assertTrue(desc.indexOf("\"messageCount\":9") < desc.indexOf("\"messageCount\":2"),
                "messageCount desc: 9 before 2, got: " + desc);
    }

    // ── GET /messages success path + row shape ────────────────────────

    @Test
    void getMessagesReturnsPageAndFullTotalCount() {
        login();
        var cid = commitInFreshTx(() -> {
            var agent = newAgent("msgs-page-agent");
            var conv = ConversationService.create(agent, "web", "u");
            appendUser(conv, "first");
            appendUser(conv, "second");
            appendUser(conv, "third");
            return conv.id;
        });

        var resp = GET("/api/conversations/" + cid + "/messages?limit=2");
        assertIsOk(resp);
        assertEquals(2, countMessages(getContent(resp)),
                "limit=2 must cap the page to two messages: " + getContent(resp));
        assertEquals("3", resp.getHeader("X-Total-Count"),
                "X-Total-Count reflects the full message count");
    }

    @Test
    void getMessagesWithNegativeLimitFallsBackToDefault() {
        login();
        var cid = commitInFreshTx(() -> {
            var agent = newAgent("msgs-neg-agent");
            var conv = ConversationService.create(agent, "web", "u");
            appendUser(conv, "only message");
            return conv.id;
        });

        var resp = GET("/api/conversations/" + cid + "/messages?limit=-1&offset=-1");
        assertIsOk(resp);
        assertEquals(1, countMessages(getContent(resp)),
                "negative paging params fall back to the default window: " + getContent(resp));
    }

    @Test
    void getMessagesSurfacesOptionalRowFields() {
        login();
        var cid = commitInFreshTx(() -> {
            var agent = newAgent("msgs-shape-agent");
            var conv = ConversationService.create(agent, "web", "u");
            var m = new Message();
            m.conversation = conv;
            m.role = MessageRole.ASSISTANT.value;
            m.content = "assistant reply";
            m.reasoning = "chain of thought here";
            m.usageJson = "{\"total_tokens\":42}";
            m.subagentRunId = 777L;
            m.messageKind = "subagent_announce";
            m.metadata = "{\"runId\":5}";
            m.truncated = true;
            m.save();
            return conv.id;
        });

        var resp = GET("/api/conversations/" + cid + "/messages");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"reasoning\":\"chain of thought here\""),
                "reasoning must surface when non-null: " + body);
        assertTrue(body.contains("\"usage\":{\"total_tokens\":42}"),
                "usage must land as a parsed object: " + body);
        assertTrue(body.contains("\"subagentRunId\":777"),
                "subagentRunId must surface when set: " + body);
        assertTrue(body.contains("\"messageKind\":\"subagent_announce\""),
                "messageKind must surface when set: " + body);
        assertTrue(body.contains("\"metadata\":{\"runId\":5}"),
                "metadata must land as a parsed object alongside messageKind: " + body);
        assertTrue(body.contains("\"truncated\":true"),
                "truncated flag must surface when true: " + body);
    }

    @Test
    void getMessagesSurfacesAttachmentsWithGenerationMetadata() {
        login();
        var cid = commitInFreshTx(() -> {
            var agent = newAgent("msgs-attach-agent");
            var conv = ConversationService.create(agent, "web", "u");
            var m = new Message();
            m.conversation = conv;
            m.role = MessageRole.ASSISTANT.value;
            m.content = "here is your image";
            m.save();
            var a = new MessageAttachment();
            a.message = m;
            a.uuid = "attach-uuid-707";
            a.originalFilename = "render.png";
            a.storagePath = "main/attachments/1/attach-uuid-707.png";
            a.mimeType = "image/png";
            a.sizeBytes = 2048;
            a.kind = MessageAttachment.KIND_IMAGE;
            a.generated = true;
            a.generationMetadata = "{\"prompt\":\"a cat\"}";
            a.generationJobId = 314L;
            a.save();
            return conv.id;
        });

        var resp = GET("/api/conversations/" + cid + "/messages");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"attachments\":[{"),
                "non-empty attachments must serialize as an array: " + body);
        assertTrue(body.contains("\"uuid\":\"attach-uuid-707\""),
                "attachment uuid must round-trip: " + body);
        assertTrue(body.contains("\"generationMetadata\":\"{\\\"prompt\\\":\\\"a cat\\\"}\""),
                "generationMetadata must surface when non-null: " + body);
        assertTrue(body.contains("\"generationJobId\":314"),
                "generationJobId must surface when non-null: " + body);
    }

    // ── distinct channels projection ──────────────────────────────────

    @Test
    void listConversationChannelsReturnsDistinctSortedChannels() {
        login();
        commitInFreshTx(() -> {
            var agent = newAgent("channels-agent");
            ConversationService.create(agent, "web", "u1");
            ConversationService.create(agent, "telegram", "u2");
            ConversationService.create(agent, "web", "u3"); // duplicate channel
            return null;
        });

        var resp = GET("/api/conversations/channels");
        assertIsOk(resp);
        // Distinct + ORDER BY channelType → telegram before web, no duplicate web.
        assertEquals("[\"telegram\",\"web\"]", getContent(resp),
                "channels must be distinct and alphabetically ordered: " + getContent(resp));
    }

    // ── helpers ───────────────────────────────────────────────────────

    /** Count conversation rows by the once-per-row {@code compactionCount} field. */
    private static int countRows(String body) {
        return count(body, "\"compactionCount\":");
    }

    /** Count message rows by the once-per-row {@code role} field. */
    private static int countMessages(String body) {
        return count(body, "\"role\":");
    }

    private static int count(String body, String token) {
        var m = Pattern.compile(Pattern.quote(token)).matcher(body);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static Agent newAgent(String name) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.enabled = true;
        a.save();
        return a;
    }

    private static long newConv(Agent agent, String channel, String peer, String preview, int messageCount) {
        var c = ConversationService.create(agent, channel, peer);
        c.preview = preview;
        c.messageCount = messageCount;
        c.save();
        return c.id;
    }

    private static void appendUser(Conversation conv, String content) {
        var m = new Message();
        m.conversation = conv;
        m.role = MessageRole.USER.value;
        m.content = content;
        m.save();
    }

    private static void seedCompaction(Conversation conv) {
        var sc = new SessionCompaction();
        sc.conversation = conv;
        sc.turnCount = 3;
        sc.summaryTokens = 100;
        sc.model = "openrouter/gpt-4.1";
        sc.summary = "test summary";
        sc.compactedAt = Instant.now();
        sc.save();
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
