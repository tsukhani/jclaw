package services;

import agents.AgentRunner;
import agents.DangerousActionGate;
import agents.QueueDrainOrchestrator;
import agents.TurnCancellation;
import com.google.gson.reflect.TypeToken;
import io.opentelemetry.context.Context;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.PendingInbound;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import utils.AppClock;
import utils.GsonHolder;

import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Per-conversation message queue that serializes agent processing.
 * Prevents state corruption when multiple messages arrive simultaneously.
 *
 * Three modes:
 * - "queue" (default): FIFO, process one at a time
 * - "collect": Batch pending messages into a single prompt
 * - "interrupt": Cancel in-flight via {@link #cancellationFlag(Long)}, queue new message for drain
 *
 * A message queued from an external channel is also kept in a {@link PendingInbound} row until its
 * USER row is appended, so a restart re-queues it (JCLAW-1521).
 */
public class ConversationQueue {

    /** Default queue mode and the event-log category tag for queue events. */
    private static final String QUEUE = "queue";

    private static final ConcurrentHashMap<Long, QueueState> queues = new ConcurrentHashMap<>();
    private static final int MAX_QUEUE_SIZE = 20;
    // Process-wide so a state evicted and recreated under a still-running stopped turn never reissues its generation.
    private static final AtomicLong GENERATIONS = new AtomicLong();
    // Not the generation: a drain hands the next message over under the same one, so two turns would share it. Seeded
    // from the boot time, as ConversationTurnStream's event ids are, so an id a page kept from before a restart names no
    // turn after it; about 1.8e15 now, under 2^53, so JavaScript reads it exactly.
    private static final AtomicLong TURNS = new AtomicLong(AppClock.now().toEpochMilli() * 1_000L);

    /**
     * @param skipUserAppend JCLAW-273: this message's content is already a persisted
     *                       USER row (a yield-resume announce), so the drain must run
     *                       it without re-appending {@code text}. Carried on the record
     *                       because the enqueue/drain boundary drops call arguments.
     * @param attachments    JCLAW-1514: the staged files the message arrived with, finalized
     *                       onto the drained turn's USER row
     * @param pendingId      JCLAW-1521: the {@link PendingInbound} row that keeps the message across
     *                       a restart, consumed with its USER row; null when it has none
     * @param ownerInitiated JF-148: {@link DangerousActionGate#ownerInitiated} where the message was
     *                       enqueued, which its drained turn runs under. A row a restart restores has
     *                       none recorded, so it drains as a guest's.
     * @param senderId       JF-237: the channel's id of the member who sent it, {@link DangerousActionGate#senderId}
     *                       where it was enqueued; null where none was bound, and for a row stored before the column
     *                       existed. In a group chat it is what makes the message one member's.
     */
    public record QueuedMessage(String text, String channelType, String peerId, Agent agent,
                                boolean skipUserAppend, List<AttachmentService.Input> attachments,
                                @Nullable Long pendingId, boolean ownerInitiated, @Nullable String senderId) {
        public QueuedMessage {
            attachments = List.copyOf(attachments);
        }

        public QueuedMessage(String text, String channelType, String peerId, Agent agent,
                             boolean skipUserAppend, List<AttachmentService.Input> attachments) {
            this(text, channelType, peerId, agent, skipUserAppend, attachments, null,
                    DangerousActionGate.ownerInitiated(), DangerousActionGate.senderId());
        }

        public QueuedMessage(String text, String channelType, String peerId, Agent agent) {
            this(text, channelType, peerId, agent, false);
        }

        public QueuedMessage(String text, String channelType, String peerId, Agent agent, boolean skipUserAppend) {
            this(text, channelType, peerId, agent, skipUserAppend, List.of());
        }

        public QueuedMessage(String text, String channelType, String peerId, Agent agent,
                             @Nullable List<AttachmentService.Input> attachments) {
            this(text, channelType, peerId, agent, false, attachments == null ? List.of() : attachments);
        }

        private QueuedMessage withPendingId(@Nullable Long id) {
            return new QueuedMessage(text, channelType, peerId, agent, skipUserAppend, attachments, id, ownerInitiated, senderId);
        }

        /** Each attachment's original file name, or its kind when it came without one. */
        public List<String> attachmentNames() {
            return attachments.stream().map(a -> a.originalFilename() != null ? a.originalFilename() : a.kind()).toList();
        }
    }

    /** Channels that acknowledge a message to the platform before JClaw stores it, so a queued one is kept in a row. */
    public static final Set<String> EXTERNAL_CHANNELS =
            Set.of(ChannelType.TELEGRAM.value, ChannelType.SLACK.value, ChannelType.WHATSAPP.value);

    private static final Type ATTACHMENT_LIST = new TypeToken<List<AttachmentService.Input>>() {}.getType();

    /** The generation a stop ended and the flag it set. */
    private record StoppedOwner(long generation, AtomicBoolean flag) {}

    static class QueueState {
        final ArrayDeque<QueuedMessage> pending = new ArrayDeque<>();
        boolean processing = false; // all reads/writes guarded by synchronized(this)
        @Nullable String mode = QUEUE;
        /** Signals in-flight processing to cancel. Set by interrupt mode, cleared on drain, replaced by {@link #stop}. */
        volatile AtomicBoolean cancelled = new AtomicBoolean(false);
        /** Renewed on every acquire and every {@link #stop}; a release carrying another value is a no-op. */
        long generation = 0;
        /** The current owner's own cancel flag, which {@link #stop} flips; set on every acquire and hand-over. */
        @Nullable AtomicBoolean ownerCancel;
        /** The current owner's {@link #ownerTurn id}, renewed with {@link #ownerCancel}; 0 while no turn owns it. */
        long ownerTurn;
        /** The owner the last {@link #stop} ended, for a run of it that has yet to read its flag ({@link #ownerCancel}). */
        @Nullable StoppedOwner lastStopped;
        /** Where the owner's reply goes, so a stop sends its notice there itself (JF-197b); null when it cannot. */
        @Nullable Consumer<String> ownerNotice;
        /**
         * The member whose message the running turn answers (JF-237); null when none is recorded, or when a collect-mode
         * batch holds more than one member's.
         */
        @Nullable String ownerSender;
        /** Whether the owner's message has yet to be saved as its USER row, so its reply cannot have been saved. */
        boolean ownerRequestPending;
        /**
         * How many {@link #clearWaiting} calls dropped a message. A read after a clear lists fewer messages while busy,
         * as one after a hand-over does, and the chat page tells the two apart by this count.
         */
        int clears;
        /**
         * Wall-clock ms of the last activity that touched this state (tryAcquire / drain /
         * releaseOwnership). Used by {@link #evictIdle} to skip recently-active entries.
         * Volatile so the read path of evictIdle's iterator doesn't have to take the lock
         * before deciding whether a state is even a candidate; the authoritative check
         * (under the per-state lock) re-reads it.
         */
        volatile long lastActivityMs = System.currentTimeMillis();

        /** @return whether the conversation was busy until now */
        synchronized boolean finishProcessing() {
            boolean wasProcessing = processing;
            processing = false;
            ownerCancel = null;
            ownerTurn = 0;
            ownerNotice = null;
            ownerSender = null;
            ownerRequestPending = false;
            return wasProcessing;
        }

        synchronized boolean isProcessing() {
            return processing;
        }
    }

    /** Returned by {@link #tryAcquireOwnership} when the message was queued instead of acquiring. */
    public static final long NOT_ACQUIRED = -1L;

    /**
     * Try to acquire the conversation for processing.
     *
     * @param conversationId the conversation to acquire
     * @param message        the inbound message asking for processing
     * @return {@code true} when acquired (caller should process the
     *         message), {@code false} when the conversation is already
     *         being processed and the message was queued / handled
     *         per the agent's queue mode
     */
    public static boolean tryAcquire(Long conversationId, QueuedMessage message) {
        return tryAcquireOwnership(conversationId, message, null) != NOT_ACQUIRED;
    }

    /**
     * {@link #tryAcquire} that returns the ownership generation the caller must pass to
     * {@link #drain(Long, long)} / {@link #releaseOwnership(Long, long)}.
     *
     * @param ownerCancel the turn's own cancel flag, which {@link #stop} flips; null registers a fresh one, which
     *                    {@link #ownerCancel} hands the turn (JF-197)
     * @return the acquired generation (non-negative), or {@link #NOT_ACQUIRED} when queued
     */
    public static long tryAcquireOwnership(Long conversationId, QueuedMessage message,
                                           @Nullable AtomicBoolean ownerCancel) {
        return tryAcquireOwnership(conversationId, message, ownerCancel, null);
    }

    /**
     * {@link #tryAcquireOwnership(Long, QueuedMessage, AtomicBoolean)} whose turn's reply goes to {@code ownerNotice},
     * where a stop sends its notice itself (JF-197b); null leaves the notice to the stopped turn.
     */
    public static long tryAcquireOwnership(Long conversationId, QueuedMessage message,
                                           @Nullable AtomicBoolean ownerCancel,
                                           @Nullable Consumer<String> ownerNotice) {
        var state = queues.computeIfAbsent(conversationId, _ -> new QueueState());

        // Read config BEFORE entering the synchronized block — ConfigService.get()
        // may hit the DB, and we don't want to hold the state lock during I/O.
        var mode = ConfigService.get("agent." + message.agent().name + ".queue.mode", QUEUE);

        // All mode-read + processing-flag + queue decisions must be atomic to prevent
        // TOCTOU races where two threads both believe they acquired the conversation.
        // We only DECIDE under the lock; the EventLogger emits (synchronous file-append
        // + a batched DB flush) happen AFTER releasing it, mirroring the "Message queued"
        // log below — never hold the ingress lock across that I/O.
        boolean interrupted = false;
        boolean droppedOverflow = false;
        @Nullable RuntimeException storeFailure = null;
        AtomicBoolean interruptedFlag = state.cancelled;
        long acquired = NOT_ACQUIRED;
        List<QueuedMessage> handed = List.of();
        long handedGeneration = 0;
        synchronized (state) {
            state.mode = mode;
            state.lastActivityMs = System.currentTimeMillis();

            if (!state.processing && ownerCancel != null && CHANNEL_STOPS.containsKey(ownerCancel)) {
                // JF-197b: a stop set and renewed the conversation's flag after this streamed channel message read it.
                // On that flag its turn would end at once as stopped; it runs as a drained message, on a fresh one.
                state.processing = true;
                state.generation = GENERATIONS.incrementAndGet();
                handedGeneration = state.generation;
                var queued = message;
                if (isStored(message)) {
                    try {
                        queued = message.withPendingId(insertPending(conversationId, message));
                    } catch (RuntimeException e) {
                        storeFailure = e;
                    }
                }
                state.pending.addLast(queued);
                handed = handOver(state);
            } else if (!state.processing) {
                state.processing = true;
                state.ownerCancel = ownerCancel != null ? ownerCancel : new AtomicBoolean(false);
                state.ownerTurn = TURNS.incrementAndGet();
                state.ownerNotice = ownerNotice;
                state.ownerSender = message.senderId();
                state.ownerRequestPending = !message.skipUserAppend();
                state.generation = GENERATIONS.incrementAndGet();
                acquired = state.generation;
            } else {
                interrupted = "interrupt".equals(mode);
                var dropped = new ArrayList<QueuedMessage>();
                if (interrupted) {
                    // Signal the in-flight processor to cancel, then queue this message
                    // so drain() will pick it up after the current run finishes.
                    interruptedFlag = state.cancelled;
                    interruptedFlag.set(true);
                    dropped.addAll(state.pending);
                    state.pending.clear();
                } else if (state.pending.size() >= MAX_QUEUE_SIZE) {
                    // Queue and collect modes: drop the oldest
                    var oldest = state.pending.pollFirst();
                    if (oldest != null) dropped.add(oldest);
                    droppedOverflow = true;
                }
                // Written under the lock, so no drain can take the message before its row commits.
                var queued = message;
                discardPending(pendingIdsOf(dropped));
                if (isStored(message)) {
                    try {
                        queued = message.withPendingId(insertPending(conversationId, message));
                    } catch (RuntimeException e) {
                        storeFailure = e;
                    }
                }
                state.pending.addLast(queued);
            }
        }

        // Queued too: every tab open on the conversation re-reads its queue and shows the message waiting (JF-202).
        announceBusy(conversationId, true);
        if (acquired != NOT_ACQUIRED) return acquired;

        if (storeFailure != null) {
            EventLogger.warn(QUEUE, message.agent().name, message.channelType(),
                    "Queued message for conversation %d not stored; a restart drops it: %s"
                            .formatted(conversationId, storeFailure.getMessage()));
        }

        if (!handed.isEmpty()) {
            QueueDrainOrchestrator.runOwned(conversationId, handedGeneration, handed);
            return NOT_ACQUIRED;
        }

        if (interrupted) {
            TurnCancellation.cancel(interruptedFlag);
            EventLogger.info(QUEUE, message.agent().name, message.channelType(),
                    "Interrupt mode: signalled cancellation for conversation %d, queued new message"
                            .formatted(conversationId));
            return NOT_ACQUIRED;
        }

        if (droppedOverflow) {
            EventLogger.warn(QUEUE, message.agent().name, message.channelType(),
                    "Queue overflow for conversation %d, dropped oldest message".formatted(conversationId));
        }

        EventLogger.info(QUEUE, message.agent().name, message.channelType(),
                "Message queued for conversation %d (position: %d)"
                        .formatted(conversationId, getQueueSize(conversationId)));

        return NOT_ACQUIRED;
    }

    /**
     * {@link #tryAcquireOwnership} for the stored messages a restart left on one conversation (JCLAW-1521).
     * They predate everything this process received, so in every mode none interrupts: the first acquires
     * when no turn holds the conversation, and the rest queue ahead of the messages that arrived since the
     * restart, in their own order. Overflow drops the oldest, as for any arrival; none is stored again.
     *
     * @param restored the conversation's {@link #restore restored} messages, oldest first; not empty
     * @return the generation when the first acquired and the caller must run it, or {@link #NOT_ACQUIRED}
     *         when every message was queued
     */
    public static long tryAcquireRestored(Long conversationId, List<QueuedMessage> restored) {
        var state = queues.computeIfAbsent(conversationId, _ -> new QueueState());
        var first = restored.getFirst();
        var mode = ConfigService.get("agent." + first.agent().name + ".queue.mode", QUEUE);
        long acquired = NOT_ACQUIRED;
        var dropped = new ArrayList<QueuedMessage>();
        synchronized (state) {
            state.mode = mode;
            var queued = restored;
            if (!state.processing) {
                state.processing = true;
                state.ownerCancel = new AtomicBoolean(false);
                state.ownerTurn = TURNS.incrementAndGet();
                state.ownerNotice = replyTarget(first);
                state.ownerSender = first.senderId();
                state.ownerRequestPending = true;
                state.generation = GENERATIONS.incrementAndGet();
                acquired = state.generation;
                queued = restored.subList(1, restored.size());
            }
            for (var message : queued.reversed()) state.pending.addFirst(message);
            while (state.pending.size() > MAX_QUEUE_SIZE) dropped.add(state.pending.removeFirst());
        }
        if (!dropped.isEmpty()) {
            discardPending(pendingIdsOf(dropped));
            EventLogger.warn(QUEUE, first.agent().name, first.channelType(),
                    "Queue overflow for conversation %d, dropped %d oldest message(s)"
                            .formatted(conversationId, dropped.size()));
        }
        if (acquired != NOT_ACQUIRED) announceBusy(conversationId, true);
        return acquired;
    }

    /**
     * The current ownership generation, for an owned-queue caller that was handed ownership
     * without its generation (the public 3-arg {@code AgentRunner.runWithOwnedQueue}).
     */
    public static long currentGeneration(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return 0;
        synchronized (state) {
            return state.generation;
        }
    }

    /**
     * Stop the turn running in {@code conversationId}, whatever the channel (GH-12): set its owner's cancel flag, write
     * the stop marker, hand the conversation to the next pending message or, with none, release it so the next send
     * acquires instead of queueing, and end the turn on the conversation's stream as stopped. The stopped turn's later
     * release carries a stale generation and is a no-op. The operator's Stop makes this stop through
     * {@link #stopOperatorTurn}; tests drive the queue through this one.
     *
     * @return {@code true} when a turn owned the conversation and was stopped; {@code false}, changing nothing,
     *         otherwise
     */
    public static boolean stop(Long conversationId) {
        return stopWhen(conversationId, null, _ -> true, null, null, null) == OperatorStop.STOPPED;
    }

    /** What the operator's stop found (JF-202). */
    public enum OperatorStop {
        STOPPED, IDLE, UNSTOPPABLE,
        /** A group member's stop of a turn that answers someone else's message (JF-237): nothing changed. */
        NOT_YOURS
    }

    /** A stop limited to the turn that answers {@code sender}'s message; a null sender, with none recorded, matches no turn. */
    public record OwnMessageOnly(@Nullable String sender) {}

    /** What a stop of a Telegram, Slack or WhatsApp conversation's turn owes the person who sent its message (JF-197b). */
    public enum ChannelStop {
        /** The operator's, which the stop had nowhere to send: the stopped turn tells the person at its end. */
        NOTICE,
        /** The operator's, which the stop sent itself, before the next queued message ran (decision 6). */
        SENT,
        /** Owes the person nothing more: their own {@code /stop} answered them, or the reply was saved first. */
        SILENT
    }

    /** The conversations whose turn the operator's Stop applies to: every one but an app's (JF-197b). */
    private static final Set<String> OPERATOR_STOPPABLE = Set.of(ChannelType.WEB.value, ChannelType.TELEGRAM.value,
            ChannelType.SLACK.value, ChannelType.WHATSAPP.value, ChannelType.VOICE.value);

    /**
     * The flags stops of channel conversations' turns set, by identity, with what each stop owes the channel. Weak, so an
     * entry goes with the stopped turn that holds the flag, whichever way that turn ends.
     */
    private static final Map<AtomicBoolean, ChannelStop> CHANNEL_STOPS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The operator's Stop, from the chat page's button and from a {@code /stop} typed in Chat (JF-202): under one lock,
     * find the conversation idle, or its turn one the operator cannot stop ({@link #operatorStoppable}), or stop it as
     * {@link #stop} does. Only a stopped turn changes anything. The next queued message then takes the conversation,
     * unless {@link #stopDropsQueue} drops the queue, and on a Telegram, Slack or WhatsApp conversation the person who
     * sent the stopped message is told (JF-197b).
     */
    public static OperatorStop stopOperatorTurn(Long conversationId, @Nullable String channelType) {
        return stopOperatorTurn(conversationId, channelType, true);
    }

    /**
     * {@link #stopOperatorTurn(Long, String)}, telling the person on the channel only when {@code notice}: a
     * {@code /stop} they sent on the channel answers them itself.
     */
    public static OperatorStop stopOperatorTurn(Long conversationId, @Nullable String channelType, boolean notice) {
        return stopOperatorTurn(conversationId, channelType, notice, null);
    }

    /**
     * {@link #stopOperatorTurn(Long, String, boolean)} of the turn whose {@link #ownerTurn id} is {@code turn}, the one
     * the chat page shows (JF-226). Once that turn no longer owns the conversation the stop finds it
     * {@link OperatorStop#IDLE} and changes nothing, so a click that lands after its reply cannot stop the message that
     * runs next. Null stops whatever runs.
     */
    public static OperatorStop stopOperatorTurn(Long conversationId, @Nullable String channelType, boolean notice,
                                                @Nullable Long turn) {
        return stopOperatorTurn(conversationId, channelType, notice, turn, null);
    }

    /**
     * {@link #stopOperatorTurn(Long, String, boolean, Long)} that, given {@code ownMessageOnly}, stops the turn only
     * when it answers that member's message and otherwise changes nothing, answering {@link OperatorStop#NOT_YOURS}
     * (JF-237): a group member's {@code /stop}. Idle is still {@link OperatorStop#IDLE}, which reveals nothing.
     */
    public static OperatorStop stopOperatorTurn(Long conversationId, @Nullable String channelType, boolean notice,
                                                @Nullable Long turn, @Nullable OwnMessageOnly ownMessageOnly) {
        var owed = channelType != null && EXTERNAL_CHANNELS.contains(channelType)
                ? (notice ? ChannelStop.NOTICE : ChannelStop.SILENT) : null;
        return stopWhen(conversationId, channelType, state -> operatorStoppable(state, channelType), owed, turn,
                ownMessageOnly);
    }

    /**
     * The id of the turn that owns {@code conversationId} at {@code generation}, which the page's Stop names (JF-226):
     * unique to that turn, whereas a drained message runs under the generation of the turn before it. Null when no
     * turn owns the conversation there: it is idle, a stop has made the generation stale, or a stop's notice owns it.
     */
    public static @Nullable Long ownerTurn(Long conversationId, long generation) {
        var state = queues.get(conversationId);
        if (state == null) return null;
        synchronized (state) {
            return state.generation == generation && stoppableFlag(state) != null ? state.ownerTurn : null;
        }
    }

    /**
     * What the stop that set {@code flag} owes the channel its turn replies on; null when no stop of a Telegram, Slack or
     * WhatsApp conversation set it, as for interrupt mode's cancel (JF-197b).
     */
    public static @Nullable ChannelStop channelStop(AtomicBoolean flag) {
        return CHANNEL_STOPS.get(flag);
    }

    /** Whether the operator's Stop applies to the turns of a conversation on {@code channelType}: all but an app's. */
    public static boolean operatorStoppable(@Nullable String channelType) {
        return channelType != null && OPERATOR_STOPPABLE.contains(channelType);
    }

    /**
     * Whether Stop drops the messages queued behind the turn on {@code channelType} rather than running the next: only on
     * voice, whose speaker is the operator and whose queued utterance's reply would never be spoken (JF-222). Everywhere
     * else they run in arrival order (JF-197b, JF-227), and {@code /queue clear} is how they are dropped.
     */
    public static boolean stopDropsQueue(@Nullable String channelType) {
        return ChannelType.VOICE.value.equals(channelType);
    }

    /**
     * @param channelType the conversation's, which decides whether the queue is dropped; null keeps it
     * @param owed        what the stop owes the channel, recorded against the stopped flag; null when nothing
     * @param turn        the {@link #ownerTurn id} of the turn to stop, which must still own the conversation; null
     *                    stops whatever runs
     * @param ownMessageOnly when set, the stop applies only to a turn answering that member's message
     */
    private static OperatorStop stopWhen(Long conversationId, @Nullable String channelType,
                                         Predicate<QueueState> allowed, @Nullable ChannelStop owed,
                                         @Nullable Long turn, @Nullable OwnMessageOnly ownMessageOnly) {
        var state = queues.get(conversationId);
        if (state == null) return OperatorStop.IDLE;
        AtomicBoolean stopped;
        long stoppedGeneration;
        List<QueuedMessage> handed = List.of();
        long handedGeneration = 0;
        @Nullable Consumer<String> notice = null;
        @Nullable RuntimeException markerFailure = null;
        synchronized (state) {
            // A stop's notice owns it with no turn behind it (decision 6): nothing runs to stop.
            if (!state.processing || state.ownerCancel == null) return OperatorStop.IDLE;
            // Under this lock, so the named turn cannot hand the conversation on between the check and the stop.
            if (turn != null && turn != state.ownerTurn) return OperatorStop.IDLE;
            stopped = stoppableFlag(state);
            if (stopped == null || !allowed.test(state)) return OperatorStop.UNSTOPPABLE;
            if (ownMessageOnly != null
                    && (ownMessageOnly.sender() == null || !ownMessageOnly.sender().equals(state.ownerSender))) {
                return OperatorStop.NOT_YOURS;
            }
            // Committed before the release, so the next turn's request cannot land ahead of it. The reply's commit
            // takes this lock too, so a request still unanswered, or not yet saved, is a reply not yet saved.
            boolean unanswered;
            try {
                unanswered = ConversationService.appendStopMarkerIfUnanswered(conversationId);
            } catch (RuntimeException e) {
                markerFailure = e;
                unanswered = true;
            }
            var stop = owed;
            if (owed == ChannelStop.NOTICE) {
                if (!unanswered && !state.ownerRequestPending) {
                    stop = ChannelStop.SILENT;
                } else if (state.ownerNotice != null) {
                    notice = state.ownerNotice;
                    stop = ChannelStop.SENT;
                }
            }
            // Before the flag: a turn that sees it set must find what the stop owes its channel.
            if (stop != null) CHANNEL_STOPS.put(stopped, stop);
            stopped.set(true);
            // A channel turn's flag is the conversation's, and the stale drain never clears it: renew it.
            if (stopped == state.cancelled) state.cancelled = new AtomicBoolean(false);
            stoppedGeneration = state.generation;
            state.lastStopped = new StoppedOwner(stoppedGeneration, stopped);
            state.generation = GENERATIONS.incrementAndGet();
            if (notice != null) {
                // Decision 6: owned by the notice, and by no turn, so a message that arrives meanwhile queues behind
                // it and a second Stop finds nothing to stop. Its thread hands the conversation on once it is sent.
                handedGeneration = state.generation;
                state.ownerCancel = null;
                state.ownerTurn = 0;
                state.ownerNotice = null;
                state.ownerSender = null;
                state.ownerRequestPending = false;
            } else if (!stopDropsQueue(channelType) && !state.pending.isEmpty()) {
                // JCLAW-117: the next message takes the conversation under this lock, so no arrival overtakes it.
                handed = handOver(state);
                handedGeneration = state.generation;
            } else {
                discardPending(pendingIdsOf(List.copyOf(state.pending)));
                state.pending.clear();
                state.finishProcessing();
            }
        }
        // The watchers' turn ends now, not when the stopped turn next looks at its flag.
        ConversationTurnStream.stop(conversationId, stoppedGeneration);
        if (markerFailure != null) {
            EventLogger.warn(QUEUE, "Stop marker not written for conversation %d: %s"
                    .formatted(conversationId, markerFailure.getMessage()));
        }
        TurnCancellation.cancel(stopped);
        if (notice != null) {
            // Ahead of the thread, whose drain can find nothing queued and announce the conversation idle.
            announceBusy(conversationId, true);
            sendThenHandOn(conversationId, handedGeneration, notice);
        } else if (handed.isEmpty()) {
            announceBusy(conversationId, false);
        } else {
            QueueDrainOrchestrator.runOwned(conversationId, handedGeneration, handed);
            // The message left the queue: every tab open on the conversation reads it again.
            announceBusy(conversationId, true);
        }
        return OperatorStop.STOPPED;
    }

    /**
     * Decision 6: off the stop's thread, so the Stop request returns at once, send the notice, then hand the
     * conversation, which the stop left owned at {@code generation}, to what is queued, or release it: the notice
     * reaches the person ahead of the next message's reply. A platform thread: a channel's send can wait out a rate
     * limit, and a virtual thread must not park on a timed wait (JDK-8373224).
     */
    private static void sendThenHandOn(Long conversationId, long generation, Consumer<String> notice) {
        // The stop's span does not follow a new thread by itself.
        Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("stop-notice")
                .start(Context.current().wrap(() -> {
                    try {
                        notice.accept(AgentRunner.STOP_NOTICE);
                    } catch (RuntimeException e) {
                        EventLogger.warn(QUEUE, "Stop notice for conversation %d not sent: %s"
                                .formatted(conversationId, e.getMessage()));
                    } finally {
                        // Whatever the send threw, an Error included: else the conversation stays owned by no one.
                        var next = drain(conversationId, generation);
                        if (!next.isEmpty()) QueueDrainOrchestrator.runOwned(conversationId, generation, next);
                    }
                }));
    }

    /**
     * Where a message's reply goes when its turn runs drained, so a stop of that turn sends its notice there itself
     * (JF-197b); null off Telegram, Slack and WhatsApp.
     */
    public static @Nullable Consumer<String> replyTarget(Agent agent, @Nullable String channelType,
                                                         @Nullable String peerId) {
        if (channelType == null || peerId == null || !EXTERNAL_CHANNELS.contains(channelType)) return null;
        return text -> AgentRunner.dispatchToChannel(agent, channelType, peerId, text);
    }

    private static @Nullable Consumer<String> replyTarget(QueuedMessage message) {
        return replyTarget(message.agent(), message.channelType(), message.peerId());
    }

    /** The owner at {@code generation} saved its message's USER row (JF-197b): its reply can be saved from here on. */
    public static void requestSaved(Long conversationId, long generation) {
        var state = queues.get(conversationId);
        if (state == null) return;
        synchronized (state) {
            if (state.generation == generation) state.ownerRequestPending = false;
        }
    }

    /** Under the state lock: whether the operator's Stop applies to the running turn ({@link #operatorStoppable}). */
    private static boolean operatorStoppable(QueueState state, @Nullable String channelType) {
        return operatorStoppable(channelType) && stoppableFlag(state) != null;
    }

    /** Under the state lock: the running turn's own cancel flag, which {@link #stop} flips; null when no turn runs. */
    private static @Nullable AtomicBoolean stoppableFlag(QueueState state) {
        return state.processing ? state.ownerCancel : null;
    }

    /**
     * Write the stop marker while the turn that acquired {@code generation} still owns the
     * conversation, ignoring its cancel flag: a stopped turn's own cancel path writes it, and an
     * operator {@link #stop} that already wrote one has made the generation stale.
     *
     * @return {@code true} when the conversation was still owned (whether or not a marker was due)
     */
    public static boolean writeStopMarkerIfOwner(Long conversationId, long generation) {
        return commitIfOwner(conversationId, generation, null,
                () -> ConversationService.appendStopMarkerIfUnanswered(conversationId));
    }

    /**
     * The cancel flag of the turn that owns {@code conversationId} at {@code generation}, for a run handed the
     * conversation with no flag in hand, a drained or restored message's (JF-197). Once that turn no longer owns it,
     * as after a {@link #stop}, the flag returned is already set, so the turn ends at its next checkpoint: the one the
     * stop set, and with it what the stop owes the turn's channel (JF-197b), when that stop was the last.
     */
    public static AtomicBoolean ownerCancel(Long conversationId, long generation) {
        var state = queues.get(conversationId);
        if (state == null) return new AtomicBoolean(true);
        synchronized (state) {
            var flag = state.generation == generation ? stoppableFlag(state) : null;
            var last = state.lastStopped;
            if (flag == null && last != null && last.generation() == generation) flag = last.flag();
            return flag != null ? flag : new AtomicBoolean(true);
        }
    }

    /**
     * Run {@code commit} only while the turn that acquired {@code generation} still owns the
     * conversation and its {@code turnCancel} flag (when it has one) is unset. The check and the
     * commit share {@link #stop}'s lock, so a racing write either lands before the stop returns
     * or not at all.
     *
     * @return {@code true} when {@code commit} ran; {@code false}, running nothing, otherwise
     */
    public static boolean commitIfOwner(Long conversationId, long generation, @Nullable AtomicBoolean turnCancel,
                                        Runnable commit) {
        var state = queues.get(conversationId);
        if (state == null) return false;
        synchronized (state) {
            if (!state.processing || state.generation != generation) return false;
            if (turnCancel != null && turnCancel.get()) return false;
            commit.run();
            return true;
        }
    }

    /**
     * {@code /stop} on a conversation the operator's Stop does not apply to, an app's: signal the in-flight turn through
     * the conversation's cancel flag and run its cancel actions.
     */
    public static void cancelTurn(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return;
        AtomicBoolean flag;
        synchronized (state) {
            // The turn may have released since the caller saw it busy; a set flag would cancel the next one.
            if (!state.processing) return;
            flag = state.cancelled;
            flag.set(true);
        }
        TurnCancellation.cancel(flag);
    }

    /**
     * Run {@code commit} unless {@code turnCancel} is already set, under {@link #stop}'s lock, so a
     * stop either lands first and nothing runs, or after {@code commit} has finished.
     *
     * @return {@code true} when {@code commit} ran
     */
    public static boolean commitUnlessCancelled(Long conversationId, @Nullable AtomicBoolean turnCancel,
                                                Runnable commit) {
        var state = queues.get(conversationId);
        if (state == null || turnCancel == null) {
            commit.run();
            return true;
        }
        synchronized (state) {
            if (turnCancel.get()) return false;
            commit.run();
            return true;
        }
    }

    /**
     * Returns an {@link AtomicBoolean} that becomes {@code true} when interrupt
     * mode signals that the in-flight processor should cancel. Callers (e.g.
     * {@link agents.AgentRunner}) should poll this in their processing loops.
     *
     * @param conversationId the conversation whose cancel flag is needed
     * @return the conversation's cancellation flag, or a never-canceled
     *         flag when the conversation has no state yet
     */
    public static AtomicBoolean cancellationFlag(Long conversationId) {
        var state = queues.get(conversationId);
        return state != null ? state.cancelled : new AtomicBoolean(false);
    }

    /**
     * Called after processing completes. Returns the next message(s) to process,
     * or empty list if the queue is empty.
     *
     * <p><b>Ownership semantics (JCLAW-117)</b>
     * When this method returns non-empty, the {@code processing} flag STAYS
     * {@code true} — the caller inherits ownership of the conversation queue
     * and is responsible for releasing it (via {@link #releaseOwnership},
     * or by re-entering {@code drain} after their run completes; the re-entry
     * will see an empty pending deque and clear the flag itself).
     *
     * <p>Keeping ownership atomic across the pop closes a prior race where a
     * new inbound message could {@code tryAcquire} in the gap between drain
     * popping B and the caller actually starting to run B — letting C
     * overtake an older queued message.
     *
     * <p>Does NOT remove the QueueState from the map — avoids a race between
     * {@code remove()} and a concurrent {@code computeIfAbsent()} that would
     * permanently orphan the conversation. Idle entries are harmless (empty
     * deque + processing=false) and will be reused on the next message.
     *
     * <p>In "collect" mode, returns all pending messages combined. In "queue"
     * mode, returns just the next message.
     *
     * @param conversationId the conversation being drained
     * @param generation     the generation the caller acquired with; a stale one
     *                       (the conversation was {@link #stop}ped since) changes nothing
     * @return the next message(s) to process, or an empty list when the
     *         queue is empty (in which case ownership is released)
     */
    public static List<QueuedMessage> drain(Long conversationId, long generation) {
        var state = queues.get(conversationId);
        if (state == null) return List.of();

        boolean released;
        synchronized (state) {
            if (state.generation != generation) return List.of();
            // A fresh flag, not a reset: a stopped channel turn still holds the old one and must stay canceled.
            if (state.cancelled.get()) state.cancelled = new AtomicBoolean(false);
            state.lastActivityMs = System.currentTimeMillis();

            if (!state.pending.isEmpty()) return handOver(state);
            // Nothing to do — release ownership. The next inbound message will re-acquire via tryAcquire.
            released = state.finishProcessing();
        }
        if (released) announceBusy(conversationId, false);
        return List.of();
    }

    /**
     * Under the state lock, with a message pending: the message(s) the drain returns, all of them in
     * collect mode. Ownership transfers to the caller — processing stays true — with a fresh cancel
     * flag of its own, which {@link #ownerCancel} hands the drained run, and the channel its reply goes
     * to, where a stop sends its notice.
     */
    private static List<QueuedMessage> handOver(QueueState state) {
        state.ownerCancel = new AtomicBoolean(false);
        state.ownerTurn = TURNS.incrementAndGet();
        List<QueuedMessage> handed;
        if ("collect".equals(state.mode)) {
            handed = new ArrayList<>(state.pending);
            state.pending.clear();
        } else {
            handed = List.of(state.pending.removeFirst());
        }
        state.ownerNotice = replyTarget(handed.getFirst());
        var sender = handed.getFirst().senderId();
        state.ownerSender = handed.stream().allMatch(m -> Objects.equals(sender, m.senderId())) ? sender : null;
        state.ownerRequestPending = handed.stream().anyMatch(m -> !m.skipUserAppend());
        return handed;
    }

    /**
     * Release queue ownership explicitly. Callers that received a non-empty
     * result from {@link #drain} must either call this on completion or
     * re-invoke {@code drain} (which releases ownership automatically when
     * the pending deque is empty).
     *
     * <p>This is the fail-safe exit path for drained-message processors —
     * their {@code finally} block calls this to guarantee {@code processing}
     * flips back to false even when the run throws an exception that
     * short-circuits the normal re-drain.
     */
    public static void releaseOwnership(Long conversationId, long generation) {
        var state = queues.get(conversationId);
        if (state == null) return;
        boolean released;
        synchronized (state) {
            if (state.generation != generation) return;
            state.lastActivityMs = System.currentTimeMillis();
            released = state.finishProcessing();
        }
        if (released) announceBusy(conversationId, false);
    }

    /** Unfenced {@link #drain(Long, long)} against the current generation; tests only. */
    public static List<QueuedMessage> drain(Long conversationId) {
        return drain(conversationId, currentGeneration(conversationId));
    }

    /** Unfenced {@link #releaseOwnership(Long, long)} against the current generation; tests only. */
    public static void releaseOwnership(Long conversationId) {
        releaseOwnership(conversationId, currentGeneration(conversationId));
    }

    /** Drops the conversation's in-memory state as a restart does, its owner and pending messages included; tests only. */
    public static void forgetForTest(Long conversationId) {
        queues.remove(conversationId);
    }

    /**
     * Publish {@link NotificationBus#BUS_CONVERSATION_BUSY} for a change the caller made under the state lock (the
     * conversation taken or given up, or a message queued), only once it has let the lock go, since the bus's own
     * listeners run on this thread. Detached: every turn takes and gives up its conversation, and its thread must not
     * wait on an SSE client. The publish never throws, so a caller always reaches the release of what it acquired.
     */
    private static void announceBusy(Long conversationId, boolean busy) {
        NotificationBus.publishDetached(NotificationBus.BUS_CONVERSATION_BUSY,
                Map.of("conversationId", conversationId, "busy", busy));
    }

    /** The rows of {@code messages} that carry one. */
    public static List<Long> pendingIdsOf(List<QueuedMessage> messages) {
        return messages.stream().map(QueuedMessage::pendingId).filter(Objects::nonNull).toList();
    }

    /**
     * Delete the rows of messages that will never run, so a restart cannot resurrect them. A failure is
     * logged, not thrown: the message is already gone from the queue.
     */
    public static void discardPending(Collection<Long> pendingIds) {
        if (pendingIds.isEmpty()) return;
        try {
            Tx.run(() -> PendingInbound.deleteByIds(pendingIds));
        } catch (RuntimeException e) {
            EventLogger.warn(QUEUE, "Stored message(s) %s not deleted; a restart re-queues them: %s"
                    .formatted(pendingIds, e.getMessage()));
        }
    }

    /** The queued message a stored row holds, carrying the row's id so the drain consumes it. */
    public static QueuedMessage restore(PendingInbound row) {
        List<AttachmentService.Input> attachments = row.attachments == null
                ? List.of() : GsonHolder.GSON.fromJson(row.attachments, ATTACHMENT_LIST);
        return new QueuedMessage(row.text, row.channelType, row.peerId, row.agent, false, attachments, row.id, false,
                row.senderId);
    }

    private static boolean isStored(QueuedMessage message) {
        return message.pendingId() == null && !message.skipUserAppend() && message.agent().id != null
                && EXTERNAL_CHANNELS.contains(message.channelType());
    }

    private static Long insertPending(Long conversationId, QueuedMessage message) {
        Supplier<Long> insert = () -> Tx.run(() -> {
            var row = new PendingInbound();
            row.conversation = JPA.em().getReference(Conversation.class, conversationId);
            row.agent = JPA.em().getReference(Agent.class, message.agent().id);
            row.channelType = message.channelType();
            row.peerId = message.peerId();
            row.senderId = message.senderId();
            row.text = message.text();
            row.attachments = message.attachments().isEmpty() ? null : GsonHolder.GSON.toJson(message.attachments());
            row.save();
            return row.id;
        });
        if (!JPA.isInsideTransaction()) return insert.get();
        // A caller's transaction commits after the lock is released, when a drain may already have run the message.
        try {
            return CompletableFuture.supplyAsync(insert, r -> Thread.ofVirtual().start(r)).join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }

    /**
     * Format collected messages into a single prompt.
     *
     * @param messages the queued messages to combine
     * @return the single message verbatim when only one was queued; a
     *         labeled multi-message block when multiple are present
     */
    public static String formatCollectedMessages(List<QueuedMessage> messages) {
        if (messages.size() == 1) return messages.getFirst().text();

        var sb = new StringBuilder("[Queued messages while agent was busy]\n");
        for (int i = 0; i < messages.size(); i++) {
            sb.append("\n---\nMessage %d:\n%s\n".formatted(i + 1, messages.get(i).text()));
        }
        return sb.toString();
    }

    /**
     * A message waiting in a conversation's queue, as the chat page shows it.
     *
     * @param attachmentNames each attachment's original file name, or its kind when it came without one
     * @param position        its place in line, 1 for the next to run
     */
    public record PendingView(String text, List<String> attachmentNames, int position) {}

    /**
     * A conversation's queue as the chat page reads it, taken under one lock so its parts agree.
     *
     * @param queueSize      every waiting message, a yield resume's announce included
     * @param stoppable      whether the operator's Stop can stop the running turn ({@link #operatorStoppable})
     * @param stopDropsQueue whether that Stop drops every waiting message rather than running the next
     *                       ({@link #stopDropsQueue})
     * @param pending        the waiting messages in the order they run; one that is already a saved row (a yield
     *                       resume's announce) is left out, since the conversation shows it, but keeps its place in
     *                       line
     * @param clears         how many {@code /queue clear}s have dropped a message from this queue, so a reader can tell
     *                       a shorter list that a clear left from one a hand-over left
     */
    public record Snapshot(boolean busy, int queueSize, boolean stoppable, boolean stopDropsQueue,
                           List<PendingView> pending, int clears) {}

    /** {@link Snapshot} of the queue of {@code conversationId}, a conversation on {@code channelType}. */
    public static Snapshot snapshot(Long conversationId, @Nullable String channelType) {
        var state = queues.get(conversationId);
        if (state == null) return new Snapshot(false, 0, false, stopDropsQueue(channelType), List.of(), 0);
        var views = new ArrayList<PendingView>();
        synchronized (state) {
            int position = 0;
            for (var message : state.pending) {
                position++;
                if (message.skipUserAppend()) continue;
                views.add(new PendingView(message.text(), message.attachmentNames(), position));
            }
            return new Snapshot(state.processing, state.pending.size(), operatorStoppable(state, channelType),
                    stopDropsQueue(channelType), views, state.clears);
        }
    }

    /**
     * The messages waiting in the queue of {@code conversationId}, in the order they run, as {@code /queue} lists them
     * (JF-227): those {@code peerId} sent on {@code channelType}, or with a null {@code channelType} every sender's, as
     * Chat's {@code /queue} lists them. A subagent result waiting to wake the agent is no sender's message, so it is
     * left out.
     */
    public static List<QueuedMessage> waiting(Long conversationId, @Nullable String channelType,
                                              @Nullable String peerId) {
        return waiting(conversationId, channelType, peerId, null);
    }

    /**
     * {@link #waiting(Long, String, String)} narrowed, given {@code ownMessageOnly}, to the messages that member sent
     * (JF-237): in a group chat {@code peerId} names the chat, so the sender is what makes a message theirs. A message
     * with no sender recorded is no member's.
     */
    public static List<QueuedMessage> waiting(Long conversationId, @Nullable String channelType,
                                              @Nullable String peerId, @Nullable OwnMessageOnly ownMessageOnly) {
        var state = queues.get(conversationId);
        if (state == null) return List.of();
        synchronized (state) {
            return state.pending.stream().filter(m -> isWaitingFrom(m, channelType, peerId, ownMessageOnly)).toList();
        }
    }

    /**
     * {@code /queue clear} (JF-227): drop the {@link #waiting} messages, and the rows that would run them again after a
     * restart. The running turn carries on, and a subagent result waiting behind it keeps its place, as does every
     * other sender's message.
     *
     * @return how many messages were dropped
     */
    public static int clearWaiting(Long conversationId, @Nullable String channelType, @Nullable String peerId) {
        return clearWaiting(conversationId, channelType, peerId, null);
    }

    /** {@link #clearWaiting(Long, String, String)} of one member's messages only, as {@link #waiting(Long, String, String, OwnMessageOnly)}. */
    public static int clearWaiting(Long conversationId, @Nullable String channelType, @Nullable String peerId,
                                   @Nullable OwnMessageOnly ownMessageOnly) {
        var state = queues.get(conversationId);
        if (state == null) return 0;
        List<QueuedMessage> dropped;
        synchronized (state) {
            dropped = state.pending.stream().filter(m -> isWaitingFrom(m, channelType, peerId, ownMessageOnly)).toList();
            if (dropped.isEmpty()) return 0;
            state.pending.removeIf(m -> isWaitingFrom(m, channelType, peerId, ownMessageOnly));
            state.clears++;
            discardPending(pendingIdsOf(dropped));
        }
        // Something was queued, so a turn still holds the conversation; every tab open on it reads the queue again.
        announceBusy(conversationId, true);
        return dropped.size();
    }

    // A Chat send into a channel conversation waits as "web" from the operator, never as the channel's sender.
    private static boolean isWaitingFrom(QueuedMessage message, @Nullable String channelType, @Nullable String peerId,
                                         @Nullable OwnMessageOnly ownMessageOnly) {
        return !message.skipUserAppend() && (channelType == null
                || (channelType.equals(message.channelType()) && Objects.equals(peerId, message.peerId())
                && (ownMessageOnly == null || (ownMessageOnly.sender() != null
                        && ownMessageOnly.sender().equals(message.senderId())))));
    }

    /** Number of pending messages waiting in a conversation's queue (0 when it has no state). */
    public static int getQueueSize(Long conversationId) {
        var state = queues.get(conversationId);
        if (state == null) return 0;
        synchronized (state) {
            return state.pending.size();
        }
    }

    /** True when the conversation has an owner currently running a turn against it. */
    public static boolean isBusy(Long conversationId) {
        var state = queues.get(conversationId);
        return state != null && state.isProcessing();
    }

    /**
     * Periodic sweep that removes idle {@link QueueState} entries whose
     * {@code lastActivityMs} is older than {@code olderThanMs} ago AND which
     * are quiescent (no pending messages, not processing, no cancellation
     * pending). Called by {@code jobs.ConversationQueueEvictionJob} (JCLAW-286).
     *
     * <p>The per-state check runs under the state's monitor so a concurrent
     * {@link #tryAcquire} that just did {@code computeIfAbsent} cannot lose
     * its state to a racing eviction — the {@code synchronized (state)} block
     * in {@code tryAcquire} stamps {@code lastActivityMs} and flips
     * {@code processing}, both of which this sweep observes before removing.
     *
     * <p>{@link ConcurrentHashMap}'s entry iterator is safe for concurrent
     * removal via {@link java.util.Iterator#remove()}.
     *
     * @param olderThanMs idle threshold in ms — entries whose
     *                    {@code lastActivityMs} is older than
     *                    {@code now - olderThanMs} are eligible for
     *                    eviction
     * @return number of entries evicted (visible to tests)
     */
    public static int evictIdle(long olderThanMs) {
        var threshold = System.currentTimeMillis() - olderThanMs;
        var evicted = 0;
        var it = queues.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var state = entry.getValue();
            synchronized (state) {
                if (state.lastActivityMs <= threshold
                        && state.pending.isEmpty()
                        && !state.processing
                        && !state.cancelled.get()) {
                    it.remove();
                    evicted++;
                }
            }
        }
        return evicted;
    }
}
