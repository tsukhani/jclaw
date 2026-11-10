import agents.TurnBroadcast;
import agents.TurnFrames;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConversationTurnStream;
import services.EventLogger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * JF-200: the per-conversation broadcaster. Every subscriber gets every frame of a turn in order, whatever thread
 * published it; a stalled subscriber delays no one and is dropped; a reconnect gets exactly what it missed, or a
 * resync when that is gone; and an idle conversation with no subscriber keeps nothing.
 */
class ConversationTurnStreamTest extends UnitTest {

    private static final String INIT = "{\"type\":\"init\",\"conversationId\":1}";
    private static final String STATUS = "{\"type\":\"status\",\"content\":\"Using tool: datetime\"}";
    private static final String COMPLETE = "{\"type\":\"complete\",\"content\":\"done\"}";
    private static final String ERROR = "{\"type\":\"error\",\"content\":\"failed\"}";
    private static final String STOPPED = "{\"type\":\"stopped\"}";
    private static final String RESYNC = "{\"type\":\"resync\"}";
    private static final String MARK = "{\"type\":\"status\",\"content\":\"next turn\"}";

    private static final Pattern EVENT = Pattern.compile("\\Aid: (\\d+)\ndata: ([^\n]*)\n\n\\z");

    @Test
    void everySubscriberGetsEveryFrameOfATurnInOrderUnderIdsThatOnlyIncrease() {
        long conversation = conversation();
        var first = new RecordingSink();
        var second = new RecordingSink();
        var unsubscribeFirst = ConversationTurnStream.subscribe(conversation, null, first);
        var unsubscribeSecond = ConversationTurnStream.subscribe(conversation, null, second);

        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.publish(json(INIT));
        turn.publish(json(token("It ")));
        turn.publish(json(STATUS));
        turn.publish(json(token("is now.")));
        turn.end(json(COMPLETE));

        var heard = first.awaitData(COMPLETE);
        assertEquals(List.of(INIT, token("It "), STATUS, token("is now."), COMPLETE), data(heard));
        assertIncreasing(heard);
        assertEquals(heard, second.awaitData(COMPLETE));
        unsubscribeFirst.run();
        unsubscribeSecond.run();
        assertFalse(ConversationTurnStream.holdsState(conversation));
    }

    @Test
    void eachWriteIsOneWholeEventCarryingItsIdAndItsData() {
        long conversation = conversation();
        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.publish(json(INIT));
        turn.end(json(COMPLETE));

        var heard = sink.awaitData(COMPLETE);
        var raw = sink.raw();
        assertEquals(List.of("id: " + heard.get(0).id() + "\ndata: " + INIT + "\n\n",
                "id: " + heard.get(1).id() + "\ndata: " + COMPLETE + "\n\n"), raw);
        unsubscribe.run();
    }

    @Test
    void framesFromThreeConcurrentPublishersReachEverySubscriberInIdOrder() throws Exception {
        long conversation = conversation();
        var first = new RecordingSink();
        var second = new RecordingSink();
        var unsubscribeFirst = ConversationTurnStream.subscribe(conversation, null, first);
        var unsubscribeSecond = ConversationTurnStream.subscribe(conversation, null, second);
        var turn = ConversationTurnStream.begin(conversation, generation());
        int perThread = 500;
        var go = new CountDownLatch(1);
        var publishers = new ArrayList<Thread>();
        for (int t = 0; t < 3; t++) {
            int publisher = t;
            publishers.add(Thread.ofPlatform().start(() -> {
                awaitQuietly(go);
                for (int i = 0; i < perThread; i++) turn.publish(json("{\"t\":" + publisher + ",\"i\":" + i + "}"));
            }));
        }
        go.countDown();
        for (var publisher : publishers) publisher.join(10_000);
        turn.end(json(COMPLETE));

        var heard = first.awaitData(COMPLETE);
        assertEquals(3 * perThread + 1, heard.size());
        assertIncreasing(heard);
        var next = new int[3];
        for (var event : heard.subList(0, heard.size() - 1)) {
            var frame = com.google.gson.JsonParser.parseString(event.data()).getAsJsonObject();
            int publisher = frame.get("t").getAsInt();
            assertEquals(next[publisher]++, frame.get("i").getAsInt(), "publisher " + publisher + " out of order");
        }
        assertEquals(heard, second.awaitData(COMPLETE));
        unsubscribeFirst.run();
        unsubscribeSecond.run();
    }

    @Test
    void aStalledSubscriberDelaysNoPublishAndIsDroppedOnceItsQueueFills() throws Exception {
        long conversation = conversation();
        var release = new CountDownLatch(1);
        var stalled = new StallingSink(release);
        var healthy = new RecordingSink();
        var unsubscribeStalled = ConversationTurnStream.subscribe(conversation, null, stalled);
        var unsubscribeHealthy = ConversationTurnStream.subscribe(conversation, null, healthy);
        var turn = ConversationTurnStream.begin(conversation, generation());
        int frames = ConversationTurnStream.MAX_QUEUED_FRAMES + 50;

        long slowestNs = 0;
        try {
            for (int i = 0; i < frames; i++) {
                long started = System.nanoTime();
                turn.publish(json(token("t" + i)));
                slowestNs = Math.max(slowestNs, System.nanoTime() - started);
                // The stall holds its first frame, and the healthy writer is never left a full queue behind just
                // because the scheduler ran the publishing loop first.
                if (i == 0) assertTrue(stalled.stalled.await(5, TimeUnit.SECONDS));
                assertEquals(1, stalled.returned.getCount(), "publish " + i + " returned with the stalled write still blocked");
                if (i % 1_024 == 1_023) healthy.awaitCount(i + 1);
            }
            turn.end(json(COMPLETE));

            // A wide bound: the structural check above is the claim, this only catches a publish that waits at all.
            assertTrue(TimeUnit.NANOSECONDS.toMillis(slowestNs) < 1_000,
                    "the slowest publish took " + TimeUnit.NANOSECONDS.toMillis(slowestNs) + " ms");
            assertTrue(stalled.closed.await(5, TimeUnit.SECONDS), "the stalled subscriber's stream must be closed");
            var heard = healthy.awaitData(COMPLETE);
            assertEquals(frames + 1, heard.size(), "the healthy subscriber misses nothing");
            assertEquals(token("t0"), heard.getFirst().data());
            assertEquals(token("t" + (frames - 1)), heard.get(frames - 1).data());
            assertEquals(1, ConversationTurnStream.subscriberCount(conversation), "only the healthy one is left");
        } finally {
            release.countDown();
        }
        var writer = stalled.writer.get();
        writer.join(5_000);
        assertFalse(writer.isAlive(), "the stalled writer ends at the pill once its write returns");
        assertEquals(1, stalled.writes.get(), "nothing queued behind the stall is written after the drop");
        unsubscribeStalled.run();
        unsubscribeHealthy.run();
        assertFalse(ConversationTurnStream.holdsState(conversation));
    }

    @Test
    void aStalledSubscriberIsDroppedOnceItsQueuedBytesPassTheCapLongBeforeItsFrameCap() throws Exception {
        long conversation = conversation();
        var release = new CountDownLatch(1);
        var stalled = new StallingSink(release);
        var healthy = new RecordingSink();
        var unsubscribeStalled = ConversationTurnStream.subscribe(conversation, null, stalled);
        var unsubscribeHealthy = ConversationTurnStream.subscribe(conversation, null, healthy);
        var turn = ConversationTurnStream.begin(conversation, generation());
        var mebibyte = "x".repeat(1024 * 1024);
        int frames = (int) (ConversationTurnStream.MAX_QUEUED_BYTES / (1024 * 1024)) + 2;
        try {
            for (int i = 0; i < frames; i++) {
                turn.publish(json(token(i + mebibyte)));
                if (i == 0) assertTrue(stalled.stalled.await(5, TimeUnit.SECONDS));
                healthy.awaitCount(i + 1);
            }
            assertTrue(stalled.closed.await(5, TimeUnit.SECONDS), "the stalled subscriber's stream must be closed");
            assertEquals(1, ConversationTurnStream.subscriberCount(conversation), "only the healthy one is left");
            turn.end(json(COMPLETE));
            assertEquals(frames + 1, healthy.awaitData(COMPLETE).size());
        } finally {
            release.countDown();
        }
        unsubscribeStalled.run();
        unsubscribeHealthy.run();
    }

    @Test
    void droppingAStalledSubscriberLogsOneWarningNamingItsConversation() throws Exception {
        long conversation = conversation();
        var release = new CountDownLatch(1);
        var stalled = new StallingSink(release);
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, stalled);
        var turn = ConversationTurnStream.begin(conversation, generation());
        try {
            turn.publish(json(token("held")));
            assertTrue(stalled.stalled.await(5, TimeUnit.SECONDS));
            var filling = EventLogger.captureForTest(() -> {
                for (int i = 0; i < ConversationTurnStream.MAX_QUEUED_FRAMES; i++) turn.publish(json(token("q" + i)));
            });
            var dropping = EventLogger.captureForTest(() -> turn.publish(json(token("one too many"))));

            assertEquals(List.of(), filling, "a full queue alone is no drop");
            assertEquals(List.of(new EventLogger.Captured("WARN", "turn-stream", null, null,
                    "Closed 1 stream(s) of conversation " + conversation + " that fell too far behind its turn", null)),
                    dropping);
            assertTrue(stalled.closed.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            turn.end(json(COMPLETE));
        }
        unsubscribe.run();
    }

    @Test
    void aSubscriberOpeningMidTurnGetsTheTurnSoFarThenLive() {
        long conversation = conversation();
        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.publish(json(INIT));
        turn.publish(json(token("a")));
        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        turn.publish(json(token("b")));
        turn.end(json(COMPLETE));

        var heard = sink.awaitData(COMPLETE);
        assertEquals(List.of(INIT, token("a"), token("b"), COMPLETE), data(heard));
        assertIncreasing(heard);
        unsubscribe.run();
    }

    @Test
    void aReconnectInsideTheTurnGetsEveryLaterFrameOnce() {
        long conversation = conversation();
        var watching = new RecordingSink();
        var unsubscribeWatching = ConversationTurnStream.subscribe(conversation, null, watching);
        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.publish(json(INIT));
        turn.publish(json(token("a")));
        turn.publish(json(token("b")));
        var seen = watching.awaitData(token("b"));

        var reconnected = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, seen.get(1).id(), reconnected);
        turn.publish(json(token("c")));
        turn.end(json(COMPLETE));

        var all = watching.awaitData(COMPLETE);
        assertEquals(all.subList(2, 5), reconnected.awaitData(COMPLETE));
        unsubscribe.run();
        unsubscribeWatching.run();
    }

    @Test
    void aReconnectFromBeforeTheTurnGetsAResyncThenTheTurnsFramesWhileOneFromItsStartGetsNone() {
        long conversation = conversation();
        var watching = new RecordingSink();
        var unsubscribeWatching = ConversationTurnStream.subscribe(conversation, null, watching);
        var earlier = ConversationTurnStream.begin(conversation, generation());
        earlier.publish(json(INIT));
        earlier.end(json(COMPLETE));
        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.publish(json(INIT));
        var seen = watching.awaitData(COMPLETE);

        var stale = new RecordingSink();
        var unsubscribeStale = ConversationTurnStream.subscribe(conversation, seen.getFirst().id(), stale);
        var current = new RecordingSink();
        var unsubscribeCurrent = ConversationTurnStream.subscribe(conversation, seen.get(1).id(), current);
        turn.end(json(ERROR));

        var resynced = stale.awaitData(ERROR);
        assertEquals(List.of(RESYNC, INIT, ERROR), data(resynced));
        assertEquals(seen.get(1).id(), resynced.getFirst().id(), "the resync carries the id the kept frames follow");
        assertIncreasing(resynced);
        assertEquals(List.of(INIT, ERROR), data(current.awaitData(ERROR)), "the earlier turn's terminal was its last frame");
        unsubscribeStale.run();
        unsubscribeCurrent.run();
        unsubscribeWatching.run();
    }

    @Test
    void aLastIdAnotherConversationIssuedInsideThisOnesRangeResyncs() {
        long watched = conversation();
        long other = conversation();
        var watching = new RecordingSink();
        var watchingOther = new RecordingSink();
        var unsubscribeWatching = ConversationTurnStream.subscribe(watched, null, watching);
        var unsubscribeOther = ConversationTurnStream.subscribe(other, null, watchingOther);
        var turn = ConversationTurnStream.begin(watched, generation());
        var otherTurn = ConversationTurnStream.begin(other, generation());
        turn.publish(json(INIT));
        otherTurn.publish(json(INIT));
        turn.publish(json(token("a")));
        var seen = watching.awaitData(token("a"));
        long foreign = watchingOther.awaitData(INIT).getFirst().id();
        assertTrue(seen.get(0).id() < foreign && foreign < seen.get(1).id(), "test precondition: inside the range");

        var confused = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(watched, foreign, confused);

        assertEquals(List.of(RESYNC, INIT, token("a")), data(confused.awaitData(token("a"))));
        turn.end(json(COMPLETE));
        otherTurn.end(json(COMPLETE));
        unsubscribe.run();
        unsubscribeWatching.run();
        unsubscribeOther.run();
    }

    @Test
    void aLastIdAboveTheLastIssuedResyncs() {
        long conversation = conversation();
        var watching = new RecordingSink();
        var unsubscribeWatching = ConversationTurnStream.subscribe(conversation, null, watching);
        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.publish(json(INIT));
        long last = watching.awaitData(INIT).getFirst().id();

        var ahead = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, last + 1, ahead);

        assertEquals(List.of(RESYNC, INIT), data(ahead.awaitData(INIT)));
        turn.end(json(COMPLETE));
        unsubscribe.run();
        unsubscribeWatching.run();
    }

    @Test
    void aReplayPastItsFrameCapResyncsUnlessTheClientSawTheLastFrameItDropped() {
        long conversation = conversation();
        var turn = ConversationTurnStream.begin(conversation, generation());
        int frames = ConversationTurnStream.MAX_REPLAY_FRAMES + 10;
        for (int i = 0; i < frames; i++) turn.publish(json(token("t" + i)));
        String last = token("t" + (frames - 1));

        var opened = new RecordingSink();
        var unsubscribeOpened = ConversationTurnStream.subscribe(conversation, null, opened);
        var heard = opened.awaitData(last);
        assertEquals(ConversationTurnStream.MAX_REPLAY_FRAMES + 1, heard.size());
        assertEquals(RESYNC, heard.getFirst().data());
        assertEquals(token("t10"), heard.get(1).data(), "the oldest frames went first");
        long lastDropped = heard.getFirst().id();

        var sawIt = new RecordingSink();
        var unsubscribeSawIt = ConversationTurnStream.subscribe(conversation, lastDropped, sawIt);
        var resumed = sawIt.awaitData(last);
        assertEquals(heard.subList(1, heard.size()), resumed, "nothing it missed was dropped");

        var missedIt = new RecordingSink();
        var unsubscribeMissedIt = ConversationTurnStream.subscribe(conversation, lastDropped - 1, missedIt);
        assertEquals(RESYNC, missedIt.awaitData(last).getFirst().data());

        turn.end(json(COMPLETE));
        unsubscribeOpened.run();
        unsubscribeSawIt.run();
        unsubscribeMissedIt.run();
    }

    @Test
    void aReplayPastItsByteCapKeepsTheNewestFrame() {
        long conversation = conversation();
        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.publish(json(INIT));
        var big = token("x".repeat((int) ConversationTurnStream.MAX_REPLAY_BYTES));
        turn.publish(json(big));

        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        assertEquals(List.of(RESYNC, big), data(sink.awaitData(big)));
        turn.end(json(COMPLETE));
        unsubscribe.run();
    }

    @Test
    void anIdleConversationSendsNothingUntilATurnStarts() {
        long conversation = conversation();
        var earlier = ConversationTurnStream.begin(conversation, generation());
        earlier.publish(json(INIT));
        var watching = new RecordingSink();
        var unsubscribeWatching = ConversationTurnStream.subscribe(conversation, null, watching);
        earlier.end(json(COMPLETE));
        var seen = watching.awaitData(COMPLETE);

        var fresh = new RecordingSink();
        var unsubscribeFresh = ConversationTurnStream.subscribe(conversation, null, fresh);
        var upToDate = new RecordingSink();
        var unsubscribeUpToDate = ConversationTurnStream.subscribe(conversation, seen.get(1).id(), upToDate);
        var missedTheEnd = new RecordingSink();
        var unsubscribeMissed = ConversationTurnStream.subscribe(conversation, seen.getFirst().id(), missedTheEnd);
        var next = ConversationTurnStream.begin(conversation, generation());
        next.publish(json(MARK));
        next.end(json(COMPLETE));

        assertEquals(List.of(MARK, COMPLETE), data(fresh.awaitData(COMPLETE)));
        assertEquals(List.of(MARK, COMPLETE), data(upToDate.awaitData(COMPLETE)));
        assertEquals(List.of(RESYNC, MARK, COMPLETE), data(missedTheEnd.awaitData(COMPLETE)),
                "the ended turn's frames are gone, so a client that missed its terminal resyncs");
        unsubscribeFresh.run();
        unsubscribeUpToDate.run();
        unsubscribeMissed.run();
        unsubscribeWatching.run();
    }

    @Test
    void aTurnThatEndsWithNoSubscriberLeavesNoStateAndOneWithASubscriberLeavesItUntilItGoes() {
        long conversation = conversation();
        assertNull(ConversationTurnStream.current(conversation));
        var turn = ConversationTurnStream.begin(conversation, generation());
        assertSame(turn, ConversationTurnStream.current(conversation));
        turn.publish(json(INIT));
        turn.end(json(COMPLETE));
        assertNull(ConversationTurnStream.current(conversation));
        assertFalse(ConversationTurnStream.holdsState(conversation));

        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, new RecordingSink());
        ConversationTurnStream.begin(conversation, generation()).end(json(COMPLETE));
        assertTrue(ConversationTurnStream.holdsState(conversation), "its subscriber keeps it");
        unsubscribe.run();
        assertFalse(ConversationTurnStream.holdsState(conversation));
        unsubscribe.run();
        assertFalse(ConversationTurnStream.holdsState(conversation), "a second unsubscribe changes nothing");
    }

    @Test
    void framesAfterATurnsTerminalAreDropped() {
        long conversation = conversation();
        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.publish(json(INIT));
        turn.end(json(COMPLETE));
        turn.publish(json(token("late")));
        turn.end(json(ERROR));
        turn.stop();
        assertFalse(turn.isOpen());
        var next = ConversationTurnStream.begin(conversation, generation());
        next.end(json(MARK));

        assertEquals(List.of(INIT, COMPLETE, MARK), data(sink.awaitData(MARK)));
        unsubscribe.run();
    }

    @Test
    void aStoppedTurnDropsTheTokensThatArriveAfterTheStop() {
        long conversation = conversation();
        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.publish(json(INIT));
        turn.publish(json(token("before")));
        turn.stop();
        turn.publish(json(token("after")));
        turn.end(json(COMPLETE));
        var next = ConversationTurnStream.begin(conversation, generation());
        next.end(json(MARK));

        assertEquals(List.of(INIT, token("before"), STOPPED, MARK), data(sink.awaitData(MARK)));
        unsubscribe.run();
    }

    @Test
    void aTurnStillOpenWhenTheNextBeginsEndsAsStoppedFirst() {
        long conversation = conversation();
        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        var stopped = ConversationTurnStream.begin(conversation, generation());
        stopped.publish(json(token("old")));
        var next = ConversationTurnStream.begin(conversation, generation());
        stopped.publish(json(token("late")));
        stopped.end(json(COMPLETE));
        next.publish(json(INIT));
        next.end(json(MARK));

        assertFalse(stopped.isOpen());
        assertEquals(List.of(token("old"), STOPPED, INIT, MARK), data(sink.awaitData(MARK)));
        unsubscribe.run();
    }

    @Test
    void aStopEndsTheTurnThatAcquiredAtItsGenerationAndNoOther() {
        long conversation = conversation();
        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        long acquired = generation();
        var turn = ConversationTurnStream.begin(conversation, acquired);
        turn.publish(json(INIT));

        ConversationTurnStream.stop(conversation, acquired - 1);
        assertTrue(turn.isOpen(), "a stop of another generation leaves the turn alone");
        ConversationTurnStream.stop(conversation, acquired);

        assertFalse(turn.isOpen());
        assertEquals(List.of(INIT, STOPPED), data(sink.awaitData(STOPPED)));
        unsubscribe.run();
    }

    @Test
    void aStoppedTurnsLateFramesCannotEndItsSuccessor() {
        long conversation = conversation();
        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        long stoppedAt = generation();
        var stopped = ConversationTurnStream.begin(conversation, stoppedAt);
        stopped.publish(json(INIT));
        ConversationTurnStream.stop(conversation, stoppedAt);
        var successor = ConversationTurnStream.begin(conversation, generation());

        stopped.publish(json(token("late")));
        stopped.end(json(COMPLETE));
        stopped.stop();
        ConversationTurnStream.stop(conversation, stoppedAt);

        assertTrue(successor.isOpen());
        assertSame(successor, ConversationTurnStream.current(conversation));
        successor.end(json(MARK));
        assertEquals(List.of(INIT, STOPPED, MARK), data(sink.awaitData(MARK)));
        unsubscribe.run();
    }

    @Test
    void aTurnThatBeginsAfterANewerOnePublishesNothingAndLeavesItOpen() {
        long conversation = conversation();
        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        long released = generation();
        var successor = ConversationTurnStream.begin(conversation, generation());

        var late = ConversationTurnStream.begin(conversation, released);
        late.publish(json(INIT));
        late.end(json(COMPLETE));

        assertFalse(late.isOpen());
        assertSame(successor, ConversationTurnStream.current(conversation));
        successor.end(json(MARK));
        var lateAfterTheEnd = ConversationTurnStream.begin(conversation, released);
        assertFalse(lateAfterTheEnd.isOpen(), "the channel remembers the newest generation while it is kept");
        assertNull(ConversationTurnStream.current(conversation));
        assertEquals(List.of(MARK), data(sink.awaitData(MARK)));
        unsubscribe.run();
    }

    @Test
    void theWriterInheritsNoInheritableThreadLocal() throws Exception {
        long conversation = conversation();
        var sender = new InheritableThreadLocal<String>();
        var seenSender = new AtomicReference<String>("unset");
        var seenVirtual = new AtomicReference<Boolean>();
        var written = new CountDownLatch(1);
        var sink = new ConversationTurnStream.Sink() {
            @Override
            public void write(byte[] event) {
                seenSender.set(sender.get());
                seenVirtual.set(Thread.currentThread().isVirtual());
                written.countDown();
            }

            @Override
            public void close() {
                // nothing to close
            }
        };
        Runnable unsubscribe;
        sender.set("the guest's turn");
        try {
            unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        } finally {
            sender.remove();
        }
        var turn = ConversationTurnStream.begin(conversation, generation());
        turn.end(json(COMPLETE));

        assertTrue(written.await(5, TimeUnit.SECONDS));
        assertNull(seenSender.get());
        assertEquals(Boolean.TRUE, seenVirtual.get());
        unsubscribe.run();
    }

    // --- JF-202: the replay rebuilds a long turn ---

    @Test
    void aFreshViewerOfALongTurnGetsItsInitThenItsMergedTextThenLiveWithNoResync() {
        long conversation = conversation();
        var broadcast = TurnBroadcast.owning(conversation, generation());
        broadcast.init(new Agent(), row(conversation));
        broadcast.token("It ");
        var text = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            broadcast.token("w" + i + " ");
            text.append('w').append(i).append(' ');
        }

        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        broadcast.token("live");
        broadcast.complete("done");

        var heard = data(sink.awaitData(complete("done")));
        assertEquals(5, heard.size(), "init, the first token, the merged text, the live token, the end: " + heard.size());
        assertEquals("init", type(heard.get(0)));
        assertEquals("It ", com.google.gson.JsonParser.parseString(heard.get(1)).getAsJsonObject().get("content").getAsString());
        assertEquals(delta(TurnFrames.Chunk.TOKEN, text.toString()), heard.get(2));
        assertEquals(delta(TurnFrames.Chunk.TOKEN, "live"), heard.get(3));
        unsubscribe.run();
    }

    @Test
    void aSubscriberWatchingLiveGetsEveryDeltaOfALongTurn() {
        long conversation = conversation();
        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        var broadcast = TurnBroadcast.owning(conversation, generation());
        broadcast.token("It ");
        for (int i = 0; i < 10_000; i++) {
            broadcast.token("w" + i + " ");
            if (i % 1_000 == 999) sink.awaitCount(i + 2);
        }
        broadcast.complete("done");

        var heard = data(sink.awaitData(complete("done")));
        assertEquals(10_002, heard.size());
        assertEquals(delta(TurnFrames.Chunk.TOKEN, "w0 "), heard.get(1));
        assertEquals(delta(TurnFrames.Chunk.TOKEN, "w9999 "), heard.get(10_000));
        unsubscribe.run();
    }

    @Test
    void theReplayMergesADeltaOnlyIntoTheFrameBeforeItOfItsOwnKindAndNeverIntoInitOrTheFirstToken() {
        long conversation = conversation();
        var broadcast = TurnBroadcast.owning(conversation, generation());
        broadcast.init(new Agent(), row(conversation));
        broadcast.token("a");
        broadcast.token("b");
        broadcast.token("c");
        broadcast.reasoning("r");
        broadcast.reasoning("s");
        broadcast.status("Using tool: datetime");
        broadcast.token("d");
        broadcast.token("e");

        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        broadcast.complete("done");

        var heard = data(sink.awaitData(complete("done")));
        assertEquals(List.of("init", "token", "token", "reasoning", "status", "token", "complete"),
                heard.stream().map(ConversationTurnStreamTest::type).toList());
        assertEquals("a", com.google.gson.JsonParser.parseString(heard.get(1)).getAsJsonObject().get("content").getAsString());
        assertEquals(List.of(delta(TurnFrames.Chunk.TOKEN, "bc"), delta(TurnFrames.Chunk.REASONING, "rs"),
                status("Using tool: datetime"), delta(TurnFrames.Chunk.TOKEN, "de")), heard.subList(2, 6));
        unsubscribe.run();
    }

    @Test
    void aLastIdMergedIntoALaterFrameResyncsThenGetsTheKeptFramesWhileTheMergedFramesOwnIdGetsNone() {
        long conversation = conversation();
        var watching = new RecordingSink();
        var unsubscribeWatching = ConversationTurnStream.subscribe(conversation, null, watching);
        var broadcast = TurnBroadcast.owning(conversation, generation());
        broadcast.init(new Agent(), row(conversation));
        broadcast.token("a");
        broadcast.token("b");
        broadcast.token("c");
        var live = watching.awaitData(delta(TurnFrames.Chunk.TOKEN, "c"));

        var merged = new RecordingSink();
        var unsubscribeMerged = ConversationTurnStream.subscribe(conversation, live.get(2).id(), merged);
        var upToDate = new RecordingSink();
        var unsubscribeUpToDate = ConversationTurnStream.subscribe(conversation, live.get(3).id(), upToDate);
        broadcast.complete("done");

        var resynced = merged.awaitData(complete("done"));
        assertEquals(List.of(RESYNC, live.get(0).data(), live.get(1).data(), delta(TurnFrames.Chunk.TOKEN, "bc"),
                complete("done")), data(resynced));
        assertIncreasing(resynced);
        assertEquals(live.get(3).id(), resynced.get(3).id(), "the merged frame carries the id of the last delta in it");
        assertEquals(List.of(complete("done")), data(upToDate.awaitData(complete("done"))));
        unsubscribeMerged.run();
        unsubscribeUpToDate.run();
        unsubscribeWatching.run();
    }

    @Test
    void initOutlivesTheReplayCapAndTheResyncSitsWhereTheDroppedFramesWere() {
        long conversation = conversation();
        var broadcast = TurnBroadcast.owning(conversation, generation());
        broadcast.init(new Agent(), row(conversation));
        int frames = ConversationTurnStream.MAX_REPLAY_FRAMES + 10;
        for (int i = 0; i < frames; i++) broadcast.status("s" + i);
        var last = status("s" + (frames - 1));

        var opened = new RecordingSink();
        var unsubscribeOpened = ConversationTurnStream.subscribe(conversation, null, opened);
        var heard = opened.awaitData(last);

        assertEquals(ConversationTurnStream.MAX_REPLAY_FRAMES + 1, heard.size(), "init, the resync, and every frame kept");
        assertEquals("init", type(heard.get(0).data()));
        assertEquals(RESYNC, heard.get(1).data());
        assertEquals(status("s11"), heard.get(2).data(), "the oldest frames after init went first");
        assertIncreasing(heard);
        var fromInit = new RecordingSink();
        var unsubscribeFromInit = ConversationTurnStream.subscribe(conversation, heard.get(0).id(), fromInit);
        assertEquals(heard, fromInit.awaitData(last), "a client that saw only init missed the dropped frames");
        var fromResync = new RecordingSink();
        var unsubscribeFromResync = ConversationTurnStream.subscribe(conversation, heard.get(1).id(), fromResync);
        assertEquals(heard.subList(2, heard.size()), fromResync.awaitData(last), "one that saw the resync missed nothing kept");
        broadcast.complete("done");
        unsubscribeOpened.run();
        unsubscribeFromInit.run();
        unsubscribeFromResync.run();
    }

    @Test
    void aMergedRunJustUnderTheReplayCapIsKeptWholeForAFreshViewer() {
        long conversation = conversation();
        var broadcast = TurnBroadcast.owning(conversation, generation());
        broadcast.init(new Agent(), row(conversation));
        broadcast.token("It ");
        var text = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            broadcast.token(thousandBytes(i));
            text.append(thousandBytes(i));
        }

        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        broadcast.complete("done");

        var heard = data(sink.awaitData(complete("done")));
        var caughtUp = heard.subList(0, heard.size() - 1);
        assertEquals("init", type(caughtUp.get(0)));
        assertEquals("It ", content(caughtUp.get(1)), "the first token is kept as it came");
        assertEquals(List.of("token"), caughtUp.subList(2, caughtUp.size()).stream()
                .map(ConversationTurnStreamTest::type).distinct().toList(), "no resync, only the run's text");
        assertEquals(text.toString(), String.join("", caughtUp.subList(2, caughtUp.size()).stream()
                .map(ConversationTurnStreamTest::content).toList()), "the whole run, in order");
        unsubscribe.run();
    }

    @Test
    void aMergedRunPastTheReplayCapKeepsItsNewestTextUnderTheCapAfterInitAndAResync() {
        long conversation = conversation();
        var broadcast = TurnBroadcast.owning(conversation, generation());
        broadcast.init(new Agent(), row(conversation));
        broadcast.token("It ");
        var text = new StringBuilder();
        for (int i = 0; i < 2_600; i++) {
            broadcast.token(thousandBytes(i));
            text.append(thousandBytes(i));
        }

        var sink = new RecordingSink();
        var unsubscribe = ConversationTurnStream.subscribe(conversation, null, sink);
        broadcast.complete("done");

        var heard = data(sink.awaitData(complete("done")));
        var caughtUp = heard.subList(0, heard.size() - 1);
        assertEquals("init", type(caughtUp.get(0)));
        assertEquals(RESYNC, caughtUp.get(1));
        var kept = caughtUp.subList(2, caughtUp.size());
        assertEquals(List.of("token"), kept.stream().map(ConversationTurnStreamTest::type).distinct().toList());
        var keptText = String.join("", kept.stream().map(ConversationTurnStreamTest::content).toList());
        assertTrue(text.toString().endsWith(keptText), "what is kept is the run's newest text");
        assertTrue(keptText.length() > ConversationTurnStream.MAX_REPLAY_BYTES / 2,
                "most of the cap still holds text: " + keptText.length());
        long replayed = caughtUp.get(0).length() + kept.stream().mapToLong(String::length).sum();
        assertTrue(replayed <= ConversationTurnStream.MAX_REPLAY_BYTES, "the replay is held under its cap: " + replayed);
        unsubscribe.run();
    }

    // --- helpers ---

    /** A delta of 1,000 ASCII bytes that names its place in the run. */
    private static String thousandBytes(int i) {
        return String.format("%06d", i) + "x".repeat(994);
    }

    private static String content(String frame) {
        return com.google.gson.JsonParser.parseString(frame).getAsJsonObject().get("content").getAsString();
    }

    private static models.Conversation row(long conversationId) {
        var conversation = new models.Conversation();
        conversation.id = conversationId;
        return conversation;
    }

    private static String delta(TurnFrames.Chunk kind, String content) {
        return new String(TurnFrames.chunk(new byte[0], kind, content, new byte[0]), StandardCharsets.UTF_8);
    }

    private static String complete(String content) {
        return new String(TurnFrames.json(TurnFrames.complete(content)), StandardCharsets.UTF_8);
    }

    private static String status(String content) {
        return new String(TurnFrames.json(TurnFrames.status(content)), StandardCharsets.UTF_8);
    }

    private static String type(String frame) {
        return com.google.gson.JsonParser.parseString(frame).getAsJsonObject().get("type").getAsString();
    }

    record Event(long id, String data) {}

    private static final AtomicLong GENERATIONS = new AtomicLong();

    /** A queue generation newer than any this class has begun a turn with. */
    static long generation() {
        return GENERATIONS.incrementAndGet();
    }

    private static long conversation() {
        // Far above any row id, so no other class's conversation shares the state.
        return ThreadLocalRandom.current().nextLong(1L << 50, 1L << 52);
    }

    private static String token(String content) {
        return "{\"type\":\"token\",\"content\":\"" + content + "\"}";
    }

    private static byte[] json(String frame) {
        return frame.getBytes(StandardCharsets.UTF_8);
    }

    static List<String> data(List<Event> events) {
        return events.stream().map(Event::data).toList();
    }

    private static void assertIncreasing(List<Event> events) {
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).id() > events.get(i - 1).id(),
                    "id " + events.get(i).id() + " follows " + events.get(i - 1).id());
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    static Event parse(String raw) {
        var matcher = EVENT.matcher(raw);
        assertTrue(matcher.matches(), "not one whole event: " + raw.substring(0, Math.min(200, raw.length())));
        return new Event(Long.parseLong(matcher.group(1)), matcher.group(2));
    }

    /** Records every event; {@link #awaitData} waits for the frame that ends what a test expects. */
    static final class RecordingSink implements ConversationTurnStream.Sink {
        private final List<String> raw = new ArrayList<>(); // guarded by this

        @Override
        public synchronized void write(byte[] event) {
            raw.add(new String(event, StandardCharsets.UTF_8));
            notifyAll();
        }

        @Override
        public void close() {
            // the tests close nothing through the sink they record with
        }

        synchronized List<String> raw() {
            return List.copyOf(raw);
        }

        /** The events up to and including the first whose data is {@code last}. */
        synchronized List<Event> awaitData(String last) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            var events = new ArrayList<Event>();
            try {
                while (true) {
                    while (events.size() < raw.size()) {
                        var event = parse(raw.get(events.size()));
                        events.add(event);
                        if (event.data().equals(last)) return List.copyOf(events);
                    }
                    long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMs <= 0) throw new AssertionError("never heard " + last + " after " + raw.size() + " event(s)");
                    wait(remainingMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        /** Every event written so far, once {@code done} holds for them. */
        synchronized List<Event> awaitUntil(java.util.function.Predicate<List<Event>> done, String what) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            var events = new ArrayList<Event>();
            try {
                while (true) {
                    while (events.size() < raw.size()) events.add(parse(raw.get(events.size())));
                    if (done.test(events)) return List.copyOf(events);
                    long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMs <= 0) throw new AssertionError("never " + what + "; heard " + data(events));
                    wait(remainingMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        /** Wait until at least {@code count} events have been written. */
        synchronized void awaitCount(int count) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                while (raw.size() < count) {
                    long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMs <= 0) throw new AssertionError("only " + raw.size() + " of " + count + " event(s)");
                    wait(remainingMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** A client whose socket stops taking bytes: its first write blocks until the test lets it go. */
    static final class StallingSink implements ConversationTurnStream.Sink {
        final CountDownLatch release;
        final CountDownLatch stalled = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        /** Counted down once the stalled write returns, so a test can tell the writer is still blocked. */
        final CountDownLatch returned = new CountDownLatch(1);
        final AtomicInteger writes = new AtomicInteger();
        final AtomicReference<Thread> writer = new AtomicReference<>();

        StallingSink(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void write(byte[] event) {
            writer.set(Thread.currentThread());
            writes.incrementAndGet();
            stalled.countDown();
            awaitQuietly(release);
            returned.countDown();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
