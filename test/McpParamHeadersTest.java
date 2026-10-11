import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpException;
import mcp.McpParamHeaders;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JCLAW-1444, JCLAW-1446: validating {@code x-mcp-header} annotations and mirroring arguments into headers. */
class McpParamHeadersTest extends UnitTest {

    private static JsonObject schema(String properties) {
        return JsonParser.parseString("{\"type\":\"object\",\"properties\":" + properties + "}").getAsJsonObject();
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void validAnnotationsPass() {
        for (var type : new String[] {"\"string\"", "\"integer\"", "\"boolean\"", "[\"string\",\"null\"]",
                "[\"integer\",\"null\"]"}) {
            assertNull(McpParamHeaders.invalidReason(schema(
                    "{\"region\":{\"type\":" + type + ",\"x-mcp-header\":\"Region\"}}")), type);
        }
        // Every RFC 9110 token character, and two names that differ by more than case.
        assertNull(McpParamHeaders.invalidReason(schema("{\"a\":{\"type\":\"string\",\"x-mcp-header\":"
                + "\"X!#$%&'*+-.^_`|~9z\"},\"b\":{\"type\":\"string\",\"x-mcp-header\":\"Region2\"},"
                + "\"c\":{\"type\":\"object\",\"properties\":{\"d\":{\"type\":\"string\"}}}}")));
        assertNull(McpParamHeaders.invalidReason(new JsonObject()), "no properties at all");
        // Composition and array keywords without an annotation beneath them are no concern.
        assertNull(McpParamHeaders.invalidReason(JsonParser.parseString("{\"type\":\"object\",\"properties\":"
                + "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"A\"},\"list\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}}},\"allOf\":[{\"required\":[\"a\"]}]}").getAsJsonObject()));
    }

    /** Every step from the root a properties key, two levels down, is statically reachable. */
    @Test
    void anAnnotationTwoPropertiesLevelsDownIsValid() {
        assertNull(McpParamHeaders.invalidReason(NESTED));
        // The same name nested and at the top level still collides.
        var twice = schema("{\"r\":{\"type\":\"string\",\"x-mcp-header\":\"region\"},"
                + "\"o\":{\"type\":\"object\",\"properties\":{\"r\":{\"type\":\"string\",\"x-mcp-header\":\"Region\"}}}}");
        assertTrue(Objects.requireNonNull(McpParamHeaders.invalidReason(twice)).contains("appears twice"));
    }

    private static final JsonObject NESTED = schema("{\"target\":{\"type\":\"object\",\"properties\":"
            + "{\"location\":{\"type\":\"object\",\"properties\":"
            + "{\"region\":{\"type\":\"string\",\"x-mcp-header\":\"Region\"}}}}},\"q\":{\"type\":\"string\"}}");

    @Test
    void eachBrokenConstraintIsNamed() {
        var cases = Map.of(
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":7}}", "is not a string",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"\"}}", "not a valid header name",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Two Words\"}}", "not a valid header name",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Re:gion\"}}", "not a valid header name",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"R\\r\\nX\"}}", "not a valid header name",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Région\"}}", "not a valid header name",
                "{\"a\":{\"type\":\"string\",\"x-mcp-header\":\"Region\"},"
                        + "\"b\":{\"type\":\"string\",\"x-mcp-header\":\"REGION\"}}", "appears twice",
                "{\"a\":{\"type\":\"object\",\"x-mcp-header\":\"Region\"}}", "is not a string, integer or boolean",
                "{\"a\":{\"type\":[\"array\",\"null\"],\"x-mcp-header\":\"Region\"}}", "is not a string, integer",
                "{\"a\":{\"x-mcp-header\":\"Region\"}}", "is not a string, integer or boolean");
        for (var c : cases.entrySet()) {
            var reason = McpParamHeaders.invalidReason(schema(c.getKey()));
            assertNotNull(reason, c.getKey());
            assertTrue(reason.contains(c.getValue()), c.getKey() + " -> " + reason);
        }
    }

    @Test
    void aNumberParameterIsInvalidAloneOrInAUnion() {
        for (var type : new String[] {"\"number\"", "[\"number\",\"null\"]", "[\"string\",\"number\"]"}) {
            var reason = McpParamHeaders.invalidReason(schema(
                    "{\"o\":{\"type\":\"object\",\"properties\":{\"n\":{\"type\":" + type + ",\"x-mcp-header\":\"N\"}}}}"));
            assertNotNull(reason, type);
            assertTrue(reason.contains("'o.n'") && reason.contains("type number"), type + " -> " + reason);
        }
    }

    /** The spec's forbidden steps, each at the root and below a property. */
    @Test
    void anAnnotationReachedThroughAnythingButPropertiesIsInvalid() {
        var annotated = "{\"type\":\"string\",\"x-mcp-header\":\"Region\"}";
        var placements = new java.util.LinkedHashMap<String, String>();
        placements.put("items", "{\"type\":\"object\",\"properties\":{\"r\":" + annotated + "}}");
        placements.put("prefixItems", "[{\"type\":\"object\",\"properties\":{\"r\":" + annotated + "}}]");
        for (var keyword : new String[] {"oneOf", "anyOf", "allOf"}) {
            placements.put(keyword, "[{\"type\":\"object\",\"properties\":{\"r\":" + annotated + "}}]");
        }
        for (var keyword : new String[] {"not", "if", "then", "else"}) {
            placements.put(keyword, "{\"properties\":{\"r\":" + annotated + "}}");
        }
        // $ref points into $defs, which is where the annotation then sits.
        placements.put("$defs", "{\"loc\":{\"type\":\"object\",\"properties\":{\"r\":" + annotated + "}}}");
        for (var p : placements.entrySet()) {
            var atRoot = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\""
                    + p.getKey() + "\":" + p.getValue() + "}").getAsJsonObject();
            var below = schema("{\"o\":{\"type\":\"object\",\"properties\":{\"inner\":{\"type\":\"object\",\""
                    + p.getKey() + "\":" + p.getValue() + "}}}}");
            for (var s : List.of(atRoot, below)) {
                var reason = McpParamHeaders.invalidReason(s);
                assertNotNull(reason, s.toString());
                assertTrue(reason.contains("'" + p.getKey() + "'"), s + " -> " + reason);
            }
        }
        var ref = schema("{\"o\":{\"$ref\":\"#/$defs/loc\",\"properties\":{\"r\":" + annotated + "}}}");
        assertNull(McpParamHeaders.invalidReason(ref), "a $ref beside properties does not move the properties chain");
        assertNotNull(McpParamHeaders.invalidReason(JsonParser.parseString(
                "{\"type\":\"object\",\"x-mcp-header\":\"Root\"}").getAsJsonObject()), "the root is not a property");
    }

    /** The rows of the spec's encoding examples table, plus the conversions of integers and booleans. */
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
        var typed = schema("{\"i\":{\"type\":\"integer\",\"x-mcp-header\":\"I\"},"
                + "\"neg\":{\"type\":\"integer\",\"x-mcp-header\":\"Neg\"},"
                + "\"exp\":{\"type\":\"integer\",\"x-mcp-header\":\"Exp\"},"
                + "\"b\":{\"type\":\"boolean\",\"x-mcp-header\":\"B\"},\"plain\":{\"type\":\"string\"}}");
        assertEquals(Map.of("Mcp-Param-I", "42", "Mcp-Param-Neg", "-7", "Mcp-Param-Exp", "1000", "Mcp-Param-B", "false"),
                McpParamHeaders.headersFor(typed, args("{\"i\":42,\"neg\":-7,\"exp\":1e3,\"b\":false,\"plain\":\"x\"}")));
        assertEquals(Map.of("Mcp-Param-I", "42"), McpParamHeaders.headersFor(typed, args("{\"i\":42.0}")),
                "an integral value written with a fraction is still that integer");
    }

    @Test
    void theSafeIntegerBoundsAreMirroredAndOneBeyondFailsNamingTheParameter() {
        var schema = schema("{\"o\":{\"type\":\"object\",\"properties\":{\"i\":{\"type\":\"integer\",\"x-mcp-header\":\"I\"}}}}");
        for (var ok : new String[] {"9007199254740991", "-9007199254740991", "0"}) {
            assertEquals(Map.of("Mcp-Param-I", ok), McpParamHeaders.headersFor(schema, args("{\"o\":{\"i\":" + ok + "}}")), ok);
        }
        for (var bad : new String[] {"9007199254740992", "-9007199254740992", "1e999999999"}) {
            var ex = assertThrows(McpException.class,
                    () -> McpParamHeaders.headersFor(schema, args("{\"o\":{\"i\":" + bad + "}}")), bad);
            assertTrue(ex.getMessage().contains("argument 'o.i' is outside the integer range"), ex.getMessage());
        }
        var fraction = assertThrows(McpException.class,
                () -> McpParamHeaders.headersFor(schema, args("{\"o\":{\"i\":3.5}}")));
        assertTrue(fraction.getMessage().contains("argument 'o.i' must be an integer"), fraction.getMessage());
    }

    @Test
    void aNestedAnnotationReadsItsValueFromTheSamePath() {
        assertEquals(Map.of("Mcp-Param-Region", "eu-west1"), McpParamHeaders.headersFor(NESTED,
                args("{\"target\":{\"location\":{\"region\":\"eu-west1\"}},\"region\":\"top\",\"q\":\"x\"}")));
        for (var none : new String[] {"{}", "{\"region\":\"top\"}", "{\"target\":{}}", "{\"target\":{\"location\":null}}",
                "{\"target\":{\"location\":\"flat\"}}", "{\"target\":{\"location\":{\"region\":null}}}"}) {
            assertEquals(Map.of(), McpParamHeaders.headersFor(NESTED, args(none)), none);
        }
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
        assertTrue(ex.getMessage().contains("argument 'a' must be a string, integer or boolean"), ex.getMessage());
    }
}
