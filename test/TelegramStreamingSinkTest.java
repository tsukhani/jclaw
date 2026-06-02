import channels.TelegramChannel;
import channels.TelegramStreamingSink;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import play.test.*;

/**
 * Unit tests for {@link TelegramStreamingSink}. We exercise the pure-logic
 * paths — image stripping, state transitions, cap detection, sealed-idempotence
 * — without making Telegram HTTP calls. End-to-end edit-loop tests that need
 * a live Telegram API are deferred to a follow-up functional test; the
 * network seams here are thin enough that unit-level tests give us most of
 * the confidence with none of the flakiness.
 */
class TelegramStreamingSinkTest extends UnitTest {

    // === stripImageRefs — the live-preview image filter ===

    @Test
    void stripImageRefsRemovesMarkdownImages() {
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
    void stripImageRefsRemovesMultipleImages() {
        var input = "One ![a](1.png) two ![b](2.png) three";
        assertEquals("One  two  three",
                TelegramStreamingSink.stripImageRefs(input));
    }

    /**
     * Three "no images present" inputs all pass through stripImageRefs
     * unchanged: a plain markdown link, the workspace-file
     * [label](&lt;path&gt;) convention, and a string with bold/italic/code
     * but no image markup.
     */
    @ParameterizedTest(name = "stripImageRefsLeaves[{0}]Alone")
    @CsvSource(delimiter = '|', value = {
            "RegularLink      | See [the docs](https://example.com/docs) for more.",
            "FileReference    | Check [report.pdf](<workspace/report.pdf>) for details.",
            "NoImagesContent  | Plain text with **bold** and _italic_ and a `code` block."
    })
    void stripImageRefsLeavesNonImageContentAlone(String label, String input) {
        assertEquals(input, TelegramStreamingSink.stripImageRefs(input));
    }

    @Test
    void stripImageRefsHandlesNullAndEmpty() {
        assertEquals("", TelegramStreamingSink.stripImageRefs(null));
        assertEquals("", TelegramStreamingSink.stripImageRefs(""));
    }

    // === update / state transitions ===

    @Test
    void updateWithNullOrEmptyIsNoOp() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update(null);
        sink.update("");
        // No flush scheduled → no placeholder sent → messageId stays null
        assertNull(sink.messageIdForTest());
        assertFalse(sink.streamCapReachedForTest());
        assertFalse(sink.sealedForTest());
    }

    @Test
    void updateBeyond4096CharsTripsStreamCap() {
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
    void updateExactlyAtCapDoesNotTripFlag() {
        // Boundary: 4096 chars is the hard cap; at-or-below is fine.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.update("x".repeat(4096));
        assertFalse(sink.streamCapReachedForTest(),
                "4096-char update must stay within the cap");
    }

    @Test
    void updateIgnoredAfterCapReached() {
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
    void sealMarksSinkSealedEvenWhenNoFinalResponse() {
        // Defensive: a null/empty final text still has to terminate the
        // sink cleanly (otherwise a later update would still try to flush).
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal(null);
        assertTrue(sink.sealedForTest(),
                "seal(null) still flips the sealed flag");
    }

    @Test
    void sealIsIdempotent() {
        // Two completion callbacks firing shouldn't double-send the error
        // message. The CAS-gated sealed flag guarantees at-most-once seal.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal(null);
        // Second seal is a no-op — no exception, flag stays set.
        sink.seal("something else");
        assertTrue(sink.sealedForTest());
    }

    @Test
    void sealAfterCapGoesThroughPlannerPath() {
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
    void errorFallbackIsIdempotent() {
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
    void sealAndErrorFallbackAreMutuallyExclusive() {
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
    // MockTelegramSinkIntegrationTest.editInPlaceReentranceGuardPreventsSecondSendMessage
    // (JCLAW-96). The old test asserted state-unchanged after a planted-guard
    // flush, which passed whether or not the guard actually short-circuited —
    // an exception from the HTTP execute path would have left state
    // unchanged too. The wire-level version counts outbound requests on a
    // mock server, which is necessary-and-sufficient: if the guard were
    // removed, both concurrent flushes would land as real HTTP calls and
    // the assertion would fail.

    @Test
    void awaitInFlightFlushWakesImmediatelyOnFutureCompletion() throws Exception {
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
    void tryFireNotifierReturnsFalseForNullConversationId() {
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
    void tryFireNotifierFiresOnceThenRateLimits() {
        TelegramStreamingSink.clearNotifierRateLimiterForTest();
        // First call within the window: fires.
        assertTrue(TelegramStreamingSink.tryFireNotifier(12345L),
                "first call for a fresh conversation must fire");
        // Second call within the 60s window: suppressed.
        assertFalse(TelegramStreamingSink.tryFireNotifier(12345L),
                "second call within the 60s window must suppress");
    }

    @Test
    void tryFireNotifierIsolatedPerConversation() {
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
    void tryFireNotifierReFiresAfterRateWindow() throws Exception {
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

    @Test
    void tryFireNotifierExplicitCooldownIsHonoured() {
        // The explicit-cooldown overload rate-limits against the passed window,
        // not the config default — so a per-binding cooldown is respected.
        TelegramStreamingSink.clearNotifierRateLimiterForTest();
        assertTrue(TelegramStreamingSink.tryFireNotifier(424242L, 60_000L),
                "first call must fire");
        assertFalse(TelegramStreamingSink.tryFireNotifier(424242L, 60_000L),
                "second call within the explicit window must suppress");
    }

    // === JCLAW-378: per-binding notifier policy + cooldown overrides ===

    @Test
    void effectiveNotifierSilent_bindingOverrideWins() {
        // Global default = reply (not silent); a binding override of silent wins.
        play.Play.configuration.remove("telegram.notifier.policy");
        String token = "jclaw378-silent-" + System.nanoTime();
        try {
            seedBindingNotifierOverride(token, "silent", null);
            assertTrue(TelegramStreamingSink.effectiveNotifierSilent(token),
                    "binding errorReplyPolicy=silent must win over config default reply");
        } finally {
            deleteBinding(token);
        }
    }

    @Test
    void effectiveNotifierSilent_overrideForcesReplyOverSilentConfig() {
        // Config says silent, but a binding override of reply forces the reply on.
        play.Play.configuration.setProperty("telegram.notifier.policy", "silent");
        String token = "jclaw378-reply-" + System.nanoTime();
        try {
            seedBindingNotifierOverride(token, "reply", null);
            assertFalse(TelegramStreamingSink.effectiveNotifierSilent(token),
                    "binding errorReplyPolicy=reply must override a silent config default");
        } finally {
            deleteBinding(token);
            play.Play.configuration.remove("telegram.notifier.policy");
        }
    }

    @Test
    void effectiveNotifierSilent_nullOverrideFallsBackToConfig() {
        play.Play.configuration.setProperty("telegram.notifier.policy", "silent");
        String token = "jclaw378-silent-fallback-" + System.nanoTime();
        try {
            seedBindingNotifierOverride(token, null, null);
            assertTrue(TelegramStreamingSink.effectiveNotifierSilent(token),
                    "null override falls back to silent config default");
        } finally {
            deleteBinding(token);
            play.Play.configuration.remove("telegram.notifier.policy");
        }
    }

    @Test
    void effectiveNotifierCooldownMs_bindingOverrideWins() {
        play.Play.configuration.setProperty("telegram.notifier.cooldownMs", "60000");
        String token = "jclaw378-cooldown-" + System.nanoTime();
        try {
            seedBindingNotifierOverride(token, null, 12_345L);
            assertEquals(12_345L, TelegramStreamingSink.effectiveNotifierCooldownMs(token),
                    "binding cooldown override must win over config default");
        } finally {
            deleteBinding(token);
            play.Play.configuration.remove("telegram.notifier.cooldownMs");
        }
    }

    @Test
    void effectiveNotifierCooldownMs_nullOverrideFallsBackToConfig() {
        play.Play.configuration.setProperty("telegram.notifier.cooldownMs", "77777");
        String token = "jclaw378-cooldown-fallback-" + System.nanoTime();
        try {
            seedBindingNotifierOverride(token, null, null);
            assertEquals(77_777L, TelegramStreamingSink.effectiveNotifierCooldownMs(token),
                    "null cooldown override falls back to config default");
        } finally {
            deleteBinding(token);
            play.Play.configuration.remove("telegram.notifier.cooldownMs");
        }
    }

    @Test
    void effectiveNotifierCooldownMs_noBindingFallsBackToConfigDefault() {
        // No config value and no binding → the hardcoded 60s default.
        play.Play.configuration.remove("telegram.notifier.cooldownMs");
        assertEquals(60_000L, TelegramStreamingSink.effectiveNotifierCooldownMs(
                "jclaw378-cooldown-none-" + System.nanoTime()));
    }

    private static void seedBindingNotifierOverride(String token, String errPolicy, Long cooldownMs) {
        services.Tx.run(() -> {
            var agent = services.AgentService.create(
                    "jclaw378-sink-agent-" + System.nanoTime(), "openrouter", "gpt-4.1");
            var b = new models.TelegramBinding();
            b.agent = agent;
            b.botToken = token;
            b.telegramUserId = "9";
            b.errorReplyPolicy = errPolicy;
            b.notifierCooldownMs = cooldownMs;
            b.save();
        });
    }

    private static void deleteBinding(String token) {
        services.Tx.run(() -> {
            var b = models.TelegramBinding.findByBotToken(token);
            if (b != null) b.delete();
        });
    }

    // === JCLAW-100: adaptive throttle ratchet ===

    @Test
    void throttleStartsAtMinimum() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        assertEquals(250L, sink.currentThrottleMsForTest(),
                "fresh sink should start at the 250 ms minimum");
    }

    @Test
    void throttleRatchetsUpOn429RetryAfter() {
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
    void throttleStaysUnchangedOnNon429Failure() {
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.recordFlushFailure(new RuntimeException("connection reset"));
        assertEquals(250L, sink.currentThrottleMsForTest(),
                "non-rate-limit failure must not touch the cadence");
    }

    @Test
    void throttleStaysUnchangedOn429WithoutRetryAfter() {
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
    void awaitInFlightFlushReturnsImmediatelyWhenIdle() throws Exception {
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
    void typingHeartbeatStartsAndIsCancelledBySeal() {
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
    void typingHeartbeatCancelledByCancel() {
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
    void cancelIsIdempotent() {
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
    void cancelAfterSealIsNoOp() {
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
    void typingHeartbeatCancelledByErrorFallback() {
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

    // === JCLAW-342: typing heartbeat robustness (TTL + 401 circuit-breaker) ===

    @Test
    void typingHeartbeatSelfStopsAtTtl() throws InterruptedException {
        // With a zero TTL the heartbeat must self-cancel on its first tick even
        // though seal()/cancel() never fire — the safety net for a turn that
        // hangs without ever sealing.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.setTypingHeartbeatMaxMsForTest(0);
        sink.startTypingHeartbeat();

        boolean stopped = false;
        for (int i = 0; i < 40; i++) {
            if (!sink.typingHeartbeatActiveForTest()) { stopped = true; break; }
            Thread.sleep(50);
        }
        assertTrue(stopped, "heartbeat must self-stop once the TTL elapses, even without seal()");
    }

    @Test
    void typingHeartbeatStopsAfterConsecutive401s() {
        // A revoked/invalid token 401s on every sendChatAction; after
        // TYPING_AUTH_FAILURE_LIMIT consecutive 401s the heartbeat must stop for
        // the turn instead of spamming every ~4s. chatId=null makes the real
        // heartbeat tick a SKIPPED no-op, so the counter seam is deterministic.
        var sink = new TelegramStreamingSink("tok", null, null);
        sink.startTypingHeartbeat();
        assertTrue(sink.typingHeartbeatActiveForTest());

        for (int i = 0; i < TelegramStreamingSink.TYPING_AUTH_FAILURE_LIMIT; i++) {
            sink.recordTypingOutcome(TelegramChannel.TypingActionOutcome.UNAUTHORIZED);
        }
        assertFalse(sink.typingHeartbeatActiveForTest(),
                "consecutive 401s must stop the heartbeat for the turn");
    }

    @Test
    void successfulTypingSendResets401Counter() {
        // A SENT outcome between 401s resets the consecutive counter, so
        // isolated/transient 401s never trip the breaker. chatId=null keeps the
        // real tick a SKIPPED no-op (deterministic).
        var sink = new TelegramStreamingSink("tok", null, null);
        sink.startTypingHeartbeat();

        int belowLimit = TelegramStreamingSink.TYPING_AUTH_FAILURE_LIMIT - 1;
        for (int i = 0; i < belowLimit; i++) {
            sink.recordTypingOutcome(TelegramChannel.TypingActionOutcome.UNAUTHORIZED);
        }
        sink.recordTypingOutcome(TelegramChannel.TypingActionOutcome.SENT); // resets the run
        for (int i = 0; i < belowLimit; i++) {
            sink.recordTypingOutcome(TelegramChannel.TypingActionOutcome.UNAUTHORIZED);
        }
        assertTrue(sink.typingHeartbeatActiveForTest(),
                "a SENT reset between 401 bursts must keep the breaker below its limit");
    }

    @Test
    void typingHeartbeatCancelledByFirstUpdate() {
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
    void typingHeartbeatDoubleStartIsNoOp() {
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
    void typingHeartbeatNotStartedAfterSeal() {
        // If something triggers startTypingHeartbeat after seal has
        // completed, it must be a no-op — the conversation is already
        // over, no need to show "typing".
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal("");
        sink.startTypingHeartbeat();
        assertFalse(sink.typingHeartbeatActiveForTest(),
                "startTypingHeartbeat must no-op when sink is already sealed");
    }

    // === JCLAW-325: residual coverage ===

    @Test
    void recordFlushFailureIsSilentOnMessageNotModified() {
        // Line 720: the benign "message is not modified" branch — return
        // without warning, leave throttle untouched.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        var ex = new org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException(
                "[400] Bad Request: message is not modified");
        sink.recordFlushFailure(ex);
        assertEquals(250L, sink.currentThrottleMsForTest(),
                "the no-modify branch must NOT touch the cadence");
    }

    @Test
    void shutdownAndReinitializeAllowsContinuedUse() {
        // Lines 134-135 / 145-146: ShutdownJob path. After shutdown(),
        // the next scheduler() call must transparently recreate the executor
        // so the rest of the JVM lifetime (or the rest of the test suite)
        // can still flush. Drive it indirectly through update() which routes
        // into scheduleFlushLocked → scheduler().
        TelegramStreamingSink.shutdown();
        var sink = new TelegramStreamingSink("tok", "chat", null);
        // update with a non-empty chunk goes through scheduleFlushLocked
        // which calls scheduler(). If the re-init were broken, this would
        // NPE or refuse to schedule.
        Assertions.assertDoesNotThrow(() -> sink.update("hello after shutdown"));
        // And a follow-up shutdown must remain idempotent / safe.
        Assertions.assertDoesNotThrow(TelegramStreamingSink::shutdown);
    }

    @Test
    void sealNullFinalResponseTreatedAsEmptyString() {
        // Line 384: seal(null) normalizes to "". With messageId=null and no
        // streamCap, the planner branch fires; we just confirm sealed=true
        // and no exception.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        Assertions.assertDoesNotThrow(() -> sink.seal(null));
        assertTrue(sink.sealedForTest());
    }

    @Test
    void containsMediaOrFileRefsDetectsImageMarkdownAndWorkspaceFiles() throws Exception {
        // Indirectly exercise containsMediaOrFileRefs via seal(). For the
        // image-md path we'd hit the planner; for plain text without media
        // we'd hit the happy-path editMessage. We assert the sealed flag
        // and the absence of exceptions on each, and that the cap-tripped
        // sink takes the planner branch (already covered) vs a non-media
        // sink that never sent a placeholder — also planner branch.
        //
        // The classifier lives behind a private static method; exercise it
        // via reflection to make sure both content shapes match.
        var m = TelegramStreamingSink.class.getDeclaredMethod(
                "containsMediaOrFileRefs", String.class);
        m.setAccessible(true);
        assertEquals(Boolean.TRUE, m.invoke(null, "before ![alt](u.png) after"));
        assertEquals(Boolean.TRUE, m.invoke(null, "see [pdf](<workspace/r.pdf>)"));
        assertEquals(Boolean.FALSE, m.invoke(null, "plain prose only"));
        assertEquals(Boolean.FALSE, m.invoke(null, (Object) null));
    }

    @Test
    void awaitInFlightFlushBoundedByCapWhenFutureNeverCompletes() throws Exception {
        // Line 743-746: the TimeoutException defensive cap. Plant a future
        // that never completes and assert seal's caller wakes within (just
        // over) 2 s rather than hanging.
        var sink = new TelegramStreamingSink("tok", "chat", null);

        var flushInFlight = TelegramStreamingSink.class.getDeclaredField("flushInFlight");
        flushInFlight.setAccessible(true);
        flushInFlight.set(sink, new java.util.concurrent.CompletableFuture<Void>());

        var awaitMethod = TelegramStreamingSink.class.getDeclaredMethod("awaitInFlightFlush");
        awaitMethod.setAccessible(true);

        long startNs = System.nanoTime();
        awaitMethod.invoke(sink);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        // SEAL_INFLIGHT_WAIT_MS = 2000 — give a bit of slack for CI jitter.
        assertTrue(elapsedMs >= 1900,
                "awaitInFlightFlush must wait close to the 2 s cap (took " + elapsedMs + " ms)");
        assertTrue(elapsedMs < 3500,
                "awaitInFlightFlush must NOT exceed the cap meaningfully (took " + elapsedMs + " ms)");
    }

    // === Per-chat throttle isolation (AC) ===

    @Test
    void perChatThrottleStateIsolated() {
        // AC: two chats with independent ratchets do not cross-contaminate.
        // Each sink owns its own currentThrottleMs.
        var sinkA = new TelegramStreamingSink("tok", "chat-A", null);
        var sinkB = new TelegramStreamingSink("tok", "chat-B", null);

        var ex = new org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException(
                "Too Many Requests");
        // Build a 429 with retry_after=1 for sinkA only.
        var params = new org.telegram.telegrambots.meta.api.objects.ResponseParameters();
        params.setRetryAfter(1);
        var apiResponse = new org.telegram.telegrambots.meta.api.objects.ApiResponse<Object>(
                false, 429, "Too Many Requests", params, null);
        var tare = new org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException(
                "Too Many Requests", apiResponse);

        sinkA.recordFlushFailure(tare);
        assertEquals(500L, sinkA.currentThrottleMsForTest(),
                "sinkA ratchets to 500 after one 429");
        assertEquals(250L, sinkB.currentThrottleMsForTest(),
                "sinkB stays at the floor — sinks don't share ratchet state");
        // Silence unused-var (the basic exception is just there to document
        // shape parity with the throttle test that lives above).
        assertNotNull(ex);
    }

    // === Sealed-sink rejection (AC) ===

    @Test
    void postSealUpdatesAreDroppedSilently() {
        // AC: post-seal updates dropped silently. After seal flips the flag,
        // update() returns at the sealed-check (line 349) without touching
        // pending, scheduling, or messageId. No exception, no state change.
        var sink = new TelegramStreamingSink("tok", "chat", null);
        sink.seal("");
        assertTrue(sink.sealedForTest());
        sink.update("late tokens");
        assertNull(sink.messageIdForTest(),
                "post-seal update must NOT trigger a placeholder send");
        assertFalse(sink.streamCapReachedForTest(),
                "post-seal update must NOT mutate streamCapReached");
    }

    // === JCLAW-369: reply target + topic thread carried through the sink ===

    @Test
    void legacyConstructorsDefaultReplyTargetAndThreadToNull() {
        // AC4: existing call sites (the three pre-369 constructors) leave both
        // new fields null → today's behavior (no reply, no topic).
        var threeArg = new TelegramStreamingSink("tok", "chat", null);
        assertNull(threeArg.replyToMessageIdForTest(),
                "3-arg constructor must default replyToMessageId to null");
        assertNull(threeArg.messageThreadIdForTest(),
                "3-arg constructor must default messageThreadId to null");

        var fourArg = new TelegramStreamingSink("tok", "chat", null, 1L);
        assertNull(fourArg.replyToMessageIdForTest());
        assertNull(fourArg.messageThreadIdForTest());

        var fiveArg = new TelegramStreamingSink("tok", "chat", null, 1L, "supergroup");
        assertNull(fiveArg.replyToMessageIdForTest());
        assertNull(fiveArg.messageThreadIdForTest());
    }

    @Test
    void fullConstructorRoundTripsReplyTargetAndThread() {
        // The JCLAW-369 constructor stores the inbound reply target + topic so
        // the placeholder send, planner send, and typing heartbeat can carry
        // them. The parent wires these in after merge.
        var sink = new TelegramStreamingSink("tok", "chat", null, 99L, "supergroup", 1234, 56);
        assertEquals(1234, sink.replyToMessageIdForTest().intValue(),
                "constructor must round-trip replyToMessageId");
        assertEquals(56, sink.messageThreadIdForTest().intValue(),
                "constructor must round-trip messageThreadId");
    }

    // === D2: link-preview suppression on streaming placeholder + edits ===
    //
    // Both sendPlaceholder() and editMessage() build their Telegram requests
    // through streamingLinkPreviewOptions(), which gates on the public
    // TelegramChannel.suppressLinkPreview() accessor (config key
    // telegram.linkPreview). The streaming sink's network seams aren't mocked
    // in these unit tests, so we assert directly on the options-builder helper
    // — the same object the builders attach via .linkPreviewOptions(...).
    // Toggling is via play.Play.configuration, mirroring the notifier-policy
    // tests above.

    @Test
    void streamingLinkPreviewOptionsDisabledWhenSuppressionOn() {
        var prior = play.Play.configuration.getProperty("telegram.linkPreview");
        play.Play.configuration.setProperty("telegram.linkPreview", "off");
        try {
            var opts = TelegramStreamingSink.streamingLinkPreviewOptions();
            assertNotNull(opts,
                    "telegram.linkPreview=off must yield non-null LinkPreviewOptions "
                            + "for the placeholder send + every streaming edit");
            assertEquals(Boolean.TRUE, opts.getIsDisabled(),
                    "the streaming options must carry is_disabled=true so live "
                            + "drafts render no URL preview card");
        } finally {
            restoreLinkPreview(prior);
        }
    }

    @Test
    void streamingLinkPreviewOptionsNullWhenSuppressionOff() {
        var prior = play.Play.configuration.getProperty("telegram.linkPreview");
        // "on" is the explicit preview-on value; also covers the default branch.
        play.Play.configuration.setProperty("telegram.linkPreview", "on");
        try {
            assertNull(TelegramStreamingSink.streamingLinkPreviewOptions(),
                    "telegram.linkPreview=on must leave Telegram's default "
                            + "preview-on behavior (null options) on streaming sends");
        } finally {
            restoreLinkPreview(prior);
        }
    }

    private static void restoreLinkPreview(String prior) {
        if (prior == null) {
            play.Play.configuration.remove("telegram.linkPreview");
        } else {
            play.Play.configuration.setProperty("telegram.linkPreview", prior);
        }
    }
}
