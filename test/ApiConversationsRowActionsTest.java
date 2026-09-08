import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConversationService;
import services.Tx;

import java.util.function.Supplier;

/**
 * Functional coverage for the conversation row actions — rename, star, pin —
 * and the two list filters they feed ({@code starred}, {@code pinned}).
 * Complements {@link ApiConversationsControllerTest} (CRUD, delete-by-filter,
 * model override) and {@link ApiConversationsControllerBranchTest} (list
 * filters, paging, message rows).
 *
 * <p>Each case asserts over the real HTTP stack against H2: status codes, the
 * emitted row shape, and which set a row lands in after the mutation.
 */
class ApiConversationsRowActionsTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    // ── rename ────────────────────────────────────────────────────────

    @Test
    void renameWritesThePreviewEveryDisplaySurfaceReads() {
        login();
        long id = commitInFreshTx(() -> newConv(newAgent("rename-agent"), "u-1", "Hi"));

        var resp = PUT("/api/conversations/" + id + "/name", "application/json",
                "{\"name\": \"Invoice thread\"}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("Invoice thread"), getContent(resp));

        var row = getContent(GET("/api/conversations/" + id));
        assertTrue(row.contains("\"preview\":\"Invoice thread\""),
                "the rename must land in the field the list, chat header and palette read: " + row);
    }

    @Test
    void renameTrimsSurroundingWhitespace() {
        login();
        long id = commitInFreshTx(() -> newConv(newAgent("rename-trim-agent"), "u-1", "Hi"));

        assertIsOk(PUT("/api/conversations/" + id + "/name", "application/json",
                "{\"name\": \"  Padded  \"}"));

        var row = getContent(GET("/api/conversations/" + id));
        assertTrue(row.contains("\"preview\":\"Padded\""), row);
    }

    /** A blank or absent name is rejected rather than clearing the display name. */
    @ParameterizedTest(name = "renameRejects[{0}]")
    @ValueSource(strings = {"{\"name\": \"   \"}", "{}"})
    void renameRejectsAnEmptyName(String body) {
        login();
        long id = commitInFreshTx(() -> newConv(newAgent("rename-empty-" + body.length()), "u-1", "Hi"));

        assertEquals(400, PUT("/api/conversations/" + id + "/name", "application/json", body)
                .status.intValue());
        assertTrue(getContent(GET("/api/conversations/" + id)).contains("\"preview\":\"Hi\""),
                "the stored name is unchanged");
    }

    /**
     * Over the column's 100-character cap is a 400, not a truncation: storing
     * something different from what the operator typed would be invisible to
     * them until they reopened the row.
     */
    @Test
    void renameRejectsANameOverTheColumnCap() {
        login();
        long id = commitInFreshTx(() -> newConv(newAgent("rename-long-agent"), "u-1", "Hi"));

        var resp = PUT("/api/conversations/" + id + "/name", "application/json",
                "{\"name\": \"" + "x".repeat(101) + "\"}");
        assertEquals(400, resp.status.intValue(), getContent(resp));
        assertTrue(getContent(GET("/api/conversations/" + id)).contains("\"preview\":\"Hi\""));

        // Exactly at the cap still lands.
        assertIsOk(PUT("/api/conversations/" + id + "/name", "application/json",
                "{\"name\": \"" + "y".repeat(100) + "\"}"));
    }

    @Test
    void renameUnknownConversationIs404() {
        login();
        var resp = PUT("/api/conversations/999999/name", "application/json", "{\"name\": \"x\"}");
        assertEquals(404, resp.status.intValue());
    }

    // ── star ──────────────────────────────────────────────────────────

    @Test
    void starDrivesTheStarredFilterAndIsIdempotent() {
        login();
        long[] ids = commitInFreshTx(() -> {
            var agent = newAgent("star-agent");
            return new long[]{newConv(agent, "u-a", "keep"), newConv(agent, "u-b", "plain")};
        });

        assertIsOk(PUT("/api/conversations/" + ids[0] + "/star", "application/json", ""));
        assertIsOk(PUT("/api/conversations/" + ids[0] + "/star", "application/json", ""));

        var starredOnly = getContent(GET("/api/conversations?starred=true"));
        assertTrue(starredOnly.contains("\"id\":" + ids[0]), starredOnly);
        assertFalse(starredOnly.contains("\"id\":" + ids[1]),
                "an unstarred conversation must not survive starred=true: " + starredOnly);

        var unstarredOnly = getContent(GET("/api/conversations?starred=false"));
        assertTrue(unstarredOnly.contains("\"id\":" + ids[1]), unstarredOnly);
        assertFalse(unstarredOnly.contains("\"id\":" + ids[0]), unstarredOnly);
    }

    @Test
    void unstarClearsTheMarker() {
        login();
        long id = commitInFreshTx(() -> newConv(newAgent("unstar-agent"), "u-1", "Hi"));

        assertIsOk(PUT("/api/conversations/" + id + "/star", "application/json", ""));
        assertIsOk(DELETE("/api/conversations/" + id + "/star"));
        assertIsOk(DELETE("/api/conversations/" + id + "/star"));

        assertTrue(getContent(GET("/api/conversations/" + id)).contains("\"starred\":false"));
        assertFalse(getContent(GET("/api/conversations?starred=true")).contains("\"id\":" + id));
    }

    // ── pin ───────────────────────────────────────────────────────────

    @Test
    void pinMovesTheRowOutOfTheDefaultPageIntoThePinnedSet() {
        login();
        long id = commitInFreshTx(() -> newConv(newAgent("pin-agent"), "u-1", "Hi"));

        assertIsOk(PUT("/api/conversations/" + id + "/pin", "application/json", ""));

        var unpinnedPage = GET("/api/conversations?pinned=false");
        assertFalse(getContent(unpinnedPage).contains("\"id\":" + id),
                "a pinned row must leave the paginated list: " + getContent(unpinnedPage));
        assertEquals("0", unpinnedPage.getHeader("X-Total-Count"),
                "the total the page quotes must exclude pinned rows too");

        assertTrue(getContent(GET("/api/conversations?pinned=true")).contains("\"id\":" + id));

        assertIsOk(DELETE("/api/conversations/" + id + "/pin"));
        assertTrue(getContent(GET("/api/conversations?pinned=false")).contains("\"id\":" + id),
                "unpinning returns the row to the paginated list");
    }

    /**
     * The chat sidebar and command palette call the list endpoint without a
     * {@code pinned} param and must keep seeing every conversation — the
     * exclusion is opt-in, not the default.
     */
    @Test
    void listWithoutThePinnedParamStillReturnsPinnedRows() {
        login();
        long id = commitInFreshTx(() -> newConv(newAgent("pin-default-agent"), "u-1", "Hi"));

        assertIsOk(PUT("/api/conversations/" + id + "/pin", "application/json", ""));

        assertTrue(getContent(GET("/api/conversations")).contains("\"id\":" + id));
    }

    @Test
    void pinningPastTheCapIs409AndLeavesTheConversationUnpinned() {
        login();
        long[] ids = commitInFreshTx(() -> {
            var agent = newAgent("pin-cap-agent");
            var out = new long[ConversationService.MAX_PINNED + 1];
            for (int i = 0; i < out.length; i++) {
                out[i] = newConv(agent, "u-" + i, "conv " + i);
            }
            return out;
        });

        for (int i = 0; i < ConversationService.MAX_PINNED; i++) {
            assertIsOk(PUT("/api/conversations/" + ids[i] + "/pin", "application/json", ""));
        }

        long overflow = ids[ConversationService.MAX_PINNED];
        var resp = PUT("/api/conversations/" + overflow + "/pin", "application/json", "");
        assertEquals(409, resp.status.intValue(), getContent(resp));
        assertTrue(getContent(resp).contains(String.valueOf(ConversationService.MAX_PINNED)),
                "the refusal names the cap so the UI can repeat it: " + getContent(resp));

        assertTrue(getContent(GET("/api/conversations/" + overflow)).contains("\"pinned\":false"));

        // Re-pinning one that is already pinned is a no-op, not a cap breach.
        assertIsOk(PUT("/api/conversations/" + ids[0] + "/pin", "application/json", ""));
    }

    // ── delete-by-filter scope ────────────────────────────────────────

    @Test
    void deleteByFilterSkipsPinnedConversations() {
        login();
        long[] ids = commitInFreshTx(() -> {
            var agent = newAgent("pin-delete-agent");
            return new long[]{newConv(agent, "u-keep", "keep"), newConv(agent, "u-drop", "drop")};
        });

        assertIsOk(PUT("/api/conversations/" + ids[0] + "/pin", "application/json", ""));

        int deleted = commitInFreshTx(() ->
                ConversationService.deleteByFilter(null, null, null, null, null));
        assertEquals(1, deleted, "only the unpinned conversation is in scope");

        assertTrue(commitInFreshTx(() -> Conversation.findById(ids[0]) != null),
                "a pinned conversation survives an unfiltered bulk delete");
        assertTrue(commitInFreshTx(() -> Conversation.findById(ids[1]) == null));
    }

    @Test
    void deleteByFilterScopedToStarredLeavesTheRestAlone() {
        login();
        long[] ids = commitInFreshTx(() -> {
            var agent = newAgent("star-delete-agent");
            return new long[]{newConv(agent, "u-star", "starred"), newConv(agent, "u-plain", "plain")};
        });

        assertIsOk(PUT("/api/conversations/" + ids[0] + "/star", "application/json", ""));

        int deleted = commitInFreshTx(() ->
                ConversationService.deleteByFilter(null, null, null, null, Boolean.TRUE));
        assertEquals(1, deleted);

        assertTrue(commitInFreshTx(() -> Conversation.findById(ids[0]) == null));
        assertTrue(commitInFreshTx(() -> Conversation.findById(ids[1]) != null));
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static Agent newAgent(String name) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.enabled = true;
        a.save();
        return a;
    }

    private static long newConv(Agent agent, String peer, String preview) {
        var c = ConversationService.create(agent, "web", peer);
        c.preview = preview;
        c.save();
        return c.id;
    }

    /** See {@code ApiConversationsControllerTest.commitInFreshTx} — the request
     *  thread's transaction isn't visible to the HTTP calls under test. */
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
