import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.AppClock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.Executors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Wall-clock discipline (JCLAW-1150): {@code app/} reads "now" through {@link AppClock}
 * and nowhere else, so a test can bind a fixed clock instead of sleeping or asserting
 * on a ±1s window.
 *
 * <p>A sibling of {@code ArchitectureTest} rather than a rule inside it: this one rule
 * owns a committed violation store, and keeping the store's rule key tied to a class
 * whose other rules churn would make every unrelated edit a candidate for a stale store.
 *
 * <p>The store lives in {@code archunit_store/} and is committed; {@code conf/archunit.properties}
 * pins {@code allowStoreCreation=false} so a run can never silently re-freeze whatever it finds.
 * Regenerate by flipping that one line for a single run — {@code playAutotest} does not forward
 * {@code -D} to the Play JVM, so the properties file is the only lever. The store key is the
 * rule's description, so editing the {@code because(...)} text orphans the baseline.
 */
class WallClockDisciplineTest extends UnitTest {

    /** See {@code ArchitectureTest.importAppClasses} for why this is a fixed path, not the JVM's CodeSource. */
    private static final JavaClasses APP_CLASSES = importAppClasses();

    private static JavaClasses importAppClasses() {
        Path mainClasses = Paths.get("build/classes/java/main");
        if (!Files.isDirectory(mainClasses)) {
            throw new IllegalStateException("WallClockDisciplineTest: " + mainClasses.toAbsolutePath()
                    + " not found — it is populated by :compileJava (a :playAutotest dependency); "
                    + "run the rules via `play autotest` or `./gradlew test`.");
        }
        return new ClassFileImporter().importPath(mainClasses);
    }

    /** The {@code java.time} types whose static {@code now(...)} reads the ambient system clock. */
    private static final Set<String> AMBIENT_NOW_OWNERS = Set.of(
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime");

    private static DescribedPredicate<JavaMethodCall> wallClockRead() {
        return DescribedPredicate.describe(
                "a wall-clock read (Instant/LocalDate/LocalDateTime/OffsetDateTime/ZonedDateTime.now "
                        + "or System.currentTimeMillis)",
                call -> {
                    var target = call.getTarget();
                    var owner = target.getOwner().getName();
                    if ("java.lang.System".equals(owner)) {
                        return "currentTimeMillis".equals(target.getName());
                    }
                    return AMBIENT_NOW_OWNERS.contains(owner) && "now".equals(target.getName());
                });
    }

    /**
     * Every wall-clock read in {@code app/} goes through {@link AppClock}.
     *
     * <p>Frozen rather than strict because the {@code System.currentTimeMillis()} sites are
     * not all wall-clock reads: most are interval baselines for a throttle, a rate-limit
     * window or a duration, where migrating them would change throttle behaviour for no gain.
     * They are recorded in {@code archunit_store} so the exception list is explicit and
     * shrinkable, and so a <em>new</em> read of either kind still fails here.
     *
     * <p>{@code System.nanoTime()} is deliberately not matched: it has no relation to
     * wall-clock time and is the correct primitive for the interval measurement in
     * {@code utils.LatencyStats}.
     */
    @Test
    void wallClockReadsGoThroughAppClock() {
        var readsTheAppClock = APP_CLASSES.stream()
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .anyMatch(call -> "utils.AppClock".equals(call.getTargetOwner().getName()));
        assertTrue(readsTheAppClock,
                "no call to utils.AppClock found in the imported classes — the scan is looking at "
                        + "the wrong bytecode and every rule below would pass vacuously");

        ArchRule rule = FreezingArchRule.freeze(noClasses()
                .that().doNotHaveFullyQualifiedName("utils.AppClock")
                .should().callMethodWhere(wallClockRead())
                .because("wall-clock reads go through utils.AppClock (JCLAW-1150) so tests can bind "
                        + "a fixed clock; the frozen entries are System.currentTimeMillis interval "
                        + "baselines, not reads of the calendar"));
        rule.check(APP_CLASSES);
    }

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2020-02-29T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void aBoundClockIsVisibleToNowAndUnbindsAfterTheScope() {
        AppClock.runWith(FIXED, () -> assertEquals(FIXED.instant(), AppClock.now()));
        assertNotEquals(FIXED.instant(), AppClock.now(),
                "the binding must not outlive its scope — the fork runs test classes concurrently");
    }

    /**
     * Pins the propagation boundary documented on {@link AppClock}: a {@code ScopedValue}
     * binding does not reach a thread from {@code newVirtualThreadPerTaskExecutor}. A test
     * that spans one of those must read the clock before spawning or rebind inside the task.
     */
    @Test
    void theBindingDoesNotCrossAVirtualThreadExecutor() throws Exception {
        Instant seenInsideTheExecutor = AppClock.callWith(FIXED, () -> {
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                return pool.submit(AppClock::now).get();
            }
        });
        assertNotEquals(FIXED.instant(), seenInsideTheExecutor);
    }
}
