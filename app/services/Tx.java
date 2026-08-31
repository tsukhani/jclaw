package services;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import org.hibernate.Session;
import play.Logger;
import play.db.jpa.JPA;
import play.libs.F;

/**
 * Transaction helper for running JPA operations from any thread context.
 * If already inside a JPA transaction (request thread, @OnApplicationStart job),
 * runs the block directly to avoid orphaning the existing EntityManager.
 * Otherwise, delegates to JPA.withTransaction to create a new one.
 */
public class Tx {

    /** ShutdownJob names each teardown virtual thread {@code shutdown-<component>}. */
    private static final String SHUTDOWN_THREAD_PREFIX = "shutdown-";

    private Tx() {}

    /**
     * JCLAW-1144: name any shutdown component that reaches the database.
     *
     * <p>Teardown must not depend on the DB, and static analysis cannot check that on its
     * own: the access is usually transitive ({@code disableIfEnabled -> isFunnelEnabled ->
     * ConfigService.get}) and can sit inside a third-party frame that ArchUnit never
     * imports. Every application transaction passes through here, so this is the one place
     * that sees all of it, whatever the route.
     *
     * <p>The thread name carries the component identity, so a hit names the culprit
     * directly. It reports only what actually executes — a warm config cache means no
     * transaction and no warning — so treat a quiet shutdown as weak evidence, not proof.
     */
    public static boolean isShutdownTeardownThread(boolean shuttingDown, String threadName) {
        return shuttingDown && threadName != null && threadName.startsWith(SHUTDOWN_THREAD_PREFIX);
    }

    private static void warnIfTeardownTouchesDb() {
        var name = Thread.currentThread().getName();
        // play.Logger, not EventLogger: EventLogger's own flush path can reach a transaction,
        // and a warning that re-enters Tx.run would recurse.
        if (isShutdownTeardownThread(EventLogger.isShuttingDown(), name)) {
            Logger.warn("Shutdown teardown reached the database from %s — "
                    + "teardown must not depend on the DB (JCLAW-1143)", name);
        }
    }

    /**
     * Run a block that returns a value, ensuring a JPA transaction is active.
     */
    @SuppressWarnings("java:S112") // Generic RuntimeException is the correct wrapper for the type-erased Throwable from F.Function0
    public static <T> T run(F.Function0<T> block) {
        warnIfTeardownTouchesDb();
        if (JPA.isInsideTransaction()) {
            try {
                return block.apply();
            } catch (Throwable t) {
                if (t instanceof RuntimeException re) throw re;
                throw new RuntimeException(t);
            }
        }
        try {
            return JPA.withTransaction("default", false, block);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException(t);
        }
    }

    /**
     * Run a void block, ensuring a JPA transaction is active.
     */
    public static void run(Runnable block) {
        run(() -> {
            block.run();
            return null;
        });
    }

    /**
     * Run {@code action} once the ambient transaction commits, or immediately when there is
     * no transaction to wait for. For cache and index evictions (JCLAW-1042): an eviction
     * ordered ahead of the commit lets a racing read re-populate the entry from the row the
     * commit has not replaced yet, so the stale value survives the commit for the cache's
     * whole TTL — and for a password hash or a revoked tool grant, "stale" means "still
     * works".
     *
     * <p>The no-transaction arm is load-bearing rather than a convenience. {@link #run} joins
     * an ambient transaction instead of opening its own, so a caller cannot tell from its own
     * code whether it has committed; registering a synchronization with no transaction present
     * would silently drop the eviction and turn a stale entry into a permanent one.
     *
     * <p>Fires only on {@link Status#STATUS_COMMITTED} — a rolled-back write invalidated
     * nothing, so evicting for it would discard a live entry for no reason.
     */
    public static void afterCommit(Runnable action) {
        if (!JPA.isInsideTransaction()) {
            action.run();
            return;
        }
        afterCommit(JPA.em().unwrap(Session.class), action);
    }

    /**
     * Session-taking overload so a test can drive the commit/rollback boundary explicitly
     * against a fresh EntityManager, rather than depending on when the harness's ambient
     * transaction ends. Public for the same reason
     * {@code ConfigService.scheduleRollbackEviction} is: Play compiles {@code test/} into the
     * default package, which cannot see package-private members.
     */
    public static void afterCommit(Session session, Runnable action) {
        session.getTransaction().registerSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                // no-op: the run/skip decision is made after completion
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) action.run();
            }
        });
    }
}
