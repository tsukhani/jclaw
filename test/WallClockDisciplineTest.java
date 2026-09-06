import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.freeze.TextFileBasedViolationStore;
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
import java.util.TreeSet;
import java.util.concurrent.Executors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Wall-clock discipline (JCLAW-1150): {@code app/} reads "now" through {@link AppClock}
 * and nowhere else, so a test can bind a fixed clock instead of sleeping or asserting
 * on a ±1s window.
 *
 * <p>A sibling of {@code ArchitectureTest} rather than a rule inside it, so the frozen stores
 * under {@code archunit_store/} read as one file per rule beside {@code CapabilityRulesTest}'s.
 *
 * <p>The store is {@code archunit_store/wall-clock-interval-baselines}, committed;
 * {@code conf/archunit.properties} pins both {@code allowStoreCreation} and
 * {@code allowStoreUpdate} off, so a run can neither mint a baseline nor rewrite one: a new
 * read fails, and a listed read that migrates also fails until its store line is deleted by
 * hand. Regenerate by flipping both switches for a single run — {@code playAutotest} does not
 * forward {@code -D} to the Play JVM, so the properties file is the only lever. The store key
 * is the rule's description, so editing the {@code because(...)} text fails the next run
 * loudly rather than silently re-freezing.
 */
class WallClockDisciplineTest extends UnitTest {

    /** See {@code ArchitectureTest.importAppClasses} for why this is a fixed path, not the JVM's CodeSource. */
    private static final JavaClasses APP_CLASSES = importAppClasses();

    /** Source files holding a frozen {@code System.currentTimeMillis()} interval baseline (JCLAW-1150). */
    private static final int FROZEN_INTERVAL_FILES = 18;

    private static JavaClasses importAppClasses() {
        Path mainClasses = Paths.get("build/classes/java/main");
        if (!Files.isDirectory(mainClasses)) {
            throw new IllegalStateException("WallClockDisciplineTest: " + mainClasses.toAbsolutePath()
                    + " not found — it is populated by :compileJava (a :playAutotest dependency); "
                    + "run the rules via `play autotest` or `./gradlew test`.");
        }
        return new ClassFileImporter().importPath(mainClasses);
    }

    /** The {@code java.time} types whose parameterless static {@code now()} reads the ambient system clock. */
    private static final Set<String> AMBIENT_NOW_OWNERS = Set.of(
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.OffsetDateTime",
            "java.time.ZonedDateTime",
            "java.time.Year",
            "java.time.YearMonth");

    private static final Set<String> SYSTEM_CLOCK_FACTORIES = Set.of("systemUTC", "systemDefaultZone", "system");

    /**
     * Matches accesses rather than calls, so a method reference ({@code Instant::now}) counts
     * the same as a call. {@code now(Clock)} reads the clock it is given, not the ambient one,
     * and passes.
     */
    private static DescribedPredicate<JavaAccess<?>> wallClockRead() {
        return DescribedPredicate.describe(
                "a wall-clock read (a parameterless java.time now(), System.currentTimeMillis(), "
                        + "new Date(), Calendar.getInstance() or a Clock.system* factory)",
                access -> {
                    var target = access.getTarget();
                    var owner = target.getOwner().getName();
                    var name = target.getName();
                    boolean noArgs = target instanceof CodeUnitAccessTarget unit
                            && unit.getRawParameterTypes().isEmpty();
                    return switch (owner) {
                        case "java.lang.System" -> "currentTimeMillis".equals(name);
                        case "java.util.Date" -> "<init>".equals(name) && noArgs;
                        case "java.util.Calendar" -> "getInstance".equals(name);
                        case "java.time.Clock" -> SYSTEM_CLOCK_FACTORIES.contains(name);
                        default -> AMBIENT_NOW_OWNERS.contains(owner) && "now".equals(name) && noArgs;
                    };
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

        // The floor guards the deliberate regeneration run, when the store switches are on: a
        // predicate that had stopped matching would then prune the baseline to empty and pass.
        var intervalFiles = new TreeSet<String>();
        for (var javaClass : APP_CLASSES) {
            if (!"utils.AppClock".equals(javaClass.getName())
                    && javaClass.getAccessesFromSelf().stream().anyMatch(wallClockRead())) {
                javaClass.getSource().flatMap(Source::getFileName).ifPresent(intervalFiles::add);
            }
        }
        assertTrue(intervalFiles.size() >= FROZEN_INTERVAL_FILES,
                "expected at least " + FROZEN_INTERVAL_FILES + " source files with a frozen interval "
                        + "baseline, found " + intervalFiles.size() + " " + intervalFiles + " — the predicate "
                        + "stopped matching. Lower the constant only once a holder was genuinely migrated");

        ArchRule rule = FreezingArchRule.freeze(noClasses()
                .that().doNotHaveFullyQualifiedName("utils.AppClock")
                .should().accessTargetWhere(wallClockRead())
                .because("wall-clock reads go through utils.AppClock (JCLAW-1150) so tests can bind "
                        + "a fixed clock; the frozen entries are System.currentTimeMillis interval "
                        + "baselines, not reads of the calendar"))
                .persistIn(new TextFileBasedViolationStore(description -> "wall-clock-interval-baselines"));
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
