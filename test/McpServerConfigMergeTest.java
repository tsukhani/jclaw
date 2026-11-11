import com.google.gson.JsonParser;
import models.McpServer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.McpServerService;

import java.util.List;

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
    void aSwitchToHttpKeepsNoSavedEnvValue() {
        var body = JsonParser.parseString("{\"url\":\"https://mcp.example.test/v1\"}").getAsJsonObject();
        assertEquals("{\"url\":\"https://mcp.example.test/v1\"}",
                McpServerService.mergeConfigJson(McpServer.Transport.HTTP, STDIO, body));
    }

    // ==================== saved headers stay with the origin they were saved for ====================

    private static final String REMOTE = """
            {"url":"https://mcp.example.test/v1","headers":{"Authorization":"Bearer t","X-Tier":"gold"}}""";

    private static String mergeRemote(String body) {
        return McpServerService.mergeConfigJson(McpServer.Transport.HTTP, REMOTE,
                JsonParser.parseString(body).getAsJsonObject());
    }

    @Test
    void aUrlOnAnotherHostDropsEverySavedHeader() {
        assertEquals("{\"url\":\"https://other.example.test/v1\"}",
                mergeRemote("{\"url\":\"https://other.example.test/v1\"}"));
    }

    @Test
    void aNewPathOnTheSameHostKeepsEverySavedHeader() {
        assertEquals("""
                {"url":"https://mcp.example.test/v2?x=1","headers":{"Authorization":"Bearer t","X-Tier":"gold"}}""",
                mergeRemote("{\"url\":\"https://mcp.example.test/v2?x=1\"}"));
    }

    @Test
    void theSameHostSpelledInAnotherCaseOrWithItsDefaultPortKeepsTheSavedHeaders() {
        assertEquals("""
                {"url":"HTTPS://MCP.Example.Test:443/v1","headers":{"Authorization":"Bearer t","X-Tier":"gold"}}""",
                mergeRemote("{\"url\":\"HTTPS://MCP.Example.Test:443/v1\"}"));
    }

    @Test
    void anotherPortOnTheSameHostNameDropsTheSavedHeaders() {
        assertEquals("{\"url\":\"https://mcp.example.test:8443/v1\"}",
                mergeRemote("{\"url\":\"https://mcp.example.test:8443/v1\"}"));
    }

    @Test
    void anotherSchemeOnTheSameHostNameDropsTheSavedHeaders() {
        assertEquals("{\"url\":\"http://mcp.example.test/v1\"}",
                mergeRemote("{\"url\":\"http://mcp.example.test/v1\"}"));
        assertEquals("{\"url\":\"http://mcp.example.test:443/v1\"}",
                mergeRemote("{\"url\":\"http://mcp.example.test:443/v1\"}"));
    }

    @Test
    void aHostThatOnlyStartsWithTheSavedOneDropsTheSavedHeaders() {
        for (var url : List.of("https://mcp.example.test.evil.test/v1", "https://mcp.example.test@evil.test/v1",
                "https://evil.test/mcp.example.test/v1", "https://evil.test/v1#@mcp.example.test")) {
            assertEquals("{\"url\":\"" + url + "\"}", mergeRemote("{\"url\":\"" + url + "\"}"), url);
        }
    }

    @Test
    void aUrlTheHttpClientCannotParseDropsTheSavedHeaders() {
        assertEquals("{\"url\":\"mcp.example.test/v1\"}", mergeRemote("{\"url\":\"mcp.example.test/v1\"}"));
    }

    @Test
    void headersSentWithTheNewHostAreTheOnlyOnesItGets() {
        assertEquals("""
                {"url":"https://other.example.test/v1","headers":{"Authorization":"Bearer for-other"}}""",
                mergeRemote("""
                        {"url":"https://other.example.test/v1","headers":{"Authorization":"Bearer for-other"}}"""));
    }

    @Test
    void anUpdateThatLeavesAUrlTheHttpClientCannotParseAloneKeepsTheSavedHeaders() {
        var stored = """
                {"url":"mcp.example.test/v1","headers":{"Authorization":"Bearer t"}}""";
        var body = JsonParser.parseString("{\"headers\":{\"X-Plan\":\"pro\"}}").getAsJsonObject();
        assertEquals("""
                {"url":"mcp.example.test/v1","headers":{"Authorization":"Bearer t","X-Plan":"pro"}}""",
                McpServerService.mergeConfigJson(McpServer.Transport.HTTP, stored, body));
    }

    @Test
    void aBodyThatRepeatsTheSavedUrlKeepsTheSavedHeaders() {
        assertEquals(REMOTE, mergeRemote("{\"url\":\"https://mcp.example.test/v1\"}"));
    }

    @Test
    void aStoredConfigThatIsNotAJsonObjectLeavesOnlyTheBody() {
        var body = JsonParser.parseString("{\"url\":\"http://127.0.0.1:1/mcp\"}").getAsJsonObject();
        assertEquals("{\"url\":\"http://127.0.0.1:1/mcp\"}",
                McpServerService.mergeConfigJson(McpServer.Transport.HTTP, "not json", body));
    }
}
