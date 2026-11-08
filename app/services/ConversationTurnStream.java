package services;

import agents.TurnFrames;
import org.jspecify.annotations.Nullable;
import utils.AppClock;
import utils.VirtualThreads;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One broadcaster per conversation (JF-200): every turn the conversation runs publishes its frames here, and every
 * view open on the conversation subscribes ({@code GET /api/conversations/{id}/stream}, JF-201).
 *
 * <p>A frame is the one-line JSON the chat stream sends, numbered under the conversation's lock. Ids come from one
 * process-wide counter, so a conversation's ids only increase, even across its state being dropped while idle. The
 * counter starts at the boot's epoch milliseconds times 1,000, so an id a client kept across a restart is below every
 * id this process issues as long as the clock did not step back past the downtime and the earlier process issued
 * fewer than 1,000 frames a millisecond on average.
 *
 * <p>Each turn carries the queue ownership generation it acquired with. A turn that begins after a newer one already
 * has (an operator stop released its conversation before it began) publishes nothing, so its frames can never end the
 * turn that replaced it.
 *
 * <p>The frames of the turn in progress are kept for a client that opens or reconnects during it. A token or reasoning
 * delta merges into the kept frame before it when that frame is a delta of the same kind (JF-202), up to 64 KiB a
 * frame, so a long reply is kept as a few frames per run of text and a late view can rebuild it; a live subscriber
 * still gets every delta. The turn's {@code init} is kept however long the turn runs; past {@link #MAX_REPLAY_FRAMES} or
 * {@link #MAX_REPLAY_BYTES}, the oldest other frames go. A client whose last id is no longer kept, because its frame
 * went or merged into a later one, gets a {@code resync} frame where the frames it cannot have went, then the kept
 * frames. A conversation with no turn running and no subscriber holds no state.
 *
 * <p>A publish never waits on a client: it numbers the frame, keeps it and offers it to each subscriber's bounded
 * queue under the lock, and a subscriber whose queue is full is closed and dropped. Each subscriber's frames are
 * written by its own virtual thread, which waits only on its queue and on the socket.
 */
public final class ConversationTurnStream {

    private ConversationTurnStream() {}

    /** Frames of the turn in progress kept for a reconnecting client, a merged run of deltas counting as one. */
    public static final int MAX_REPLAY_FRAMES = 8_192;
    /** Bytes of the turn in progress kept for a reconnecting client. */
    public static final long MAX_REPLAY_BYTES = 2L * 1024 * 1024;
    /** Frames one subscriber may fall behind before it is dropped. */
    public static final int MAX_QUEUED_FRAMES = 4_096;
    /** Bytes one subscriber may fall behind before it is dropped; a single larger frame still goes to an empty queue. */
    public static final long MAX_QUEUED_BYTES = 8L * 1024 * 1024;

    private static final byte[] STOPPED = TurnFrames.json(TurnFrames.stopped());
    private static final byte[] RESYNC = TurnFrames.json(TurnFrames.resync());
    private static final byte[] NO_BYTES = new byte[0];

    /**
     * Past this, a run of deltas goes on in a new kept frame of its kind, so a long reply is evicted from its oldest
     * text a frame at a time, and no one frame copied under the lock on a catch-up outgrows it by more than a delta.
     */
    private static final long MAX_MERGED_FRAME_BYTES = 64L * 1024;

    private static final AtomicLong IDS = new AtomicLong(AppClock.now().toEpochMilli() * 1_000L);

    private static final ConcurrentHashMap<Long, Channel> CHANNELS = new ConcurrentHashMap<>();

    /** Where one subscriber's frames go. Each write is one whole event: its {@code id:} line and its {@code data:} line. */
    public interface Sink {
        void write(byte[] event);

        void close();
    }

    @SuppressWarnings("java:S6218") // never compared or printed: identity-held in a queue
    private record Frame(long id, byte[] json) {}

    private static final Frame POISON = new Frame(-1, new byte[0]);

    /**
     * A frame the replay keeps. A delta keeps its text rather than its JSON, so the later deltas merged into it cost
     * an append, and its JSON is built once per client that catches up; it carries the id of the last delta merged in.
     */
    private static final class Kept {
        long id;
        private final byte @Nullable [] json;
        private final TurnFrames.@Nullable Chunk kind;
        private final @Nullable StringBuilder text;
        /** What it counts against {@link #MAX_REPLAY_BYTES}: the length of its JSON. */
        long bytes;

        private Kept(Frame frame, TurnFrames.@Nullable Chunk kind, @Nullable String content) {
            this.id = frame.id();
            this.json = kind == null ? frame.json() : null;
            this.kind = kind;
            this.text = content == null ? null : new StringBuilder(content);
            this.bytes = frame.json().length;
        }

        boolean takes(TurnFrames.Chunk delta) {
            return kind == delta;
        }

        /** Merge the next delta of its kind, {@code frame} carrying {@code content}; returns the bytes it adds. */
        long merge(Frame frame, String content) {
            Objects.requireNonNull(text).append(content);
            id = frame.id();
            long added = frame.json().length - Objects.requireNonNull(kind).overhead();
            bytes += added;
            return added;
        }

        /** Under the channel's lock: what a catching-up client gets, its text copied as it stands now. */
        Snapshot snapshot() {
            return json != null ? new Snapshot(id, json, null, null)
                    : new Snapshot(id, null, kind, Objects.requireNonNull(text).toString());
        }
    }

    /** A kept frame as a catching-up client gets it; a merged delta's JSON is built on the client's writer, off the lock. */
    @SuppressWarnings("java:S6218") // never compared or printed: written to a client once
    private record Snapshot(long id, byte @Nullable [] json, TurnFrames.@Nullable Chunk kind, @Nullable String text) {
        byte[] bytes() {
            return json != null ? json
                    : TurnFrames.chunk(NO_BYTES, Objects.requireNonNull(kind), Objects.requireNonNull(text), NO_BYTES);
        }
    }

    /**
     * Start the turn that acquired {@code conversationId} at queue ownership {@code generation}. A turn still open there
     * is one an operator stop released before it noticed (GH-12): it ends first, as stopped, so its late frames never
     * land inside this one. A turn from an older generation than one already begun is returned ended, publishing nothing.
     */
    public static Turn begin(Long conversationId, long generation) {
        var channel = lockChannel(conversationId);
        List<Subscriber> dropped = List.of();
        Turn turn;
        try {
            turn = new Turn(channel, channel.lastIssued, generation);
            var open = channel.turn;
            if (generation < channel.latestGeneration || (open != null && generation < open.generation)) {
                turn.open = false;
                channel.retireIfIdle();
            } else {
                if (open != null) dropped = open.endLocked(STOPPED);
                channel.latestGeneration = generation;
                channel.turn = turn;
            }
        } finally {
            channel.lock.unlock();
        }
        closeDropped(channel, dropped);
        return turn;
    }

    /** End the turn open in {@code conversationId} as stopped if it is the one that acquired at {@code generation}. */
    public static void stop(Long conversationId, long generation) {
        var channel = CHANNELS.get(conversationId);
        if (channel == null) return;
        Turn open;
        channel.lock.lock();
        try {
            open = channel.turn;
        } finally {
            channel.lock.unlock();
        }
        if (open != null && open.generation == generation) open.stop();
    }

    /** The turn open in {@code conversationId}, or null when none is. */
    public static @Nullable Turn current(Long conversationId) {
        var channel = CHANNELS.get(conversationId);
        if (channel == null) return null;
        channel.lock.lock();
        try {
            return channel.retired ? null : channel.turn;
        } finally {
            channel.lock.unlock();
        }
    }

    /**
     * Subscribe {@code sink} to {@code conversationId}: the kept frames of the turn in progress after
     * {@code lastEventId} (all of them when it is null), its deltas merged, then every frame live. When some frame the
     * client cannot have is gone, dropped or merged into a later one, a {@code resync} frame stands where it was:
     * after the turn's {@code init} when that precedes the gap, with the kept frames after it. Returns the unsubscribe
     * handle, which is idempotent.
     */
    public static Runnable subscribe(Long conversationId, @Nullable Long lastEventId, Sink sink) {
        var subscriber = new Subscriber(sink);
        var channel = lockChannel(conversationId);
        List<Snapshot> catchUp;
        try {
            catchUp = channel.catchUp(lastEventId);
            channel.subscribers.add(subscriber);
        } finally {
            channel.lock.unlock();
        }
        VirtualThreads.startUnbound("turn-stream-writer", () -> write(subscriber, catchUp));
        return () -> unsubscribe(channel, subscriber);
    }

    /** Whether {@code conversationId} holds any state: a turn running or a subscriber. */
    public static boolean holdsState(Long conversationId) {
        return CHANNELS.containsKey(conversationId);
    }

    /** The subscribers open on {@code conversationId}. */
    public static int subscriberCount(Long conversationId) {
        var channel = CHANNELS.get(conversationId);
        if (channel == null) return 0;
        channel.lock.lock();
        try {
            return channel.subscribers.size();
        } finally {
            channel.lock.unlock();
        }
    }

    /** Close every subscriber at shutdown; their writers end at the poison pill. */
    public static void shutdown() {
        for (var channel : CHANNELS.values()) {
            List<Subscriber> closing;
            channel.lock.lock();
            try {
                closing = List.copyOf(channel.subscribers);
                for (var subscriber : closing) subscriber.release();
                channel.subscribers.clear();
                channel.retireIfIdle();
            } finally {
                channel.lock.unlock();
            }
            for (var subscriber : closing) closeQuietly(subscriber.sink);
        }
    }

    /** One turn's handle: it publishes frames until its single terminal frame, and drops every frame after that. */
    public static final class Turn {
        private final Channel channel;
        /** The conversation's last id before this turn's first frame. */
        private final long startedAfter;
        private final long generation;
        private final AtomicBoolean firstTokenClaimed = new AtomicBoolean();
        // Written under the channel's lock; read without it only to skip building a frame that would be dropped.
        private volatile boolean open = true;

        private Turn(Channel channel, long startedAfter, long generation) {
            this.channel = channel;
            this.startedAfter = startedAfter;
            this.generation = generation;
        }

        public boolean isOpen() {
            return open;
        }

        /** True once, for the caller about to publish the turn's first token, which the chat stream stamps. */
        public boolean claimFirstToken() {
            return firstTokenClaimed.compareAndSet(false, true);
        }

        /** Publish one frame's JSON; dropped once the turn has ended. */
        public void publish(byte[] json) {
            append(json, false, null, null);
        }

        /** Publish the turn's {@code init}, which the replay keeps for as long as the turn runs. */
        public void publishInit(byte[] json) {
            append(json, true, null, null);
        }

        /**
         * Publish one token or reasoning delta. Every subscriber gets it as its own frame; the replay merges it into
         * the frame before it when that is a delta of the same kind.
         */
        public void publishDelta(TurnFrames.Chunk kind, String content) {
            if (!open) return;
            append(TurnFrames.chunk(NO_BYTES, kind, content, NO_BYTES), false, kind, content);
        }

        private void append(byte[] json, boolean pinned, TurnFrames.@Nullable Chunk delta, @Nullable String content) {
            if (!open) return;
            List<Subscriber> dropped;
            channel.lock.lock();
            try {
                if (!open) return;
                dropped = channel.append(json, pinned, delta, content);
            } finally {
                channel.lock.unlock();
            }
            closeDropped(channel, dropped);
        }

        /** Publish the turn's terminal frame and end it; a second terminal is dropped. */
        public void end(byte[] json) {
            if (!open) return;
            List<Subscriber> dropped;
            channel.lock.lock();
            try {
                if (!open) return;
                dropped = endLocked(json);
                channel.retireIfIdle();
            } finally {
                channel.lock.unlock();
            }
            closeDropped(channel, dropped);
        }

        /** End the turn with a {@code stopped} frame. */
        public void stop() {
            end(STOPPED);
        }

        /** Under the channel's lock. The replay goes with the turn; retiring the channel is the caller's call. */
        private List<Subscriber> endLocked(byte[] json) {
            var dropped = channel.append(json, false, null, null);
            open = false;
            channel.turn = null;
            channel.replay.clear();
            channel.init = null;
            channel.newest = null;
            channel.replayBytes = 0;
            channel.coveredFrom = channel.lastIssued;
            return dropped;
        }
    }

    private static final class Channel {
        final Long conversationId;
        final ReentrantLock lock = new ReentrantLock();
        // Everything below is guarded by lock.
        boolean retired;
        @Nullable Turn turn;
        /** The newest generation a turn has begun with here. */
        long latestGeneration = Long.MIN_VALUE;
        /** The turn's {@code init}, kept apart from {@link #replay} so no cap evicts it. */
        @Nullable Kept init;
        /** The kept frames other than {@link #init}, oldest first. */
        final ArrayDeque<Kept> replay = new ArrayDeque<>();
        /** The frame kept last, the only one a delta can merge into. */
        @Nullable Kept newest;
        long replayBytes;
        /**
         * Every frame of this conversation with a larger id is kept, in {@link #replay} or as {@link #init}, or merged
         * into a later frame there.
         */
        long coveredFrom;
        long lastIssued;
        final List<Subscriber> subscribers = new ArrayList<>();

        Channel(Long conversationId) {
            this.conversationId = conversationId;
            // Every id an earlier state of this conversation issued is at most this.
            this.lastIssued = IDS.get();
            this.coveredFrom = lastIssued;
        }

        /**
         * Number, keep and fan out one frame: {@code pinned} for the turn's {@code init}, a {@code delta} of that kind
         * carrying {@code content}, or neither. Returns the subscribers it dropped, still to be closed.
         */
        List<Subscriber> append(byte[] json, boolean pinned, TurnFrames.@Nullable Chunk delta, @Nullable String content) {
            var frame = new Frame(IDS.incrementAndGet(), json);
            lastIssued = frame.id();
            keep(frame, pinned, delta, content);
            List<Subscriber> dropped = null;
            for (var it = subscribers.iterator(); it.hasNext(); ) {
                var subscriber = it.next();
                if (subscriber.offer(frame)) continue;
                it.remove();
                // A writer that failed has closed already; only one that fell behind is worth a line.
                boolean fellBehind = !subscriber.closed;
                subscriber.release();
                if (fellBehind) {
                    if (dropped == null) dropped = new ArrayList<>();
                    dropped.add(subscriber);
                }
            }
            return dropped == null ? List.of() : dropped;
        }

        private void keep(Frame frame, boolean pinned, TurnFrames.@Nullable Chunk delta, @Nullable String content) {
            var last = newest;
            if (delta != null && content != null && last != null && last.takes(delta)
                    && last.bytes < MAX_MERGED_FRAME_BYTES) {
                replayBytes += last.merge(frame, content);
            } else {
                var kept = new Kept(frame, delta, content);
                if (pinned && init == null) init = kept;
                else replay.addLast(kept);
                newest = kept;
                replayBytes += kept.bytes;
            }
            // The newest frame always stays, and init is never in the replay to be evicted.
            while (replay.size() > 1
                    && (replay.size() + (init == null ? 0 : 1) > MAX_REPLAY_FRAMES || replayBytes > MAX_REPLAY_BYTES)) {
                var evicted = replay.removeFirst();
                replayBytes -= evicted.bytes;
                coveredFrom = evicted.id;
            }
        }

        /** The frames a new subscriber starts with: what it missed of the turn in progress, with a resync if needed. */
        List<Snapshot> catchUp(@Nullable Long lastEventId) {
            if (lastEventId == null) {
                if (turn == null) return List.of();
                return keptAfter(Long.MIN_VALUE, coveredFrom > turn.startedAfter);
            }
            long last = lastEventId;
            // Ids are process-wide, and a delta's id goes when a later delta merges into its frame, so only an id this
            // conversation issued and still accounts for proves the rest kept. Below the floor only init is kept, and
            // a client that last saw it missed the frames dropped after it.
            boolean known = last >= coveredFrom && (last == coveredFrom || last == lastIssued || keeps(last));
            return known ? keptAfter(last, false) : keptAfter(Long.MIN_VALUE, true);
        }

        private boolean keeps(long id) {
            var pinned = init;
            if (pinned != null && pinned.id == id) return true;
            for (var kept : replay) {
                if (kept.id == id) return true;
            }
            return false;
        }

        /**
         * The kept frames after {@code last} in id order. With {@code resync}, a resync frame goes where the frames
         * that are gone were, carrying the floor's id: after a pinned init that precedes them, so ids still increase.
         */
        private List<Snapshot> keptAfter(long last, boolean resync) {
            var frames = new ArrayList<Snapshot>(replay.size() + 2);
            boolean resyncDue = resync;
            var pinned = init;
            for (var kept : replay) {
                if (pinned != null && pinned.id < kept.id) {
                    resyncDue = add(frames, pinned, last, resyncDue);
                    pinned = null;
                }
                resyncDue = add(frames, kept, last, resyncDue);
            }
            if (pinned != null) resyncDue = add(frames, pinned, last, resyncDue);
            if (resyncDue) frames.add(new Snapshot(coveredFrom, RESYNC, null, null));
            return frames;
        }

        /** Add {@code kept} if it follows {@code last}, after the resync if that is due before it; returns if it still is. */
        private boolean add(List<Snapshot> frames, Kept kept, long last, boolean resyncDue) {
            boolean due = resyncDue;
            if (due && kept.id > coveredFrom) {
                frames.add(new Snapshot(coveredFrom, RESYNC, null, null));
                due = false;
            }
            if (kept.id > last) frames.add(kept.snapshot());
            return due;
        }

        void retireIfIdle() {
            if (retired || turn != null || !subscribers.isEmpty()) return;
            retired = true;
            CHANNELS.remove(conversationId, this);
        }
    }

    private static final class Subscriber {
        final Sink sink;
        // One slot over the cap, so the poison pill always fits.
        final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES + 1);
        final AtomicLong queuedBytes = new AtomicLong();
        volatile boolean closed;

        Subscriber(Sink sink) {
            this.sink = sink;
        }

        /** Under the channel's lock, the only place frames are offered; false when the subscriber must go. */
        boolean offer(Frame frame) {
            if (closed || queue.size() >= MAX_QUEUED_FRAMES) return false;
            int length = frame.json().length;
            if (!queue.isEmpty() && queuedBytes.get() + length > MAX_QUEUED_BYTES) return false;
            queuedBytes.addAndGet(length);
            return queue.offer(frame);
        }

        /** Under the channel's lock: no frame follows, and the writer ends at the pill. */
        void release() {
            closed = true;
            queue.clear();
            queuedBytes.set(0);
            while (!queue.offer(POISON)) queue.poll();
        }
    }

    private static Channel lockChannel(Long conversationId) {
        while (true) {
            var channel = CHANNELS.computeIfAbsent(conversationId, Channel::new);
            channel.lock.lock();
            if (!channel.retired) return channel;
            channel.lock.unlock();
        }
    }

    private static void unsubscribe(Channel channel, Subscriber subscriber) {
        channel.lock.lock();
        try {
            if (!channel.subscribers.remove(subscriber)) return;
            subscriber.release();
            channel.retireIfIdle();
        } finally {
            channel.lock.unlock();
        }
    }

    /** Outside the lock: closing an SSE stream runs its close hooks, which unsubscribe. */
    private static void closeDropped(Channel channel, List<Subscriber> dropped) {
        if (dropped.isEmpty()) return;
        for (var subscriber : dropped) closeQuietly(subscriber.sink);
        EventLogger.warn("turn-stream", "Closed %d stream(s) of conversation %d that fell too far behind its turn"
                .formatted(dropped.size(), channel.conversationId));
    }

    private static void closeQuietly(Sink sink) {
        try {
            sink.close();
        } catch (RuntimeException _) {
            // A sink that cannot close is already gone.
        }
    }

    /** The subscriber's writer thread: its catch-up frames, then its queue until the pill. Never interrupted. */
    private static void write(Subscriber subscriber, List<Snapshot> catchUp) {
        try {
            for (var frame : catchUp) {
                if (subscriber.closed) return;
                subscriber.sink.write(event(frame.id(), frame.bytes()));
            }
            while (true) {
                var frame = subscriber.queue.take();
                if (frame == POISON || subscriber.closed) return;
                subscriber.queuedBytes.addAndGet(-frame.json().length);
                subscriber.sink.write(event(frame.id(), frame.json()));
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException _) {
            // The next publish finds the subscriber closed and drops it.
            subscriber.closed = true;
            closeQuietly(subscriber.sink);
        }
    }

    /** {@code id: N\ndata: <json>\n\n} in one array, so the id and its data reach the client in one chunk. */
    private static byte[] event(long id, byte[] json) {
        var head = ("id: " + id + "\ndata: ").getBytes(StandardCharsets.UTF_8);
        var event = new byte[head.length + json.length + 2];
        System.arraycopy(head, 0, event, 0, head.length);
        System.arraycopy(json, 0, event, head.length, json.length);
        event[event.length - 2] = '\n';
        event[event.length - 1] = '\n';
        return event;
    }
}
