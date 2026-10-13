import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * A task run owns the subagent runs it started, and the database cannot delete them for it: a
 * run's transcript, search documents and spawned agent sit outside any foreign key. So every bulk
 * delete of task runs has to hand them to {@code SubagentRegistry.deleteRunsStartedBy} first. A
 * site that forgets leaves those runs behind for good, bounded by nothing.
 */
class TaskRunDeletionConformanceTest extends UnitTest {

    private static final String BULK_DELETE = "DELETE FROM TaskRun r";
    private static final String HAND_OVER = "SubagentRegistry.deleteRunsStartedBy(";

    @Test
    void everyBulkDeleteOfTaskRunsFirstDeletesTheRunsTheyStarted() throws IOException {
        var offenders = new ArrayList<String>();
        int sites = 0;
        try (var files = Files.walk(Path.of(Play.applicationPath.getAbsolutePath()).resolve("app"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var source = ResourceLeakGateConformanceTest.withoutComments(Files.readString(file));
                int deletes = count(source, BULK_DELETE);
                sites += deletes;
                if (deletes > count(source, HAND_OVER)) offenders.add(file.getFileName().toString());
            }
        }
        assertTrue(offenders.isEmpty(), "these files bulk-delete task runs without first deleting the "
                + "subagent runs those task runs started: " + offenders);
        assertTrue(sites >= 6, "the scan must still find the delete sites it guards; it found " + sites);
    }

    private static int count(String source, String needle) {
        int n = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) n++;
        return n;
    }
}
