import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The one boot-job ordering the app cannot start without (JCLAW-1266).
 *
 * <p>{@link jobs.ApiTokenLegacyColumnsJob} relaxes the NOT NULL that stops the internal token
 * being minted on an upgraded database. {@link jobs.DefaultConfigJob} performs that mint, and runs
 * at {@code priority = -100} precisely because it is meant to go early. If the migration does not
 * run first, the boot dies inside the mint and the migration never executes — the failure the
 * migration exists to prevent, with the fix present but too late.
 *
 * <p>Ordering between {@code @OnApplicationStart} jobs is otherwise unconstrained here — 34 of
 * them carry no priority at all — so this pins the single pair that matters rather than imposing
 * an order nobody needs. Lower runs earlier.
 *
 * <p>Read from source rather than by reflection so the check works without booting the jobs, with
 * comments stripped: a priority quoted in prose is not a priority.
 */
class BootJobOrderConformanceTest extends UnitTest {

    private static final Pattern PRIORITY =
            Pattern.compile("@OnApplicationStart\\(\\s*priority\\s*=\\s*(-?\\d+)");

    private static final String MIGRATION = "ApiTokenLegacyColumnsJob";
    private static final String MINTER = "DefaultConfigJob";

    /** Job simple name to declared priority; absent means Play's default of 0. */
    private static TreeMap<String, Integer> declaredPriorities() throws IOException {
        var out = new TreeMap<String, Integer>();
        var dir = Path.of(Play.applicationPath.getAbsolutePath()).resolve("app/jobs");
        try (Stream<Path> files = Files.walk(dir)) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var name = file.getFileName().toString().replace(".java", "");
                var source = ResourceLeakGateConformanceTest.withoutComments(Files.readString(file));
                var matcher = PRIORITY.matcher(source);
                out.put(name, matcher.find() ? Integer.parseInt(matcher.group(1)) : 0);
            }
        }
        return out;
    }

    @Test
    void theApiTokenMigrationRunsBeforeTheJobThatMintsTheToken() throws IOException {
        var priorities = declaredPriorities();

        assertTrue(priorities.containsKey(MIGRATION) && priorities.containsKey(MINTER),
                "both jobs must exist for this check to mean anything; saw " + priorities.keySet());

        int migration = priorities.get(MIGRATION);
        int minter = priorities.get(MINTER);
        assertTrue(migration < minter,
                MIGRATION + " (priority " + migration + ") must run before " + MINTER + " (priority "
                        + minter + "), which mints the internal token. Lower runs earlier. At or after "
                        + "it, an upgraded database dies inside the mint and this migration never runs.");
    }

    @Test
    void nothingElseClaimsToRunBeforeTheMigration() throws IOException {
        var priorities = declaredPriorities();
        int migration = priorities.get(MIGRATION);

        var earlier = new TreeMap<String, Integer>();
        priorities.forEach((job, priority) -> {
            if (!job.equals(MIGRATION) && priority <= migration) earlier.put(job, priority);
        });

        assertTrue(earlier.isEmpty(),
                "these jobs run at or before " + MIGRATION + " (priority " + migration + "). Anything "
                        + "touching api_token or the config table from there would run against the "
                        + "un-migrated schema: " + earlier);
    }

    /** A regex that stopped matching would make both checks above pass on an empty reading. */
    @Test
    void theScanActuallyReadsTheAnnotations() throws IOException {
        var priorities = declaredPriorities();
        assertTrue(priorities.size() >= 30,
                "expected 30+ job classes under app/jobs, found " + priorities.size());
        assertEquals(-100, priorities.get(MINTER),
                "DefaultConfigJob's priority is the anchor this check compares against; if it moved, "
                        + "re-derive the migration's rather than trusting the comparison");
        assertTrue(priorities.containsValue(100),
                "expected DbSchedulerBootstrapJob's +100 to still be read, or the parser is matching "
                        + "nothing and every comparison above is vacuous");
    }
}
