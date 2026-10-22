import play.db.jpa.JPA;

/**
 * The test thread's transaction outlives a test, so a scheduler call the test deferred
 * (JCLAW-1515) and never committed would fire at the next test's commit, into that test's stub.
 */
public final class DeferredSchedulerCalls {

    private DeferredSchedulerCalls() {}

    /** Roll back the test's open transaction, dropping its deferred calls, and begin a fresh one. */
    public static void discard() {
        var tx = JPA.em().getTransaction();
        if (!tx.isActive()) return;
        tx.rollback();
        tx.begin();
    }
}
