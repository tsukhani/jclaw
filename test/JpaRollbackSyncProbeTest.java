import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;

/**
 * JCLAW-832 investigation probe: does a Hibernate {@code afterCompletion}
 * synchronization fire on resource-local transaction commit/rollback in this
 * play1 fork? Uses a FRESH, thread-unbound EntityManager ({@link JPA#newEntityManager})
 * so it never touches the harness's ambient JPA context — that ambient conflict,
 * not the mechanism, is what broke the earlier withTransaction-based 832 tests.
 * If these pass, the synchronization approach is viable and the fix is re-shippable.
 */
class JpaRollbackSyncProbeTest extends UnitTest {

    private int registerAndComplete(boolean rollback) {
        var fired = new int[] {-999};
        EntityManager em = JPA.newEntityManager("default");
        try {
            em.getTransaction().begin();
            em.unwrap(Session.class).getTransaction().registerSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    // no-op
                }

                @Override
                public void afterCompletion(int status) {
                    fired[0] = status;
                }
            });
            if (rollback) {
                em.getTransaction().rollback();
            } else {
                em.getTransaction().commit();
            }
        } finally {
            if (em.isOpen()) {
                em.close();
            }
        }
        return fired[0];
    }

    @Test
    void afterCompletionFiresWithRolledBackStatusOnRollback() {
        assertEquals(Status.STATUS_ROLLEDBACK, registerAndComplete(true),
                "afterCompletion must fire with STATUS_ROLLEDBACK when the tx rolls back");
    }

    @Test
    void afterCompletionFiresWithCommittedStatusOnCommit() {
        assertEquals(Status.STATUS_COMMITTED, registerAndComplete(false),
                "afterCompletion must fire with STATUS_COMMITTED when the tx commits");
    }
}
