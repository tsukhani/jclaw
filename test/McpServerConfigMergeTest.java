import com.google.gson.JsonParser;
import models.McpServer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.McpServerService;

/**
 * {@link McpServerService#mergeConfigJson}: an MCP server update is a JSON Merge Patch over the stored config,
 * so a field or a key the body leaves out is kept rather than wiped.
 */
class McpServerConfigMergeTest extends UnitTest {

    private static final String STDIO = """
            {"command":"node","args":["v1.js"],"env":{"API_KEY":"k-live","MODE":"prod"}}""";
    private static final String HTTP = """
            {"url":"http://127.0.0.1:1/mcp","headers":{"Authorization":"Bearer t","X-Tier":"gold"}}""";

    private static String mergeStdio(String body) {
        return McpServerService.mergeConfigJson(McpServer.Transport.STDIO, STDIO,
                JsonParser.parseString(body).getAsJsonObject());
    }

    @Test
    void aBodyWithOnlyArgsKeepsTheCommandAndEveryEnvValue() {
        assertEquals("""
                {"command":"node","args":["v2.js"],"env":{"API_KEY":"k-live","MODE":"prod"}}""",
                mergeStdio("{\"args\":[\"v2.js\"]}"));
    }

    @Test
    void envChangesOnlyTheKeysTheBodyNames() {
        assertEquals("""
                {"command":"node","args":["v1.js"],"env":{"API_KEY":"k-live","MODE":"dev","DEBUG":"1"}}""",
                mergeStdio("{\"env\":{\"MODE\":\"dev\",\"DEBUG\":\"1\"}}"));
    }

    @Test
    void aNullEnvValueRemovesOnlyThatKey() {
        assertEquals("""
                {"command":"node","args":["v1.js"],"env":{"API_KEY":"k-live"}}""",
                mergeStdio("{\"env\":{\"MODE\":null}}"));
    }

    @Test
    void anEmptyEnvChangesNothing() {
        assertEquals(STDIO, mergeStdio("{\"env\":{}}"));
    }

    @Test
    void aNullEnvClearsEveryKey() {
        assertEquals("""
                {"command":"node","args":["v1.js"]}""", mergeStdio("{\"env\":null}"));
    }

    @Test
    void aNullArgsClearsTheArgsAndKeepsTheRest() {
        assertEquals("""
                {"command":"node","env":{"API_KEY":"k-live","MODE":"prod"}}""", mergeStdio("{\"args\":null}"));
    }

    @Test
    void headersMergeByKeyAndKeepTheUrl() {
        var body = JsonParser.parseString("{\"headers\":{\"X-Tier\":null,\"X-Plan\":\"pro\"}}").getAsJsonObject();
        assertEquals("""
                {"url":"http://127.0.0.1:1/mcp","headers":{"Authorization":"Bearer t","X-Plan":"pro"}}""",
                McpServerService.mergeConfigJson(McpServer.Transport.HTTP, HTTP, body));
    }

    @Test
    void aTransportSwitchKeepsNothingOfTheOldTransport() {
        var body = JsonParser.parseString("{\"command\":\"node\"}").getAsJsonObject();
        assertEquals("{\"command\":\"node\"}",
                McpServerService.mergeConfigJson(McpServer.Transport.STDIO, HTTP, body));
    }

    @Test
    void aStoredConfigThatIsNotAJsonObjectLeavesOnlyTheBody() {
        var body = JsonParser.parseString("{\"url\":\"http://127.0.0.1:1/mcp\"}").getAsJsonObject();
        assertEquals("{\"url\":\"http://127.0.0.1:1/mcp\"}",
                McpServerService.mergeConfigJson(McpServer.Transport.HTTP, "not json", body));
    }
}
