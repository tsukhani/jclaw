import org.junit.jupiter.api.*;
import play.test.*;
import channels.*;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

public class ChannelTest extends UnitTest {

    // === Telegram ===

    @Test
    public void telegramParseValidUpdate() {
        var json = JsonParser.parseString("""
                {
                    "update_id": 12345,
                    "message": {
                        "message_id": 1,
                        "from": {"id": 999, "is_bot": false, "first_name": "John", "username": "john_doe"},
                        "chat": {"id": 999, "type": "private"},
                        "date": 1712345678,
                        "text": "Hello bot"
                    }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg);
        assertEquals("999", msg.chatId());
        assertEquals("Hello bot", msg.text());
        assertEquals("999", msg.fromId());
        assertEquals("john_doe", msg.fromUsername());
    }

    @Test
    public void telegramParseNonMessageUpdate() {
        var json = JsonParser.parseString("""
                {"update_id": 12345, "edited_message": {"text": "edited"}}
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNull(msg);
    }

    @Test
    public void telegramParseUpdateWithoutText() {
        var json = JsonParser.parseString("""
                {"update_id": 12345, "message": {"message_id": 1, "chat": {"id": 999}, "photo": [{}]}}
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNull(msg);
    }

    // === Slack ===

    @Test
    public void slackVerifyValidSignature() throws Exception {
        var secret = "test_signing_secret";
        var timestamp = String.valueOf(Instant.now().getEpochSecond());
        var body = """
                {"type":"event_callback","event":{"type":"message","text":"hello"}}
                """;

        var baseString = "v0:%s:%s".formatted(timestamp, body);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var hash = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
        var signature = "v0=" + HexFormat.of().formatHex(hash);

        assertTrue(SlackChannel.verifySignature(secret, timestamp, body, signature));
    }

    @Test
    public void slackRejectInvalidSignature() {
        assertFalse(SlackChannel.verifySignature("secret",
                String.valueOf(Instant.now().getEpochSecond()),
                "body", "v0=invalid_signature"));
    }

    @Test
    public void slackRejectReplayAttack() throws Exception {
        var secret = "test_secret";
        var oldTimestamp = String.valueOf(Instant.now().getEpochSecond() - 600); // 10 min ago
        var body = "test";
        var baseString = "v0:%s:%s".formatted(oldTimestamp, body);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var hash = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
        var signature = "v0=" + HexFormat.of().formatHex(hash);

        assertFalse(SlackChannel.verifySignature(secret, oldTimestamp, body, signature));
    }

    @Test
    public void slackParseMessageEvent() {
        var json = JsonParser.parseString("""
                {
                    "type": "event_callback",
                    "event": {
                        "type": "message",
                        "channel": "C01234567",
                        "user": "U99999",
                        "text": "Hello from Slack"
                    }
                }
                """).getAsJsonObject();

        var msg = SlackChannel.parseEvent(json);
        assertNotNull(msg);
        assertEquals("C01234567", msg.channelId());
        assertEquals("U99999", msg.userId());
        assertEquals("Hello from Slack", msg.text());
    }

    @Test
    public void slackIgnoreBotMessage() {
        var json = JsonParser.parseString("""
                {
                    "type": "event_callback",
                    "event": {
                        "type": "message",
                        "channel": "C01234567",
                        "bot_id": "B12345",
                        "text": "Bot message"
                    }
                }
                """).getAsJsonObject();

        var msg = SlackChannel.parseEvent(json);
        assertNull(msg);
    }

    @Test
    public void slackIgnoreSubtypeMessage() {
        var json = JsonParser.parseString("""
                {
                    "type": "event_callback",
                    "event": {
                        "type": "message",
                        "subtype": "message_changed",
                        "channel": "C01234567",
                        "text": "edited"
                    }
                }
                """).getAsJsonObject();

        var msg = SlackChannel.parseEvent(json);
        assertNull(msg);
    }

    // === WhatsApp ===

    @Test
    public void whatsappVerifyValidSignature() throws Exception {
        var secret = "test_app_secret";
        var body = """
                {"object":"whatsapp_business_account","entry":[]}
                """;
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        var signature = "sha256=" + HexFormat.of().formatHex(hash);

        assertTrue(WhatsAppChannel.verifySignature(secret, body, signature));
    }

    @Test
    public void whatsappRejectInvalidSignature() {
        assertFalse(WhatsAppChannel.verifySignature("secret", "body", "sha256=invalid"));
    }

    @Test
    public void whatsappParseTextMessage() {
        var json = JsonParser.parseString("""
                {
                    "object": "whatsapp_business_account",
                    "entry": [{
                        "id": "WABA_ID",
                        "changes": [{
                            "value": {
                                "messaging_product": "whatsapp",
                                "metadata": {"display_phone_number": "15551234567", "phone_number_id": "123456789"},
                                "contacts": [{"profile": {"name": "John"}, "wa_id": "15559876543"}],
                                "messages": [{
                                    "from": "15559876543",
                                    "id": "wamid.HBgL123",
                                    "timestamp": "1712345678",
                                    "type": "text",
                                    "text": {"body": "Hi there"}
                                }]
                            },
                            "field": "messages"
                        }]
                    }]
                }
                """).getAsJsonObject();

        var msg = WhatsAppChannel.parseWebhook(json);
        assertNotNull(msg);
        assertEquals("15559876543", msg.from());
        assertEquals("Hi there", msg.text());
        assertEquals("wamid.HBgL123", msg.messageId());
        assertEquals("123456789", msg.phoneNumberId());
    }

    @Test
    public void whatsappParseStatusUpdate() {
        var json = JsonParser.parseString("""
                {
                    "object": "whatsapp_business_account",
                    "entry": [{
                        "changes": [{
                            "value": {
                                "messaging_product": "whatsapp",
                                "statuses": [{"id": "wamid.123", "status": "delivered"}]
                            },
                            "field": "messages"
                        }]
                    }]
                }
                """).getAsJsonObject();

        var msg = WhatsAppChannel.parseWebhook(json);
        assertNull(msg); // Status updates should not be parsed as messages
    }

    @Test
    public void whatsappParseNonTextMessage() {
        var json = JsonParser.parseString("""
                {
                    "object": "whatsapp_business_account",
                    "entry": [{
                        "changes": [{
                            "value": {
                                "messages": [{
                                    "from": "15559876543",
                                    "id": "wamid.456",
                                    "type": "image",
                                    "image": {"id": "img123"}
                                }]
                            }
                        }]
                    }]
                }
                """).getAsJsonObject();

        var msg = WhatsAppChannel.parseWebhook(json);
        assertNull(msg); // Image messages not supported yet
    }
}
