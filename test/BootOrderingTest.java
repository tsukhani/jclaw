import org.junit.jupiter.api.Test;
import play.jobs.OnApplicationStart;
import play.test.UnitTest;

/**
 * JCLAW-413: boot-ordering invariants enforced via {@code @OnApplicationStart}
 * priority (play1 1.13.27+ runs startup jobs in ascending priority order — lower
 * runs first). Guards against a priority/async change silently reintroducing the
 * boot races these orderings prevent (notably JCLAW-411: a task firing on the
 * scheduler's first poll before the tool registry has been published).
 */
class BootOrderingTest extends UnitTest {

    private static int priority(Class<?> jobClass) {
        var a = jobClass.getAnnotation(OnApplicationStart.class);
        assertNotNull(a, jobClass.getSimpleName() + " must be @OnApplicationStart");
        return a.priority();
    }

    private static boolean isSync(Class<?> jobClass) {
        return !jobClass.getAnnotation(OnApplicationStart.class).async();
    }

    @Test
    void schedulerBootstrapRunsAfterConfigToolsAndSchema() {
        int bootstrap = priority(jobs.DbSchedulerBootstrapJob.class);
        assertTrue(priority(jobs.DefaultConfigJob.class) < bootstrap,
                "config must be seeded before the scheduler can fire tasks");
        assertTrue(priority(jobs.ToolRegistrationJob.class) < bootstrap,
                "built-in tools must register before the scheduler fires (JCLAW-411)");
        assertTrue(priority(jobs.DbSchedulerSchemaInitJob.class) < bootstrap,
                "scheduled_tasks schema must exist before the scheduler starts");
    }

    @Test
    void defaultConfigRunsBeforeItsStartupReaders() {
        int config = priority(jobs.DefaultConfigJob.class);
        assertTrue(config < priority(jobs.ToolRegistrationJob.class),
                "DefaultConfigJob must run before ToolRegistrationJob reads config");
        assertTrue(config < priority(jobs.OllamaLocalProbeJob.class),
                "DefaultConfigJob must run before the Ollama probe reads config");
        assertTrue(config < priority(jobs.LmStudioProbeJob.class),
                "DefaultConfigJob must run before the LM-Studio probe reads config");
    }

    @Test
    void orderedBootJobsAreSynchronous() {
        // Async jobs are only *submitted* in priority order; the before/after
        // guarantee these orderings depend on requires the jobs to be synchronous.
        assertTrue(isSync(jobs.DefaultConfigJob.class), "DefaultConfigJob must be synchronous");
        assertTrue(isSync(jobs.ToolRegistrationJob.class), "ToolRegistrationJob must be synchronous");
        assertTrue(isSync(jobs.DbSchedulerSchemaInitJob.class), "DbSchedulerSchemaInitJob must be synchronous");
        assertTrue(isSync(jobs.DbSchedulerBootstrapJob.class), "DbSchedulerBootstrapJob must be synchronous");
    }
}
