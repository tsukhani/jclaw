import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import jobs.PeriodicJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.jobs.Every;
import play.jobs.On;
import play.test.UnitTest;

import javax.tools.ToolProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Every job Play schedules extends {@link PeriodicJob} (JF-23): H2 closes the database at SIGTERM
 * while Play still fires {@code @Every} and {@code @On} jobs, and only the base skips those ticks.
 */
class PeriodicJobConformanceTest extends UnitTest {

    private static final ArchRule SCHEDULED_JOBS_EXTEND_PERIODIC_JOB = classes()
            .that().areAnnotatedWith(Every.class).or().areAnnotatedWith(On.class)
            .should().beAssignableTo(PeriodicJob.class)
            .because("H2 closes the database at SIGTERM while Play still fires scheduled jobs, "
                    + "and PeriodicJob skips a tick from then on (JF-23)");

    @Test
    void everyScheduledJobExtendsPeriodicJob() {
        SCHEDULED_JOBS_EXTEND_PERIODIC_JOB.check(ArchitectureTest.APP_CLASSES);
    }

    @Test
    void theRuleSeesTheScheduledJobs() {
        List<String> scheduled = ArchitectureTest.APP_CLASSES.stream()
                .filter(c -> c.isAnnotatedWith(Every.class) || c.isAnnotatedWith(On.class))
                .map(JavaClass::getName)
                .toList();

        assertTrue(scheduled.contains("jobs.ScrapeJobRunner"), "the rule must see the scheduled jobs; it saw " + scheduled);
    }

    // Play compiles test/ in memory, leaving ArchUnit no bytecode to read, so the probe is compiled
    // here. It is not a Job, so Play would never schedule it even on its classpath.
    @Test
    void theRuleReportsAScheduledClassThatIsNotAPeriodicJob(@TempDir Path dir) throws Exception {
        var source = dir.resolve("Probe.java");
        Files.writeString(source, "@play.jobs.Every(\"1h\") public class Probe {}\n");
        var playJar = Path.of(Every.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-cp", playJar, "-d", dir.toString(), source.toString()), "the probe compiles");

        var details = SCHEDULED_JOBS_EXTEND_PERIODIC_JOB.evaluate(new ClassFileImporter().importPath(dir))
                .getFailureReport().getDetails();

        assertEquals(1, details.size(), details.toString());
        assertTrue(details.getFirst().startsWith("Class <Probe> is not assignable to jobs.PeriodicJob"), details.getFirst());
    }
}
