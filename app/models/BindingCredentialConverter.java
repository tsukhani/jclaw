package models;

import jakarta.persistence.AttributeConverter;
import org.jspecify.annotations.Nullable;
import utils.CredentialCipher;

/**
 * Stores a {@link BindingCredential} column encrypted with {@link CredentialCipher} (JCLAW-1455). A converter cannot
 * see which attribute it converts, so each column has its own subclass naming it.
 *
 * <p>A stored value that will not decrypt reads as null, as if unset. The binding entities are {@code @DynamicUpdate},
 * so saving an edit to another field leaves that ciphertext in place for the right secret to recover.
 */
public abstract class BindingCredentialConverter implements AttributeConverter<String, String> {

    private final BindingCredential column;

    BindingCredentialConverter(BindingCredential column) {
        this.column = column;
    }

    /** The column this converter seals; its field name is the one the boot pass opens the raw value under. */
    public BindingCredential column() {
        return column;
    }

    @Override
    public @Nullable String convertToDatabaseColumn(@Nullable String plaintext) {
        // An empty value holds no secret, as in ConfigService.
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        return CredentialCipher.current().encrypt(column.field(), plaintext);
    }

    @Override
    public @Nullable String convertToEntityAttribute(@Nullable String stored) {
        if (stored == null) return null;
        return CredentialCipher.current().open(column.field(), stored).plaintext();
    }

    public static final class SlackBotToken extends BindingCredentialConverter {
        public SlackBotToken() {
            super(BindingCredential.SLACK_BOT_TOKEN);
        }
    }

    public static final class SlackSigningSecret extends BindingCredentialConverter {
        public SlackSigningSecret() {
            super(BindingCredential.SLACK_SIGNING_SECRET);
        }
    }

    public static final class SlackAppToken extends BindingCredentialConverter {
        public SlackAppToken() {
            super(BindingCredential.SLACK_APP_TOKEN);
        }
    }

    public static final class TelegramBotToken extends BindingCredentialConverter {
        public TelegramBotToken() {
            super(BindingCredential.TELEGRAM_BOT_TOKEN);
        }
    }

    public static final class TelegramWebhookSecret extends BindingCredentialConverter {
        public TelegramWebhookSecret() {
            super(BindingCredential.TELEGRAM_WEBHOOK_SECRET);
        }
    }

    public static final class WhatsAppAccessToken extends BindingCredentialConverter {
        public WhatsAppAccessToken() {
            super(BindingCredential.WHATSAPP_ACCESS_TOKEN);
        }
    }

    public static final class WhatsAppAppSecret extends BindingCredentialConverter {
        public WhatsAppAppSecret() {
            super(BindingCredential.WHATSAPP_APP_SECRET);
        }
    }

    public static final class WhatsAppVerifyToken extends BindingCredentialConverter {
        public WhatsAppVerifyToken() {
            super(BindingCredential.WHATSAPP_VERIFY_TOKEN);
        }
    }
}
