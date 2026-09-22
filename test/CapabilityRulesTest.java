import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.freeze.TextFileBasedViolationStore;
import controllers.AgentAccess;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The five authorities {@code app/} actually holds, each pinned to the classes allowed to
 * exercise it: spawn an OS process, resolve a model-controlled filesystem path, open an
 * outbound connection, reach the database, and mutate state on behalf of the agent
 * principal.
 *
 * <p>Java 25 cannot express a capability in a signature, and JEP 486 removed the SecurityManager
 * so nothing confines one at runtime either — which class holds which authority is invisible to
 * javac. These rules are the substitute: bytecode allowlists that fail the build when a class
 * picks up an authority it was never granted (JCLAW-1152).
 *
 * <p>Shell and filesystem have pre-existing holders, so they run as {@link FreezingArchRule}s
 * and the checked-in store under {@code archunit_store/} <em>is</em> the allowlist: a listed
 * site passes and a new one fails. {@code conf/archunit.properties} pins both store switches
 * off, so a listed site that stops matching fails too ("Updating frozen violations is
 * disabled") until its line is deleted by hand — the store changes only when a human edits
 * it. Line numbers are not part of the match, so editing a listed file does not red the build.
 *
 * <p>The predicates match accesses rather than calls, so a method reference
 * ({@code ProcessBuilder::start}, {@code Path::of}) counts the same as a call.
 *
 * <p>Each frozen rule is paired with a floor on the live match count. It guards the deliberate
 * regeneration run, when the store switches are on: a predicate that had silently stopped
 * matching — an ArchUnit upgrade, a renamed target — would then prune the store to empty and
 * pass forever.
 *
 * <p>Network and database hold clean, so those stay strict rules. They live here rather than in
 * {@link ArchitectureTest} so the capabilities read as one section.
 */
class CapabilityRulesTest extends UnitTest {

    /** Imported once per run by {@link ArchitectureTest}; both classes assert over the same bytecode. */
    private static final JavaClasses APP_CLASSES = ArchitectureTest.APP_CLASSES;

    // ===== Capability: spawn an OS process =====

    /** Source files holding the spawn capability when the baseline was frozen (JCLAW-1152). */
    private static final int FROZEN_PROCESS_SPAWNER_FILES = 17;

    /** Constructing a {@code ProcessBuilder} counts: a class that assembles the command line holds
     *  the capability even when a collaborator makes the {@code start()} call. */
    private static final DescribedPredicate<JavaAccess<?>> PROCESS_SPAWN = DescribedPredicate.describe(
            "a call or method reference that starts an OS process",
            access -> switch (access.getTargetOwner().getName()) {
                case "java.lang.ProcessBuilder" ->
                        Set.of("<init>", "start", "startPipeline").contains(access.getTarget().getName());
                case "java.lang.Runtime" -> "exec".equals(access.getTarget().getName());
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
        assertFloor(sourceFilesAccessing(PROCESS_SPAWN, javaClass -> true),
                FROZEN_PROCESS_SPAWNER_FILES, "process-spawning source files");

        ArchRule rule = noClasses()
                .should().accessTargetWhere(PROCESS_SPAWN)
                .because("spawning an OS process is an unconfined authority — JEP 486 removed the "
                        + "SecurityManager, so the child is bounded only by the JVM's own user "
                        + "(JCLAW-1152); the classes granted it are enumerated in archunit_store/");
        frozen(rule, "shell-process-spawners").check(APP_CLASSES);
    }

    // ===== Capability: resolve a model-controlled filesystem path =====

    /** Files under {@code tools..} building a path directly when the baseline was frozen (JCLAW-1152). */
    private static final int FROZEN_DIRECT_PATH_FILES = 5;

    private static final DescribedPredicate<JavaAccess<?>> DIRECT_PATH_CONSTRUCTION = DescribedPredicate.describe(
            "a call or method reference that builds a filesystem path directly",
            access -> switch (access.getTargetOwner().getName()) {
                case "java.nio.file.Path" -> "of".equals(access.getTarget().getName());
                case "java.nio.file.Paths" -> "get".equals(access.getTarget().getName());
                case "java.io.File" -> "<init>".equals(access.getTarget().getName());
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
        assertFloor(sourceFilesAccessing(DIRECT_PATH_CONSTRUCTION, CapabilityRulesTest::isToolsClass),
                FROZEN_DIRECT_PATH_FILES, "tools/ direct-path-building source files");

        ArchRule rule = noClasses()
                .that().resideInAPackage("tools..")
                .should().accessTargetWhere(DIRECT_PATH_CONSTRUCTION)
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
     *  {@code URL.openConnection()} — constructors, methods and references to either, hence {@code JavaAccess}. */
    private static final DescribedPredicate<JavaAccess<?>> RAW_SOCKET_ACCESS = DescribedPredicate.describe(
            "a call or reference to new java.net.Socket(...), new java.net.ServerSocket(...) or URL.openConnection()",
            access -> {
                String owner = access.getTargetOwner().getName();
                String name = access.getTarget().getName();
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
                .should().accessTargetWhere(RAW_SOCKET_ACCESS)
                .because("outbound network access goes through HttpFactories' OkHttp clients "
                        + "(JCLAW-1151); services.printing speaks LPD/9100 and LocalSidecarDaemon "
                        + "binds a loopback port probe");
        rule.check(APP_CLASSES);

        assertTrue(APP_CLASSES.stream()
                        .filter(c -> c.getPackageName().startsWith("services.printing"))
                        .flatMap(c -> c.getAccessesFromSelf().stream())
                        .anyMatch(RAW_SOCKET_ACCESS),
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

    // ===== Capability: mutate state as the agent principal =====

    private static final int ADJUDICATED_API_ROUTES = 249;
    /**
     * The two ways an {@code OWN_ONLY} action can honour its level. A row route refuses one it
     * does not own; a list route has no row to refuse, so it pins the query to the caller
     * instead — a list that 403s on someone else's rows would have had to read them first.
     */
    private static final Set<String> OWNERSHIP_CHECKS =
            Set.of("mayReachAgentScopedRow", "callingAgent");

    private static final Set<String> MUTATING_VERBS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String PRINCIPAL_CLASS = "controllers.RequestPrincipal";

    /**
     * Every routed {@code /api} action says out loud what the agent principal may do with it.
     *
     * <p>The boundary used to be four mechanisms — {@code JClawApiTool.PATH_BLOCKLIST},
     * {@code @ChatHidden}, {@code @AgentCallable} and the {@code requireOperator} helpers — of
     * which only the last refused anyone; the first two merely hid a route from one tool, so an
     * agent holding the internal token walked past them. Worse, the gate was default-allow, so a
     * new endpoint was reachable the moment it was written and the omission looked exactly like a
     * decision (JCLAW-1253/1266). {@link AgentAccess} collapses all four into one declaration that
     * both the tool and {@code AgentAccessGate} read, and its absence means
     * {@code OPERATOR_ONLY} — silence now closes a route instead of opening it (JCLAW-1270).
     *
     * <p>Reads are in scope now, which they were not before. The old rule skipped them on the
     * grounds that a leaky GET is a masking problem; that left 91 reads answering to no rule at
     * all, including the conversation history of every other agent.
     *
     * <p>Not frozen: all 249 routes are adjudicated, so the rule lands green and a new route with
     * no level fails immediately. The floor is on the route count rather than on matched files,
     * because a routes file that stopped parsing would otherwise adjudicate nothing and pass.
     */
    @Test
    void everyRoutedApiActionDeclaresAnAgentAccessLevel() {
        var routes = apiRouteActions();
        assertFloor(routes.keySet(), ADJUDICATED_API_ROUTES, "/api conf/routes entries");

        var unresolved = new TreeSet<String>();
        for (var route : routes.entrySet()) {
            var owner = route.getValue().controller();
            if (!APP_CLASSES.contain(owner)
                    || APP_CLASSES.get(owner).getMethods().stream()
                            .noneMatch(m -> m.getName().equals(route.getValue().action()))) {
                unresolved.add(route.getKey());
            }
        }
        assertTrue(unresolved.isEmpty(),
                "these routes resolve to no imported controller action, so the rule below cannot "
                        + "see them and would pass over a genuinely undeclared endpoint: " + unresolved);

        classes()
                .that().resideInAPackage("controllers")
                .should(declareAnAgentAccessLevel(routes))
                .because("an undeclared route is refused rather than reachable, but a route nobody "
                        + "adjudicated is a decision nobody made — the annotation is what turns the "
                        + "default from a backstop into a statement (JCLAW-1270)")
                .check(APP_CLASSES);
    }

    /**
     * The rule above passes because every route is adjudicated, which is exactly the state in
     * which a broken condition also passes. This drives it against a stanceless action to show
     * it still fails, and against one of each stance to show it is not simply failing everything.
     *
     * <p>The negative case borrows {@code ApiController.status}, a GET read: no mutating route
     * is left without a stance to point at, and inventing one in {@code conf/routes} would mean
     * shipping an ungated endpoint to test the gate.
     */
    @Test
    void theStanceRuleFailsAnActionThatDeclaresNothing() {
        // Built outside the lambda so the only thing that can throw inside it is the
        // check: a constructor failure would otherwise satisfy assertThrows and pass
        // this negative case without the rule having run at all.
        var undeclared = new RouteAction("controllers.AgentAccessGate", "levelFor");
        var error = assertThrows(AssertionError.class, () -> checkLevelOf(undeclared));
        assertTrue(error.getMessage().contains("declares no @AgentAccess"), error.getMessage());

        checkLevelOf(new RouteAction("controllers.ApiTasksController", "create"));       // OPEN + reason
        checkLevelOf(new RouteAction("controllers.ApiAgentsController", "update"));      // OPEN + reason
        checkLevelOf("GET /api/probe",
                new RouteAction("controllers.ApiTailscaleController", "status"));        // OPERATOR_ONLY
    }

    /**
     * An {@code OPEN} or {@code OWN_ONLY} mutating route with an empty reason records a decision
     * without its grounds, which is worse than no annotation — it reads as adjudicated. Driven
     * against a synthetic level rather than a real route, so the day someone ships one this fails
     * instead of the rule having no case to prove itself on.
     */
    @Test
    void anOpenMutatingRouteMustSayWhatBoundsIt() {
        var error = assertThrows(AssertionError.class,
                () -> checkReason("POST /api/probe", AgentAccess.Level.OPEN, ""));
        assertTrue(error.getMessage().contains("names nothing that bounds it"), error.getMessage());

        checkReason("POST /api/probe", AgentAccess.Level.OPEN, "DangerousActionGate bounds it");
        checkReason("GET /api/probe", AgentAccess.Level.OPEN, "");   // reads need no reason
        checkReason("POST /api/probe", AgentAccess.Level.OPERATOR_ONLY, "");
    }

    private static void checkReason(String route, AgentAccess.Level level, String reason) {
        var violations = new TreeSet<String>();
        auditLevel(route, "Probe.action", level, reason, false, violations);
        assertTrue(violations.isEmpty(), String.join("; ", violations));
    }

    private static void checkLevelOf(RouteAction action) {
        checkLevelOf("POST /api/probe", action);
    }

    private static void checkLevelOf(String route, RouteAction action) {
        classes()
                .that().resideInAPackage("controllers")
                .should(declareAnAgentAccessLevel(Map.of(route, action)))
                .check(APP_CLASSES);
    }

    /** A {@code conf/routes} action target, split the way Play resolves it. */
    private record RouteAction(String controller, String action) {}

    /**
     * Every {@code /api} route as declared in {@code conf/routes}, keyed {@code "METHOD path"}.
     * ArchUnit imports bytecode and cannot see the routes file, so the binding is read from
     * disk — the same approach {@code WebhookControllerTest.webhookRoutes} takes.
     */
    private static Map<String, RouteAction> apiRouteActions() {
        try {
            var found = new TreeMap<String, RouteAction>();
            for (var line : Files.readAllLines(Play.getFile("conf/routes").toPath())) {
                var trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                var cols = trimmed.split("\\s+");
                // A three-column entry whose target has no ':' is a controller action; the
                // rest are staticDir/staticFile/module bindings with no method behind them.
                if (cols.length < 3 || !cols[1].startsWith("/api") || cols[2].contains(":")) continue;
                int split = cols[2].lastIndexOf('.');
                if (split < 0) continue;
                found.put(cols[0] + " " + cols[1],
                        new RouteAction("controllers." + cols[2].substring(0, split), cols[2].substring(split + 1)));
            }
            return found;
        } catch (IOException e) {
            throw new AssertionError("cannot read conf/routes", e);
        }
    }

    private static ArchCondition<JavaClass> declareAnAgentAccessLevel(Map<String, RouteAction> routes) {
        return new ArchCondition<>("declare an @AgentAccess level on every routed /api action") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (var route : routes.entrySet()) {
                    if (!javaClass.getName().equals(route.getValue().controller())) continue;
                    for (JavaMethod method : javaClass.getMethods()) {
                        if (!method.getName().equals(route.getValue().action())) continue;
                        var where = javaClass.getSimpleName() + "." + method.getName();
                        if (!method.isAnnotatedWith(AgentAccess.class)) {
                            events.add(SimpleConditionEvent.violated(method, route.getKey() + " -> "
                                    + where + " declares no @AgentAccess level. An undeclared route "
                                    + "is refused, so this is not a hole — but it is an unmade "
                                    + "decision. State OPEN, OWN_ONLY or OPERATOR_ONLY"));
                            continue;
                        }
                        var declared = method.getAnnotationOfType(AgentAccess.class);
                        var violations = new TreeSet<String>();
                        auditLevel(route.getKey(), where, declared.value(), declared.reason(),
                                reachesOwnershipCheck(method), violations);
                        violations.forEach(v -> events.add(SimpleConditionEvent.violated(method, v)));
                    }
                }
            }
        };
    }

    /**
     * The two obligations a level carries beyond existing, collected rather than thrown so the
     * rule reports every offending route in one run.
     *
     * <p>Split out from the ArchUnit condition so the negative cases can drive it on a synthetic
     * route: a rule whose failure path is only reachable by shipping the defect it forbids is a
     * rule nobody has seen fail.
     */
    private static void auditLevel(String route, String where, AgentAccess.Level level,
                                   String reason, boolean reachesOwnershipCheck,
                                   Set<String> violations) {
        boolean mutating = MUTATING_VERBS.contains(route.split(" ", 2)[0]);
        if (mutating && level != AgentAccess.Level.OPERATOR_ONLY && reason.isBlank()) {
            violations.add(route + " -> " + where + " is " + level + " but names nothing that "
                    + "bounds it. A mutating route left open without its grounds reads as "
                    + "adjudicated when it was only unannotated");
        }
        if (level == AgentAccess.Level.OWN_ONLY && !reachesOwnershipCheck) {
            violations.add(route + " -> " + where + " is OWN_ONLY but reaches none of "
                    + "RequestPrincipal." + OWNERSHIP_CHECKS + ". Only the action knows which row "
                    + "it is about, so the level declares the intent and the action enforces it");
        }
    }

    /** BFS through helpers declared on the same controller — the check is usually one hop away,
     *  occasionally two. Matched by what it reaches rather than by the helper's name, so renaming
     *  one is safe and a helper called {@code requireOwner} that checks nothing does not pass. */
    private static boolean reachesOwnershipCheck(JavaMethod method) {
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<JavaMethod>();
        queue.add(method);
        seen.add(method.getFullName());

        while (!queue.isEmpty()) {
            for (var call : queue.poll().getCallsFromSelf()) {
                if (PRINCIPAL_CLASS.equals(call.getTargetOwner().getName())
                        && OWNERSHIP_CHECKS.contains(call.getTarget().getName())) {
                    return true;
                }
                if (!call.getTargetOwner().equals(method.getOwner())) continue;
                var member = call.getTarget().resolveMember();
                if (member.isPresent() && member.get() instanceof JavaMethod next && seen.add(next.getFullName())) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    // ===== Test-only seams =====

    /** Each seam's owner and method; a production caller would widen SSRF or the frozen-page bound. */
    private static final Map<String, String> TEST_SEAMS = Map.of(
            "utils.SsrfGuard", "permitOriginForTest",
            "tools.jev.JevPage", "callWithCallLimitForTest");

    private static final DescribedPredicate<JavaAccess<?>> TEST_SEAM_ACCESS = DescribedPredicate.describe(
            "a call or method reference to a test-only seam",
            access -> access.getTarget().getName().equals(TEST_SEAMS.get(access.getTargetOwner().getName())));

    /**
     * {@code SsrfGuard.permitOriginForTest} admits a loopback origin and
     * {@code JevPage.callWithCallLimitForTest} shortens the frozen-page bound (JCLAW-1277). Both are
     * {@code ScopedValue} bindings, so a test cannot leak one into another class, but a production
     * caller would carry the widened check to every URL it screens.
     */
    @Test
    void noAppClassCallsATestOnlySeam() {
        TEST_SEAMS.forEach((owner, method) -> assertTrue(
                APP_CLASSES.contain(owner) && APP_CLASSES.get(owner).getMethods().stream()
                        .anyMatch(m -> m.getName().equals(method)),
                owner + "." + method + " is gone, so this rule would pass while guarding nothing"));

        ArchRule rule = noClasses()
                .should().accessTargetWhere(TEST_SEAM_ACCESS)
                .because("the seams exist so tests can reach a local fixture and a short bound; "
                        + "production never binds them (JCLAW-1277)");
        rule.check(APP_CLASSES);
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
     * Fails when a predicate matches fewer holders than the baseline recorded. With the store
     * switches pinned off a stale entry already fails the rule; the floor is what protects the
     * deliberate regeneration run, where a predicate that stopped matching would prune its
     * store to empty and then pass forever.
     */
    private static void assertFloor(Set<String> matched, int floor, String what) {
        assertTrue(matched.size() >= floor,
                "expected at least " + floor + " " + what + ", found " + matched.size()
                        + " " + matched + " — the predicate stopped matching, and the frozen rule would "
                        + "prune its baseline to empty and pass. Lower the constant only once a holder "
                        + "was genuinely removed");
    }

    private static boolean isToolsClass(JavaClass javaClass) {
        var pkg = javaClass.getPackageName();
        return "tools".equals(pkg) || pkg.startsWith("tools.");
    }

    /** Distinct {@code .java} file names, within {@code scope}, whose compiled classes make a matching access. */
    private static Set<String> sourceFilesAccessing(DescribedPredicate<JavaAccess<?>> predicate,
                                                    Predicate<JavaClass> scope) {
        var files = new TreeSet<String>();
        for (JavaClass javaClass : APP_CLASSES) {
            if (scope.test(javaClass) && javaClass.getAccessesFromSelf().stream().anyMatch(predicate)) {
                javaClass.getSource().flatMap(Source::getFileName).ifPresent(files::add);
            }
        }
        return files;
    }
}
