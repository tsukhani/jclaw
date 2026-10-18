import agents.ToolContext;
import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationDeletionCascade;
import services.ConversationService;
import services.Tx;
import tools.ShellExecTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@code /api/processes} (JCLAW-1565): the operator reaches every agent's background processes, an
 * agent only its own. No {@code Fixtures.deleteDatabase}: every row here belongs to an agent this class names.
 */
class ApiProcessesControllerTest extends FunctionalTest {

    private final List<String> agentNames = new ArrayList<>();
    private Long callerId;
    private long callerPid;
    private long alienPid;

    @BeforeEach
    void seed() throws Exception {
        // shell.sandbox and the allowlist are process-global and read on every exec.
        ShellSandboxSync.acquire();
        AuthFixture.seedAdminPassword("changeme");
        var suffix = String.valueOf(System.nanoTime() % 1_000_000_000L);
        var callerName = "procapi-caller-" + suffix;
        var alienName = "procapi-alien-" + suffix;
        agentNames.add(callerName);
        agentNames.add(alienName);
        callerId = commit(() -> {
            ConfigService.set("shell.allowlist", "sleep,echo");
            agentNamed(alienName);
            return agentNamed(callerName).id;
        });
        callerPid = startInBackground(callerName, "sleep 4761");
        alienPid = startInBackground(alienName, "sleep 4762");
    }

    @AfterEach
    void cleanUp() {
        clearCookies();
        sleepers("sleep 4761", "sleep 4762", "sleep 4763").forEach(ProcessHandle::destroyForcibly);
        for (var name : agentNames) {
            commit(() -> {
                var agent = Agent.findByName(name);
                if (agent != null) AgentService.delete(agent);
                return null;
            });
            deleteDir(AgentService.workspacePath(name));
        }
        agentNames.clear();
        ShellSandboxSync.release();
    }

    @Test
    void theOperatorListsAndStopsAnyAgentsProcess() throws Exception {
        login();
        var listed = pids(GET("/api/processes"));
        assertTrue(listed.contains(callerPid) && listed.contains(alienPid), "both agents' processes: " + listed);

        assertIsOk(POST("/api/processes/" + alienPid + "/stop", "application/json", ""));
        awaitGone("sleep 4762");
        assertIsNotFound(POST("/api/processes/" + alienPid + "/stop", "application/json", ""));
        assertIsNotFound(POST("/api/processes/" + ProcessHandle.current().pid() + "/stop", "application/json", ""));
    }

    @Test
    void anAgentReachesOnlyItsOwnProcesses() {
        var listed = pids(asAgent(() -> GET(agentRequest(callerId), "/api/processes")));
        assertTrue(listed.contains(callerPid), "its own process: " + listed);
        assertFalse(listed.contains(alienPid), "never another agent's: " + listed);

        var refused = asAgent(() -> POST(agentRequest(callerId), "/api/processes/" + alienPid + "/stop",
                "application/json", ""));
        assertIsNotFound(refused);
        assertEquals(1, sleepers("sleep 4762").size(), "a refused stop stops nothing");
    }

    @Test
    void deletingAConversationStopsWhatItsCommandsLeftRunning() throws Exception {
        var callerName = agentNames.getFirst();
        Long conversationId = commit(() -> ConversationService.create(Agent.findByName(callerName), "web", "procapi").id);
        startInBackground(callerName, "sleep 4763", conversationId);

        // The stop runs after the delete commits, on the committing thread.
        commit(() -> ConversationDeletionCascade.deleteByIds(List.of(conversationId)));

        awaitGone("sleep 4763");
        assertEquals(1, sleepers("sleep 4761").size(), "a process from no deleted conversation keeps running");
    }

    private static long startInBackground(String agentName, String command) throws InterruptedException {
        return startInBackground(agentName, command, null);
    }

    private static long startInBackground(String agentName, String command, Long conversationId)
            throws InterruptedException {
        commit(() -> ToolContext.withScope(conversationId, null, () -> new ShellExecTool().execute(
                "{\"command\": \"" + command + " & echo started\"}", Agent.findByName(agentName))));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var found = sleepers(command);
            if (!found.isEmpty()) return found.getFirst().pid();
            Thread.sleep(50);
        }
        throw new AssertionError(command + " never started");
    }

    private static List<Long> pids(Http.Response response) {
        assertIsOk(response);
        var pids = new ArrayList<Long>();
        for (var entry : JsonParser.parseString(getContent(response)).getAsJsonArray().asList()) {
            pids.add(entry.getAsJsonObject().get("pid").getAsLong());
        }
        return pids;
    }

    private static void awaitGone(String command) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!sleepers(command).isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(sleepers(command).isEmpty(), command + " was stopped");
    }

    private static List<ProcessHandle> sleepers(String... commands) {
        return ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .filter(h -> h.info().commandLine()
                        .map(c -> Arrays.stream(commands).anyMatch(c::endsWith)).orElse(false))
                .toList();
    }

    private static Agent agentNamed(String name) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private void login() {
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    private static Http.Request agentRequest(Long agentId) {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    /** A bearer call leaves an agent-principal cookie in the JVM-global jar; clear it so no sibling inherits it. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }

    private static <T> T commit(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
