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
class MockTelegramSinkIntegrationTest extends UnitTest {

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
    void mockServerReceivesSendMessageWhenChannelTargetsIt() {
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

    @Test
    void editInPlaceReentranceGuardPreventsSecondSendMessage() throws Exception {
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
    void tenConcurrentSinksDoNotSerialize() throws Exception {
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
    void throttleRatchetsUpOnMockTelegram429() throws Exception {
        // Mock returns 429 with retry_after=1 on every sendMessage. The
        // real flush path catches TelegramApiRequestException and invokes
        // recordFlushFailure which ratchets currentThrottleMs. Previously
        // only unit-tested with a hand-built exception; this proves the
        // SDK's exception shape is what the classifier expects.
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

    // ==================== Gap 6: planner-dedupe sendPhoto count ====================

    @Test
    void duplicateImageReferencesResultInOneSendPhoto() {
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
    void deliveryFailureTriggersFollowUpNotifier() {
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
    void secondDeliveryFailureWithin60sDoesNotRefireNotifier() {
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
     * Poke the sink's pending buffer directly via reflection. Skips
     * {@link TelegramStreamingSink#update(String)} on purpose: update()
     * schedules a virtual-thread flush via the static scheduler, which
     * then races with the test's direct {@code flushMethod.invoke(sink)}
     * call. When the scheduler-side flush wins the race for the
     * {@code flushInFlight} re-entrance guard, the test's direct invoke
     * returns early and that iteration's expected ratchet step never
     * fires — surfacing as a flaky "expected 750 but was 500" failure
     * on the {@code throttleRatchetsUpOnMockTelegram429} test under CI
     * load. Mutating the {@code pending} StringBuilder directly bypasses
     * scheduling, so the test's manual flush is the only flush in
     * flight.
     */
    private static void pokePending(TelegramStreamingSink sink, String text) {
        try {
            var f = TelegramStreamingSink.class.getDeclaredField("pending");
            f.setAccessible(true);
            var pending = (StringBuilder) f.get(sink);
            pending.append(text);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokeQuietly(java.lang.reflect.Method m, Object target) {
        try {
            m.invoke(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== JCLAW-325: residual coverage ====================

    @Test
    void sealHappyPathEditsPlaceholderToFinalHtml() throws Exception {
        // Drives the EDIT_IN_PLACE happy path in seal(): a flush set
        // messageId, the final response fits the cap, no media refs, so
        // seal calls editMessage with html=true (line 441). One placeholder
        // sendMessage + one final editMessageText must land on the wire.
        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 7L, "private");

        // Force a placeholder by directly running flush after poking pending.
        pokePending(sink, "live preview tokens");
        var flushMethod = TelegramStreamingSink.class.getDeclaredMethod("flush");
        flushMethod.setAccessible(true);
        flushMethod.invoke(sink);

        assertNotNull(sink.messageIdForTest(),
                "first flush must set messageId from the mock placeholder response");
        int placeholderCount = (int) server.countRequests("sendMessage");
        assertTrue(placeholderCount >= 1, "placeholder sendMessage must have landed");

        // Seal with plain text well under the cap — editMessage path.
        sink.seal("This is the **final** response.");

        assertTrue(sink.sealedForTest());
        assertTrue(server.countRequests("editMessageText") >= 1,
                "seal must dispatch one editMessageText to swap to HTML");
    }

    @Test
    void sealWithMediaReferencesDeletesPlaceholderAndRoutesThroughPlanner() throws Exception {
        // Drives lines 411-422: messageId != null + containsMediaOrFileRefs
        // → delete placeholder then route via planner. Seed a workspace file
        // so the planner has something concrete to dispatch as a photo.
        services.AgentService.writeWorkspaceFile(agent.name, "scrn-325.png", "png-bytes");
        String url = "/api/agents/" + agent.id + "/files/scrn-325.png";

        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 8L, "private");
        pokePending(sink, "preview text");
        var flushMethod = TelegramStreamingSink.class.getDeclaredMethod("flush");
        flushMethod.setAccessible(true);
        flushMethod.invoke(sink);
        assertNotNull(sink.messageIdForTest(),
                "placeholder must be sent before the media-bearing seal");

        // Final response carries an image markdown — needsPlanner = true.
        sink.seal("Here is the screenshot:\n\n![Screenshot](" + url + ")\n\nDone.");

        assertTrue(server.countRequests("deleteMessage") >= 1,
                "media-bearing seal must delete the live placeholder");
        assertTrue(server.countRequests("sendPhoto") >= 1,
                "planner must dispatch a sendPhoto for the workspace image");
    }

    @Test
    void errorFallbackDeletesPlaceholderAndSendsErrorMessage() throws Exception {
        // Drives lines 517-523: errorFallback with a placeholder already
        // sent. Must delete and emit the fixed error string. Distinct from
        // seal's notifier path — error text differs.
        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 9L, "private");
        pokePending(sink, "partial response");
        var flushMethod = TelegramStreamingSink.class.getDeclaredMethod("flush");
        flushMethod.setAccessible(true);
        flushMethod.invoke(sink);
        assertNotNull(sink.messageIdForTest());

        int beforeSends = (int) server.countRequests("sendMessage");

        sink.errorFallback(new RuntimeException("boom"));

        assertTrue(server.countRequests("deleteMessage") >= 1,
                "errorFallback must delete the placeholder");
        boolean sawErrorBody = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .anyMatch(r -> r.body().contains("an error occurred")
                        || r.body().contains("processing your message"));
        assertTrue(sawErrorBody,
                "errorFallback must emit a user-facing error sendMessage; bodies="
                        + server.requests().stream()
                                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                                .map(MockTelegramServer.RecordedRequest::body)
                                .toList());
        assertTrue(server.countRequests("sendMessage") > beforeSends,
                "errorFallback must dispatch at least one new sendMessage");
    }

    @Test
    void typingHeartbeatFiresImmediatelyWhenStarted() throws Exception {
        // AC: typingHeartbeat lifecycle — start on first flush is not how
        // jclaw works (the heartbeat starts BEFORE the LLM call from
        // AgentRunner). Verify the heartbeat actually hits the wire when
        // started, then cancels on seal so no more pulses arrive.
        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 10L, "private");
        sink.startTypingHeartbeat();
        assertTrue(sink.typingHeartbeatActiveForTest());

        // Wait briefly for the first scheduled fire (initialDelay=0L) to
        // reach the mock. The scheduler hop + VT spawn + HTTP is fast on
        // loopback.
        long deadline = System.currentTimeMillis() + 1000;
        while (server.countRequests("sendChatAction") == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(server.countRequests("sendChatAction") >= 1,
                "typing heartbeat must fire at least one sendChatAction");

        sink.seal("");
        assertFalse(sink.typingHeartbeatActiveForTest(),
                "seal must cancel the heartbeat");

        long countAtSeal = server.countRequests("sendChatAction");
        Thread.sleep(150);
        assertEquals(countAtSeal, server.countRequests("sendChatAction"),
                "no further sendChatAction after seal — heartbeat fully cancelled");
    }

    // ==================== JCLAW-369: reply target + topic-aware sink ====================

    @Test
    void placeholderSendCarriesReplyTargetAndTopicThread() throws Exception {
        // The streaming placeholder is the turn's first message, so under the
        // default reply mode (first) it must carry reply_parameters for the
        // inbound message id, and message_thread_id for the (non-General) topic.
        play.Play.configuration.remove("telegram.replyTo.mode"); // → default "first"
        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 11L, "supergroup",
                4321, 88);
        pokePending(sink, "live preview tokens");
        var flushMethod = TelegramStreamingSink.class.getDeclaredMethod("flush");
        flushMethod.setAccessible(true);
        flushMethod.invoke(sink);

        assertNotNull(sink.messageIdForTest(), "first flush must send a placeholder");
        String placeholderBody = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .map(MockTelegramServer.RecordedRequest::body)
                .reduce("", (a, b) -> a + b);
        assertTrue(placeholderBody.contains("reply_parameters") && placeholderBody.contains("4321"),
                "placeholder must reply to the inbound message id; body=" + placeholderBody);
        assertTrue(placeholderBody.contains("allow_sending_without_reply"),
                "placeholder reply must set allow_sending_without_reply");
        assertTrue(placeholderBody.contains("message_thread_id") && placeholderBody.contains("88"),
                "placeholder must carry the non-General topic thread id");
    }

    @Test
    void placeholderSendOmitsReplyAndThreadForLegacySink() throws Exception {
        // AC4 wire proof: a legacy sink (no reply target, no topic) sends a
        // plain placeholder with neither field — unchanged behavior.
        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 12L, "private");
        pokePending(sink, "plain preview");
        var flushMethod = TelegramStreamingSink.class.getDeclaredMethod("flush");
        flushMethod.setAccessible(true);
        flushMethod.invoke(sink);

        String body = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendMessage"))
                .map(MockTelegramServer.RecordedRequest::body)
                .reduce("", (a, b) -> a + b);
        assertFalse(body.contains("reply_parameters"),
                "legacy sink placeholder must omit reply_parameters");
        assertFalse(body.contains("message_thread_id"),
                "legacy sink placeholder must omit message_thread_id");
    }

    // ==================== JCLAW-375: ack-reaction lifecycle ====================

    @Test
    void ackReactionSetsWorkingOnStartAndSuccessOnSealWhenEnabled() {
        // AC2: ackReaction=on → 👀 on turn start (constructor), ✅ on seal.
        // The triggering message is the sink's replyToMessageId (= 4321 here).
        play.Play.configuration.setProperty("telegram.ackReaction", "on");
        try {
            var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 20L, "private",
                    4321, null);
            // Constructor already placed the working reaction.
            assertEquals(1, server.countRequests("setMessageReaction"),
                    "turn-start must place the working reaction");
            assertReactionBody(server, "👀", 4321); // 👀

            sink.seal("All done.");
            assertEquals(2, server.countRequests("setMessageReaction"),
                    "seal must replace the working reaction with a success reaction");
            assertReactionBody(server, "✅", 4321); // ✅
        } finally {
            play.Play.configuration.remove("telegram.ackReaction");
        }
    }

    @Test
    void ackReactionSetsErrorOnErrorFallbackWhenEnabled() {
        // AC2: ackReaction=on → ❌ on errorFallback.
        play.Play.configuration.setProperty("telegram.ackReaction", "on");
        try {
            var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 21L, "private",
                    777, null);
            assertEquals(1, server.countRequests("setMessageReaction"),
                    "turn-start working reaction");
            sink.errorFallback(new RuntimeException("boom"));
            assertEquals(2, server.countRequests("setMessageReaction"),
                    "errorFallback must place the error reaction");
            assertReactionBody(server, "❌", 777); // ❌
        } finally {
            play.Play.configuration.remove("telegram.ackReaction");
        }
    }

    @Test
    void ackReactionDisabledByDefaultSendsNoReaction() {
        // AC2: default off — no setMessageReaction at any point in the lifecycle.
        play.Play.configuration.remove("telegram.ackReaction"); // → default off
        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 22L, "private",
                999, null);
        sink.seal("done");
        assertEquals(0, server.countRequests("setMessageReaction"),
                "ack lifecycle off by default — no reaction calls; requests=" + server.requests());
    }

    @Test
    void ackReactionNoOpWhenReplyTargetIsNull() {
        // AC2: no-op when replyToMessageId is null even if the feature is on —
        // there is no message to react to.
        play.Play.configuration.setProperty("telegram.ackReaction", "on");
        try {
            var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 23L, "private");
            sink.seal("done");
            assertEquals(0, server.countRequests("setMessageReaction"),
                    "null reply target must suppress the ack lifecycle; requests=" + server.requests());
        } finally {
            play.Play.configuration.remove("telegram.ackReaction");
        }
    }

    private static void assertReactionBody(MockTelegramServer server, String emojiEscape, int messageId) {
        boolean found = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("setMessageReaction"))
                .anyMatch(r -> r.body().contains(emojiEscape)
                        && r.body().contains("\"message_id\":" + messageId));
        assertTrue(found, "expected a setMessageReaction carrying " + emojiEscape
                + " for message " + messageId + "; bodies=" + server.requests().stream()
                        .filter(r -> r.method().equalsIgnoreCase("setMessageReaction"))
                        .map(MockTelegramServer.RecordedRequest::body).toList());
    }

    @Test
    void typingHeartbeatCarriesTopicThreadIncludingGeneral() throws Exception {
        // AC3: the typing heartbeat scopes the indicator to the topic, with
        // General (thread id 1) INCLUDED — unlike sends.
        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 13L, "supergroup",
                null, 1);
        sink.startTypingHeartbeat();
        long deadline = System.currentTimeMillis() + 1000;
        while (server.countRequests("sendChatAction") == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(server.countRequests("sendChatAction") >= 1,
                "typing heartbeat must fire at least once");
        String body = server.requests().stream()
                .filter(r -> r.method().equalsIgnoreCase("sendChatAction"))
                .map(MockTelegramServer.RecordedRequest::body)
                .reduce("", (a, b) -> a + b);
        assertTrue(body.contains("message_thread_id"),
                "typing action must carry the topic thread id (General included)");
        sink.seal("");
    }

    // ==================== JCLAW-384: streamed reply lands in the bot-sent-id cache ====================

    @Test
    void streamedReplyIsRecordedInBotSentIdCache() throws Exception {
        // The streamed reply bubble is sent by the sink's placeholder path, not
        // TelegramChannel's direct send methods, so before JCLAW-384 it never
        // reached the bot-sent-id cache and notify=own under-notified on it.
        // After a placeholder send, wasSentByBot(token, chat, thatId) must be true.
        var sink = new TelegramStreamingSink(BOT_TOKEN, CHAT_ID, agent, 30L, "private");

        // Cold cache: the reply id is unknown before any send.
        assertFalse(TelegramChannel.wasSentByBot(BOT_TOKEN, CHAT_ID, 1),
                "cache must be cold before the placeholder send");

        // Force the placeholder send (sets messageId from the mock response).
        pokePending(sink, "live preview tokens");
        var flushMethod = TelegramStreamingSink.class.getDeclaredMethod("flush");
        flushMethod.setAccessible(true);
        flushMethod.invoke(sink);

        Integer replyId = sink.messageIdForTest();
        assertNotNull(replyId, "placeholder send must set the reply message id");
        assertTrue(TelegramChannel.wasSentByBot(BOT_TOKEN, CHAT_ID, replyId),
                "the streamed reply message id must be recorded in the bot-sent-id cache");

        // Sealing to the happy-path edit keeps the same id recorded.
        sink.seal("This is the **final** response.");
        assertTrue(TelegramChannel.wasSentByBot(BOT_TOKEN, CHAT_ID, replyId),
                "the recorded reply id must survive seal's in-place HTML edit");
    }
}
