package models;

import jakarta.persistence.AttributeConverter;
import org.jspecify.annotations.Nullable;
import utils.CredentialCipher;

/**
 * Stores a {@link JsonCredential} column with its secret values encrypted by {@link CredentialCipher} (JCLAW-1455). A
 * converter cannot see which attribute it converts, so each column has its own subclass naming it.
 */
public abstract class JsonCredentialConverter implements AttributeConverter<String, String> {

    private final JsonCredential column;

    JsonCredentialConverter(JsonCredential column) {
        this.column = column;
    }

    public JsonCredential column() {
        return column;
    }

    @Override
    public @Nullable String convertToDatabaseColumn(@Nullable String json) {
        return column.sealed(json, CredentialCipher.current());
    }

    @Override
    public @Nullable String convertToEntityAttribute(@Nullable String stored) {
        return column.opened(stored, CredentialCipher.current());
    }

    public static final class McpServerConfig extends JsonCredentialConverter {
        public McpServerConfig() {
            super(JsonCredential.MCP_SERVER);
        }
    }

    public static final class ChannelConfigJson extends JsonCredentialConverter {
        public ChannelConfigJson() {
            super(JsonCredential.CHANNEL_CONFIG);
        }
    }
}
