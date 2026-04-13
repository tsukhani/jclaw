package models;

/**
 * Enum for the {@code role} column on {@link Message}. Values are stored as
 * lowercase strings in the database (matching the OpenAI-compatible wire format)
 * so JPA {@code @Enumerated(EnumType.STRING)} is <em>not</em> used — Play 1.x
 * keeps the column as a plain VARCHAR and conversion goes through the accessors
 * on this enum.
 */
public enum MessageRole {

    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    SYSTEM("system");

    public final String value;

    MessageRole(String value) {
        this.value = value;
    }

    /**
     * Resolve a raw database/wire string to the corresponding enum constant.
     * Returns {@code null} for unrecognised values so callers can fall through
     * to a default branch without throwing.
     */
    public static MessageRole fromValue(String value) {
        if (value == null) return null;
        return switch (value) {
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            case "tool" -> TOOL;
            case "system" -> SYSTEM;
            default -> null;
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
