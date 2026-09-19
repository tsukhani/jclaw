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
import controllers.AgentCallable;
import controllers.ChatHidden;
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

    /** Mutating {@code conf/routes} entries when the stance sweep landed (JCLAW-1253). */
    private static final int ADJUDICATED_MUTATING_ROUTES = 134;

    private static final Set<String> MUTATING_VERBS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String PRINCIPAL_CLASS = "controllers.RequestPrincipal";
    private static final String PRINCIPAL_CHECK = "isAgentOriginated";

    /**
     * Every mutating route says out loud whether an agent may drive it.
     *
     * <p>The boundary was three hand-maintained lists — {@code JClawApiTool.PATH_BLOCKLIST},
     * {@link ChatHidden} and the {@code requireOperator} helpers — with nothing watching for a
     * route that joined none of them. Default-allow plus silence meant a new endpoint was
     * agent-reachable the moment it was written, and the omission looked exactly like a
     * decision (JCLAW-1253). So silence is now the failure: an action reaches a
     * {@link controllers.RequestPrincipal#isAgentOriginated()} guard, or carries
     * {@link AgentCallable} saying why it is deliberately open.
     *
     * <p>{@link ChatHidden} was a third accepted stance until JCLAW-1266 and no longer is. It is
     * consulted only inside {@code JClawApiTool}, so it hides a route from that tool without
     * refusing anyone: an agent holding the internal token and any HTTP capability reaches the
     * route anyway. Thirty-two controllers rested on it alone, among them the database
     * backup/restore surface whose archive carries the token's own plaintext config row. Those
     * are recorded in {@code archunit_store/agent-principal-stance} and counted down from there;
     * a route that joins them now fails the build instead.
     *
     * <p>The guard is matched by what it reaches rather than by being named
     * {@code requireOperator}: every one of those helpers is a one-line wrapper around the same
     * check, and a name-matched rule would pass a method called {@code requireOperator} that
     * checks nothing while failing a correct guard someone renamed.
     *
     * <p>Reads are out of scope. A GET that leaks is a masking problem at the seam, which is
     * where Personal Edition puts it; this capability is about state an agent can change.
     */
    @Test
    void everyMutatingRouteDeclaresAnAgentPrincipalStance() {
        var routes = mutatingRouteActions();
        assertFloor(routes.keySet(), ADJUDICATED_MUTATING_ROUTES, "mutating conf/routes entries");

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
                "these mutating routes resolve to no imported controller action, so the rule below "
                        + "cannot see them and would pass over a genuinely ungated endpoint: " + unresolved);

        ArchRule rule = classes()
                .that().resideInAPackage("controllers")
                .should(declareAnAgentPrincipalStance(routes))
                .because("a mutating route is reachable by the agent principal unless something "
                        + "refuses it at the request layer, so leaving the stance unstated grants "
                        + "the capability by accident (JCLAW-1253/1266); a "
                        + "RequestPrincipal.isAgentOriginated guard and @AgentCallable are the two "
                        + "ways to state one, and @ChatHidden is not among them");
        frozen(rule, "agent-principal-stance").check(APP_CLASSES);
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
        var unstanced = new RouteAction("controllers.ApiController", "status");
        var error = assertThrows(AssertionError.class, () -> checkStanceOf(unstanced));
        assertTrue(error.getMessage().contains("declares no agent-principal stance"), error.getMessage());

        checkStanceOf(new RouteAction("controllers.ApiTasksController", "create"));        // @AgentCallable
        checkStanceOf(new RouteAction("controllers.ApiAgentsController", "update"));       // guard, two hops
        checkStanceOf("POST /api/webhooks/telegram/{bindingId}",
                new RouteAction("controllers.WebhookTelegramController", "webhook"));      // signature-verified
    }

    /**
     * {@link ChatHidden} alone no longer satisfies the rule (JCLAW-1266). Driven against a route
     * that carries it and nothing else, so the day someone re-admits it as a stance this fails
     * rather than quietly widening the boundary back to the tool layer.
     */
    @Test
    void chatHiddenAloneIsNotAStance() {
        var toolLayerOnly = new RouteAction("controllers.ApiMetricsController", "purgeLogs");
        var error = assertThrows(AssertionError.class, () -> checkStanceOf(toolLayerOnly));
        assertTrue(error.getMessage().contains("@ChatHidden does not count"), error.getMessage());
    }

    private static void checkStanceOf(RouteAction action) {
        checkStanceOf("POST /api/probe", action);
    }

    private static void checkStanceOf(String route, RouteAction action) {
        classes()
                .that().resideInAPackage("controllers")
                .should(declareAnAgentPrincipalStance(Map.of(route, action)))
                .check(APP_CLASSES);
    }

    /** A {@code conf/routes} action target, split the way Play resolves it. */
    private record RouteAction(String controller, String action) {}

    /**
     * The mutating routes as declared in {@code conf/routes}, keyed {@code "METHOD path"}.
     * ArchUnit imports bytecode and cannot see the routes file, so the binding is read from
     * disk — the same approach {@code WebhookControllerTest.webhookRoutes} takes.
     */
    private static Map<String, RouteAction> mutatingRouteActions() {
        try {
            var found = new TreeMap<String, RouteAction>();
            for (var line : Files.readAllLines(Play.getFile("conf/routes").toPath())) {
                var trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                var cols = trimmed.split("\\s+");
                // A three-column entry whose target has no ':' is a controller action; the
                // rest are staticDir/staticFile/module bindings with no method behind them.
                if (cols.length < 3 || !MUTATING_VERBS.contains(cols[0]) || cols[2].contains(":")) continue;
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

    /** Violation text deliberately carries no line number: the frozen rules in this class share
     *  a store keyed by message, and an unrelated edit above an action must not red the build. */
    private static ArchCondition<JavaClass> declareAnAgentPrincipalStance(Map<String, RouteAction> routes) {
        return new ArchCondition<>("declare an agent-principal stance on every mutating route") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (var route : routes.entrySet()) {
                    if (!javaClass.getName().equals(route.getValue().controller())) continue;
                    for (JavaMethod method : javaClass.getMethods()) {
                        if (!method.getName().equals(route.getValue().action())
                                || declaresStance(method, route.getKey())) continue;
                        events.add(SimpleConditionEvent.violated(method, route.getKey()
                                + " -> " + javaClass.getSimpleName() + "." + method.getName()
                                + " declares no agent-principal stance: add an isAgentOriginated "
                                + "guard, or @AgentCallable with the reason. @ChatHidden does not "
                                + "count — it hides the route from the tool without refusing it"));
                    }
                }
            }
        };
    }

    /**
     * {@link ChatHidden} is deliberately <em>not</em> accepted here (JCLAW-1266). It is read in one
     * place — {@code JClawApiTool.discover} and {@code isCallable} — so it refuses an agent only
     * while that tool is the agent's only route to the API, and an agent holding the internal token
     * and any HTTP capability walks past it. It states what the tool advertises, not who may call.
     * The two stances that survive are the request-layer refusal and an explicit opt-in.
     */
    private static boolean declaresStance(JavaMethod method, String route) {
        return isUnauthenticatedWebhook(route)
                || method.isAnnotatedWith(AgentCallable.class)
                || reachesAgentPrincipalCheck(method);
    }

    /** The {@code /api/webhooks/} surface has no session to judge a principal against: it bypasses
     *  AuthCheck by design and verifies its provider's signature instead. Deferred to rather than
     *  restated, because {@code WebhookControllerTest} already pins that surface route-for-route. */
    private static boolean isUnauthenticatedWebhook(String route) {
        return route.contains(" /api/webhooks/");
    }

    /** BFS through helpers declared on the same controller — the guard is usually one hop away
     *  ({@code requireOperator}), occasionally two ({@code requireOperatorForAcpChange}). */
    private static boolean reachesAgentPrincipalCheck(JavaMethod method) {
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<JavaMethod>();
        queue.add(method);
        seen.add(method.getFullName());

        while (!queue.isEmpty()) {
            for (var call : queue.poll().getCallsFromSelf()) {
                if (PRINCIPAL_CLASS.equals(call.getTargetOwner().getName())
                        && PRINCIPAL_CHECK.equals(call.getTarget().getName())) {
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
