import controllers.ApiAppInvokeController;
import org.junit.jupiter.api.Test;
import play.data.Upload;
import play.db.jpa.NoTransaction;
import play.test.UnitTest;

/**
 * JCLAW-807-follow-up / JCLAW-199 pattern: {@code invoke} must opt out of Play 1.x's
 * per-request JPA transaction. That annotation is the load-bearing mechanism of the
 * fix — with the request tx open, {@code AgentRunner.run}'s internal {@code Tx.run}
 * blocks short-circuit into it and the pooled HikariCP connection is pinned for the
 * whole multi-second agent run. The end-to-end wiring (conversation + attachments
 * still persisted and visible under {@code @NoTransaction}, each DB touch now in its
 * own short {@code Tx.run}) is covered by {@code ApiAppInvokeControllerTest}; this
 * guards against a silent removal of the opt-out that would reintroduce the stall.
 */
class ApiAppInvokeControllerNoTransactionTest extends UnitTest {

    @Test
    void invokeOptsOutOfThePerRequestTransaction() throws NoSuchMethodException {
        var invoke = ApiAppInvokeController.class.getDeclaredMethod("invoke", String.class, Upload[].class);
        assertNotNull(invoke.getAnnotation(NoTransaction.class),
                "invoke() must carry @NoTransaction so the agent run never pins the request's pooled DB connection");
    }
}
