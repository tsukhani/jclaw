import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpException;
import mcp.McpParamHeaders;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.Map;

/** JCLAW-1444: validating {@code x-mcp-header} annotations and mirroring arguments into headers. */
class McpParamHeadersTest extends UnitTest {

    private static JsonObject schema(String properties) {
        return JsonParser.parseString("{\"type\":\"object\",\"properties\":" + properties + "}").getAsJsonObject();
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void validAnnotationsPass() {
        for (var type : new String[] {"\"string\"", "\"number\"", "\"integer\"", "\"boolean\"", "[\"string\",\"null\"]"}) {
            assertNull(McpParamHeaders.invalidReason(schema(
                    "{\"region\":{\"type\":" + type + ",\"x-mcp-header\":\"Region\"}}")), type);
        }
        // Every RFC 9110 token character, and two names that differ by more than case.
        assertNull(McpParamHeaders.invalidReason(schema("{\"a\":{\"type\":\"string\",\"x-mcp-header\":"
                + "\"X!#$%&'*+-.^_`|~9z\"},\"b\":{\"type\":\"string\",\"x-mcp-header\":\"Region2\"},"
                + "\"c\":{\"type\":\"object\",\"properties\":{\"d\":{\"type\":\"string\"}}}}")));
        assertNull(McpParamHeaders.invalidReason(new JsonObject()), "no properties at all");
    }

    @Test
    void eachBrokenConstraintIsNamed() {
        var cases = Map.of(
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":7}}", "is not a string",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"\"}}", "not a valid header name",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Two Words\"}}", "not a valid header name",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Re:gion\"}}", "not a valid header name",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Région\"}}", "not a valid header name",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Region\"},"
                        + "\"b\":{\"type\":\"string\",\"x-mcp-header\":\"REGION\"}}", "appears twice",
                "{\"a\":{\"type\":\"object\",\"x-mcp-header\":\"Region\"}}", "is not a string, number",
                "{\"a\":{\"type\":[\"array\",\"null\"],\"x-mcp-header\":\"Region\"}}", "is not a string, number",
                "{\"a\":{\"x-mcp-header\":\"Region\"}}", "is not a string, number",
                "{\"a\":{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\",\"x-mcp-header\":\"B\"}}}}",
                "below the top-level property 'a'");
        for (var c : cases.entrySet()) {
            var reason = McpParamHeaders.invalidReason(schema(c.getKey()));
            assertNotNull(reason, c.getKey());
            assertTrue(reason.contains(c.getValue()), c.getKey() + " -> " + reason);
        }
    }

    /** The rows of the spec's encoding examples table, plus the conversions of numbers and booleans. */
    @Test
    void argumentsAreConvertedAndEncodedAsTheSpecsTableShows() {
        var schema = schema("{\"v\":{\"type\":\"string\",\"x-mcp-header\":\"V\"}}");
        var rows = Map.of(
                "us-west1", "us-west1",
                "Hello, 世界", "=?base64?SGVsbG8sIOS4lueVjA==?=",
                " padded ", "=?base64?IHBhZGRlZCA=?=",
                "line1\nline2", "=?base64?bGluZTEKbGluZTI=?=",
                "=?base64?SGVsbG8=?=", "=?base64?PT9iYXNlNjQ/U0dWc2JHOD0/PQ==?=");
        for (var row : rows.entrySet()) {
            var a = new JsonObject();
            a.addProperty("v", row.getKey());
            assertEquals(Map.of("Mcp-Param-V", row.getValue()), McpParamHeaders.headersFor(schema, a), row.getKey());
        }
        var typed = schema("{\"n\":{\"type\":\"number\",\"x-mcp-header\":\"N\"},"
                + "\"i\":{\"type\":\"integer\",\"x-mcp-header\":\"I\"},"
                + "\"b\":{\"type\":\"boolean\",\"x-mcp-header\":\"B\"},\"plain\":{\"type\":\"string\"}}");
        assertEquals(Map.of("Mcp-Param-N", "3.25", "Mcp-Param-I", "42", "Mcp-Param-B", "false"),
                McpParamHeaders.headersFor(typed, args("{\"n\":3.25,\"i\":42,\"b\":false,\"plain\":\"x\"}")));
    }

    @Test
    void anAbsentOrNullArgumentSendsNoHeader() {
        var schema = schema("{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"A\"},"
                + "\"b\":{\"type\":\"string\",\"x-mcp-header\":\"B\"}}");
        assertEquals(Map.of(), McpParamHeaders.headersFor(schema, args("{\"b\":null}")));
        // An empty string is a value, not an absence: it is sent, in the sentinel form.
        assertEquals(Map.of("Mcp-Param-A", "=?base64??="), McpParamHeaders.headersFor(schema, args("{\"a\":\"\"}")));
    }

    @Test
    void anObjectWhereTheHeaderNeedsAPrimitiveFailsTheCallNamingTheArgument() {
        var schema = schema("{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"A\"}}");
        var ex = assertThrows(McpException.class, () -> McpParamHeaders.headersFor(schema, args("{\"a\":{\"x\":1}}")));
        assertTrue(ex.getMessage().contains("argument 'a' must be a string, number or boolean"), ex.getMessage());
    }
}
