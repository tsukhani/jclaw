import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.freeze.TextFileBasedViolationStore;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The four authorities {@code app/} actually holds, each pinned to the classes allowed to
 * exercise it: spawn an OS process, resolve a model-controlled filesystem path, open an
 * outbound connection, reach the database.
 *
 * <p>Java 25 cannot express a capability in a signature, and JEP 486 removed the SecurityManager
 * so nothing confines one at runtime either — which class holds which authority is invisible to
 * javac. These rules are the substitute: bytecode allowlists that fail the build when a class
 * picks up an authority it was never granted (JCLAW-1152).
 *
 * <p>Shell and filesystem have pre-existing holders, so they run as {@link FreezingArchRule}s
 * and the checked-in store under {@code archunit_store/} <em>is</em> the allowlist: a listed
 * site passes, a new one fails, and fixing one prunes it from the store on the next run. Line
 * numbers are not part of the match, so editing a listed file does not red the build.
 *
 * <p>Each frozen rule is paired with a floor on the live match count. Without it a predicate
 * that silently stopped matching — an ArchUnit upgrade, a renamed target — would let the store
 * prune itself to empty and pass forever.
 *
 * <p>Network and database hold clean, so those stay strict rules. They live here rather than in
 * {@link ArchitectureTest} so the four capabilities read as one section.
 */
class CapabilityRulesTest extends UnitTest {

    /** Imported once per run by {@link ArchitectureTest}; both classes assert over the same bytecode. */
    private static final JavaClasses APP_CLASSES = ArchitectureTest.APP_CLASSES;

    // ===== Capability: spawn an OS process =====

    /** Source files holding the spawn capability when the baseline was frozen (JCLAW-1152). */
    private static final int FROZEN_PROCESS_SPAWNER_FILES = 17;

    /** Constructing a {@code ProcessBuilder} counts: a class that assembles the command line holds
     *  the capability even when a collaborator makes the {@code start()} call. */
    private static final DescribedPredicate<JavaCall<?>> PROCESS_SPAWN = DescribedPredicate.describe(
            "a call that starts an OS process",
            call -> switch (call.getTargetOwner().getName()) {
                case "java.lang.ProcessBuilder" ->
                        Set.of("<init>", "start", "startPipeline").contains(call.getTarget().getName());
                case "java.lang.Runtime" -> "exec".equals(call.getTarget().getName());
                default -> false;
            });

    /**
     * A spawned process inherits the JVM's user and filesystem access and nothing in the language
     * confines it afterwards, which makes this the widest authority in the codebase. The holders
     * are the sidecar supervisors, the media transcoders, the harness runners and
     * {@code ShellExecTool}; they are enumerated in the store rather than in this file, so the
     * list shrinks by deleting an entry and never by editing a rule.
     */
    @Test
    void onlyFrozenHoldersSpawnOsProcesses() {
        assertFloor(sourceFilesCalling(PROCESS_SPAWN, javaClass -> true),
                FROZEN_PROCESS_SPAWNER_FILES, "process-spawning");

        ArchRule rule = noClasses()
                .should().callCodeUnitWhere(PROCESS_SPAWN)
                .because("spawning an OS process is an unconfined authority — JEP 486 removed the "
                        + "SecurityManager, so the child is bounded only by the JVM's own user "
                        + "(JCLAW-1152); the classes granted it are enumerated in archunit_store/");
        frozen(rule, "shell-process-spawners").check(APP_CLASSES);
    }

    // ===== Capability: resolve a model-controlled filesystem path =====

    /** Files under {@code tools..} building a path directly when the baseline was frozen (JCLAW-1152). */
    private static final int FROZEN_DIRECT_PATH_FILES = 5;

    private static final DescribedPredicate<JavaCall<?>> DIRECT_PATH_CONSTRUCTION = DescribedPredicate.describe(
            "a call that builds a filesystem path directly",
            call -> switch (call.getTargetOwner().getName()) {
                case "java.nio.file.Path" -> "of".equals(call.getTarget().getName());
                case "java.nio.file.Paths" -> "get".equals(call.getTarget().getName());
                case "java.io.File" -> "<init>".equals(call.getTarget().getName());
                default -> false;
            });

    /**
     * {@code tools..} is the one package whose arguments come from the model, so a path built
     * there escapes the agent workspace unless it resolves through {@code tools.FsPaths} or the
     * {@code utils.WorkspacePathGuard} containment underneath it.
     *
     * <p>Bytecode cannot tell a tool argument from a compile-time constant, so the rule bans
     * direct construction across the package and freezes what is already there: two genuinely
     * model-controlled sites in {@code ShellExecTool} (the command token and the {@code workdir}
     * argument) and three that read an env var, a config key or a literal. The coarseness is
     * deliberate — a new {@code Path.of} in this package is worth a look either way.
     *
     * <p>Scoped to model-controlled paths on purpose: application-internal file access — config,
     * caches, sidecar working directories — is not this capability and is not covered here.
     */
    @Test
    void toolPathsResolveThroughTheWorkspaceGuard() {
        assertFloor(sourceFilesCalling(DIRECT_PATH_CONSTRUCTION, CapabilityRulesTest::isToolsClass),
                FROZEN_DIRECT_PATH_FILES, "tools/ direct-path-building");

        ArchRule rule = noClasses()
                .that().resideInAPackage("tools..")
                .should().callCodeUnitWhere(DIRECT_PATH_CONSTRUCTION)
                .because("a path resolved from a tool argument must be contained by "
                        + "WorkspacePathGuard, which tools.FsPaths reaches on the model-facing side "
                        + "(JCLAW-1152); the sites predating that seam are listed in archunit_store/");
        frozen(rule, "filesystem-tool-paths").check(APP_CLASSES);
    }

    // ===== Capability: open an outbound connection =====

    /** Packages and classes allowed to open a raw socket; see {@link #rawSocketsAreConfinedToThePrintingStack}. */
    private static final String PRINTING_PACKAGE = "services.printing..";
    private static final String PORT_PROBE_CLASS = "services.LocalSidecarDaemon";

    /** Matches {@code new java.net.Socket(...)}, {@code new java.net.ServerSocket(...)} and
     *  {@code URL.openConnection()} — constructor and method calls together, hence {@code JavaCall}. */
    private static final DescribedPredicate<JavaCall<?>> RAW_SOCKET_CALL = DescribedPredicate.describe(
            "a call to new java.net.Socket(...), new java.net.ServerSocket(...) or URL.openConnection()",
            call -> {
                String owner = call.getTargetOwner().getName();
                String name = call.getTarget().getName();
                boolean socketCtor = ("java.net.Socket".equals(owner) || "java.net.ServerSocket".equals(owner))
                        && "<init>".equals(name);
                return socketCtor || ("java.net.URL".equals(owner) && "openConnection".equals(name));
            });

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
                .should().callConstructorWhere(ArchitectureTest.constructorCall("okhttp3.OkHttpClient$Builder", true))
                .because("outbound OkHttp clients must come from HttpFactories (JCLAW-185..188); "
                        + "SsrfGuard and the Telegram SDK are the documented exceptions");
        rule.check(APP_CLASSES);
    }

    /**
     * Outbound network access is {@code HttpFactories}' OkHttp clients and nothing else
     * (JCLAW-1151). A raw {@code java.net} socket or a {@code URL.openConnection()} is a
     * second stack that bypasses the shared pools, the timeout policy, the SSRF DNS — and
     * the {@code HttpFactories.runWith} transport override, so anything built on one cannot
     * be tested without a live listener. The self-built OkHttp clients ({@code SsrfGuard},
     * {@code TelegramBotApiHttpClients}) are named in
     * {@link #okHttpClientsAreProvisionedThroughHttpFactories}, not here — they are still
     * OkHttp and still substitutable.
     *
     * <p>{@code services.printing} is the standing exception: LPD and JetDirect 9100 are
     * socket protocols, not HTTP, and discovery probes reachability by connecting.
     * {@code LocalSidecarDaemon} binds a loopback {@code ServerSocket} to learn whether a
     * port is already held — a probe that sends nothing, not traffic.
     */
    @Test
    void rawSocketsAreConfinedToThePrintingStack() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage(PRINTING_PACKAGE)
                .and().doNotHaveFullyQualifiedName(PORT_PROBE_CLASS)
                .should().callCodeUnitWhere(RAW_SOCKET_CALL)
                .because("outbound network access goes through HttpFactories' OkHttp clients "
                        + "(JCLAW-1151); services.printing speaks LPD/9100 and LocalSidecarDaemon "
                        + "binds a loopback port probe");
        rule.check(APP_CLASSES);

        assertTrue(APP_CLASSES.stream()
                        .filter(c -> c.getPackageName().startsWith("services.printing"))
                        .flatMap(c -> c.getCodeUnitCallsFromSelf().stream())
                        .anyMatch(RAW_SOCKET_CALL),
                "no socket call found inside " + PRINTING_PACKAGE + " — the exclusion is carrying "
                        + "nothing, so the predicate has stopped matching and the rule passes vacuously");
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

    // ===== Capability: reach the database =====

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

    // ===== Shared machinery =====

    /**
     * Freezes {@code rule} against {@code archunit_store/<storeFileName>}. The explicit name
     * replaces ArchUnit's default random UUID so the checked-in allowlist can be read, reviewed
     * and counted down as a file rather than chased through {@code stored.rules}.
     */
    private static ArchRule frozen(ArchRule rule, String storeFileName) {
        return FreezingArchRule.freeze(rule)
                .persistIn(new TextFileBasedViolationStore(description -> storeFileName));
    }

    /**
     * Fails when a predicate matches fewer holders than the baseline recorded. A frozen rule
     * whose predicate stopped matching prunes its store to empty and then passes forever, so
     * the count is asserted separately from the rule.
     */
    private static void assertFloor(Set<String> matched, int floor, String what) {
        assertTrue(matched.size() >= floor,
                "expected at least " + floor + " " + what + " source files, found " + matched.size()
                        + " " + matched + " — the predicate stopped matching, and the frozen rule would "
                        + "prune its baseline to empty and pass. Lower the constant only once a holder "
                        + "was genuinely removed");
    }

    private static boolean isToolsClass(JavaClass javaClass) {
        var pkg = javaClass.getPackageName();
        return "tools".equals(pkg) || pkg.startsWith("tools.");
    }

    /** Distinct {@code .java} file names, within {@code scope}, whose compiled classes make a matching call. */
    private static Set<String> sourceFilesCalling(DescribedPredicate<JavaCall<?>> predicate,
                                                  Predicate<JavaClass> scope) {
        var files = new TreeSet<String>();
        for (JavaClass javaClass : APP_CLASSES) {
            if (scope.test(javaClass) && javaClass.getCodeUnitCallsFromSelf().stream().anyMatch(predicate)) {
                javaClass.getSource().flatMap(Source::getFileName).ifPresent(files::add);
            }
        }
        return files;
    }
}
