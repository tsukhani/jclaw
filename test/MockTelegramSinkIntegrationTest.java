import channels.TelegramChannel;
import channels.TelegramStreamingSink;
import org.junit.jupiter.api.*;
import play.test.*;
import services.AgentService;

/**
 * Wire-format integration tests for TelegramStreamingSink using the embedded
 * {@link MockTelegramServer} (JCLAW-96). Complements
 * {@code TelegramStreamingSinkTest}, which stays focused on pure-logic unit
 * coverage (regex classifiers, state transitions, rate-limit semantics).
 *
 * <p>The tests here prove that what we ASSERT about the sink's behavior is
 * actually what HAPPENS on the wire — catching the class of bugs that
 * reflection-based unit tests miss, the same class that surfaced three
 * times in JCLAW-104's prod smoke testing.
 *
 * <p>Each test installs a fresh {@code MockTelegramServer}, points the
 * {@link TelegramChannel} cache at it via
 * {@link TelegramChannel#installForTest}, runs the sink behavior under
 * test, and inspects {@code server.requests()} to verify wire-format
 * outcomes. {@code @AfterEach} tears down both the server and the cache
 * entry so tests are independent.
 */
public class MockTelegramSinkIntegrationTest extends UnitTest {

    private static final String BOT_TOKEN = "mock-bot-token";
    private static final String CHAT_ID = "12345";

    private MockTelegramServer server;
    private models.Agent agent;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        server = new MockTelegramServer();
        server.start();
        TelegramChannel.installForTest(BOT_TOKEN, server.telegramUrl());
        agent = AgentService.create("sink-integration-agent", "openrouter", "gpt-4.1");
    }

    @AfterEach
    void teardown() {
        if (server != null) server.close();
        TelegramChannel.clearForTest(BOT_TOKEN);
    }

    // ==================== Sanity: infrastructure wiring ====================

    @Test
    public void mockServerReceivesSendMessageWhenChannelTargetsIt() {
        boolean ok = TelegramChannel.sendMessage(BOT_TOKEN, CHAT_ID, "ping");
        assertTrue(ok, "sendMessage should succeed against the default-ok mock");
        assertEquals(1, server.countRequests("sendMessage"),
                "exactly one sendMessage request should have landed on the mock server");
    }

    // ==================== Gap 1: re-entrance guard (necessary-AND-sufficient) ====================
    //
    // Pre-JCLAW-96 the existing unit test planted flushInFlight via reflection
    // and asserted state was unchanged. That passed either with or without
    // the guard — if the guard were removed, sendPlaceholder would still
    // throw from the HTTP execute and the catch would leave state unchanged.
    // Wire-level tests prove the guard actually prevents the network call
    // rather than the exception path producing the same observable state.

    // Note: the DRAFT-path re-entrance test was removed alongside DRAFT
    // disablement (JCLAW-121). The EDIT_IN_PLACE variant below covers the
    // same guard — DRAFT and EDIT_IN_PLACE share flushInFlight state.

    @Test
    public void editInPlaceReentranceGuardPreventsSecondSendMessage() throws Exception {
        // EDIT_IN_PLACE variant: first flush sends a placeholder sendMessage;
        // while that's mid-HTTP, a second flush attempt must short-circuit.
        // Without the guard we'd see 2 sendMessages (both as placeholders,
        // because messageId isn't set until the first response lands).
        server.delay(150);
        // chatType=null selects EDIT_IN_PLACE.
        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 1L, null);
        pokePending(sink, "placeholder text");

        var flushMethod = TelegramStreamingSink.class.getDeclaredMethod("flush");
        flushMethod.setAccessible(true);

        var t1 = Thread.ofVirtual().start(() -> invokeQuietly(flushMethod, sink));
        Thread.sleep(10);
        var t2 = Thread.ofVirtual().start(() -> invokeQuietly(flushMethod, sink));

        t1.join(2000);
        t2.join(2000);

        assertEquals(1, server.countRequests("sendMessage"),
                "re-entrance guard must prevent the second EDIT_IN_PLACE flush from sending; "
                        + "requests=" + server.requests());
    }

    // ==================== Gap 3: cross-sink parallelism timing ====================

    @Test
    public void tenConcurrentSinksDoNotSerialize() throws Exception {
        // JCLAW-95 AC3: 10 sinks each with one update(), mock delays each
        // sendMessage by 200ms. Parallel → ~200ms total. Serialized →
        // ~2000ms. Assert under 500ms (generous epsilon for CI jitter).
        //
        // Important: update() itself schedules a flush via the static
        // scheduler with wait=0 (lastSentAt=0 makes the throttle math
        // collapse to zero on a fresh sink), and the scheduled task
        // spawns its own virtual thread per sink. That IS the production
        // parallelism path — no need to also manually invoke flush() via
        // reflection. The earlier version of this test did both, which
        // produced a CI-flaky race: when the scheduler-spawned virtual
        // thread won the per-sink synchronized lock first, the manually-
        // spawned thread short-circuited via the re-entrance guard, its
        // join(3000) returned immediately, and the assertion ran while
        // the in-flight scheduled flushes were still mid-HTTP (count=0).
        // Polling for the count to reach n is both correct (waits for the
        // wire-level outcome rather than a proxy thread) and exercises
        // the real production scheduling path.
        server.delay(200);

        int n = 10;
        var sinks = new TelegramStreamingSink[n];
        for (int i = 0; i < n; i++) {
            // Each sink gets its own conversationId so they don't share
            // ConversationQueue locks. chatType=null selects EDIT_IN_PLACE.
            sinks[i] = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent,
                    (long) (100 + i), null);
        }

        long start = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            sinks[i].update("payload " + i);
        }

        long deadline = start + 3000;
        while (server.countRequests("sendMessage") < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        long elapsedMs = System.currentTimeMillis() - start;

        assertEquals(n, server.countRequests("sendMessage"),
                "each sink should fire exactly one sendMessage placeholder");
        assertTrue(elapsedMs < 500,
                "10 sinks @ 200ms/flush should complete in ~200ms total (parallel), "
                        + "not ~2000ms (serialized). actual=" + elapsedMs + "ms");
    }

    // ==================== Gap 4: adaptive 429 throttle ratchet (wire-level) ====================

    @Test
    public void throttleRatchetsUpOnMockTelegram429() throws Exception {
        // Mock returns 429 with retry_after=1 on every sendMessage. The
        // real flush path catches TelegramApiRequestException and invokes
        // recordFlushFailure which ratchets currentThrottleMs. Previously
        // only unit-tested with a hand-built exception; this proves the
        // SDK's exception shape is what the classifier expects.
        //
        // Post-JCLAW-121: EDIT_IN_PLACE is the only transport, so we
        // throttle sendMessage (the placeholder call) instead of
        // sendMessageDraft.
        server.respondWith("sendMessage", 429,
                "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                        + "\"parameters\":{\"retry_after\":1}}");

        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 2L, null);
        assertEquals(250L, sink.currentThrottleMsForTest(), "fresh sink at floor");

        var flushMethod = TelegramStreamingSink.class.getDeclaredMethod("flush");
        flushMethod.setAccessible(true);

        // Drive four flushes. The ratchet progresses 250 → 500 → 750 → 1000
        // → 1000 (capped). We pokePending before each call so there's
        // something to flush.
        long[] expected = { 500, 750, 1000, 1000 };
        for (int i = 0; i < 4; i++) {
            pokePending(sink, "payload " + i);
            flushMethod.invoke(sink);
            assertEquals(expected[i], sink.currentThrottleMsForTest(),
                    "after 429 #" + (i + 1) + " the ratchet should be at " + expected[i] + "ms");
        }
    }

    // ==================== Gap 5: draft-unsupported runtime fallback ====================
    //
    // The draft-unsupported fallback test was removed alongside DRAFT
    // disablement (JCLAW-121). The classifier itself remains covered by
    // the unit-level draftUnsupportedClassifierRecognizesExpectedPatterns
    // test in TelegramStreamingSinkTest, retained as documented dead code
    // in case Bot API 10.x gains a working draft-clear.

    // ==================== Gap 6: planner-dedupe sendPhoto count ====================

    @Test
    public void duplicateImageReferencesResultInOneSendPhoto() throws Exception {
        // Seed a workspace file so TelegramOutboundPlanner can resolve the URL.
        services.AgentService.writeWorkspaceFile(agent.name, "screenshot-42.png", "png-bytes");
        String url = "/api/agents/" + agent.id + "/files/screenshot-42.png";

        // sendMessage via TelegramChannel (bypassing the sink) exercises
        // TelegramOutboundPlanner end-to-end. Content references the same
        // file twice — once as image, once as plain link — exactly the
        // smoke-test bug JCLAW-104's dedupe fixed.
        String content = "![Screenshot](" + url + ")\n\nHere is the page.\n\n[screenshot](" + url + ")";
        boolean ok = TelegramChannel.sendMessage(BOT_TOKEN, CHAT_ID, content, agent);

        assertTrue(ok, "send should succeed");
        assertEquals(1, server.countRequests("sendPhoto"),
                "planner must dedupe the two references to one sendPhoto; requests="
                        + server.requests());
    }

    // ==================== Gap 7: delivery-failure notifier (JCLAW-106) ====================

    @Test
    public void deliveryFailureTriggersFollowUpNotifier() throws Exception {
        // JCLAW-106 integration coverage: when the final sendMessage fails
        // through all retries, the sink fires a follow-up "please try
        // again" message. Mock returns 500 on sendMessage so Channel.sendWithRetry
        // exhausts its two attempts and returns false.
        server.respondWith("sendMessage", 500,
                "{\"ok\":false,\"error_code\":500,\"description\":\"internal server error\"}");
        TelegramStreamingSink.clearNotifierRateLimiterForTest();

        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 4L, "private");
        sink.seal("final content");

        // The notifier's body should contain the user-facing retry message.
        // Match on a substring that survives markdown-to-HTML formatting
        // (TelegramMarkdownFormatter converts straight apostrophes to curly
        // ones, so "couldn't" on the wire is "couldn\u2019t").
        long notifierBodyCount = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .filter(r -> r.body().contains("deliver it to this chat")
                        && r.body().contains("Please try again"))
                .count();
        assertTrue(notifierBodyCount >= 1,
                "notifier body should land at least once; actual bodies: "
                        + server.requests().stream()
                                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                                .map(MockTelegramServer.RecordedRequest::body)
                                .toList());
    }

    @Test
    public void secondDeliveryFailureWithin60sDoesNotRefireNotifier() throws Exception {
        // JCLAW-106 AC6 second half (wire-level): the rate limiter holds
        // across actual failed seal() calls, not just the unit-test
        // invocations of tryFireNotifier. Two seals back-to-back on the
        // same conversationId must produce no additional notifier bodies
        // beyond what the first seal generated.
        //
        // Note: Channel.sendWithRetry retries once on failure, so a single
        // notifier invocation produces two mock hits with the same body.
        // That's fine — we assert the rate limit by delta, not absolute count:
        // the count of notifier bodies after the second seal must equal the
        // count after the first, proving the second seal's notifier was
        // suppressed.
        server.respondWith("sendMessage", 500,
                "{\"ok\":false,\"error_code\":500,\"description\":\"internal server error\"}");
        TelegramStreamingSink.clearNotifierRateLimiterForTest();

        long convId = 5L;
        new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, convId, "private")
                .seal("first attempt");
        long countAfterFirst = countNotifierBodies(server);
        assertTrue(countAfterFirst >= 1,
                "first seal must fire the notifier at least once; saw " + countAfterFirst);

        new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, convId, "private")
                .seal("second attempt");
        long countAfterBoth = countNotifierBodies(server);

        assertEquals(countAfterFirst, countAfterBoth,
                "rate limiter must suppress the second notifier within 60s; "
                        + "after first=" + countAfterFirst
                        + ", after both=" + countAfterBoth);
    }

    private static long countNotifierBodies(MockTelegramServer server) {
        return server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .filter(r -> r.body().contains("deliver it to this chat"))
                .count();
    }

    // ==================== Helpers ====================

    /**
     * Poke the sink's pending buffer directly via reflection + call update.
     * update() schedules a flush via the scheduler; we invoke flush()
     * directly from tests so we don't have to wait for the throttle delay.
     */
    private static void pokePending(TelegramStreamingSink sink, String text) {
        // update() appends to pending and schedules a flush. We only need
        // the pending contents; the scheduled flush is ignored by the
        // reflection-invoked manual flush below.
        sink.update(text);
    }

    private static void invokeQuietly(java.lang.reflect.Method m, Object target) {
        try {
            m.invoke(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
