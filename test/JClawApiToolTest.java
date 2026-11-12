import agents.DangerousActionGate;
import agents.ToolContext;
import controllers.RequestPrincipal;
import models.Agent;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import tools.JClawApiTool;
import utils.HttpFactories;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Unit coverage for {@link JClawApiTool}'s argument parsing, its refusal of unnormalized
 * paths, and the discover/call gate.
 *
 * <p>Skips the live-HTTP round trip -- bearer-auth + controller dispatch is
 * exercised by the FunctionalTest suite. What only this layer verifies is that
 * the gate and arg validation refuse bad inputs before any socket opens.
 *
 * <p>Gate model: {@code jclaw_api} discovers and invokes only the {@code /api/} routes whose
 * action declares {@link controllers.AgentAccess} {@code OPEN} or {@code OWN_ONLY}; an
 * {@code OPERATOR_ONLY} or undeclared action is refused. These tests run the real route-table
 * scan (UnitTest boots Play, so {@code Router.routes} is populated and controllers carry their
 * runtime annotations).
 *
 * <p>JF-148: a call is refused unless the turn is the owner's, so every other test makes its call as one.
 */
class JClawApiToolTest extends UnitTest {

    private static final String NOT_THE_OWNERS_TURN = "jclaw_api is not available in a conversation the owner didn't start.";

    private static JClawApiTool tool;

    @BeforeAll
    static void setup() {
        tool = new JClawApiTool();
    }

    /** The call on an owner-initiated turn, which every check below assumes. */
    private static String asOwner(String argsJson, Agent agent) {
        return DangerousActionGate.withOwnerInitiated(true, () -> tool.execute(argsJson, agent));
    }

    // ==================== argument validation ====================

    @Test
    void onlyTheEndpointListIsVerbatim() {
        assertTrue(tool.verbatimResult("{\"action\":\"discover\",\"filter\":\"tasks\"}"));
        assertFalse(tool.verbatimResult("{\"action\":\"call\",\"method\":\"GET\",\"path\":\"/api/tasks\"}"));
        assertFalse(tool.verbatimResult("{\"method\":\"GET\",\"path\":\"/api/tasks\"}"), "call is the default action");
        assertFalse(tool.verbatimResult("not json"));
    }

    @Test
    void rejectsMalformedJson() {
        var result = asOwner("this is not json", null);
        assertTrue(result.startsWith("Error: arguments are not valid JSON"),
                "expected JSON-parse error; got: " + result);
    }

    @Test
    void rejectsMissingMethod() {
        var result = asOwner("{\"path\":\"/api/agents\"}", null);
        assertTrue(result.startsWith("Error: both 'method' and 'path' are required"),
                "got: " + result);
    }

    @Test
    void rejectsMissingPath() {
        var result = asOwner("{\"method\":\"GET\"}", null);
        assertTrue(result.startsWith("Error: both 'method' and 'path' are required"),
                "got: " + result);
    }

    @Test
    void rejectsUnknownMethod() {
        var result = asOwner(
                "{\"method\":\"OPTIONS\",\"path\":\"/api/agents\"}", null);
        assertTrue(result.contains("method must be one of"),
                "got: " + result);
        assertTrue(result.contains("OPTIONS"),
                "error should echo the offending verb so the model can correct it; got: " + result);
    }

    @Test
    void rejectsNonApiPath() {
        var result = asOwner(
                "{\"method\":\"GET\",\"path\":\"/somewhere-else\"}", null);
        assertTrue(result.contains("path must start with /api/"),
                "got: " + result);
    }

    // ==================== operator-only routes (security) ====================

    @Test
    void blocksChatSendPath() {
        // Recursion guard: a chat agent calling /api/chat/send to send
        // another chat would invoke itself. Refused regardless of method.
        var result = asOwner(
                "{\"method\":\"POST\",\"path\":\"/api/chat/send\"," +
                "\"body\":{\"agentId\":1,\"message\":\"hi\"}}", null);
        assertTrue(result.contains("is not callable through jclaw_api"),
                "expected the default-deny refusal; got: " + result);
        assertTrue(result.contains("/api/chat/"),
                "the refusal should name the path it refused; got: " + result);
    }

    @Test
    void blocksAuthLoginPath() {
        var result = asOwner(
                "{\"method\":\"POST\",\"path\":\"/api/auth/login\"," +
                "\"body\":{\"username\":\"admin\",\"password\":\"x\"}}", null);
        assertTrue(result.contains("/api/auth/"),
                "auth endpoints must be blocked; got: " + result);
    }

    @Test
    void blocksWebhookPath() {
        var result = asOwner(
                "{\"method\":\"POST\",\"path\":\"/api/webhooks/telegram/x/y\"}", null);
        assertTrue(result.contains("/api/webhooks/"),
                "webhook endpoints must be blocked; got: " + result);
    }

    @Test
    void blocksEventsPath() {
        var result = asOwner(
                "{\"method\":\"GET\",\"path\":\"/api/events\"}", null);
        assertTrue(result.contains("/api/events"),
                "events SSE must be blocked; got: " + result);
    }

    @Test
    void blocksBindingsPath() {
        var result = asOwner("{\"method\":\"GET\",\"path\":\"/api/bindings\"}", null);
        assertTrue(result.contains("/api/bindings"),
                "bindings (comms routing) must be refused; got: " + result);
    }

    @Test
    void blocksTelegramBindingsPath() {
        var result = asOwner(
                "{\"method\":\"GET\",\"path\":\"/api/channels/telegram/bindings\"}", null);
        assertTrue(result.contains("is not callable through jclaw_api"),
                "telegram bindings (bot tokens) must stay refused; got: " + result);
    }

    @Test
    void blocksTailscalePath() {
        var result = asOwner("{\"method\":\"GET\",\"path\":\"/api/tailscale\"}", null);
        assertTrue(result.contains("/api/tailscale"),
                "tailscale (infra config) must be refused; got: " + result);
    }

    @Test
    void blocksLogsPath() {
        var result = asOwner("{\"method\":\"GET\",\"path\":\"/api/logs\"}", null);
        assertTrue(result.contains("/api/logs"),
                "logs (secret leak) must be refused; got: " + result);
    }

    @Test
    void blocksLoadtestPath() {
        var result = asOwner("{\"method\":\"POST\",\"path\":\"/api/metrics/loadtest\"}", null);
        assertTrue(result.contains("/api/metrics/loadtest"),
                "load-test harness (resource abuse) must be refused; got: " + result);
    }

    // ==================== path normalization (JCLAW-1227) ====================

    /**
     * The traversal the audit found. {@code GET /api/skills/{name}/files/{<.+>filePath}} is an
     * {@code OPEN} wildcard route, so its pattern swallows the {@code ../} and a gate reading the
     * raw string passes it — while OkHttp resolves it to {@code /api/logs} while parsing, which is
     * the path that actually leaves. Verified against the shipped okhttp-jvm-5.5.0:
     * {@code HttpUrl.parse(base + that).encodedPath()} is {@code /api/logs}.
     */
    @Test
    void aDotSegmentCannotWalkOutOfAWildcardRouteIntoAnOperatorOnlyRoute() {
        var result = asOwner(
                "{\"method\":\"GET\",\"path\":\"/api/skills/x/files/../../../logs\"}", null);
        assertTrue(result.startsWith("Error:"), "the traversal must be refused; got: " + result);
        assertFalse(result.startsWith("HTTP "),
                "the traversal must not reach the HTTP call at all; got: " + result);
    }

    /**
     * The same walk written {@code %2e%2e}, which the raw-string check cannot see: it is a
     * dot-segment only after OkHttp decodes it. This is what pins the ordering rather than the
     * up-front rejection — with the gate back on the raw path it resolves to {@code /api/logs}
     * and is sent.
     */
    @Test
    void aPercentEncodedDotSegmentIsGatedOnTheResolvedPath() {
        var result = asOwner(
                "{\"method\":\"GET\",\"path\":\"/api/skills/x/files/%2e%2e/%2e%2e/%2e%2e/logs\"}", null);
        assertTrue(result.contains("/api/logs"),
                "the resolved path must be what the gate sees; got: " + result);
        assertTrue(result.contains("is not callable through jclaw_api"),
                "expected the default-deny refusal; got: " + result);
    }

    @Test
    void refusesTheOtherFormsOkHttpRewrites() {
        // Measured on okhttp-jvm-5.5.0: a backslash parses as '/', and '#' truncates the rest
        // of the URL including the query. '//' is not collapsed, but it is a path the router
        // and the gate can read differently, so it is refused with them.
        assertTrue(asOwner("{\"method\":\"GET\",\"path\":\"/api/skills\\\\x/files\"}", null)
                        .contains("backslash"),
                "a backslash resolves to a path separator and must be refused");
        assertTrue(asOwner("{\"method\":\"GET\",\"path\":\"/api/status#/../logs\"}", null)
                        .contains("'#'"),
                "a '#' truncates the URL and must be refused");
        assertTrue(asOwner("{\"method\":\"GET\",\"path\":\"/api//memories\"}", null)
                        .contains("empty path segment"),
                "an empty path segment must be refused");
    }

    /** A query string in {@code path} still reaches the gate — the refusals above must not
     *  fire on a {@code //} or a dot inside a parameter value. */
    @Test
    void aQueryStringInThePathIsNotMistakenForATraversal() {
        var result = asOwner(
                "{\"method\":\"GET\",\"path\":\"/api/config?url=https://example.com/..\"}", null);
        assertFalse(result.contains("cannot be invoked through jclaw_api -- pass the resolved path"),
                "the query string is not part of the path; got: " + result);
    }

    // ==================== the request and the reply ====================

    @Test
    void aCallFromAChatNamesTheChatAndShowsTheRoutesNoteToTheModel() {
        var sent = new AtomicReference<Request>();
        var client = canned(sent, "End your turn now.");
        // JF-148: the chat must exist, since whether the owner reads it alone is read off it.
        var agent = AgentService.create("jf148-api-" + java.util.UUID.randomUUID(), "openrouter", "gpt-4.1");
        var chat = ConversationService.create(agent, "web", null).id;

        var result = HttpFactories.callWith(client, () -> ToolContext.withConversation(chat,
                () -> asOwner("{\"method\":\"POST\",\"path\":\"/api/tasks/5/run\"}", null)));

        assertEquals("HTTP 200\nNote: End your turn now.\n{\"id\":5}", result);
        assertEquals(String.valueOf(chat), sent.get().header(RequestPrincipal.CALLER_CONVERSATION_HEADER));
    }

    @Test
    void aCallOutsideAChatNamesNoChatAndAReplyWithoutANoteIsShownAsSent() {
        var sent = new AtomicReference<Request>();
        var client = canned(sent, null);

        var result = HttpFactories.callWith(client,
                () -> asOwner("{\"method\":\"POST\",\"path\":\"/api/tasks/5/run\"}", null));

        assertEquals("HTTP 200\n{\"id\":5}", result);
        assertNull(sent.get().header(RequestPrincipal.CALLER_CONVERSATION_HEADER));
    }

    private static String readReturning(String body) {
        return HttpFactories.callWith(replying(new AtomicReference<>(), 200, body),
                () -> asOwner("{\"method\":\"GET\",\"path\":\"/api/agents\"}", null));
    }

    /** A GET of a {@code body} with {@code charOffset} set to {@code offset}, a JSON value. */
    private static String readFrom(String body, String offset) {
        return HttpFactories.callWith(replying(new AtomicReference<>(), 200, body),
                () -> asOwner("{\"method\":\"GET\",\"path\":\"/api/agents\",\"charOffset\":" + offset + "}", null));
    }

    private static String readOn(int from, int to, int length, int next) {
        return "\n\n[Truncated: characters %d to %d of %d shown. ccr_retrieve cannot return the rest; read on with "
                .formatted(from, to, length)
                + "charOffset=%d. For a list, narrow the request first: pass a limit or a filter, or read one item "
                .formatted(next)
                + "through its own route.]";
    }

    @Test
    void aResponseAtTheCapIsShownWhole() {
        var body = "a".repeat(50_000);

        assertEquals("HTTP 200\n" + body, readReturning(body));
    }

    @Test
    void aGetOneCharacterOverTheCapIsCutAndNamesTheOffsetThatReadsOn() {
        var kept = "a".repeat(50_000);

        assertEquals("HTTP 200\n" + kept + readOn(0, 49_999, 50_001, 50_000), readReturning(kept + "b"));
    }

    @Test
    void aMiddlePartShowsItsRangeAndTheOffsetOfTheNext() {
        var body = "a".repeat(50_000) + "b".repeat(50_000) + "c".repeat(20_000);

        assertEquals("HTTP 200\n" + "b".repeat(50_000) + readOn(50_000, 99_999, 120_000, 100_000),
                readFrom(body, "50000"));
    }

    @Test
    void theLastPartSaysItReachesTheEnd() {
        var body = "a".repeat(100_000) + "c".repeat(20_000);

        assertEquals("HTTP 200\n" + "c".repeat(20_000)
                + "\n\n[Characters 100000 to 119999 of 120000 shown: the end of the response.]", readFrom(body, "100000"));
    }

    @Test
    void anOffsetAtTheLastCharacterReadsItAndOnePastTheEndIsRefused() {
        var body = "a".repeat(50_000) + "b";

        assertEquals("HTTP 200\nb\n\n[Characters 50000 to 50000 of 50001 shown: the end of the response.]",
                readFrom(body, "50000"));
        assertEquals("Error: charOffset 50001 is at or past the end of the response, which is 50001 characters long.",
                readFrom(body, "50001"));
    }

    @Test
    void noOffsetOrAZeroOffsetShowsTheResponseAsBefore() {
        assertEquals("HTTP 200\n{\"id\":5}", readReturning("{\"id\":5}"));
        assertEquals("HTTP 200\n{\"id\":5}", readFrom("{\"id\":5}", "0"));
        assertEquals("HTTP 200\n{\"id\":5}", readFrom("{\"id\":5}", "null"));
    }

    @Test
    void aWholeNumberSentAsTextOrWithAZeroFractionIsTheSameOffset() {
        var body = "a".repeat(50_000) + "b";
        var expected = "HTTP 200\nb\n\n[Characters 50000 to 50000 of 50001 shown: the end of the response.]";

        assertEquals(expected, readFrom(body, "\"50000\""));
        assertEquals(expected, readFrom(body, "50000.0"));
    }

    @Test
    void anOffsetThatIsNoWholeNumberOfZeroOrMoreIsRefusedBeforeAnyRequest() {
        for (var offset : List.of("-1", "1.5", "\"ten\"", "true", "[1]", "3000000000")) {
            var sent = new AtomicReference<Request>();
            var result = HttpFactories.callWith(replying(sent, 200, "{}"), () -> asOwner(
                    "{\"method\":\"GET\",\"path\":\"/api/agents\",\"charOffset\":" + offset + "}", null));

            assertEquals("Error: charOffset must be a whole number of characters, 0 or more -- got: " + offset, result);
            assertNull(sent.get(), offset);
        }
    }

    @Test
    void anOffsetOnAWriteIsRefusedBeforeAnyRequestButAZeroOneIsNot() {
        for (var method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            var sent = new AtomicReference<Request>();
            var result = HttpFactories.callWith(replying(sent, 200, "{}"), () -> asOwner(
                    "{\"method\":\"" + method + "\",\"path\":\"/api/tasks/5/run\",\"charOffset\":50000}", null));

            assertEquals("Error: charOffset reads on through a GET response only -- sending a " + method
                    + " again could act twice.", result);
            assertNull(sent.get(), method);
        }
        var sent = new AtomicReference<Request>();
        var zero = HttpFactories.callWith(replying(sent, 200, "{\"id\":5}"), () -> asOwner(
                "{\"method\":\"POST\",\"path\":\"/api/tasks/5/run\",\"charOffset\":0}", null));

        assertEquals("HTTP 200\n{\"id\":5}", zero);
        assertEquals("POST", sent.get().method());
    }

    @Test
    void aWriteOverTheCapIsCutAndSaysToNarrowSinceItCannotBeSentAgain() {
        var kept = "a".repeat(50_000);
        var result = HttpFactories.callWith(replying(new AtomicReference<>(), 200, kept + "b".repeat(70_000)),
                () -> asOwner("{\"method\":\"POST\",\"path\":\"/api/tasks/5/run\"}", null));

        assertEquals("HTTP 200\n" + kept + "\n\n[Truncated: 50000 of 120000 characters shown, 70000 dropped. The dropped "
                + "part was not kept, so ccr_retrieve cannot return it. Narrow the request instead: read one item through "
                + "its own route, or pass a limit or a filter.]", result);
    }

    @Test
    void anErrorIsShownFromItsStartWhateverTheOffset() {
        var result = HttpFactories.callWith(replying(new AtomicReference<>(), 404, "{\"code\":\"not_found\"}"),
                () -> asOwner("{\"method\":\"GET\",\"path\":\"/api/agents\",\"charOffset\":50000}", null));

        assertEquals("HTTP 404\n{\"code\":\"not_found\"}", result);
    }

    @Test
    void aReadOnIsLoggedWithTheCharacterItStartsFrom() {
        var body = "a".repeat(50_000) + "b";

        assertEquals(List.of("INFO GET /api/agents from character 50000: HTTP 200"), loggedCalls(
                replying(new AtomicReference<>(), 200, body),
                "{\"method\":\"GET\",\"path\":\"/api/agents\",\"charOffset\":50000}"));
    }

    /** A task run of a task recorded with {@code origin} and {@code ownerReadsAlone}, in the ambient transaction. */
    private static Long taskRun(Agent agent, String origin, Boolean ownerReadsAlone) {
        var task = new models.Task();
        task.agent = agent;
        task.name = "jf148-api-task-" + java.util.UUID.randomUUID();
        task.type = models.Task.Type.IMMEDIATE;
        task.status = models.Task.initialStatusFor(models.Task.Type.IMMEDIATE);
        task.originChannel = origin;
        task.ownerReadsAlone = ownerReadsAlone;
        task.save();
        var run = new models.TaskRun();
        run.task = task;
        run.save();
        return run.id;
    }

    // ==================== every call is logged ====================

    @Test
    void aWriteIsLoggedWithItsEndpointTheFieldsItSentAndTheStatus() {
        var agent = AgentService.create("api-log-" + java.util.UUID.randomUUID(), "openrouter", "gpt-4.1");
        var chat = ConversationService.create(agent, "web", null).id;

        var lines = EventLogger.captureForTest(() -> HttpFactories.callWith(replying(new AtomicReference<>(), 200, "{}"),
                () -> ToolContext.withConversation(chat, () -> asOwner("{\"method\":\"PUT\",\"path\":"
                        + "\"/api/router/models/reasoning\",\"body\":{\"models\":[{\"provider\":\"p\",\"model\":\"m\"}]}}",
                        agent)))).stream().filter(e -> JClawApiTool.LOG_CATEGORY.equals(e.category())).toList();

        assertEquals(1, lines.size(), lines.toString());
        var line = lines.getFirst();
        assertEquals(List.of("INFO", agent.name, "web", "PUT /api/router/models/reasoning (body: models): HTTP 200"),
                List.of(line.level(), line.agentId(), line.channel(), line.message()));
    }

    @Test
    void aReadIsLoggedWithoutItsQueryOrABody() {
        // A GET sends no body, so a body the model attached is not what the call set.
        assertEquals(List.of("INFO GET /api/agents: HTTP 200"), loggedCalls(replying(new AtomicReference<>(), 200, "[]"),
                "{\"method\":\"GET\",\"path\":\"/api/agents?limit=5\",\"body\":{\"ignored\":true}}"));
    }

    @Test
    void aCallJClawRefusesIsLoggedAsAWarningWithItsErrorCode() {
        var refusal = "{\"type\":\"error\",\"code\":\"agent_scope\",\"message\":\"Only the operator or the main agent may "
                + "change an agent's settings.\"}";

        assertEquals(List.of("WARN PUT /api/agents/7 (body: modelId): HTTP 403 agent_scope"),
                loggedCalls(replying(new AtomicReference<>(), 403, refusal),
                        "{\"method\":\"PUT\",\"path\":\"/api/agents/7\",\"body\":{\"modelId\":\"m\"}}"));
    }

    @Test
    void anErrorWithoutACodeIsLoggedWithItsStatusAlone() {
        assertEquals(List.of("WARN POST /api/tasks/5/run: HTTP 500"),
                loggedCalls(replying(new AtomicReference<>(), 500, "Internal Server Error"),
                        "{\"method\":\"POST\",\"path\":\"/api/tasks/5/run\"}"));
    }

    @Test
    void aCallTheToolRefusesIsLoggedAndNothingIsSent() {
        var sent = new AtomicReference<Request>();
        var client = replying(sent, 200, "{}");

        assertEquals(List.of("WARN DELETE /api/conversations: refused, not callable through jclaw_api"),
                loggedCalls(client, "{\"method\":\"DELETE\",\"path\":\"/api/conversations\"}"));
        assertEquals(List.of("WARN GET /api/skills/x/../../logs: refused, the path contains a dot-segment"),
                loggedCalls(client, "{\"method\":\"GET\",\"path\":\"/api/skills/x/../../logs\"}"));
        assertEquals(List.of("WARN GET /logs: refused, not an /api/ path"),
                loggedCalls(client, "{\"method\":\"GET\",\"path\":\"/logs\"}"));
        assertNull(sent.get(), "a refused call sends nothing");
    }

    @Test
    void aRequestThatNeverArrivesIsLoggedAsFailed() {
        Interceptor down = chain -> {
            throw new java.io.IOException("connection refused");
        };

        assertEquals(List.of("WARN POST /api/tasks/5/run: failed, connection refused"),
                loggedCalls(new OkHttpClient.Builder().addInterceptor(down).build(),
                        "{\"method\":\"POST\",\"path\":\"/api/tasks/5/run\"}"));
    }

    @Test
    void theLogNamesUpToTwelveBodyFieldsAndCountsTheRest() {
        var twelve = new StringBuilder();
        for (var i = 1; i <= 12; i++) twelve.append(i == 1 ? "" : ",").append("\"f").append(i).append("\":1");
        var client = replying(new AtomicReference<>(), 200, "{}");

        assertEquals(List.of("INFO POST /api/tasks/5/run (body: f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12): HTTP 200"),
                loggedCalls(client, "{\"method\":\"POST\",\"path\":\"/api/tasks/5/run\",\"body\":{" + twelve + "}}"));
        assertEquals(List.of("INFO POST /api/tasks/5/run (body: f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, +1 more): "
                + "HTTP 200"), loggedCalls(client,
                "{\"method\":\"POST\",\"path\":\"/api/tasks/5/run\",\"body\":{" + twelve + ",\"f13\":1}}"));
    }

    @Test
    void describeCallNamesTheMethodAndPathAlone() {
        assertEquals("PUT /api/router/models/reasoning", tool.describeCall(
                "{\"method\":\"put\",\"path\":\"/api/router/models/reasoning?dryRun=1\",\"body\":{\"models\":[]}}"));
        assertNull(tool.describeCall("{\"action\":\"discover\",\"method\":\"GET\",\"path\":\"/api/agents\"}"));
        assertNull(tool.describeCall("{\"method\":\"POST\"}"));
        assertNull(tool.describeCall("{\"path\":\"/api/agents\"}"));
        assertNull(tool.describeCall("not json"));
    }

    /** The prompt's Call: line must name where the request goes, which the model's string need not say. */
    @Test
    void describeCallNamesTheEndpointTheRequestReachesAndTheLogAgrees() {
        var disguised = "{\"method\":\"POST\",\"path\":\"/api/agents/7/%2e%2e/%2e%2e/mcp-servers\",\"body\":{\"name\":\"x\"}}";

        assertEquals("POST /api/mcp-servers", tool.describeCall(disguised));
        assertEquals(List.of("INFO POST /api/mcp-servers (body: name): HTTP 200"),
                loggedCalls(replying(new AtomicReference<>(), 200, "{}"), disguised));
        assertEquals("PUT /api/router/models/reason%20ing",
                tool.describeCall("{\"method\":\"PUT\",\"path\":\"/api/router/models/reason ing\"}"),
                "the percent-encoded path is the one sent");
    }

    @Test
    void describeCallSaysWhenTheCallWillBeRefused() {
        assertEquals("GET /api/logs (refused: not callable through jclaw_api)",
                tool.describeCall("{\"method\":\"GET\",\"path\":\"/api/skills/x/files/%2e%2e/%2e%2e/%2e%2e/logs\"}"));
        assertEquals("GET /api/skills/x/../../logs (refused: the path contains a dot-segment)",
                tool.describeCall("{\"method\":\"GET\",\"path\":\"/api/skills/x/../../logs\"}"));
        assertEquals("GET /logs (refused: not an /api/ path)", tool.describeCall("{\"method\":\"GET\",\"path\":\"/logs\"}"));
    }

    @Test
    void aPathPastTwoHundredCharactersKeepsBothEnds() {
        var twoHundred = "/api/router/models/" + "a".repeat(181);
        var twoHundredOne = twoHundred + "z";

        assertEquals("PUT " + twoHundred, tool.describeCall("{\"method\":\"PUT\",\"path\":\"" + twoHundred + "\"}"));
        assertEquals("PUT " + twoHundredOne.substring(0, 100) + "…" + twoHundredOne.substring(101),
                tool.describeCall("{\"method\":\"PUT\",\"path\":\"" + twoHundredOne + "\"}"));
    }

    /** The jclaw_api lines one owner's call with {@code argsJson} wrote through {@code client}, as "LEVEL message". */
    private static List<String> loggedCalls(OkHttpClient client, String argsJson) {
        return EventLogger.captureForTest(() -> HttpFactories.callWith(client, () -> asOwner(argsJson, null))).stream()
                .filter(e -> JClawApiTool.LOG_CATEGORY.equals(e.category()))
                .map(e -> e.level() + " " + e.message())
                .toList();
    }

    private static OkHttpClient replying(AtomicReference<Request> sent, int code, String body) {
        Interceptor reply = chain -> {
            sent.set(chain.request());
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("status " + code)
                    .body(ResponseBody.create(body, null))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(reply).build();
    }

    private static OkHttpClient canned(AtomicReference<Request> sent, String note) {
        Interceptor reply = chain -> {
            sent.set(chain.request());
            var response = new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create("{\"id\":5}", null));
            if (note != null) response.header(RequestPrincipal.AGENT_NOTE_HEADER, note);
            return response.build();
        };
        return new OkHttpClient.Builder().addInterceptor(reply).build();
    }

    // ==================== JF-148: only on a turn the owner started ====================

    @Test
    void aTurnTheOwnerDidNotStartIsRefusedBeforeAnyRequestIsSent() {
        var agent = AgentService.create("jf148-api-" + java.util.UUID.randomUUID(), "openrouter", "gpt-4.1");
        var whatsapp = ConversationService.create(agent, "whatsapp", "15550001111").id;
        var telegram = ConversationService.create(agent, "telegram", "5151").id;
        var telegramGroup = ConversationService.create(agent, "telegram", "-100200").id;
        var slackChannel = ConversationService.create(agent, "slack", "C123").id;
        var slackDm = ConversationService.create(agent, "slack", "D123").id;
        var app = ConversationService.create(agent, "app", "an-app").id;
        var guestsTaskRun = taskRun(agent, "telegram", null);

        for (var call : List.of("{\"method\":\"GET\",\"path\":\"/api/tasks\"}",
                "{\"method\":\"POST\",\"path\":\"/api/tasks/5/run\"}", "{\"action\":\"discover\"}")) {
            var guests = new LinkedHashMap<String, Supplier<String>>();
            guests.put("WhatsApp", () -> ToolContext.withConversation(whatsapp, () -> tool.execute(call, agent)));
            guests.put("a non-owner's Telegram DM", () -> DangerousActionGate.withOwnerInitiated(false,
                    () -> ToolContext.withConversation(telegram, () -> tool.execute(call, agent))));
            guests.put("the owner in a Telegram group", () -> DangerousActionGate.withOwnerInitiated(true,
                    () -> ToolContext.withConversation(telegramGroup, () -> tool.execute(call, agent))));
            guests.put("the owner in a Slack channel", () -> DangerousActionGate.withOwnerInitiated(true,
                    () -> ToolContext.withConversation(slackChannel, () -> tool.execute(call, agent))));
            // SlackInbound binds owner-initiated only against a saved owner, so with none it binds nothing.
            guests.put("a Slack DM on a binding with no owner saved",
                    () -> ToolContext.withConversation(slackDm, () -> tool.execute(call, agent)));
            guests.put("a hosted app", () -> ToolContext.withConversation(app, () -> tool.execute(call, agent)));
            guests.put("a fire of a task a guest set up", () -> DangerousActionGate.withFireOrigin("telegram",
                    () -> ToolContext.withScope(null, guestsTaskRun, () -> tool.execute(call, agent))));
            guests.put("no turn at all", () -> tool.execute(call, agent));
            guests.forEach((who, turn) -> {
                var sent = new AtomicReference<Request>();
                var result = HttpFactories.callWith(canned(sent, null), turn);
                assertEquals(NOT_THE_OWNERS_TURN, result, who + ", " + call);
                assertNull(sent.get(), who + ", " + call + ": no request may leave");
            });
        }
    }

    @Test
    void theOwnersTurnsStillReachTheApi() {
        var agent = AgentService.create("jf148-api-" + java.util.UUID.randomUUID(), "openrouter", "gpt-4.1");
        var web = ConversationService.create(agent, "web", null).id;
        var voice = ConversationService.create(agent, "voice", "admin").id;
        var telegram = ConversationService.create(agent, "telegram", "4242").id;
        var slack = ConversationService.create(agent, "slack", "D456").id;
        var ownersTaskRun = taskRun(agent, "telegram", Boolean.TRUE);
        var call = "{\"method\":\"GET\",\"path\":\"/api/tasks\"}";

        var owners = new LinkedHashMap<String, Supplier<String>>();
        owners.put("web chat", () -> ToolContext.withConversation(web, () -> tool.execute(call, agent)));
        owners.put("voice", () -> ToolContext.withConversation(voice, () -> tool.execute(call, agent)));
        owners.put("the owner's Telegram DM", () -> DangerousActionGate.withOwnerInitiated(true,
                () -> ToolContext.withConversation(telegram, () -> tool.execute(call, agent))));
        owners.put("the owner's Slack DM", () -> DangerousActionGate.withOwnerInitiated(true,
                () -> ToolContext.withConversation(slack, () -> tool.execute(call, agent))));
        owners.put("a fire of a task the operator set up", () -> DangerousActionGate.withFireOrigin("web",
                () -> tool.execute(call, agent)));
        owners.put("a fire of a task the owner made in their DM", () -> DangerousActionGate.withFireOrigin("telegram",
                () -> ToolContext.withScope(null, ownersTaskRun, () -> tool.execute(call, agent))));
        owners.forEach((who, turn) -> {
            var sent = new AtomicReference<Request>();
            assertEquals("HTTP 200\n{\"id\":5}", HttpFactories.callWith(canned(sent, null), turn), who);
            assertEquals("/api/tasks", sent.get().url().encodedPath(), who);
            // A task the call creates is marked as made where the owner reads alone; a fire has no chat to say so.
            assertEquals(who.startsWith("a fire") ? null : "true",
                    sent.get().header(RequestPrincipal.CALLER_OWNER_READS_ALONE_HEADER), who);
        });
    }

    // ==================== call gate ====================

    @Test
    void callAllowsAnOpenEndpoint() {
        // /api/status declares OPEN, so execute proceeds past the gate to the HTTP
        // call (which then errors at the socket in the unit JVM).
        var result = asOwner("{\"method\":\"GET\",\"path\":\"/api/status\"}", null);
        assertFalse(result.contains("is not callable"),
                "OPEN /api/status must be callable; got: " + result);
    }

    @Test
    void callRejectsAnOperatorOnlyEndpoint() {
        // DELETE /api/conversations (bulk wipe) is OPERATOR_ONLY -- refused even
        // though GET /api/conversations is callable.
        var result = asOwner("{\"method\":\"DELETE\",\"path\":\"/api/conversations\"}", null);
        assertTrue(result.contains("is not callable"),
                "OPERATOR_ONLY endpoint must be refused; got: " + result);
    }

    @Test
    void isCallableAllowsOpenRoutes() {
        assertTrue(JClawApiTool.isCallable("GET", "/api/status"),
                "OPEN GET /api/status is callable");
        // Every route declares a level, so a callable mutating route says so with OPEN or
        // OWN_ONLY rather than by carrying nothing.
        assertTrue(JClawApiTool.isCallable("DELETE", "/api/tasks/5"),
                "OPEN DELETE task is callable -- it removes a task the agent can equally create");
        assertTrue(JClawApiTool.isCallable("POST", "/api/providers/refresh-prices"),
                "refresh-prices is callable -- the route itself answers only the operator and main (JF-292)");
        assertTrue(JClawApiTool.isCallable("GET", "/api/providers/openrouter/models"),
                "concrete path resolves against the route pattern");
        // JCLAW-1020: the read-only halves of the two operator-only system actions share their
        // paths, so gating by verb is what keeps "what version am I on?" answerable to main,
        // the one agent those reads answer (JF-292).
        assertTrue(JClawApiTool.isCallable("GET", "/api/system/upgrade"),
                "the upgrade preflight is read-only and stays callable");
        assertTrue(JClawApiTool.isCallable("GET", "/api/system/restart"),
                "the restart preflight is read-only and stays callable");
        // JF-350: main restarts the instance through the API, and checks the restart afterwards.
        assertTrue(JClawApiTool.isCallable("POST", "/api/system/restart"),
                "the restart answers the operator and main");
        assertTrue(JClawApiTool.isCallable("GET", "/api/system/restart/progress"),
                "the restart's progress answers the operator and main");
    }

    @Test
    void isCallableRefusesOperatorOnlyAndUnknown() {
        assertFalse(JClawApiTool.isCallable("DELETE", "/api/conversations"),
                "deleteConversations is OPERATOR_ONLY");
        assertFalse(JClawApiTool.isCallable("PUT", "/api/channels/web"),
                "channels save is OPERATOR_ONLY");
        assertFalse(JClawApiTool.isCallable("GET", "/api/tailscale"),
                "tailscale is OPERATOR_ONLY");
        // JCLAW-941: memory is cross-agent personal data — /api/memories is the operator's
        // admin view over every agent's corpus, so an agent reaching it could read, edit or
        // delete another agent's memories. The scoped `memory` tool is the agent path.
        assertFalse(JClawApiTool.isCallable("GET", "/api/memories"),
                "listing memories across agents is OPERATOR_ONLY");
        assertFalse(JClawApiTool.isCallable("DELETE", "/api/memories/7"),
                "deleting a memory is OPERATOR_ONLY");
        assertFalse(JClawApiTool.isCallable("POST", "/api/memories/recall"),
                "recall for an arbitrary agentId is OPERATOR_ONLY");
        assertFalse(JClawApiTool.isCallable("POST", "/api/memories/7/verify"),
                "a human verification is the operator's alone");
        assertFalse(JClawApiTool.isCallable("GET", "/api/logs"),
                "logs is OPERATOR_ONLY");
        // JCLAW-1020: the semver gate stopped the download leaving the pinned repo, but a
        // legitimate older release is still an attacker's goal — v0.17.77 predates this
        // sprint's fixes, so an agent that could reinstall it would reopen them and then
        // walk back through.
        assertFalse(JClawApiTool.isCallable("POST", "/api/system/upgrade"),
                "upgrade replaces the install and restarts -- OPERATOR_ONLY");
        // JCLAW-1022: the config table holds the instance's own security controls -- the shell
        // allowlist, the approval policy, the funnel switch -- so a caller able to write it
        // widens every other gate rather than defeating one. Reads stay callable, masked, and
        // answer only main (JF-292).
        assertFalse(JClawApiTool.isCallable("POST", "/api/config"),
                "writing config is OPERATOR_ONLY");
        assertFalse(JClawApiTool.isCallable("DELETE", "/api/config/shell.allowlist"),
                "deleting a config row is OPERATOR_ONLY");
        assertFalse(JClawApiTool.isCallable("GET", "/api/no-such-endpoint-xyz"),
                "nonexistent path matches only the OPERATOR_ONLY 404 catch-all -> refused");
    }

    @Test
    void isCallableIgnoresQueryString() {
        assertTrue(JClawApiTool.isCallable("GET", "/api/config?foo=bar"),
                "query string should be stripped before matching");
    }

    // ==================== discover ====================

    @Test
    void discoverUsesOperationSummaryAndBodyHint() {
        var out = asOwner("{\"action\":\"discover\"}", null);
        assertTrue(out.contains("/api/agents"), "agents endpoint missing: " + out);
        assertTrue(out.toLowerCase().contains("list agents"), "@Operation summary missing: " + out);
        assertTrue(out.contains("/api/mcp-servers"), "mcp-servers endpoint missing: " + out);
        // Body hint mined from the Swagger @RequestBody record, read off AgentRequest: an
        // OPERATOR_ONLY endpoint such as config-save contributes no hint because it is not in
        // the catalog at all.
        assertTrue(out.contains("name, modelProvider"),
                "agent-write body hint from @RequestBody record missing: " + out);
    }

    @Test
    void discoverPointsATranscriptSearchHitAtItsRunsMessagesForTheFullText() {
        var lines = asOwner("{\"action\":\"discover\",\"filter\":\"/api/task-runs/\"}", null).lines().toList();
        assertEquals(List.of("- GET /api/task-runs/search - Full-text search task-run transcripts (q, limit up to 50); "
                        + "each hit carries a snippet around the match with task/run context, and the message's "
                        + "full text comes from GET /api/task-runs/{id}/messages"),
                lines.stream().filter(l -> l.startsWith("- GET /api/task-runs/search ")).toList());
        assertEquals(List.of("- GET /api/task-runs/{id}/messages - Turn-by-turn message trace for one TaskRun "
                        + "(turnIndex order), 404 if run missing; where a GET /api/task-runs/search hit's full "
                        + "message text comes from"),
                lines.stream().filter(l -> l.startsWith("- GET /api/task-runs/{id}/messages ")).toList());
    }

    @Test
    void discoverIncludesOpenEndpoints() {
        var out = asOwner("{\"action\":\"discover\"}", null);
        assertTrue(out.contains("/api/status"), "OPEN /api/status must be discovered: " + out);
        assertTrue(out.contains("/api/tasks"), "OPEN /api/tasks must be discovered: " + out);
    }

    @Test
    void discoverExcludesOperatorOnlyAndCatchAll() {
        var out = asOwner("{\"action\":\"discover\"}", null);
        assertFalse(out.contains("/api/chat"), "operator-only /api/chat leaked: " + out);
        assertFalse(out.contains("/api/auth"), "operator-only /api/auth leaked: " + out);
        assertFalse(out.contains("/api/tailscale"), "operator-only /api/tailscale leaked: " + out);
        assertFalse(out.contains("/api/logs"), "operator-only /api/logs leaked: " + out);
        assertFalse(out.contains("ANY /api/"), "404 catch-all (OPERATOR_ONLY) leaked into discover: " + out);
    }

    @Test
    void discoverFilterNarrowsResults() {
        var out = asOwner("{\"action\":\"discover\",\"filter\":\"mcp-servers\"}", null);
        assertTrue(out.contains("/api/mcp-servers"), "mcp-servers endpoint missing under filter: " + out);
        assertFalse(out.contains("/api/agents"), "filter=mcp-servers should exclude agents: " + out);
    }

    // ==================== schema-level checks ====================

    @Test
    void parametersNoLongerForceMethodAndPath() {
        assertNull(tool.parameters().get("required"),
                "method/path must not be schema-required so discover can omit them");
    }

    @Test
    void nameMatchesPublicConstant() {
        assertEquals(JClawApiTool.TOOL_NAME, tool.name());
    }

    // ============ JCLAW-844: per-call danger classification ============

    @Test
    void dangerousForMutatingVerbs() {
        assertTrue(tool.dangerous("{\"method\":\"POST\",\"path\":\"/api/mcp-servers\"}"),
                "POST is a mutation and must be gated");
        assertTrue(tool.dangerous("{\"method\":\"PUT\",\"path\":\"/api/config\"}"), "PUT must be gated");
        assertTrue(tool.dangerous("{\"method\":\"PATCH\",\"path\":\"/api/agents/1\"}"), "PATCH must be gated");
        assertTrue(tool.dangerous("{\"method\":\"DELETE\",\"path\":\"/api/agents/1\"}"), "DELETE must be gated");
    }

    @Test
    void dangerousIsCaseInsensitiveOnVerb() {
        assertTrue(tool.dangerous("{\"method\":\"post\",\"path\":\"/api/mcp-servers\"}"),
                "a lowercase verb must still classify as a mutation");
    }

    @Test
    void notDangerousForReadsAndDiscover() {
        assertFalse(tool.dangerous("{\"method\":\"GET\",\"path\":\"/api/mcp-servers\"}"),
                "GET is a read; masking (JCLAW-780) handles read exposure, no gate");
        assertFalse(tool.dangerous("{\"action\":\"discover\"}"),
                "discover only lists endpoints; not a mutation");
        assertFalse(tool.dangerous("{\"action\":\"discover\",\"method\":\"POST\"}"),
                "discover is a read even if a stray method is present");
    }

    @Test
    void notDangerousForMalformedOrMethodlessArgs() {
        assertFalse(tool.dangerous(null), "null args -> no-op -> not dangerous");
        assertFalse(tool.dangerous("not json"), "malformed args are rejected by execute() -> not dangerous");
        assertFalse(tool.dangerous("[1,2,3]"), "non-object JSON -> not dangerous");
        assertFalse(tool.dangerous("{\"path\":\"/api/agents\"}"), "method-less call is a no-op -> not dangerous");
    }

    /**
     * The exact set of endpoints {@code jclaw_api} advertises and will invoke.
     *
     * <p>Every routed {@code /api} action declares {@link controllers.AgentAccess}, and one that
     * declares nothing is {@code OPERATOR_ONLY}; the tool advertises and calls only the
     * {@code OPEN} and {@code OWN_ONLY} routes (JCLAW-1270). This pin makes widening that surface
     * a visible diff: a route that becomes agent-reachable fails this test until its author lists
     * it here.
     *
     * <p>So a diff to this list is not a chore — it is the review. Adding a line says "an agent
     * may drive this"; if that is wrong, the fix is {@code OPERATOR_ONLY} on the action.
     */
    @Test
    void theCallableApiSurfaceIsExactlyTheseRoutes() {
        var expected = new TreeSet<>(List.of("""
            DELETE /api/conversations/{id}/model-override
            DELETE /api/conversations/{id}/pin
            DELETE /api/conversations/{id}/star
            DELETE /api/conversations/{id}/thinking-override
            DELETE /api/mcp-servers/{id}
            DELETE /api/prompts/{id}
            DELETE /api/scrape-jobs/{id}
            DELETE /api/subagent-runs
            DELETE /api/subagent-runs/{id}
            DELETE /api/tasks/{id}
            GET /api/agents
            GET /api/agents/{agentId}/core-migration
            GET /api/agents/{id}
            GET /api/agents/{id}/prompt-breakdown
            GET /api/agents/{id}/prompt-text
            GET /api/agents/{id}/shell/effective-allowlist
            GET /api/agents/{id}/skills
            GET /api/agents/{id}/skills/{name}/files
            GET /api/agents/{id}/skills/{name}/files/{<.+>filePath}
            GET /api/agents/{id}/tools
            GET /api/apps
            GET /api/apps/{slug}/files/{uuid}
            GET /api/attachments/{uuid}
            GET /api/breakers
            GET /api/channels
            GET /api/channels/active
            GET /api/channels/whatsapp/bindings
            GET /api/channels/whatsapp/bindings/{id}/qr
            GET /api/config
            GET /api/config/{key}
            GET /api/conversations
            GET /api/conversations/channels
            GET /api/conversations/{id}
            GET /api/conversations/{id}/messages
            GET /api/conversations/{id}/messages/{messageId}
            GET /api/conversations/{id}/queue
            GET /api/imagegen/bfl/models
            GET /api/imagegen/capability
            GET /api/imagegen/local/state
            GET /api/imagegen/models
            GET /api/imagegen/progress
            GET /api/logging/levels
            GET /api/mcp-servers
            GET /api/mcp-servers/{id}
            GET /api/metrics/compression
            GET /api/metrics/compression/rows
            GET /api/metrics/cost
            GET /api/metrics/db-pool
            GET /api/metrics/jvm
            GET /api/metrics/latency
            GET /api/metrics/latency/rows
            GET /api/metrics/logs
            GET /api/notifications
            GET /api/ocr/status
            GET /api/onboarding/tour-status
            GET /api/printers
            GET /api/printers/default
            GET /api/printers/default/status
            GET /api/printers/options
            GET /api/processes
            GET /api/prompts
            GET /api/prompts/categories
            GET /api/prompts/export
            GET /api/prompts/{id}
            GET /api/providers
            GET /api/providers/{name}/embedding-models
            GET /api/providers/{name}/models
            GET /api/providers/{name}/reachable
            GET /api/providers/{name}/video-models
            GET /api/router/status
            GET /api/router/usage/ollama-cloud
            GET /api/scrape-jobs
            GET /api/scrape-jobs/{id}
            GET /api/scrape-jobs/{id}/download
            GET /api/scrape-jobs/{id}/pages
            GET /api/scrape-jobs/{id}/pages/{pageId}/content
            GET /api/skills
            GET /api/skills/by-agent
            GET /api/skills/catalog/search
            GET /api/skills/catalogs
            GET /api/skills/{name}
            GET /api/skills/{name}/files
            GET /api/skills/{name}/files/{<.+>filePath}
            GET /api/slash-commands
            GET /api/status
            GET /api/subagent-runs
            GET /api/subagent-runs/{id}
            GET /api/subagent-runs/{id}/steps
            GET /api/subagents/acp-command
            GET /api/subagents/acp-harnesses
            GET /api/system/java-runtime
            GET /api/system/restart
            GET /api/system/restart/progress
            GET /api/system/upgrade
            GET /api/system/upgrade/status
            GET /api/task-runs/recent
            GET /api/task-runs/search
            GET /api/task-runs/{id}/messages
            GET /api/tasks
            GET /api/tasks/stats
            GET /api/tasks/{id}
            GET /api/tasks/{id}/delivery-advisory
            GET /api/tasks/{id}/runs
            GET /api/timezones
            GET /api/tools
            GET /api/tools/meta
            GET /api/tools/{name}
            GET /api/transcription/diarization/models
            GET /api/transcription/state
            GET /api/tts/state
            GET /api/videogen/bfl/models
            GET /api/videogen/capability
            GET /api/videogen/jobs
            GET /api/videogen/jobs/recent
            GET /api/videogen/models
            GET /api/videogen/state
            GET /api/workspace/stats
            PATCH /api/tasks/{id}
            POST /api/agents
            POST /api/mcp-servers
            POST /api/mcp-servers/{id}/test
            POST /api/notifications/{id}/ack
            POST /api/onboarding/tour-progress
            POST /api/processes/{pid}/stop
            POST /api/prompts
            POST /api/prompts/generate
            POST /api/providers/refresh-prices
            POST /api/providers/{name}/discover-models
            POST /api/providers/{name}/embedding-probe
            POST /api/providers/{name}/models
            POST /api/scrape-jobs/{id}/cancel
            POST /api/scrape-jobs/{id}/pause
            POST /api/scrape-jobs/{id}/resume
            POST /api/subagent-runs/{id}/kill
            POST /api/system/restart
            POST /api/task-runs/{runId}/cancel
            POST /api/tasks
            POST /api/tasks/{id}/cancel
            POST /api/tasks/{id}/pause
            POST /api/tasks/{id}/reenable
            POST /api/tasks/{id}/resume
            POST /api/tasks/{id}/retry
            POST /api/tasks/{id}/run
            PUT /api/agents/{id}
            PUT /api/conversations/{id}/model-override
            PUT /api/conversations/{id}/name
            PUT /api/conversations/{id}/pin
            PUT /api/conversations/{id}/star
            PUT /api/conversations/{id}/thinking-override
            PUT /api/mcp-servers/{id}
            PUT /api/prompts/{id}
            PUT /api/router/models/{taskClass}
            WS /api/voice""".split("\n")));

        assertEquals(expected, callableSurface(),
                "The jclaw_api callable surface changed. Anything listed here can be invoked by "
                        + "an agent through the tool. If the new route writes privilege — config, "
                        + "grants, workspace files, instance lifecycle — it declares @AgentAccess "
                        + "OPERATOR_ONLY and does not belong on this list. A route reaches this "
                        + "list only as OPEN or OWN_ONLY, and CapabilityRulesTest requires a "
                        + "mutating one to say what bounds it (JCLAW-1270).");
    }

    /** What {@code discover} actually advertises, which is what an agent can act on. */
    private static SortedSet<String> callableSurface() {
        var found = new TreeSet<String>();
        for (var line : asOwner("{\"action\":\"discover\"}", null).split("\n")) {
            var trimmed = line.strip();
            if (!trimmed.startsWith("- ")) continue;
            var cols = trimmed.substring(2).strip().split("\\s+");
            if (cols.length >= 2) found.add(cols[0] + " " + cols[1]);
        }
        return found;
    }

}
