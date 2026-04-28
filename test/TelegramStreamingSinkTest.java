import channels.TelegramStreamingSink;
import org.junit.jupiter.api.*;
import play.test.*;

/**
 * Unit tests for {@link TelegramStreamingSink}. We exercise the pure-logic
 * paths — image stripping, state transitions, cap detection, sealed-idempotence
 * — without making Telegram HTTP calls. End-to-end edit-loop tests that need
 * a live Telegram API are deferred to a follow-up functional test; the
 * network seams here are thin enough that unit-level tests give us most of
 * the confidence with none of the flakiness.
 */
public class TelegramStreamingSinkTest extends UnitTest {

    // === stripImageRefs — the live-preview image filter ===

    @Test
    public void stripImageRefsRemovesMarkdownImages() {
        // Note: surrounding whitespace is preserved (single-pass regex over
        // the image token). Matches OpenClaw's behavior — the live preview
        // gets the text minus the image markup; seal-time delivery via the
        // planner is where images actually appear as media messages.
        assertEquals("before  after",
                TelegramStreamingSink.stripImageRefs("before ![](x.png) after"));
        assertEquals("Here: ",
                TelegramStreamingSink.stripImageRefs("Here: ![alt text](path/to/img.jpg)"));
    }

    @Test
    public void stripImageRefsRemovesMultipleImages() {
        var input = "One ![a](1.png) two ![b](2.png) three";
        assertEquals("One  two  three",
                TelegramStreamingSink.stripImageRefs(input));
    }

    @Test
    public void stripImageRefsLeavesRegularLinksAlone() {
        // Plain markdown links ([text](url)) — not images — must pass through
        // so the seal-time planner can see them.
        var input = "See [the docs](https://example.com/docs) for more.";
        assertEquals(input, TelegramStreamingSink.stripImageRefs(input));
    }

    @Test
    public void stripImageRefsLeavesFileReferencesAlone() {
        // JClaw workspace-file convention is [label](<path>) — the angle
        // brackets mean this isn't an image and must survive streaming so
        // the seal path's TelegramOutboundPlanner can still handle it.
        var input = "Check [report.pdf](<workspace/report.pdf>) for details.";
        assertEquals(input, TelegramStreamingSink.stripImageRefs(input));
    }

    @Test
    public void stripImageRefsHandlesNullAndEmpty() {
        assertEquals("", TelegramStreamingSink.stripImageRefs(null));
        assertEquals("", TelegramStreamingSink.stripImageRefs(""));
    }

    @Test
    public void stripImageRefsPreservesContentWithoutImages() {
        var input = "Plain text with **bold** and _italic_ and a `code` block.";
        assertEquals(input, TelegramStreamingSink.stripImageRefs(input));
    }

    // === update / state transitions ===

    @Test
    public void updateWithNullOrEmptyIsNoOp() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update(null);
        sink.update("");
        // No flush scheduled → no placeholder sent → messageId stays null
        assertNull(sink.messageIdForTest());
        assertFalse(sink.streamCapReachedForTest());
        assertFalse(sink.sealedForTest());
    }

    @Test
    public void updateBeyond4096CharsTripsStreamCap() {
        // Accumulating past the Telegram message cap stops live streaming.
        // seal() will then fall back to the planner path regardless of how
        // the final formatted response looks. This is the JCLAW-94 AC: "If
        // the response exceeds 4096 characters, streaming stops at the cap".
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update("x".repeat(4097));
        assertTrue(sink.streamCapReachedForTest(),
                "4097-char update must flip the cap flag");
    }

    @Test
    public void updateExactlyAtCapDoesNotTripFlag() {
        // Boundary: 4096 chars is the hard cap; at-or-below is fine.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update("x".repeat(4096));
        assertFalse(sink.streamCapReachedForTest(),
                "4096-char update must stay within the cap");
    }

    @Test
    public void updateIgnoredAfterCapReached() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update("x".repeat(4097));
        assertTrue(sink.streamCapReachedForTest());

        // Further updates must not change state — no retry, no reset.
        sink.update("more");
        assertTrue(sink.streamCapReachedForTest(),
                "cap is sticky: further updates must not clear it");
    }

    // === seal lifecycle ===

    @Test
    public void sealMarksSinkSealedEvenWhenNoFinalResponse() {
        // Defensive: a null/empty final text still has to terminate the
        // sink cleanly (otherwise a later update would still try to flush).
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal(null);
        assertTrue(sink.sealedForTest(),
                "seal(null) still flips the sealed flag");
    }

    @Test
    public void sealIsIdempotent() {
        // Two completion callbacks firing shouldn't double-send the error
        // message. The CAS-gated sealed flag guarantees at-most-once seal.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal(null);
        // Second seal is a no-op — no exception, flag stays set.
        sink.seal("something else");
        assertTrue(sink.sealedForTest());
    }

    @Test
    public void sealAfterCapGoesThroughPlannerPath() {
        // Preparation: trip the cap first. This sink never sent a
        // placeholder (messageId stayed null), so the planner path won't
        // try to delete anything — it'll just send the final response as
        // a fresh message via TelegramChannel.sendMessage.
        //
        // We can't assert the Telegram API was called here without a mock,
        // so this test documents the precondition: the sink reaches
        // seal() in its "cap tripped, never flushed" state with messageId
        // still null.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update("y".repeat(5000));
        assertTrue(sink.streamCapReachedForTest());
        assertNull(sink.messageIdForTest(),
                "overflow before any flush must leave messageId null — "
                        + "seal will deliver via the planner, no placeholder to delete");
    }

    // === errorFallback lifecycle ===

    @Test
    public void errorFallbackIsIdempotent() {
        // Two error callbacks shouldn't double-delete a placeholder or
        // double-send the user-facing error. Same CAS guarantee as seal.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        // Calling errorFallback without any prior activity — messageId is
        // null so no delete attempt, no network call; flips the sealed flag.
        sink.errorFallback(new RuntimeException("first"));
        assertTrue(sink.sealedForTest());
        // Second call: no-op.
        sink.errorFallback(new RuntimeException("second"));
        assertTrue(sink.sealedForTest());
    }

    @Test
    public void sealAndErrorFallbackAreMutuallyExclusive() {
        // If seal wins the CAS, a subsequent error callback is a no-op
        // (and vice versa). Prevents duplicate error messages to the user
        // when the LLM layer fires both onComplete and onError (which
        // shouldn't happen but has in past edge cases).
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal(null);
        sink.errorFallback(new RuntimeException("late"));
        assertTrue(sink.sealedForTest(),
                "sealed state holds against a trailing error callback");
    }

    // === JCLAW-95: flushInFlight re-entrance ===
    //
    // The reflection-based test that lived here was superseded by
    // MockTelegramSinkIntegrationTest.draftReentranceGuardPreventsSecondSendMessageDraft
    // and editInPlaceReentranceGuardPreventsSecondSendMessage (JCLAW-96).
    // The old test asserted state-unchanged after a planted-guard flush,
    // which passed whether or not the guard actually short-circuited —
    // an exception from the HTTP execute path would have left state
    // unchanged too. The wire-level versions count outbound requests on
    // a mock server, which is necessary-and-sufficient: if the guard
    // were removed, both concurrent flushes would land as real HTTP
    // calls and the assertion would fail.

    @Test
    public void awaitInFlightFlushWakesImmediatelyOnFutureCompletion() throws Exception {
        // JCLAW-100: the old awaitInFlightFlush polled flushInFlight in a
        // 20 ms sleep loop. Replaced with a CompletableFuture the flush
        // completes on exit, so seal() wakes the instant the flush returns.
        // Simulate an in-flight flush, kick off an awaiter on another VT,
        // complete the future, and assert the awaiter returned in well under
        // the 2 s cap (not a multiple of the old 20 ms tick).
        var sink = new TelegramStreamingSink("tok", "chat", null);

        var flushInFlight = TelegramStreamingSink.class.getDeclaredField("flushInFlight");
        flushInFlight.setAccessible(true);
        var future = new java.util.concurrent.CompletableFuture<Void>();
        flushInFlight.set(sink, future);

        var awaitMethod = TelegramStreamingSink.class.getDeclaredMethod("awaitInFlightFlush");
        awaitMethod.setAccessible(true);

        long startNs = System.nanoTime();
        var awaitThread = Thread.ofVirtual().start(() -> {
            try { awaitMethod.invoke(sink); }
            catch (Exception e) { throw new RuntimeException(e); }
        });

        // Let the awaiter enter the future.get() call, then complete it.
        Thread.sleep(10);
        future.complete(null);
        awaitThread.join(500);

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertFalse(awaitThread.isAlive(), "awaitInFlightFlush should return after future completes");
        assertTrue(elapsedMs < 250,
                "awaitInFlightFlush should wake promptly on future completion (took " + elapsedMs + " ms)");
    }

    // === JCLAW-106: delivery-failure notifier rate limiter ===

    @Test
    public void tryFireNotifierReturnsFalseForNullConversationId() {
        // Test sinks constructed without a conversation (the two
        // non-full constructors) pass null. We have no key to rate-limit
        // against, so the notifier must decline to fire rather than
        // accidentally sending a "your delivery failed" message from a
        // sink that has no user context.
        TelegramStreamingSink.clearNotifierRateLimiterForTest();
        assertFalse(TelegramStreamingSink.tryFireNotifier(null),
                "null conversationId must decline to fire");
    }

    @Test
    public void tryFireNotifierFiresOnceThenRateLimits() {
        TelegramStreamingSink.clearNotifierRateLimiterForTest();
        // First call within the window: fires.
        assertTrue(TelegramStreamingSink.tryFireNotifier(12345L),
                "first call for a fresh conversation must fire");
        // Second call within the 60s window: suppressed.
        assertFalse(TelegramStreamingSink.tryFireNotifier(12345L),
                "second call within the 60s window must suppress");
    }

    @Test
    public void tryFireNotifierIsolatedPerConversation() {
        // One conversation's rate limit shouldn't block another's. This
        // matters during a Telegram outage: every affected chat should
        // still get its first notification, even if they fail
        // simultaneously.
        TelegramStreamingSink.clearNotifierRateLimiterForTest();
        assertTrue(TelegramStreamingSink.tryFireNotifier(111L));
        assertTrue(TelegramStreamingSink.tryFireNotifier(222L));
        assertTrue(TelegramStreamingSink.tryFireNotifier(333L));
    }

    @Test
    public void tryFireNotifierReFiresAfterRateWindow() throws Exception {
        // Simulate time passing: we can't actually wait 61s in a unit
        // test, so we backdate the last-fired timestamp via reflection.
        // A genuine concern this guards against: a bug that sets the
        // timestamp but never lets it age out would manifest as a
        // conversation that can only ever fire once per JVM lifetime.
        TelegramStreamingSink.clearNotifierRateLimiterForTest();
        assertTrue(TelegramStreamingSink.tryFireNotifier(999L));

        var mapField = TelegramStreamingSink.class
                .getDeclaredField("LAST_NOTIFIER_FIRE_MS");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.concurrent.ConcurrentHashMap<Long, Long>)
                mapField.get(null);
        // Backdate to 61s ago so the window has elapsed.
        map.put(999L, System.currentTimeMillis() - 61_000);

        assertTrue(TelegramStreamingSink.tryFireNotifier(999L),
                "should fire again once the rate window has elapsed");
    }

    // === JCLAW-121: transport is always EDIT_IN_PLACE ===
    //
    // DRAFT transport was disabled after we discovered Bot API 9.5 has no
    // working draft-clear — sendMessageDraft with empty text is rejected HTTP
    // 400 by both the SDK validator and the server, leaving stale draft
    // bubbles visible for several seconds until Telegram's client cache
    // auto-expires. These tests now pin the "always EDIT_IN_PLACE" contract.

    @Test
    public void transportIsEditInPlaceWhenChatTypeOmitted() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        assertEquals(TelegramStreamingSink.Transport.EDIT_IN_PLACE,
                sink.transportForTest());
    }

    @Test
    public void transportIsEditInPlaceForPrivateChats() {
        var sink = new TelegramStreamingSink("tok", "12345", null, null, "private");
        assertEquals(TelegramStreamingSink.Transport.EDIT_IN_PLACE,
                sink.transportForTest());
    }

    @Test
    public void transportIsEditInPlaceForGroupSupergroupAndChannel() {
        for (String type : new String[] { "group", "supergroup", "channel" }) {
            var sink = new TelegramStreamingSink("tok", "chat", null, null, type);
            assertEquals(TelegramStreamingSink.Transport.EDIT_IN_PLACE,
                    sink.transportForTest(),
                    "chat.type=" + type + " must select EDIT_IN_PLACE (DRAFT disabled)");
        }
    }

    @Test
    public void transportIsEditInPlaceForNullOrBlankChatType() {
        // Null / blank chatType still produces EDIT_IN_PLACE — same contract
        // as when DRAFT was active, just now universal.
        assertEquals(TelegramStreamingSink.Transport.EDIT_IN_PLACE,
                new TelegramStreamingSink("tok", "chat", null, null, null)
                        .transportForTest());
        assertEquals(TelegramStreamingSink.Transport.EDIT_IN_PLACE,
                new TelegramStreamingSink("tok", "chat", null, null, "")
                        .transportForTest());
        assertEquals(TelegramStreamingSink.Transport.EDIT_IN_PLACE,
                new TelegramStreamingSink("tok", "chat", null, null, "   ")
                        .transportForTest());
    }

    @Test
    public void draftWasSentIsFalseOnFreshSink() {
        var sink = new TelegramStreamingSink("tok", "chat", null, null, "private");
        assertFalse(sink.draftWasSentForTest(),
                "a fresh DRAFT sink has not yet saved a draft");
    }

    @Test
    public void draftUnsupportedClassifierRecognizesExpectedPatterns() throws Exception {
        // JCLAW-103: when sendMessageDraft returns a 400 matching either
        // DRAFT_METHOD_UNAVAILABLE_RE or DRAFT_CHAT_UNSUPPORTED_RE, the sink
        // must transition to EDIT_IN_PLACE. Verify the classifier directly
        // via reflection — the sink's runtime fallback path is driven off it.
        var classifier = TelegramStreamingSink.class
                .getDeclaredMethod("isDraftUnsupported",
                        org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException.class);
        classifier.setAccessible(true);

        String[] unsupportedMessages = {
                "method sendMessageDraft can be used only in private chats",
                "400: Bad Request: method not found",
                "unknown method",
                "unsupported",
                "can't be used in this chat",
        };
        for (String msg : unsupportedMessages) {
            var ex = new org.telegram.telegrambots.meta.exceptions
                    .TelegramApiRequestException(msg);
            assertEquals(true, classifier.invoke(null, ex),
                    "should classify '" + msg + "' as draft-unsupported");
        }

        // Unrelated errors must NOT be classified as draft-unsupported
        // (they should propagate via the normal 429/other-error path).
        String[] unrelatedMessages = {
                "Too Many Requests",
                "Bad Request: chat not found",
                "Forbidden: bot was blocked by the user",
        };
        for (String msg : unrelatedMessages) {
            var ex = new org.telegram.telegrambots.meta.exceptions
                    .TelegramApiRequestException(msg);
            assertEquals(false, classifier.invoke(null, ex),
                    "must not classify '" + msg + "' as draft-unsupported");
        }
    }

    // === JCLAW-100: adaptive throttle ratchet ===

    @Test
    public void throttleStartsAtMinimum() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        assertEquals(250L, sink.currentThrottleMsForTest(),
                "fresh sink should start at the 250 ms minimum");
    }

    @Test
    public void throttleRatchetsUpOn429RetryAfter() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        // 250 → 500 → 750 → 1000 → 1000 (capped).
        long[] expected = { 500, 750, 1000, 1000 };
        for (int i = 0; i < 4; i++) {
            sink.recordFlushFailure(buildTelegram429(1));
            assertEquals(expected[i], sink.currentThrottleMsForTest(),
                    "step " + (i + 1) + " after 429");
        }
    }

    @Test
    public void throttleStaysUnchangedOnNon429Failure() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.recordFlushFailure(new RuntimeException("connection reset"));
        assertEquals(250L, sink.currentThrottleMsForTest(),
                "non-rate-limit failure must not touch the cadence");
    }

    @Test
    public void throttleStaysUnchangedOn429WithoutRetryAfter() {
        // A TelegramApiRequestException without parameters (or with a
        // zero/missing retry_after) is not a trustworthy rate-limit signal —
        // treat it as a generic failure and leave the cadence alone.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        var ex = new org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException(
                "rate-ish but no params");
        sink.recordFlushFailure(ex);
        assertEquals(250L, sink.currentThrottleMsForTest());
    }

    /**
     * Build a {@code TelegramApiRequestException} with a populated
     * {@code ResponseParameters} carrying the supplied retry-after — the
     * shape the SDK produces when Telegram's Bot API returns a 429 with a
     * {@code retry_after} field in its JSON body.
     */
    private static org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
            buildTelegram429(int retryAfterSec) {
        var params = new org.telegram.telegrambots.meta.api.objects.ResponseParameters();
        params.setRetryAfter(retryAfterSec);
        var apiResponse = new org.telegram.telegrambots.meta.api.objects.ApiResponse<Object>(
                false, 429, "Too Many Requests", params, null);
        return new org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException(
                "Too Many Requests", apiResponse);
    }

    @Test
    public void awaitInFlightFlushReturnsImmediatelyWhenIdle() throws Exception {
        // When no flush is in progress (flushInFlight == null), seal() must
        // fall through without any wait at all.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        // Field is null by default; no setup needed.

        var awaitMethod = TelegramStreamingSink.class.getDeclaredMethod("awaitInFlightFlush");
        awaitMethod.setAccessible(true);

        long startNs = System.nanoTime();
        awaitMethod.invoke(sink);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertTrue(elapsedMs < 50,
                "awaitInFlightFlush on idle sink should be near-instant (took " + elapsedMs + " ms)");
    }

    // === JCLAW-98: typing heartbeat lifecycle ===

    @Test
    public void typingHeartbeatStartsAndIsCancelledBySeal() {
        // startTypingHeartbeat must schedule the indicator; seal() must
        // cancel it so no further sendChatAction calls fire after the
        // response is delivered.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.startTypingHeartbeat();
        assertTrue(sink.typingHeartbeatActiveForTest(),
                "startTypingHeartbeat must schedule the heartbeat");

        sink.seal("");
        assertFalse(sink.typingHeartbeatActiveForTest(),
                "seal must cancel the typing heartbeat");
    }

    @Test
    public void typingHeartbeatCancelledByCancel() {
        // /stop fires ConversationQueue.cancellationFlag, the streaming thread
        // early-returns out of runStreaming, and runStreaming routes through
        // sink.cancel() (wired as onCancel). cancel() must stop the heartbeat
        // so the Telegram client doesn't keep showing "typing..." forever.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.startTypingHeartbeat();
        assertTrue(sink.typingHeartbeatActiveForTest());

        sink.cancel();
        assertFalse(sink.typingHeartbeatActiveForTest(),
                "cancel must stop the typing heartbeat");
        assertTrue(sink.sealedForTest(),
                "cancel must mark the sink sealed so late update() calls no-op");
    }

    @Test
    public void cancelIsIdempotent() {
        // Multiple checkCancelled checkpoints fire onCancel each time; cancel()
        // must therefore tolerate repeated invocation without exploding or
        // re-arming any state.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.startTypingHeartbeat();

        sink.cancel();
        sink.cancel(); // second call must be a no-op, not throw
        assertFalse(sink.typingHeartbeatActiveForTest());
    }

    @Test
    public void cancelAfterSealIsNoOp() {
        // A sink that finished naturally (seal) and then gets a late
        // cancel signal must not be re-sealed or otherwise disturbed.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.startTypingHeartbeat();
        sink.seal("");
        assertTrue(sink.sealedForTest());

        sink.cancel(); // must be a no-op since sealed.compareAndSet returns false
        assertTrue(sink.sealedForTest());
        assertFalse(sink.typingHeartbeatActiveForTest());
    }

    @Test
    public void typingHeartbeatCancelledByErrorFallback() {
        // Error path must also cancel — the error message send will
        // replace the typing indicator, so leaving the heartbeat running
        // would produce stale "typing" pulses after the user already sees
        // the error reply.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.startTypingHeartbeat();
        assertTrue(sink.typingHeartbeatActiveForTest());

        sink.errorFallback(new RuntimeException("boom"));
        assertFalse(sink.typingHeartbeatActiveForTest(),
                "errorFallback must cancel the typing heartbeat");
    }

    @Test
    public void typingHeartbeatCancelledByFirstUpdate() {
        // The first visible-content update means the placeholder is about
        // to land and replace the indicator. Stop the heartbeat so we
        // don't waste API calls during the subsequent edit-loop.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.startTypingHeartbeat();
        assertTrue(sink.typingHeartbeatActiveForTest());

        sink.update("first tokens");
        assertFalse(sink.typingHeartbeatActiveForTest(),
                "update must cancel the typing heartbeat on first call");
    }

    @Test
    public void typingHeartbeatDoubleStartIsNoOp() {
        // Idempotence: calling startTypingHeartbeat twice must not
        // orphan the first scheduled future or produce two competing
        // heartbeats.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.startTypingHeartbeat();
        var firstActive = sink.typingHeartbeatActiveForTest();
        sink.startTypingHeartbeat();  // no-op
        assertTrue(firstActive && sink.typingHeartbeatActiveForTest(),
                "second startTypingHeartbeat must be a no-op with the first still running");

        sink.seal("");
    }

    @Test
    public void typingHeartbeatNotStartedAfterSeal() {
        // If something triggers startTypingHeartbeat after seal has
        // completed, it must be a no-op — the conversation is already
        // over, no need to show "typing".
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal("");
        sink.startTypingHeartbeat();
        assertFalse(sink.typingHeartbeatActiveForTest(),
                "startTypingHeartbeat must no-op when sink is already sealed");
    }
}
