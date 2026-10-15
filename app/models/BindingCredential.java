package models;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The channel-binding columns that hold a third-party credential, each sealed by its {@link BindingCredentialConverter}
 * under its own {@link #field()} name (JCLAW-1455). A column a finder or a unique constraint matches by value has a
 * {@link #lookupColumn} holding the HMAC of the plaintext, since each encryption of it differs.
 */
public enum BindingCredential {
    SLACK_BOT_TOKEN("slack_binding", "bot_token", "bot_token_lookup"),
    SLACK_SIGNING_SECRET("slack_binding", "signing_secret", null),
    SLACK_APP_TOKEN("slack_binding", "app_token", null),
    TELEGRAM_BOT_TOKEN("telegram_binding", "bot_token", "bot_token_lookup"),
    TELEGRAM_WEBHOOK_SECRET("telegram_binding", "webhook_secret", null),
    WHATSAPP_ACCESS_TOKEN("whatsapp_binding", "access_token", null),
    WHATSAPP_APP_SECRET("whatsapp_binding", "app_secret", null),
    WHATSAPP_VERIFY_TOKEN("whatsapp_binding", "verify_token", "verify_token_lookup");

    public final String table;
    public final String column;
    public final @Nullable String lookupColumn;

    BindingCredential(String table, String column, @Nullable String lookupColumn) {
        this.table = table;
        this.column = column;
        this.lookupColumn = lookupColumn;
    }

    /** The cipher's additional authenticated data: a ciphertext moved to another column does not open there. */
    public String field() {
        return table + "." + column;
    }

    /**
     * Columns an older schema left on the binding tables that no entity maps, each holding a credential where it holds
     * anything: Slack's user token from the reverted JCLAW-456, and the Telegram webhook URL, whose path carried the
     * webhook secret before JCLAW-784. Nothing reads them, so they are widened and sealed in place but never opened.
     */
    public static final List<Unmapped> UNMAPPED = List.of(
            new Unmapped("slack_binding", "user_token"),
            new Unmapped("telegram_binding", "webhook_url"));

    public record Unmapped(String table, String column) {
        public String field() {
            return table + "." + column;
        }
    }
}
