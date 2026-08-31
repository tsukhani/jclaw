import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Rules 1 and 2 currently hold clean. Rule 3 carries an explicit, visible list
 * of the three pre-existing {@code new Gson()} offenders the wave-5 audit found;
 * the rule guards every <em>other</em> class from regressing, and each excluded
 * entry should be deleted as that class migrates to {@code GsonHolder.GSON}.
 * When a baseline like this grows past a handful of sites, switch the rule to
 * {@link com.tngtech.archunit.library.freeze.FreezingArchRule#freeze} instead,
 * which manages the baseline in a committed violation store.
 */
class ArchitectureTest extends UnitTest {

    /** The app's compiled production classes, read from disk (ArchUnit works on bytecode). */
    private static final JavaClasses APP_CLASSES = importAppClasses();

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
    private static DescribedPredicate<JavaConstructorCall> constructorCall(String ownerFqn, boolean noArgOnly) {
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
     * Outbound OkHttp clients must be provisioned through {@code HttpFactories}
     * (JCLAW-185..188), which owns the shared connection pools and virtual-thread
     * dispatcher. The two documented exceptions build their own tuned clients:
     * {@code SsrfGuard} (per-request DNS allow-list) and {@code TelegramBotApiHttpClients}
     * (the Telegram SDK's stack). Deriving a per-call client via {@code someFactoryClient
     * .newBuilder()} is fine — it reuses the pool — so this rule targets only the
     * from-scratch {@code new OkHttpClient.Builder()} constructor.
     */
    @Test
    void okHttpClientsAreProvisionedThroughHttpFactories() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName("utils.HttpFactories")
                .and().doNotHaveFullyQualifiedName("utils.SsrfGuard")
                .and().doNotHaveFullyQualifiedName("channels.TelegramBotApiHttpClients")
                .should().callConstructorWhere(constructorCall("okhttp3.OkHttpClient$Builder", true))
                .because("outbound OkHttp clients must come from HttpFactories (JCLAW-185..188); "
                        + "SsrfGuard and the Telegram SDK are the documented exceptions");
        rule.check(APP_CLASSES);
    }

    /**
     * Outbound HTTP in app/ is OkHttp-only; the JDK {@code java.net.http.HttpClient}
     * stack was removed in the OkHttp migration (JCLAW-185..188) to avoid the LM Studio
     * h2c-upgrade hang and to keep a single virtual-thread-clean client. This guards
     * against the second stack creeping back in.
     */
    @Test
    void noJdkHttpClientInApp() {
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("java.net.http..")
                .because("outbound HTTP is OkHttp-only in app/ (JCLAW-185..188); the JDK HttpClient was removed");
        rule.check(APP_CLASSES);
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

    // ===== JCLAW-1143/1144: shutdown teardown must not reach the database =====

    /** Call targets that mean "this reached the database". */
    private static final Set<String> DB_SINK_OWNERS = Set.of(
            "services.Tx", "services.ConfigService", "play.db.jpa.JPA");

    /**
     * Traversal stops here. EventLogger is called by nearly every component and its flush
     * path does reach a transaction, but it is explicitly shutdown-aware: ShutdownJob calls
     * markShuttingDown() before any component runs, after which record() and flush() go
     * file-only. Following it would flag all 17 components for a path that cannot execute.
     */
    private static final Set<String> SHUTDOWN_AWARE = Set.of("services.EventLogger");

    /**
     * A method that consults {@code EventLogger.isShuttingDown()} has already been made
     * teardown-aware, so neither its own DB calls nor anything it calls run during shutdown.
     * {@code McpConnectionManager.clearAllowlistAndAudit} is the existing example: it skips
     * its DELETE while shutting down because the next boot re-registers the allowlist anyway.
     *
     * <p>This is a heuristic and it cuts both ways — it cannot tell a real guard from an
     * incidental read of the flag, so a method that checks it and then reaches the DB anyway
     * would pass. Recognising the idiom beats a hard-coded name list, which would go stale.
     */
    private static boolean guardsOnShutdownFlag(JavaMethod method) {
        return method.getCallsFromSelf().stream().anyMatch(call ->
                "services.EventLogger".equals(call.getTargetOwner().getName())
                        && "isShuttingDown".equals(call.getTarget().getName()));
    }

    /**
     * No subsystem stopped by {@link jobs.ShutdownJob} may reach the database, transitively.
     *
     * <p>Teardown that needs a connection has no useful recovery when it cannot get one, and
     * the one observed failure here was exactly that — a config read on the shutdown path
     * that surfaced as an unexplained "JDBC begin transaction failed" (JCLAW-1143).
     *
     * <p>What this cannot see: anything inside a third-party frame. ArchUnit imports only
     * {@code app/}, so db-scheduler reaching H2 through {@code Scheduler.stop()} is invisible
     * here — that is what the runtime tripwire in {@link services.Tx} is for. Virtual calls
     * also resolve to the declared target, so an interface hop can hide an implementation's
     * DB access. Treat a pass as "no direct path in our own code", not as proof.
     */
    @Test
    void shutdownComponentsMustNotReachTheDatabase() {
        var doJob = APP_CLASSES.get("jobs.ShutdownJob").getMethod("doJob");

        // The Component list holds method references, so the roots are discovered rather
        // than hand-listed — a new subsystem is covered the moment it is registered.
        List<JavaMethod> roots = doJob.getMethodReferencesFromSelf().stream()
                .map(ref -> ref.getTarget().resolveMember())
                .flatMap(Optional::stream)
                .filter(JavaMethod.class::isInstance)
                .map(JavaMethod.class::cast)
                .toList();

        assertFalse(roots.isEmpty(),
                "no shutdown component method references resolved — the rule would pass vacuously");

        var offenders = new ArrayList<String>();
        for (JavaMethod root : roots) {
            String path = findDbPath(root);
            if (path != null) offenders.add(path);
        }
        assertTrue(offenders.isEmpty(),
                "shutdown components must not reach the database:\n  " + String.join("\n  ", offenders));
    }

    /** BFS from a component's stop method; returns a readable call path to a DB sink, or null. */
    private static String findDbPath(JavaMethod root) {
        var seen = new HashSet<String>();
        var parent = new HashMap<String, String>();
        var queue = new ArrayDeque<JavaMethod>();
        queue.add(root);
        seen.add(root.getFullName());

        while (!queue.isEmpty()) {
            JavaMethod current = queue.poll();
            if (guardsOnShutdownFlag(current)) continue;
            for (var call : current.getCallsFromSelf()) {
                String owner = call.getTargetOwner().getName();
                if (DB_SINK_OWNERS.contains(owner)) {
                    return renderPath(parent, current.getFullName(), root)
                            + " -> " + owner + "." + call.getTarget().getName();
                }
                if (SHUTDOWN_AWARE.contains(owner)) continue;
                var member = call.getTarget().resolveMember();
                if (member.isEmpty() || !(member.get() instanceof JavaMethod next)) continue;
                if (seen.add(next.getFullName())) {
                    parent.put(next.getFullName(), current.getFullName());
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private static String renderPath(Map<String, String> parent, String from, JavaMethod root) {
        var chain = new ArrayList<String>();
        for (String at = from; at != null; at = parent.get(at)) {
            chain.add(0, at);
            if (at.equals(root.getFullName())) break;
        }
        return String.join(" -> ", chain);
    }
}
