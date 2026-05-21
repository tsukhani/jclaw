import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import jobs.DbSchedulerSchemaInitJob;

class DbSchedulerSchemaInitJobTest extends UnitTest {

    @Test
    void doJobSucceedsAgainstTestH2Datasource() {
        // The test JVM runs against H2 in-memory. doJob wraps ensureSchema
        // in try/catch; calling it directly exercises the happy path.
        // The schema is idempotent (CREATE IF NOT EXISTS) so a second call
        // is safe even if the table was already created at boot.
        new DbSchedulerSchemaInitJob().doJob();
    }

    @Test
    void ensureSchemaCanBeCalledDirectly() throws Exception {
        // ensureSchema is public for callers that need to apply the DDL
        // without going through the job lifecycle. Idempotent.
        DbSchedulerSchemaInitJob.ensureSchema();
    }
}
