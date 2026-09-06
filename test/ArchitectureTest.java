import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import play.db.jpa.NoTransaction;
import play.mvc.Before;
import play.test.UnitTest;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guardrails, enforced as ordinary unit tests via ArchUnit.
 *
 * <p>The static audit waves keep re-finding the same class of debt: the project
 * invests in a canonical seam (HttpFactories, GsonHolder, the OkHttp-only outbound
 * stack), then new code hand-rolls the same logic beside the available import and
 * the "single source of truth" quietly erodes. Import hygiene is the one dimension
 * that never regresses precisely because it is <em>build-enforced</em> (Spotless +
 * a pre-push grep gate) rather than left to reviewer memory. This test extends that
 * same "make it a gate, not a convention" treatment to a few high-value seams.
 *
 * <p>ArchUnit reads compiled bytecode, so these rules need no Play runtime — they
 * import the app's class files directly and assert against constructor calls and
 * package dependencies. A violation fails the check exactly like any other test.
 *
 * <p>Every rule here is strict, so a violation is a regression rather than debt.
 * The capability allowlists — which class may spawn a process, resolve a
 * model-controlled path, open a connection or reach the database, and the frozen
 * baseline of the ones that already do — live next door in {@link CapabilityRulesTest}
 * (JCLAW-1152), which asserts over this class's import.
 */
class ArchitectureTest extends UnitTest {

    /** The app's compiled production classes, read from disk (ArchUnit works on bytecode).
     *  Package-private so {@link CapabilityRulesTest} asserts over the same single import. */
    static final JavaClasses APP_CLASSES = importAppClasses();

    /**
     * Import ONLY the Gradle main-source output — {@code build/classes/java/main} — which the
     * {@code org.playframework.play1} {@code :playAutotest} task refreshes via its {@code :compileJava}
     * dependency (and {@code ./gradlew test} likewise). This is deliberately a fixed, app-only path
     * rather than the running JVM's {@code CodeSource}: under Play's FirePhoque test harness the app
     * classes are loaded from a broad, stale precompiled/framework location, so resolving via
     * {@code CodeSource} would scan the wrong bytecode entirely. Reading the Gradle output keeps the
     * rules scoped to freshly-compiled {@code app/} classes under every runner.
     */
    private static JavaClasses importAppClasses() {
        Path mainClasses = Paths.get("build/classes/java/main");
        if (!Files.isDirectory(mainClasses)) {
            throw new IllegalStateException("ArchitectureTest: " + mainClasses.toAbsolutePath()
                    + " not found — it is populated by :compileJava (a :playAutotest dependency); "
                    + "run the rules via `play autotest` or `./gradlew test`.");
        }
        return new ClassFileImporter().importPath(mainClasses);
    }

    /** Matches a call to {@code new <ownerFqn>(...)}; when {@code noArgOnly}, only the
     * zero-parameter constructor. Bytecode owner names use {@code $} for nested classes. */
    static DescribedPredicate<JavaConstructorCall> constructorCall(String ownerFqn, boolean noArgOnly) {
        return DescribedPredicate.describe(
                "a call to new " + ownerFqn + (noArgOnly ? "()" : "(...)"),
                call -> {
                    var target = call.getTarget();
                    if (!target.getOwner().getName().equals(ownerFqn)) {
                        return false;
                    }
                    return !noArgOnly || target.getRawParameterTypes().isEmpty();
                });
    }

    /**
     * JCLAW-199/772: Play 1.x wraps an action in a JPA transaction and commits only once it
     * returns. streamChat blocks its invocation thread for the whole SSE stream, so an ambient
     * transaction would hold that thread's HikariCP connection for the stream's full duration —
     * at the production pool of 64, capping concurrent DB-touching streams at 64 while thousands
     * of virtual threads sit idle. {@code @NoTransaction} opts the action out; every DB touch on
     * the path goes through a short {@code services.Tx#run} instead.
     *
     * <p>This guards the annotation, not the runtime property, and that limit is deliberate.
     * The runtime property is not observable in-process: the connection would be pinned by the
     * "agent-stream" virtual thread while it blocks on the SSE read, and OkHttp delivers tokens
     * on its own dispatcher thread, so no callback ever runs on the pinned thread — a probe
     * there reads false whether or not the defect is present (verified by wrapping
     * streamLlmLoop in a Tx.run: the probe still passed). A pool gauge is process-global and
     * play1 runs test classes concurrently, so it would read other tests' connections. Removing
     * the annotation is the regression that actually recurs, and this catches it.
     */
    @Test
    void streamingChatActionOptsOutOfThePerRequestTransaction() throws Exception {
        var streamChat = Class.forName("controllers.ApiChatController").getDeclaredMethod("streamChat");
        assertTrue(streamChat.isAnnotationPresent(NoTransaction.class),
                "ApiChatController.streamChat must carry @NoTransaction — without it Play holds a "
                        + "JPA connection for the entire SSE stream (JCLAW-772)");
    }

    /** Actions deliberately reachable with no interceptor: the unauthenticated health probe the
     *  frontend boot path and the Docker healthcheck both poll. */
    private static final Set<String> INTENTIONALLY_UNGATED = Set.of("controllers.ApiController.status");

    /** Controllers known to gate via {@code only}; a floor, so a scan that matches nothing fails. */
    private static final int KNOWN_ALLOWLIST_GATED_CONTROLLERS = 2;

    /**
     * JCLAW-772: {@code only} is an allowlist, so an action absent from every {@code @Before}
     * list is served with <em>no</em> interceptor — {@code dbPool} shipped that way and was
     * caught by a hand-written per-action test that existed only because someone thought to
     * write it. This asserts the invariant instead, so a newly added action fails here until
     * it is either gated or named in {@link #INTENTIONALLY_UNGATED}.
     *
     * <p>Every public static method counts, not just routed ones: {@code conf/routes} uses
     * Play's {@code {controller}.{action}} catch-all, so a public static helper on a controller
     * is web-reachable whether or not anyone meant it to be.
     *
     * <p>Controllers are discovered rather than listed — a hard-coded list would reproduce the
     * same "remember to add it" failure this test exists to remove. A controller with no
     * {@code only} interceptor is out of scope: a bare {@code @Before} and an
     * {@code @Before(unless = …)} denylist both already cover a new action by default.
     */
    @Test
    void everyActionOnAnAllowlistGatedControllerIsGatedOrDeclaredPublic() {
        var scanned = 0;
        for (var javaClass : APP_CLASSES) {
            if (!"controllers".equals(javaClass.getPackageName())) continue;
            var controller = javaClass.reflect();
            var gated = new HashSet<String>();
            for (var method : controller.getDeclaredMethods()) {
                var before = method.getAnnotation(Before.class);
                if (before != null) gated.addAll(Arrays.asList(before.only()));
            }
            if (gated.isEmpty()) continue;
            scanned++;
            for (var method : controller.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)) continue;
                if (method.getReturnType() != void.class) continue;
                var qualified = controller.getName() + "." + method.getName();
                assertTrue(gated.contains(method.getName()) || INTENTIONALLY_UNGATED.contains(qualified),
                        qualified + " is served UNAUTHENTICATED: @Before(only = …) is an allowlist and this "
                                + "action is in none of them. Add it to the interceptor's list, or to "
                                + "ArchitectureTest.INTENTIONALLY_UNGATED if that is deliberate (JCLAW-772)");
            }
        }
        assertTrue(scanned >= KNOWN_ALLOWLIST_GATED_CONTROLLERS,
                "expected at least " + KNOWN_ALLOWLIST_GATED_CONTROLLERS + " allowlist-gated controllers, "
                        + "scanned " + scanned + " — the scan matched nothing and would pass vacuously");
    }

    /**
     * A default-configured {@code new Gson()} silently bypasses {@code GsonHolder.GSON}'s
     * wire-format contract — {@code serializeNulls()}, the ISO-8601 {@code Instant} adapter,
     * and the deliberate HTML-escape setting (JCLAW-686/730). Purpose-built {@code GsonBuilder}
     * instances (e.g. the pretty-printer, the compact serializer) are legitimate and not
     * matched here; only the bare no-arg constructor is.
     *
     * <p>This rule is fully strict — the wave-5 remediation migrated the last three
     * offenders ({@code DiarizeAudioTool}, {@code AcpHarnessProbe}, {@code VideoInterpretationClient})
     * to {@code GsonHolder.GSON}, so any bare {@code new Gson()} anywhere but the holder now fails.
     */
    @Test
    void defaultGsonConstructionGoesThroughGsonHolder() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName("utils.GsonHolder")
                .should().callConstructorWhere(constructorCall("com.google.gson.Gson", true))
                .because("a bare `new Gson()` bypasses GsonHolder.GSON's serializeNulls / "
                        + "Instant-adapter / HTML-escape contract (JCLAW-686/730)");
        rule.check(APP_CLASSES);
    }

    /**
     * Every model-status/prefetch store must extend {@code ModelPrefetchStore}, the shared base
     * that owns the {@code State} enum + {@code wireName()} frontend-lockstep contract, the
     * single-flight prefetch primitive, and the status ladder (wave-5 DRY consolidation). This
     * stops a third {@code *ModelStore} from re-copying that machine the way {@code AsrModelStore}
     * and {@code DiarizeModelStore} once did — the exact duplication the base was extracted to end.
     */
    @Test
    void modelStoresExtendTheSharedPrefetchBase() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("ModelStore")
                .should().beAssignableTo("services.transcription.ModelPrefetchStore")
                .because("a *ModelStore must reuse ModelPrefetchStore's shared status/prefetch machine, "
                        + "not re-copy it (wave-5 DRY consolidation)");
        rule.check(APP_CLASSES);
    }

    /**
     * {@code LuceneIndexer} is the one search class invoked <em>from</em> JPA lifecycle
     * callbacks — every indexed entity calls it from {@code @PostPersist}/{@code @PostUpdate}/
     * {@code @PostRemove}. A dependency back into {@code models} therefore closes a write-path
     * loop: an entity hook calls the indexer, which reads another entity. The last such edge was
     * a pre-JCLAW-304 convenience overload, {@code upsert(TaskRunMessage)}, with a single call
     * site; inlining it to the explicit {@code upsert(Scope, long, String)} form removed the
     * import. This rule keeps it removed — the indexer takes scope + id + text, never an entity.
     *
     * <p>Deliberately scoped to this one class rather than the whole {@code services.search}
     * package: the search <em>repositories</em> ({@code DirectLuceneMessageSearchRepository},
     * {@code MessageSearch}, and the two {@code MessageSearchRepository} implementations) query
     * and return entities, which is the ordinary services-to-models direction and is not part of
     * any callback path. A package-wide rule would flag that legitimate traffic.
     *
     * <p>Scope note: ArchUnit checks direct dependencies, which is what this rule pins. A
     * transitive path still exists via {@code services.EventLogger} (the indexer's no-throw
     * failure log) into {@code models.EventLog} — that one is safe by construction because
     * {@code EventLogger} queues to a {@code ConcurrentLinkedQueue} and flushes out-of-band,
     * so no JPA write re-enters from inside a lifecycle callback.
     */
    @Test
    void luceneIndexerDoesNotDependOnModels() {
        ArchRule rule = noClasses()
                .that().haveFullyQualifiedName("services.search.LuceneIndexer")
                .should().dependOnClassesThat().resideInAPackage("models..")
                .because("LuceneIndexer is called from entity @PostPersist/@PostUpdate/@PostRemove hooks; "
                        + "depending back on models closes a write-path cycle. It takes scope + id + text, "
                        + "never an entity");
        rule.check(APP_CLASSES);
    }
}
