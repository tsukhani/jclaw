package agents;

import com.google.errorprone.annotations.MustBeClosed;
import org.jspecify.annotations.Nullable;
import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Actions to run when a turn is cancelled, keyed by the turn's own cancel flag (by identity).
 * Every path that cancels a turn calls {@link #cancel}, so a tool registers once and is reached
 * by a stop, an SSE disconnect or interrupt mode alike, with no poller and no interrupt.
 */
public final class TurnCancellation {

    private static final ConcurrentHashMap<AtomicBoolean, Set<Registration>> ACTIONS = new ConcurrentHashMap<>();

    private TurnCancellation() {}

    /** One registered action; {@link #close} deregisters it, after which it never runs. */
    public static final class Registration implements AutoCloseable {
        private final @Nullable AtomicBoolean flag;
        private final Runnable action;
        /** Claimed by whichever of run and close comes first, so the action runs at most once. */
        private final AtomicBoolean claimed = new AtomicBoolean(false);

        private Registration(@Nullable AtomicBoolean flag, Runnable action) {
            this.flag = flag;
            this.action = action;
        }

        /** A registration with nothing behind it, for a scope with no turn flag. */
        static Registration none() {
            return new Registration(null, () -> {});
        }

        @Override
        public void close() {
            claimed.set(true);
            var f = flag;
            if (f == null) return;
            ACTIONS.computeIfPresent(f, (_, set) -> {
                set.remove(this);
                return set.isEmpty() ? null : set;
            });
        }
    }

    /**
     * Register {@code action} to run when {@code flag} is cancelled through {@link #cancel}. When the
     * flag is already set, the action runs at once (still off the caller's thread).
     */
    @MustBeClosed
    public static Registration register(AtomicBoolean flag, Runnable action) {
        var reg = new Registration(flag, action);
        ACTIONS.compute(flag, (_, set) -> {
            var s = set != null ? set : ConcurrentHashMap.<Registration>newKeySet();
            s.add(reg);
            return s;
        });
        if (flag.get()) {
            ACTIONS.computeIfPresent(flag, (_, set) -> {
                set.remove(reg);
                return set.isEmpty() ? null : set;
            });
            runOffThread(List.of(reg));
        }
        return reg;
    }

    /** Set {@code flag} and run every action registered against it, once, on a new platform thread. */
    public static void cancel(AtomicBoolean flag) {
        flag.set(true);
        var regs = ACTIONS.remove(flag);
        if (regs != null && !regs.isEmpty()) runOffThread(new ArrayList<>(regs));
    }

    // A platform thread: an action may wait a timed grace (JDK-8373224) or open a transaction.
    private static void runOffThread(List<Registration> regs) {
        Thread.ofPlatform().daemon().name("turn-cancel").start(() -> {
            for (var reg : regs) {
                if (!reg.claimed.compareAndSet(false, true)) continue;
                try {
                    reg.action.run();
                } catch (RuntimeException e) {
                    EventLogger.warn("agent", "Turn cancel action threw: %s".formatted(e));
                }
            }
        });
    }
}
