package tools;

/**
 * Shared JSON Schema vocabulary constants used by Tool {@link agents.ToolRegistry.Tool#parameters()}
 * implementations. Centralizing these keys catches typos the compiler can't and eliminates
 * the java:S1192 duplicated-literal flags those tool files were collecting.
 */
public final class SchemaKeys {

    private SchemaKeys() {}

    // Structural keys (JSON property names in a JSON Schema object)
    public static final String TYPE = "type";
    public static final String DESCRIPTION = "description";
    public static final String PROPERTIES = "properties";
    public static final String REQUIRED = "required";
    public static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    public static final String ENUM = "enum";
    public static final String ITEMS = "items";

    // Schema type values (the value of "type")
    public static final String OBJECT = "object";
    public static final String STRING = "string";
    public static final String BOOLEAN = "boolean";
    public static final String INTEGER = "integer";
    public static final String NUMBER = "number";
    public static final String ARRAY = "array";
}
