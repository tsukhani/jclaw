import com.google.gson.JsonParser;
import controllers.AgentAccess;
import controllers.RequestPrincipal;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.mvc.Router;
import play.test.FunctionalTest;
import services.Tx;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The permission table on the user guide's Agents page ("What jclaw_api lets each agent do", JF-315)
 * stays true to the code. Each row is pinned here with its three cells and one example route, so an
 * edit to the table fails here until the pin moves with it, and a change to a route's access fails
 * here until the table does.
 *
 * <p>The operator's and the custom agent's columns are driven over HTTP: the custom agent with the
 * bearer and agent header {@code jclaw_api} sends. The Main Agent's column is read off the example
 * route's {@link AgentAccess} level, since {@code main} is provisioned at boot and a sibling's
 * {@code Fixtures.deleteDatabase} can remove it mid-suite: a cell of {@code No} is exactly an
 * {@code OPERATOR_ONLY} route. Every example route is one the operator can call with no effect, a
 * read or an id that names nothing, because the operator's half really runs.
 */
class JclawApiPermissionMatrixTest extends FunctionalTest {

    private static final String SECTION_HEADING = "## What jclaw_api lets each agent do {#jclaw-api-permissions}";
    private static final String NO_SUCH_ID = "999999999";

    /** One table row: its three cells as the guide shows them, and the route that stands for it. */
    private record Row(String you, String main, String custom, String method, String path, String body) {}

    private static final String AGENT_ID = "{customAgentId}";

    private static final Map<String, Row> ROWS = new LinkedHashMap<>();

    static {
        pin("Read, rename, star or pin conversations", "All", "Every agent's", "Its own",
                "GET", "/api/conversations", null);
        pin("Change a conversation's model or thinking level", "Yes", "Yes", "No",
                "PUT", "/api/conversations/" + NO_SUCH_ID + "/model-override", "{}");
        pin("Delete a conversation or a message, stop a running reply, or send a chat message through the API",
                "Yes", "No", "No", "DELETE", "/api/conversations/" + NO_SUCH_ID, null);
        pin("Create, change, run, pause or delete tasks, and read their runs", "All", "Every agent's",
                "Its own, without choosing the model", "GET", "/api/tasks", null);
        pin("Mark a task as trusted, or clear the task run history", "Yes", "No", "No",
                "POST", "/api/tasks/" + NO_SUCH_ID + "/trust", "{}");
        pin("Subagent runs, scrape jobs and background programs: read, stop or delete", "All", "Every agent's",
                "Its own", "GET", "/api/subagent-runs", null);
        pin("Notifications, video jobs, and cost and latency figures", "All", "Every agent's", "Its own",
                "GET", "/api/notifications", null);
        pin("Read an agent's settings, tools and skills", "All", "Every agent's", "Its own",
                "GET", "/api/agents/" + AGENT_ID + "/tools", null);
        pin("Create an agent, or change an agent's model, prompt or other settings", "Yes", "Yes",
                "No, not even its own", "PUT", "/api/agents/" + NO_SUCH_ID, "{}");
        pin("Delete an agent, switch its tools or skills on or off, give it `jclaw_api`, or manage its workspace "
                + "files and approvals", "Yes", "No", "No",
                "PUT", "/api/agents/" + NO_SUCH_ID + "/tools/jclaw_api", "{\"enabled\":true}");
        pin("List and read saved prompts", "Yes", "Yes", "Yes", "GET", "/api/prompts", null);
        pin("Create, change, delete, generate or export prompts", "Yes", "Yes", "No",
                "DELETE", "/api/prompts/" + NO_SUCH_ID, null);
        pin("Import prompts", "Yes", "No", "No", "POST", "/api/prompts/import", "{}");
        pin("Read skills, tools, slash commands, prompt categories, time zones, hosted apps, JClaw's status, and "
                + "speech, image, video, OCR and printer status", "Yes", "Yes", "Yes", "GET", "/api/skills", null);
        pin("Add a skill to **Global Skills** (from a catalog, a zip or an agent's workspace), rename or delete one, "
                + "or install one on an agent", "Yes", "No", "No", "DELETE", "/api/skills/jf315-no-such-skill", null);
        pin("MCP servers: read, add, change, remove or test", "Yes", "Yes", "No", "GET", "/api/mcp-servers", null);
        pin("Read the configuration, providers and model router; add a provider's model, set the router's lists, "
                + "refresh prices", "Yes", "Yes", "No", "GET", "/api/config", null);
        pin("Change a configuration key or a log level", "Yes", "No", "No",
                "DELETE", "/api/config/jf315.no.such.key", null);
        pin("See which channel kinds are active", "Yes", "Yes", "Yes", "GET", "/api/channels/active", null);
        pin("Read the channel setup", "Yes", "Channel settings and WhatsApp links", "Its own WhatsApp links",
                "GET", "/api/channels/whatsapp/bindings", null);
        pin("Add, change or remove a Telegram, Slack or WhatsApp link", "Yes", "No", "No",
                "DELETE", "/api/channels/telegram/bindings/" + NO_SUCH_ID, null);
        pin("Read metrics, circuit breakers, and the restart, upgrade and Java status", "Yes", "Yes", "No",
                "GET", "/api/breakers", null);
        pin("Restart, upgrade, back up, restore, repair or compact", "Yes", "No", "No",
                "DELETE", "/api/system/database/backups/jf315-no-such-backup", null);
        pin("Memory, logs, sign-in and password, Tailscale, Chrome, telemetry and the software factory",
                "Yes", "No", "No", "GET", "/api/logs", null);
    }

    private static void pin(String what, String you, String main, String custom, String method, String path,
                            String body) {
        ROWS.put(what, new Row(you, main, custom, method, path, body));
    }

    private Long customAgentId;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and the one row here is this
    // class's by its unique name.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        var name = "jf315-custom-" + UUID.randomUUID().toString().substring(0, 8);
        customAgentId = commit(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            return agent.id;
        });
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    @Test
    void theGuidesTableHoldsExactlyThePinnedRowsAndCells() throws IOException {
        var expected = new ArrayList<String>();
        ROWS.forEach((what, row) -> expected.add(String.join(" | ", what, row.you(), row.main(), row.custom())));

        assertEquals(expected, guideRows());
    }

    @Test
    void theOperatorIsRefusedNoRow() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
        var wrong = new ArrayList<String>();
        ROWS.forEach((what, row) -> {
            var resp = send(newRequest(), row);
            // A 5xx would mean the example route broke, which measures nothing about access.
            if (resp.status == 401 || resp.status == 403 || resp.status >= 500) wrong.add(what + " -> " + describe(resp));
        });
        assertEquals(List.of(), wrong);
    }

    @Test
    void aCustomAgentIsRefusedExactlyTheRowsMarkedNo() {
        var wrong = new ArrayList<String>();
        ROWS.forEach((what, row) -> {
            var resp = asAgent(() -> send(agentRequest(), row));
            // agent_scope from a route the operator and main share, operator_only from one that is the operator's alone.
            var got = describe(resp);
            boolean refused = got.startsWith("403 agent_scope ") || got.startsWith("403 operator_only ");
            if (refused != row.custom().startsWith("No")) {
                wrong.add(what + " (" + row.custom() + ") -> " + got);
            }
        });
        assertEquals(List.of(), wrong);
    }

    @Test
    void theMainAgentsColumnSaysNoExactlyWhereTheRouteIsTheOperatorsAlone() throws Exception {
        var wrong = new ArrayList<String>();
        for (var entry : ROWS.entrySet()) {
            var row = entry.getValue();
            var level = declaredLevel(row);
            if ((level == AgentAccess.Level.OPERATOR_ONLY) != row.main().equals("No")) {
                wrong.add(entry.getKey() + " (" + row.main() + ") -> " + row.method() + " " + row.path() + " is " + level);
            }
        }
        assertEquals(List.of(), wrong);
    }

    // --- helpers ------------------------------------------------------------------------

    /** The guide table's rows as {@code what | you | main | custom}, read from the Agents page. */
    private static List<String> guideRows() throws IOException {
        var lines = Files.readAllLines(Path.of(Play.applicationPath.getAbsolutePath(), "docs", "user-guide", "agents.md"));
        int start = lines.indexOf(SECTION_HEADING);
        assertTrue(start >= 0, "the Agents page has no heading " + SECTION_HEADING);
        var rows = new ArrayList<String>();
        boolean inTable = false;
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("## "); i++) {
            var line = lines.get(i).strip();
            if (!line.startsWith("|")) {
                if (inTable) break;
                continue;
            }
            inTable = true;
            var cells = Arrays.stream(line.substring(1, line.length() - 1).split("\\|")).map(String::strip).toList();
            if (cells.get(0).equals("What") || cells.get(0).startsWith("---")) continue;
            rows.add(String.join(" | ", cells));
        }
        return rows;
    }

    private AgentAccess.Level declaredLevel(Row row) throws Exception {
        var path = row.path().replace(AGENT_ID, String.valueOf(customAgentId));
        var action = Router.route(row.method(), path).get("action");
        assertNotNull(action, row.method() + " " + path + " routes to no action");
        var dot = action.lastIndexOf('.');
        var controller = Play.classloader.loadClass("controllers." + action.substring(0, dot));
        Method method = Arrays.stream(controller.getMethods())
                .filter(m -> m.getName().equals(action.substring(dot + 1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(action + " is not a public action"));
        var access = method.getAnnotation(AgentAccess.class);
        assertNotNull(access, action + " declares no AgentAccess level");
        return access.value();
    }

    private Http.Response send(Http.Request request, Row row) {
        var path = row.path().replace(AGENT_ID, String.valueOf(customAgentId));
        return switch (row.method()) {
            case "POST" -> POST(request, path, "application/json", row.body());
            case "PUT" -> PUT(request, path, "application/json", row.body());
            case "DELETE" -> DELETE(request, path);
            default -> GET(request, path);
        };
    }

    /** {@code "<status> <code> <message>"} for an error body, and the status alone otherwise. */
    private static String describe(Http.Response response) {
        var body = getContent(response);
        try {
            var json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("code") && json.has("message")) {
                return response.status + " " + json.get("code").getAsString() + " " + json.get("message").getAsString();
            }
        } catch (RuntimeException e) {
            // Not a JSON error body: the status alone says what happened.
        }
        return String.valueOf(response.status);
    }

    private Http.Request agentRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(customAgentId)));
        return request;
    }

    /** A bearer call answers with a Set-Cookie carrying the agent principal, and FunctionalTest's
     *  saved cookies are JVM-global, so one left behind would 403 a sibling class. */
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
}
