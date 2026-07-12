package channels;

import services.EventLogger;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Generic approve/deny lifecycle shared by {@link TelegramApprovalService} and
 * {@link SlackApprovalService} (JCLAW-718). Those two services were ~200-line
 * near-verbatim twins: a process-local pending-approval registry, per-user gating
 * on resolve, a blocking {@link #await}, and a timeout/shutdown {@link #expire} —
 * differing only in the channel-specific emit (Telegram inline keyboard vs Slack
 * Block Kit), the {@code Decision}→{@code Outcome} mapping, and how a resolved or
 * expired prompt is retired.
 *
 * <p>Follows the house Pure-Fabrication idiom ({@link IdleDebounceBuffer},
 * {@code TelegramOutboundPlanner}): this type owns the lifecycle machinery once and
 * a channel supplies only what differs. The channel's {@code request} emits its own
 * prompt, then hands the pending future plus a {@link LivePrompt} — how to swap the
 * live message on resolve/expire — to {@link #register}. A third channel would need
 * only its emitter and a {@code LivePrompt}.
 *
 * <p>The registry is process-local and in-memory: a JVM restart drops pending
 * approvals (the user's tap then resolves to "no longer pending" and the requester
 * sees a timeout). That matches the ephemeral nature of an in-flight tool call —
 * there is nothing durable to recover.
 *
 * @param <D> the channel's button-decision enum ({@code …ApprovalCallback.Decision})
 * @param <O> the channel's resolved-outcome enum ({@code …ApprovalService.Outcome})
 */
final class ApprovalRegistry<D, O> {

    private static final String LOG_CATEGORY = "channel";
    private static final String NOT_PENDING = "This approval is no longer pending.";
    private static final String NOT_FOR_YOU = "This approval was not issued for you.";

    /**
     * The live channel prompt behind a pending approval — the seam a channel fills
     * so the registry can retire the message without knowing its transport. Telegram
     * leaves the message untouched on resolve (its callback dispatcher edits it
     * separately) and edits it on expire; Slack swaps to a static confirmation on
     * both.
     */
    interface LivePrompt {
        /** Called once, after a decisive tap has removed the entry. */
        void onResolved(boolean denied);

        /** Called once, on timeout/shutdown expiry. */
        void onExpired();

        /** No live message — used for test-registered entries. */
        LivePrompt NONE = new LivePrompt() {
            @Override public void onResolved(boolean denied) { /* no live message */ }
            @Override public void onExpired() { /* no live message */ }
        };
    }

    /** A pending approval awaiting a decisive tap. */
    private record Pending<T>(String authorizedUserId, CompletableFuture<T> future, LivePrompt prompt) {}

    /**
     * Outcome of a {@link #resolve} attempt.
     *
     * @param resolved    whether this tap completed the pending future
     * @param outcome     the resolved outcome when {@code resolved}; empty otherwise
     * @param userMessage short status for the channel's callback ack
     */
    record Resolution<T>(boolean resolved, Optional<T> outcome, String userMessage) {}

    private final ConcurrentHashMap<String, Pending<O>> pending = new ConcurrentHashMap<>();
    private final String channelName;
    private final Function<D, O> toOutcome;
    private final Predicate<O> isDenied;

    /**
     * @param channelName for the shared log lines (e.g. {@code "telegram"})
     * @param toOutcome   maps a tapped {@code Decision} to the channel's {@code Outcome}
     * @param isDenied    whether an outcome is a denial (drives the ack wording)
     */
    ApprovalRegistry(String channelName, Function<D, O> toOutcome, Predicate<O> isDenied) {
        this.channelName = channelName;
        this.toOutcome = toOutcome;
        this.isDenied = isDenied;
    }

    /**
     * Register an already-emitted approval: store its pending future keyed by
     * {@code approvalId} and return the future. The channel's {@code request} does
     * the live emit first (so a send failure never reaches here) and passes the
     * {@link LivePrompt} that retires the message on resolve/expire.
     */
    CompletableFuture<O> register(String approvalId, String authorizedUserId,
                                  CompletableFuture<O> future, LivePrompt prompt) {
        pending.put(approvalId, new Pending<>(authorizedUserId, future, prompt));
        return future;
    }

    /**
     * Block the calling (virtual) thread until the approval resolves or the timeout
     * elapses. On timeout the pending entry is dropped and expired so its stale
     * prompt can't be tapped afterwards.
     *
     * @param timedOut the outcome to yield (and expire with) on timeout
     */
    O await(CompletableFuture<O> future, Duration timeout, O timedOut) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            // Find and expire the entry this future belongs to.
            pending.entrySet().stream()
                    .filter(en -> en.getValue().future() == future)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .ifPresent(id -> expire(id, timedOut));
            future.complete(timedOut);
            return timedOut;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return timedOut;
        } catch (ExecutionException e) {
            EventLogger.warn(LOG_CATEGORY, null, channelName,
                    "Approval await failed: %s".formatted(e.getMessage()));
            return timedOut;
        }
    }

    /**
     * Resolve a pending approval from a button tap. Gates on the bound user id: a
     * tap from any other user is rejected without resolving (fail closed — a null
     * authorized id rejects everyone). On the winning tap the future completes, the
     * entry is removed before completion so a double-tap can't resolve twice, and
     * the {@link LivePrompt} retires the message.
     */
    Resolution<O> resolve(String approvalId, D decision, String fromId) {
        var p = pending.get(approvalId);
        if (p == null) {
            return new Resolution<>(false, Optional.empty(), NOT_PENDING);
        }
        if (p.authorizedUserId() == null || !p.authorizedUserId().equals(fromId)) {
            EventLogger.warn(LOG_CATEGORY, null, channelName,
                    "Rejected approval %s tap from user %s: issued for user %s".formatted(
                            approvalId, fromId, p.authorizedUserId()));
            return new Resolution<>(false, Optional.empty(), NOT_FOR_YOU);
        }
        // Won the race? Remove before completing so a double-tap can't resolve twice.
        if (pending.remove(approvalId, p)) {
            var outcome = toOutcome.apply(decision);
            p.future().complete(outcome);
            boolean denied = isDenied.test(outcome);
            p.prompt().onResolved(denied);
            EventLogger.info(LOG_CATEGORY, null, channelName,
                    "Approval %s resolved as %s by user %s".formatted(approvalId, outcome, fromId));
            return new Resolution<>(true, Optional.of(outcome), denied ? "Denied." : "Approved.");
        }
        return new Resolution<>(false, Optional.empty(), NOT_PENDING);
    }

    /**
     * Expire a pending approval without a user decision (timeout or shutdown).
     * Completes the future with {@code outcome}, removes the entry, and retires the
     * message via {@link LivePrompt#onExpired()}. No-op if already resolved.
     */
    void expire(String approvalId, O outcome) {
        var p = pending.remove(approvalId);
        if (p == null) return;
        p.future().complete(outcome);
        p.prompt().onExpired();
    }

    /** Visible only for testing: whether an approval id is still pending. */
    boolean isPending(String approvalId) {
        return pending.containsKey(approvalId);
    }

    /** Visible only for testing: drop every pending entry. */
    void clearAll() {
        pending.clear();
    }

    /**
     * Visible only for testing: register a pending entry with no live message, so
     * the resolve/expire/gating logic can be exercised against an in-memory future
     * without any channel I/O.
     */
    CompletableFuture<O> registerForTest(String approvalId, String authorizedUserId) {
        var future = new CompletableFuture<O>();
        pending.put(approvalId, new Pending<>(authorizedUserId, future, LivePrompt.NONE));
        return future;
    }

    /**
     * A short, callback-budget-friendly approval id: the first segment of a random
     * UUID gives 32 bits of entropy in 8 chars — collision-safe for the handful of
     * in-flight approvals a single bound user can have, and tiny against a 64-byte
     * callback_data budget.
     */
    static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
