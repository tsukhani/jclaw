import com.google.gson.JsonParser;
import mcp.McpAppPage;
import models.McpServer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/** The Content-Security-Policy an MCP Apps view page is served under, and the pages it refuses before any request. */
class McpAppPageTest extends UnitTest {

    /** {@code frame-ancestors 'self'} is what lets the chat page frame the view. */
    private static final String HOST = "; object-src 'none'; form-action 'none'; frame-ancestors 'self'; sandbox allow-scripts";

    private static final McpAppPage.Host JCLAW = new McpAppPage.Host("jclaw.example");
    private static final McpAppPage.Reach REMOTE = new McpAppPage.Reach(JCLAW, false);

    private static McpAppPage.Policy connecting(McpAppPage.Reach reach, String... origins) {
        var list = new com.google.gson.JsonArray();
        for (var o : origins) list.add(o);
        var csp = new com.google.gson.JsonObject();
        csp.add("connectDomains", list);
        var ui = new com.google.gson.JsonObject();
        ui.add("csp", csp);
        return McpAppPage.policy(ui, reach);
    }

    private static String connectSrc(McpAppPage.Policy policy) {
        var header = policy.header();
        var start = header.indexOf("connect-src ") + "connect-src ".length();
        return header.substring(start, header.indexOf(';', start));
    }

    private static String policy(String uiJson) {
        return McpAppPage.policy(JsonParser.parseString(uiJson).getAsJsonObject(), REMOTE).header();
    }

    @Test
    void aPageDeclaringNoCspGetsTheSpecsRestrictiveDefault() {
        var expected = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                + "img-src data:; media-src data:; connect-src 'none'; base-uri 'none'" + HOST;
        assertEquals(expected, McpAppPage.policy(null, REMOTE).header());
        assertEquals(expected, policy("{\"prefersBorder\":true}"), "a ui without csp");
        assertEquals(expected, policy("{\"csp\":\"oops\"}"), "a csp that is not an object");
    }

    @Test
    void serpApisResourceDomainReachesScriptsStylesImagesFontsAndMediaOnly() {
        var cdn = " https://cdn.jsdelivr.net";
        assertEquals("default-src 'none'; script-src 'unsafe-inline'" + cdn + "; style-src 'unsafe-inline'" + cdn
                        + "; img-src data:" + cdn + "; font-src" + cdn + "; media-src data:" + cdn
                        + "; connect-src 'none'; frame-src 'none'; base-uri 'none'" + HOST,
                policy("{\"csp\":{\"resourceDomains\":[\"https://cdn.jsdelivr.net\"]}}"));
    }

    @Test
    void eachDeclaredListReachesItsOwnDirective() {
        assertEquals("default-src 'none'; script-src 'unsafe-inline' https://*.cdn.example; "
                        + "style-src 'unsafe-inline' https://*.cdn.example; img-src data: https://*.cdn.example; "
                        + "font-src https://*.cdn.example; media-src data: https://*.cdn.example; "
                        + "connect-src https://api.example.com wss://rt.example.com:8443; frame-src https://www.youtube.com; "
                        + "base-uri https://cdn.example.com/app/" + HOST,
                policy("{\"csp\":{\"resourceDomains\":[\"https://*.cdn.example\"],"
                        + "\"connectDomains\":[\"https://api.example.com\",\"wss://rt.example.com:8443\"],"
                        + "\"frameDomains\":[\"https://www.youtube.com\"],\"baseUriDomains\":[\"https://cdn.example.com/app/\"]}}"));
    }

    @Test
    void emptyListsDeclareNothing() {
        assertEquals("default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                        + "img-src data:; font-src 'none'; media-src data:; connect-src 'none'; frame-src 'none'; "
                        + "base-uri 'none'" + HOST,
                policy("{\"csp\":{\"resourceDomains\":[],\"connectDomains\":[],\"frameDomains\":[],\"baseUriDomains\":[]}}"));
    }

    @Test
    void anEntryThatIsNotAnOriginIsLeftOutAndReported() {
        var ui = JsonParser.parseString("{\"csp\":{\"connectDomains\":[\"https://ok.example\","
                + "\"https://a.example; script-src *\",\"'unsafe-eval'\",\"*\",\"https:\",\"data:\",\" https://b.example\","
                + "\"https://c.example\\n\",\"javascript://x\",{\"o\":1}],\"frameDomains\":[\"'self'\"]}}").getAsJsonObject();
        var policy = McpAppPage.policy(ui, REMOTE);
        assertEquals("default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
                + "img-src data:; font-src 'none'; media-src data:; connect-src https://ok.example; "
                + "frame-src 'none'; base-uri 'none'" + HOST, policy.header());
        assertEquals("{\"connectDomains\":[\"https://a.example; script-src *\",\"'unsafe-eval'\",\"*\",\"https:\",\"data:\","
                + "\" https://b.example\",\"https://c.example\\n\",\"javascript://x\",{\"o\":1}],\"frameDomains\":[\"'self'\"]}",
                policy.dropped().toString());
    }

    @Test
    void aRemoteServersViewCannotReachTheOperatorsMachineOrNetworkHoweverTheHostIsWritten() {
        var policy = connecting(REMOTE, "http://localhost:11434", "http://127.0.0.1:8080", "http://2130706433",
                "http://0x7f.1", "http://127.1", "http://0177.0.0.1", "http://192.168.1.10", "http://10.0.0.5",
                "http://172.16.0.1", "http://169.254.169.254", "http://0.0.0.0", "http://100.100.1.1",
                "https://*.localhost", "http://app.localhost:3000", "http://1.2.3.999",
                "https://api.example.com", "http://8.8.8.8", "https://*.cdn.example");
        assertEquals("https://api.example.com http://8.8.8.8 https://*.cdn.example", connectSrc(policy));
        assertEquals("{\"connectDomains\":[\"http://localhost:11434\",\"http://127.0.0.1:8080\",\"http://2130706433\","
                + "\"http://0x7f.1\",\"http://127.1\",\"http://0177.0.0.1\",\"http://192.168.1.10\",\"http://10.0.0.5\","
                + "\"http://172.16.0.1\",\"http://169.254.169.254\",\"http://0.0.0.0\",\"http://100.100.1.1\","
                + "\"https://*.localhost\",\"http://app.localhost:3000\",\"http://1.2.3.999\"]}", policy.dropped().toString());
    }

    @Test
    void aLocalServersViewKeepsTheOperatorsNetworkButNeverJclawsHost() {
        var policy = connecting(new McpAppPage.Reach(new McpAppPage.Host("jclaw.lan"), true), "http://localhost:3001",
                "http://192.168.1.10:8080", "http://nas.local", "http://jclaw.lan:9000", "https://jclaw.lan:9443",
                "http://JCLAW.LAN", "https://api.example.com");
        assertEquals("http://localhost:3001 http://192.168.1.10:8080 http://nas.local https://api.example.com", connectSrc(policy));
        assertEquals("{\"connectDomains\":[\"http://jclaw.lan:9000\",\"https://jclaw.lan:9443\",\"http://JCLAW.LAN\"]}",
                policy.dropped().toString());
    }

    @Test
    void jclawsHostIsDroppedAtAnyPortAndUnderAWildcardThatCoversIt() {
        var remote = connecting(REMOTE, "https://jclaw.example:9000", "https://jclaw.example:9443", "http://jclaw.example:3000",
                "https://jclaw.example", "https://*.example", "https://*.other.example", "https://other.example");
        assertEquals("https://*.other.example https://other.example", connectSrc(remote));
        assertEquals("{\"connectDomains\":[\"https://jclaw.example:9000\",\"https://jclaw.example:9443\","
                + "\"http://jclaw.example:3000\",\"https://jclaw.example\",\"https://*.example\"]}", remote.dropped().toString());
    }

    @Test
    void conventionalLocalNamesCountAsTheOperatorsNetworkWithoutDns() {
        var remote = connecting(REMOTE, "http://nas:5000", "https://printer.local", "http://router.lan", "http://files.home",
                "http://box.home.arpa", "https://svc.internal", "http://wiki.intranet", "https://git.corp",
                "http://pc.localdomain", "HTTP://NAS.LOCAL", "https://*.local", "https://local.example.com",
                "https://home.example", "https://corp.example.org");
        assertEquals("https://local.example.com https://home.example https://corp.example.org", connectSrc(remote));
        assertEquals("{\"connectDomains\":[\"http://nas:5000\",\"https://printer.local\",\"http://router.lan\","
                + "\"http://files.home\",\"http://box.home.arpa\",\"https://svc.internal\",\"http://wiki.intranet\","
                + "\"https://git.corp\",\"http://pc.localdomain\",\"HTTP://NAS.LOCAL\",\"https://*.local\"]}",
                remote.dropped().toString());
    }

    @Test
    void anOriginOfThousandsOfLabelsIsReadWithoutOverflowingTheStack() {
        var origin = "https://a" + ".b".repeat(100_000);

        assertEquals(origin, connectSrc(connecting(REMOTE, origin)));
    }

    private static McpServer server(McpServer.Transport transport, String config) {
        var row = new McpServer();
        row.transport = transport;
        row.configJson = config;
        return row;
    }

    @Test
    void aServerIsLocalWhenItRunsHereOrItsUrlNamesALoopbackOrPrivateHost() {
        assertTrue(McpAppPage.isLocal(server(McpServer.Transport.STDIO, "{\"command\":\"npx\"}")));
        for (var url : List.of("http://localhost:3000/mcp", "http://127.0.0.1:8931/mcp", "http://192.168.1.5/mcp",
                "http://[::1]:8080/mcp", "http://[fd00::5]/mcp", "http://mcp.local/mcp", "http://nas:8080/mcp")) {
            assertTrue(McpAppPage.isLocal(server(McpServer.Transport.HTTP, "{\"url\":\"" + url + "\"}")), url);
        }
        for (var url : List.of("https://mcp.serpapi.com/mcp", "http://8.8.8.8/mcp", "not a url")) {
            assertFalse(McpAppPage.isLocal(server(McpServer.Transport.HTTP, "{\"url\":\"" + url + "\"}")), url);
        }
    }

    @Test
    void anAppPageIsTextHtmlWithProfileMcpAppHoweverItsParametersAreWritten() {
        for (var page : List.of("text/html;profile=mcp-app", "text/html; profile=mcp-app",
                "text/html; charset=utf-8; profile=mcp-app", "TEXT/HTML; Profile=\"MCP-App\"", " text/html ;profile = mcp-app ")) {
            assertTrue(mcp.McpResources.isAppPage(page), page);
        }
        for (var other : java.util.Arrays.asList("text/html", "text/plain;profile=mcp-app", "text/html;profile=mcp-apps",
                "text/html;profile", "text/html;charset=mcp-app", "text/html;profile=\"mcp-app", "", null)) {
            assertFalse(mcp.McpResources.isAppPage(other), String.valueOf(other));
        }
    }

    @Test
    void aUriOutsideTheUiSchemeIsRefusedWithoutAsking() {
        var ex = assertThrows(McpAppPage.Unavailable.class, () -> McpAppPage.load("serpapi", "serpapi://engines", JCLAW));
        assertEquals(400, ex.status());
        assertEquals("Not an MCP Apps page URI.", ex.getMessage());
    }

    @Test
    void aServerThatIsNotConnectedIsSaidToBe() {
        var server = "absent-" + System.nanoTime();
        var ex = assertThrows(McpAppPage.Unavailable.class, () -> McpAppPage.load(server, "ui://t.html", JCLAW));
        assertEquals(503, ex.status());
        assertEquals("MCP server '" + server + "' is not connected.", ex.getMessage());
    }
}
