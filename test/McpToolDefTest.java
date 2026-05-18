import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpToolDef;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;

/**
 * JCLAW-312: schema-container coverage for {@link McpToolDef}.
 *
 * <p>Two public surfaces:
 * <ul>
 *   <li>{@link McpToolDef#fromJson(JsonObject)} — parses the
 *       {@code tools/list} entry shape into the record.</li>
 *   <li>{@link McpToolDef#parametersAsMap()} — converts the
 *       {@code inputSchema} JsonObject into the {@code Map<String, Object>}
 *       that downstream {@code ToolRegistry.Tool#parameters()} expects.</li>
 * </ul>
 *
 * <p>These tests exercise the JSON-Schema primitives the production MCP
 * servers actually advertise (string, number, boolean, object, array, enum)
 * and the optional/required-field tracking that lets the model construct
 * valid {@code tools/call} arguments.
 */
class McpToolDefTest extends UnitTest {

    // ==================== fromJson ====================

    @Test
    void fromJsonParsesNameAndDescription() {
        var raw = parse("""
                {"name":"create_issue","description":"Create a GitHub issue",
                 "inputSchema":{"type":"object"}}""");
        var def = McpToolDef.fromJson(raw);
        assertEquals("create_issue", def.name());
        assertEquals("Create a GitHub issue", def.description());
        assertNotNull(def.inputSchema());
    }

    @Test
    void fromJsonDescriptionDefaultsToEmptyWhenMissing() {
        var raw = parse("""
                {"name":"noop","inputSchema":{"type":"object"}}""");
        var def = McpToolDef.fromJson(raw);
        assertEquals("", def.description(),
                "description must default to empty string per Javadoc — keeps "
                        + "downstream concatenation NPE-free");
    }

    @Test
    void fromJsonDescriptionDefaultsToEmptyWhenNull() {
        var raw = parse("""
                {"name":"noop","description":null,"inputSchema":{"type":"object"}}""");
        var def = McpToolDef.fromJson(raw);
        assertEquals("", def.description(),
                "JSON-null description treated identically to absent key");
    }

    @Test
    void fromJsonInputSchemaDefaultsToEmptyObjectWhenAbsent() {
        var raw = parse("""
                {"name":"params-free","description":"No params"}""");
        var def = McpToolDef.fromJson(raw);
        assertNotNull(def.inputSchema(),
                "MCP spec allows parameter-less tools; missing inputSchema yields {}");
        assertTrue(def.inputSchema().entrySet().isEmpty());
    }

    @Test
    void fromJsonInputSchemaDefaultsToEmptyObjectWhenNotObject() {
        // Defensive: a server that emits the wrong shape (string, array)
        // should not crash discovery — fromJson swaps to an empty schema.
        var raw = parse("""
                {"name":"weird","inputSchema":"oops"}""");
        var def = McpToolDef.fromJson(raw);
        assertNotNull(def.inputSchema());
        assertTrue(def.inputSchema().entrySet().isEmpty());
    }

    // ==================== parametersAsMap — primitives ====================

    @Test
    void parametersAsMapStringField() {
        var def = defWithSchema("""
                {"type":"object","properties":{"q":{"type":"string"}}}""");
        Map<String, Object> map = def.parametersAsMap();
        assertEquals("object", map.get("type"));
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) map.get("properties");
        @SuppressWarnings("unchecked")
        var q = (Map<String, Object>) props.get("q");
        assertEquals("string", q.get("type"));
    }

    @Test
    void parametersAsMapNumberAndIntegerFields() {
        // The spec uses Number for both; verify both type tags round-trip.
        var def = defWithSchema("""
                {"type":"object","properties":{
                  "count":{"type":"integer"},
                  "ratio":{"type":"number"}}}""");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.parametersAsMap().get("properties");
        @SuppressWarnings("unchecked")
        var count = (Map<String, Object>) props.get("count");
        @SuppressWarnings("unchecked")
        var ratio = (Map<String, Object>) props.get("ratio");
        assertEquals("integer", count.get("type"));
        assertEquals("number", ratio.get("type"));
    }

    @Test
    void parametersAsMapBooleanField() {
        var def = defWithSchema("""
                {"type":"object","properties":{"force":{"type":"boolean"}}}""");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.parametersAsMap().get("properties");
        @SuppressWarnings("unchecked")
        var force = (Map<String, Object>) props.get("force");
        assertEquals("boolean", force.get("type"));
    }

    @Test
    void parametersAsMapEnumField() {
        var def = defWithSchema("""
                {"type":"object","properties":{
                  "level":{"type":"string","enum":["info","warn","error"]}}}""");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.parametersAsMap().get("properties");
        @SuppressWarnings("unchecked")
        var level = (Map<String, Object>) props.get("level");
        assertTrue(level.get("enum") instanceof List);
        @SuppressWarnings("unchecked")
        var values = (List<Object>) level.get("enum");
        assertEquals(3, values.size());
        assertEquals("info", values.get(0));
        assertEquals("error", values.get(2));
    }

    @Test
    void parametersAsMapArrayField() {
        var def = defWithSchema("""
                {"type":"object","properties":{
                  "tags":{"type":"array","items":{"type":"string"}}}}""");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.parametersAsMap().get("properties");
        @SuppressWarnings("unchecked")
        var tags = (Map<String, Object>) props.get("tags");
        assertEquals("array", tags.get("type"));
        @SuppressWarnings("unchecked")
        var items = (Map<String, Object>) tags.get("items");
        assertEquals("string", items.get("type"));
    }

    @Test
    void parametersAsMapNullPrimitiveYieldsNull() {
        // JsonNull elements (e.g. default:null) round-trip to Java null,
        // not to "null" string — the AgentRunner uses Map.get() and
        // expects null for absent values.
        var def = defWithSchema("""
                {"type":"object","properties":{
                  "opt":{"type":"string","default":null}}}""");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.parametersAsMap().get("properties");
        @SuppressWarnings("unchecked")
        var opt = (Map<String, Object>) props.get("opt");
        assertNull(opt.get("default"));
    }

    // ==================== parametersAsMap — required & optional tracking ====================

    @Test
    void parametersAsMapRequiredArrayPreserved() {
        // The downstream ToolDef inspects `required` to decide which fields
        // the model MUST populate. Missing required fields are caught there;
        // here we verify the array survives the JsonObject → Map conversion.
        var def = defWithSchema("""
                {"type":"object",
                 "properties":{"a":{"type":"string"},"b":{"type":"string"}},
                 "required":["a"]}""");
        Map<String, Object> map = def.parametersAsMap();
        assertTrue(map.get("required") instanceof List);
        @SuppressWarnings("unchecked")
        var required = (List<Object>) map.get("required");
        assertEquals(1, required.size());
        assertEquals("a", required.get(0));
    }

    @Test
    void parametersAsMapOptionalFieldsHaveNoEntryInRequired() {
        // Optional fields are simply absent from the required[] array.
        var def = defWithSchema("""
                {"type":"object",
                 "properties":{"a":{"type":"string"},"b":{"type":"string"}}}""");
        Map<String, Object> map = def.parametersAsMap();
        assertFalse(map.containsKey("required"),
                "no required[] → all fields optional, no implicit empty array");
    }

    // ==================== parametersAsMap — property order ====================

    @Test
    void parametersAsMapPreservesPropertyOrder() {
        // Per Javadoc: LinkedHashMap preserves declaration order so the
        // LLM sees properties in the order the MCP server declared them.
        // Some models bias toward emitting required fields first.
        var def = defWithSchema("""
                {"type":"object","properties":{
                  "z_last":{"type":"string"},
                  "a_first":{"type":"string"},
                  "m_middle":{"type":"string"}}}""");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.parametersAsMap().get("properties");
        var keys = props.keySet().toArray(new String[0]);
        assertEquals("z_last", keys[0], "declaration order: z_last first");
        assertEquals("a_first", keys[1]);
        assertEquals("m_middle", keys[2]);
    }

    // ==================== parametersAsMap — nested objects ====================

    @Test
    void parametersAsMapDeeplyNestedObjects() {
        // Production servers nest 3-4 levels (e.g. filter:{date:{from:{...}}}).
        // Each level must round-trip without flattening.
        var def = defWithSchema("""
                {"type":"object","properties":{
                  "filter":{"type":"object","properties":{
                    "date":{"type":"object","properties":{
                      "from":{"type":"string"},
                      "to":{"type":"string"}}}}}}}""");
        @SuppressWarnings("unchecked")
        var outerProps = (Map<String, Object>) def.parametersAsMap().get("properties");
        @SuppressWarnings("unchecked")
        var filter = (Map<String, Object>) outerProps.get("filter");
        @SuppressWarnings("unchecked")
        var filterProps = (Map<String, Object>) filter.get("properties");
        @SuppressWarnings("unchecked")
        var date = (Map<String, Object>) filterProps.get("date");
        @SuppressWarnings("unchecked")
        var dateProps = (Map<String, Object>) date.get("properties");
        @SuppressWarnings("unchecked")
        var from = (Map<String, Object>) dateProps.get("from");
        assertEquals("string", from.get("type"),
                "deeply nested leaf field type must survive 3 levels of nesting");
    }

    @Test
    void parametersAsMapNestedArrayOfObjects() {
        // tags:[{name,value},...] is a common shape (env vars, headers).
        var def = defWithSchema("""
                {"type":"object","properties":{
                  "pairs":{"type":"array","items":{
                    "type":"object","properties":{
                      "k":{"type":"string"},
                      "v":{"type":"number"}}}}}}""");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) def.parametersAsMap().get("properties");
        @SuppressWarnings("unchecked")
        var pairs = (Map<String, Object>) props.get("pairs");
        @SuppressWarnings("unchecked")
        var items = (Map<String, Object>) pairs.get("items");
        @SuppressWarnings("unchecked")
        var itemProps = (Map<String, Object>) items.get("properties");
        @SuppressWarnings("unchecked")
        var v = (Map<String, Object>) itemProps.get("v");
        assertEquals("number", v.get("type"));
    }

    // ==================== helpers ====================

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static McpToolDef defWithSchema(String schemaJson) {
        return new McpToolDef("t", "desc", parse(schemaJson));
    }
}
