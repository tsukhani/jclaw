package services;

import com.google.gson.Gson;
import play.Logger;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static utils.GsonHolder.GSON;

/**
 * Simple in-memory pub/sub notification bus for pushing real-time events
 * to SSE-connected frontends. Any backend code can publish events;
 * the SSE endpoint subscribes listeners.
 *
 * Events are typed JSON objects: { "type": "skill.promoted", "data": { ... } }
 *
 * Each listener invocation is dispatched on a per-task virtual-thread
 * executor with a per-listener timeout. Because every listener runs on its
 * own thread, all dispatches proceed concurrently and the publisher's total
 * wait is bounded to a single shared deadline ({@code LISTENER_TIMEOUT_MS}
 * from the first {@code get}) regardless of listener count — so a
 * slow/stalled SSE writer can never block the fan-out for other subscribers.
 * SSE listeners that throw or time out are removed.
 *
 * <p>{@link #publishDetached} hands that fan-out, wait included, to one bus-owned platform thread, for a
 * publisher on every turn's path that must not wait on a client. Those fan-outs run one at a time, so there a
 * client slow on every write delays later detached events for all clients, until it stalls past the deadline
 * and is dropped.
 */
public class NotificationBus {

    private NotificationBus() {}

    /** JCLAW-662: bus event type for one persisted step of a coding-harness run
     *  (Rail B). Payload carries {@code runId}, {@code conversationId} (the chat the run
     *  was started from), {@code seq}, {@code kind}, {@code text}. */
    public static final String BUS_CODINGRUN_STEP = "codingrun.step";
    /** JCLAW-662: bus event type signaling a run reached its terminal status, so a live
     *  coding-run monitor can stop tailing. Payload carries {@code runId}, {@code status}
     *  and, when the run did not complete, {@code outcome}. */
    public static final String BUS_CODINGRUN_DONE = "codingrun.done";
    /** JCLAW-1206: a new SubagentRun row committed. Payload carries {@code runId},
     *  {@code parentConversationId}, {@code childConversationId}, {@code childAgentId},
     *  {@code status} and {@code label}. */
    public static final String BUS_SUBAGENT_RUN_STARTED = "subagentrun.started";
    /** JCLAW-1206: a SubagentRun terminal status committed; same payload as
     *  {@link #BUS_SUBAGENT_RUN_STARTED}. */
    public static final String BUS_SUBAGENT_RUN_ENDED = "subagentrun.ended";
    /** A message was added to a conversation outside a turn the open chat page is streaming, so it
     *  reloads its messages. Payload carries {@code conversationId}. */
    public static final String BUS_CONVERSATION_UPDATED = "conversation.updated";
    /** A conversation's queue went from idle to busy or back. Payload carries {@code conversationId} and
     *  {@code busy}; two threads' transitions can arrive out of order, so a reader re-reads the queue. */
    public static final String BUS_CONVERSATION_BUSY = "conversation.busy";

    private static final Gson gson = GSON;
    private static final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<BiConsumer<String, Map<String, Object>>> eventListeners =
            new CopyOnWriteArrayList<>();

    /** Per-listener dispatch timeout. SSE writes hanging longer indicate a dead client. */
    public static final long LISTENER_TIMEOUT_MS = 3_000L;

    // Per-task virtual threads so every listener dispatch runs concurrently — the
    // per-listener timeout then actually caps the publisher, instead of futures
    // queueing behind a fixed worker count. SSE writes are blocking socket I/O
    // (not Thread.sleep), so virtual threads unmount cleanly while a writer stalls.
    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("notification-bus-", 0).factory());

    // One thread runs the detached fan-outs in submission order, so each client gets them in the order they were
    // made. A platform thread, so the timed wait on a stalled client parks it and not a turn's virtual thread
    // (JDK-8373224). Daemon and never shut down, as RetryScheduler's is. It serves every sender's events, so it
    // inherits no InheritableThreadLocal from the turn that first publishes, and carries no trace context: SSE
    // writes are not traced.
    private static final ExecutorService DISPATCHER = Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .daemon()
            .name("jclaw-notification-dispatcher")
            .inheritInheritableThreadLocals(false)
            .factory());

    /** Subscribe to receive SSE-formatted event strings. Returns an unsubscribe handle. */
    public static Runnable subscribe(Consumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Subscribe a server-side listener to each event's type and data. It runs on the publisher's
     * thread, so it must return at once, handing any work to a thread of its own; one that throws
     * is logged and kept. Returns an unsubscribe handle.
     */
    public static Runnable subscribeEvents(BiConsumer<String, Map<String, Object>> listener) {
        eventListeners.add(listener);
        return () -> eventListeners.remove(listener);
    }

    /** Publish an event to all connected listeners. */
    public static void publish(String type, Map<String, Object> data) {
        notifyEventListeners(type, data);
        var ssePayload = ssePayload(type, data);
        if (listeners.isEmpty()) return;
        fanOut(ssePayload);
    }

    /**
     * Publish as {@link #publish} does, but return once the {@link #subscribeEvents} listeners have heard it: the SSE
     * clients get it afterwards, in submission order, on the bus's dispatcher thread, which waits on each for up to
     * {@link #LISTENER_TIMEOUT_MS} and drops one that stalls, so a stalled client delays later detached events and
     * never the caller. A failure is logged and never reaches the caller.
     */
    public static void publishDetached(String type, Map<String, Object> data) {
        try {
            notifyEventListeners(type, data);
            var ssePayload = ssePayload(type, data);
            if (listeners.isEmpty()) return;
            DISPATCHER.execute(() -> {
                try {
                    fanOut(ssePayload);
                } catch (RuntimeException e) {
                    Logger.warn(e, "NotificationBus: delivering %s failed", type);
                }
            });
        } catch (RuntimeException e) {
            Logger.warn(e, "NotificationBus: publishing %s failed", type);
        }
    }

    private static void notifyEventListeners(String type, Map<String, Object> data) {
        for (var listener : eventListeners) {
            try {
                listener.accept(type, data);
            } catch (RuntimeException e) {
                Logger.warn(e, "NotificationBus: a listener failed on %s", type);
            }
        }
    }

    private static String ssePayload(String type, Map<String, Object> data) {
        return "data: %s\n\n".formatted(gson.toJson(Map.of("type", type, "data", data)));
    }

    private static void fanOut(String ssePayload) {
        var pairs = new ArrayList<Map.Entry<Consumer<String>, CompletableFuture<Void>>>();
        for (var listener : listeners) {
            var future = CompletableFuture.runAsync(() -> {
                try {
                    listener.accept(ssePayload);
                } finally {
                    // Clear any interrupt status before the worker thread terminates —
                    // future.cancel(true) interrupts the thread to abort a stalled
                    // listener; clearing it keeps the abort from surfacing as a stray
                    // InterruptedException in any shutdown/finalization on this thread.
                    Thread.interrupted();
                }
            }, EXECUTOR);
            pairs.add(Map.entry(listener, future));
        }

        // One shared deadline for the whole fan-out: dispatches run concurrently on
        // their own virtual threads, so the publisher's total wait is bounded to
        // LISTENER_TIMEOUT_MS regardless of how many listeners are slow — get() each
        // future against the time REMAINING to that deadline, not a fresh budget each.
        var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LISTENER_TIMEOUT_MS);
        var failed = new ArrayList<Consumer<String>>();
        for (var pair : pairs) {
            var future = pair.getValue();
            try {
                var remainingNanos = deadlineNanos - System.nanoTime();
                // A non-positive remaining budget still means "wait until the shared
                // deadline" of now — get(0) returns immediately if already done,
                // otherwise times out, so a listener that finished in time is never
                // dropped while late ones are canceled.
                future.get(Math.max(0L, remainingNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException _) {
                future.cancel(true);
                failed.add(pair.getKey());
            } catch (ExecutionException _) {
                failed.add(pair.getKey());
            } catch (InterruptedException _) {
                // Re-set the flag the catch cleared, then stop processing further
                // futures so the interrupt actually propagates up the call stack.
                Thread.currentThread().interrupt();
                failed.add(pair.getKey());
                break;
            }
        }
        if (!failed.isEmpty()) listeners.removeAll(failed);
    }

    /**
     * Publish once the ambient transaction commits, or now when there is none, so a client is
     * never told about a row it cannot read yet. A failure is logged and never reaches the caller.
     */
    public static void publishAfterCommit(String type, Map<String, Object> data) {
        afterCommit(type, () -> publish(type, data));
    }

    /** {@link #publishDetached} once the ambient transaction commits, or now when there is none; a rollback sends nothing. */
    public static void publishDetachedAfterCommit(String type, Map<String, Object> data) {
        afterCommit(type, () -> publishDetached(type, data));
    }

    private static void afterCommit(String type, Runnable publish) {
        Runnable guarded = () -> {
            try {
                publish.run();
            } catch (RuntimeException e) {
                Logger.warn(e, "NotificationBus: publishing %s failed", type);
            }
        };
        try {
            Tx.afterCommit(guarded);
        } catch (RuntimeException e) {
            Logger.warn(e, "NotificationBus: could not schedule %s after commit", type);
        }
    }

    /** Wait until every detached fan-out submitted before this call has finished; tests only. */
    public static void awaitDetachedForTest() {
        try {
            DISPATCHER.submit(() -> {}).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            throw new IllegalStateException("the bus's dispatcher did not drain", e);
        }
    }

    /** Convenience: publish a simple event with a message. */
    public static void publish(String type, String key, Object value) {
        publish(type, Map.of(key, value));
    }

    /** Number of active listeners (for diagnostics). */
    public static int listenerCount() {
        return listeners.size();
    }
}
