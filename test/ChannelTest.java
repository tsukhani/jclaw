import org.junit.jupiter.api.*;
import play.test.*;
import channels.*;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

class ChannelTest extends UnitTest {

    // === Telegram ===

    @Test
    void telegramParseValidUpdate() {
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
    void telegramParseNonMessageUpdate() {
        var json = JsonParser.parseString("""
                {"update_id": 12345, "edited_message": {"text": "edited"}}
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNull(msg);
    }

    @Test
    void telegramParseTrulyEmptyUpdateReturnsNull() {
        // No text, no caption, no attachments — nothing to act on. JCLAW-136
        // preserved the null-return for this case so webhook handlers still
        // bail out cleanly on unhandled update shapes.
        var json = JsonParser.parseString("""
                {"update_id": 12345, "message": {"message_id": 1, "chat": {"id": 999}}}
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNull(msg);
    }

    // === JCLAW-136: inbound attachment parsing ===

    @Test
    void telegramParsePhotoWithCaption() {
        var json = JsonParser.parseString("""
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 1,
                    "from": {"id": 42, "is_bot": false, "first_name": "X"},
                    "chat": {"id": 42, "type": "private"},
                    "date": 1712345678,
                    "caption": "look at this",
                    "photo": [
                      {"file_id": "SMALL", "file_unique_id": "us", "file_size": 100, "width": 90, "height": 51},
                      {"file_id": "LARGE", "file_unique_id": "ul", "file_size": 20000, "width": 1280, "height": 720}
                    ]
                  }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg);
        assertEquals("look at this", msg.text());
        assertEquals(1, msg.attachments().size());
        var att = msg.attachments().get(0);
        assertEquals("LARGE", att.telegramFileId(),
                "highest-resolution PhotoSize (last in array) must win");
        assertEquals(models.MessageAttachment.KIND_IMAGE, att.kind());
    }

    @Test
    void telegramParsePhotoWithoutCaptionYieldsEmptyText() {
        var json = JsonParser.parseString("""
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 1,
                    "from": {"id": 42, "is_bot": false, "first_name": "X"},
                    "chat": {"id": 42, "type": "private"},
                    "date": 1,
                    "photo": [{"file_id": "F", "file_unique_id": "u", "width": 1, "height": 1}]
                  }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg, "photo-only message must still parse (JCLAW-136)");
        assertEquals("", msg.text(), "no synthetic default prompt; empty string for no caption");
        assertEquals(1, msg.attachments().size());
    }

    @Test
    void telegramParseVoiceNote() {
        var json = JsonParser.parseString("""
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 1,
                    "from": {"id": 42, "is_bot": false, "first_name": "X"},
                    "chat": {"id": 42, "type": "private"},
                    "date": 1,
                    "voice": {"file_id": "V", "file_unique_id": "uv", "duration": 3, "mime_type": "audio/ogg", "file_size": 3000}
                  }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg);
        assertEquals(1, msg.attachments().size());
        assertEquals(models.MessageAttachment.KIND_AUDIO, msg.attachments().get(0).kind());
    }

    @Test
    void telegramParseAudioWithCaption() {
        var json = JsonParser.parseString("""
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 1,
                    "from": {"id": 42, "is_bot": false, "first_name": "X"},
                    "chat": {"id": 42, "type": "private"},
                    "date": 1,
                    "caption": "Transcribe verbatim",
                    "audio": {"file_id": "A", "file_unique_id": "ua", "duration": 5, "mime_type": "audio/mpeg", "file_name": "harvard.wav", "file_size": 3000000}
                  }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg);
        assertEquals("Transcribe verbatim", msg.text());
        assertEquals(1, msg.attachments().size());
        var att = msg.attachments().get(0);
        assertEquals(models.MessageAttachment.KIND_AUDIO, att.kind());
        assertEquals("harvard.wav", att.suggestedFilename());
    }

    @Test
    void telegramParseDocumentWithCaption() {
        var json = JsonParser.parseString("""
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 1,
                    "from": {"id": 42, "is_bot": false, "first_name": "X"},
                    "chat": {"id": 42, "type": "private"},
                    "date": 1,
                    "caption": "Summarize this",
                    "document": {"file_id": "D", "file_unique_id": "ud", "mime_type": "application/pdf", "file_name": "report.pdf", "file_size": 1200000}
                  }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg);
        assertEquals("Summarize this", msg.text());
        assertEquals(models.MessageAttachment.KIND_FILE,
                msg.attachments().get(0).kind());
    }

    @Test
    void telegramParseDocumentWithImageMimeClassifiesAsImage() {
        // User attached a PNG via "File" upload (to skip Telegram's JPEG
        // compression) instead of "Photo". We still want the multimodal
        // gate to treat it as an image so vision-capable models see it.
        var json = JsonParser.parseString("""
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 1,
                    "from": {"id": 42, "is_bot": false, "first_name": "X"},
                    "chat": {"id": 42, "type": "private"},
                    "date": 1,
                    "document": {"file_id": "D", "file_unique_id": "ud", "mime_type": "image/png", "file_name": "screenshot.png", "file_size": 200000}
                  }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg);
        assertEquals(models.MessageAttachment.KIND_IMAGE,
                msg.attachments().get(0).kind(),
                "document with image/* MIME must classify as IMAGE");
    }

    @Test
    void telegramParseVideo() {
        var json = JsonParser.parseString("""
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 1,
                    "from": {"id": 42, "is_bot": false, "first_name": "X"},
                    "chat": {"id": 42, "type": "private"},
                    "date": 1,
                    "caption": "Describe this clip",
                    "video": {"file_id": "V", "file_unique_id": "uv", "duration": 10, "width": 640, "height": 480, "mime_type": "video/mp4", "file_size": 5000000}
                  }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg);
        assertEquals(models.MessageAttachment.KIND_FILE,
                msg.attachments().get(0).kind());
    }

    @Test
    void telegramParseMediaGroupIdPreserved() {
        var json = JsonParser.parseString("""
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 1,
                    "from": {"id": 42, "is_bot": false, "first_name": "X"},
                    "chat": {"id": 42, "type": "private"},
                    "date": 1,
                    "media_group_id": "98765",
                    "caption": "album",
                    "photo": [{"file_id": "F", "file_unique_id": "u", "width": 1, "height": 1}]
                  }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(json);
        assertNotNull(msg);
        assertEquals("98765", msg.mediaGroupId(),
                "media_group_id must round-trip so the handler can reassemble albums");
    }

    // === Telegram outbound chunker ===

    @Test
    void telegramChunkShortTextReturnsSingleChunk() {
        var chunks = TelegramChannel.chunk("hello world", 4000);
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.getFirst());
    }

    @Test
    void telegramChunkSplitsAtParagraphBoundaries() {
        var text = "A".repeat(100) + "\n\n" + "B".repeat(100) + "\n\n" + "C".repeat(100);
        var chunks = TelegramChannel.chunk(text, 150);
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).startsWith("A"));
        assertTrue(chunks.get(1).startsWith("B"));
        assertTrue(chunks.get(2).startsWith("C"));
        for (var c : chunks) {
            assertTrue(c.length() <= 150, () -> "chunk too long: " + c.length());
        }
    }

    @Test
    void telegramChunkFallsBackToLineBoundary() {
        // No paragraph break within 50 chars, but newlines are available.
        var text = "line1\n" + "line2\n" + "line3\n" + "x".repeat(100);
        var chunks = TelegramChannel.chunk(text, 50);
        assertTrue(chunks.size() >= 2);
        for (var c : chunks) {
            assertTrue(c.length() <= 50, () -> "chunk too long: " + c.length());
        }
    }

    @Test
    void telegramChunkHardCutsWhenNoBoundary() {
        var text = "X".repeat(12000);
        var chunks = TelegramChannel.chunk(text, 4000);
        assertEquals(3, chunks.size());
        for (var c : chunks) {
            assertTrue(c.length() <= 4000, () -> "chunk too long: " + c.length());
        }
    }

    @Test
    void telegramChunkRealAgentReply() {
        // The production failure: a 4285-char agent response exceeded Telegram's
        // 4096 limit by ~200 chars. Verify a similar payload splits cleanly.
        var sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("### Section ").append(i).append("\n\n");
            sb.append("A".repeat(50)).append(' ').append("B".repeat(10)).append("\n\n");
        }
        var chunks = TelegramChannel.chunk(sb.toString(), 4000);
        assertTrue(chunks.size() >= 2, () -> "expected multiple chunks, got " + chunks.size());
        for (var c : chunks) {
            assertTrue(c.length() <= 4000, () -> "chunk too long: " + c.length());
        }
    }

    // === ChannelTransport enum ===

    @Test
    void channelTransportParsesFallbacks() {
        assertEquals(ChannelTransport.POLLING, ChannelTransport.parse(null, ChannelTransport.POLLING));
        assertEquals(ChannelTransport.WEBHOOK, ChannelTransport.parse("", ChannelTransport.WEBHOOK));
        assertEquals(ChannelTransport.POLLING, ChannelTransport.parse("   ", ChannelTransport.POLLING));
        assertEquals(ChannelTransport.POLLING, ChannelTransport.parse("bogus", ChannelTransport.POLLING));
    }

    @Test
    void channelTransportParsesValidValuesCaseInsensitive() {
        assertEquals(ChannelTransport.POLLING, ChannelTransport.parse("POLLING", ChannelTransport.WEBHOOK));
        assertEquals(ChannelTransport.POLLING, ChannelTransport.parse("polling", ChannelTransport.WEBHOOK));
        assertEquals(ChannelTransport.WEBHOOK, ChannelTransport.parse(" WEBHOOK ", ChannelTransport.POLLING));
        assertTrue(ChannelTransport.WEBHOOK.requiresPublicUrl());
        assertTrue(ChannelTransport.HTTP.requiresPublicUrl());
        assertFalse(ChannelTransport.POLLING.requiresPublicUrl());
        assertFalse(ChannelTransport.SOCKET.requiresPublicUrl());
    }

    // === Telegram bindings (JCLAW-89) ===

    @Test
    void telegramBindingPersistsAndQueriesByToken() {
        var agent = findOrCreateAgent("test-bindings-agent");
        var binding = new models.TelegramBinding();
        binding.botToken = "tok-" + System.nanoTime();
        binding.agent = agent;
        binding.telegramUserId = "111111";
        binding.transport = ChannelTransport.POLLING;
        binding.enabled = true;
        binding.save();

        var fetched = models.TelegramBinding.findByBotToken(binding.botToken);
        assertNotNull(fetched);
        assertEquals(binding.id, fetched.id);
        assertEquals("111111", fetched.telegramUserId);
        assertEquals(agent.id, fetched.agent.id);

        binding.delete();
        assertNull(models.TelegramBinding.findByBotToken(binding.botToken));
    }

    @Test
    void telegramBindingFindAllEnabledIsScopedToEnabled() {
        // Agent uniqueness is enforced at the schema (privacy constraint:
        // agent memory is per-agent, so binding one agent to two users would
        // share memories). Use distinct agents for the two bindings.
        var agentOn = findOrCreateAgent("test-bindings-enabled-on");
        var agentOff = findOrCreateAgent("test-bindings-enabled-off");

        var on = new models.TelegramBinding();
        on.botToken = "on-" + System.nanoTime();
        on.agent = agentOn;
        on.telegramUserId = "444444";
        on.transport = ChannelTransport.POLLING;
        on.enabled = true;
        on.save();

        var off = new models.TelegramBinding();
        off.botToken = "off-" + System.nanoTime();
        off.agent = agentOff;
        off.telegramUserId = "555555";
        off.transport = ChannelTransport.POLLING;
        off.enabled = false;
        off.save();

        var enabledIds = models.TelegramBinding.findAllEnabled().stream()
                .map(b -> b.id)
                .toList();
        assertTrue(enabledIds.contains(on.id));
        assertFalse(enabledIds.contains(off.id));

        on.delete();
        off.delete();
    }

    /**
     * Lookup helper: return a reusable test agent with the given name, creating
     * it if absent. Test agents are plain rows; the LoadTest reserved-name guard
     * doesn't apply here.
     */
    private static models.Agent findOrCreateAgent(String name) {
        var existing = models.Agent.findByName(name);
        if (existing != null) return existing;
        var a = new models.Agent();
        a.name = name;
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.save();
        return a;
    }

    // === ChannelConfig cache-miss from non-request threads ===

    @Test
    void channelConfigFindByTypeSurvivesCacheMissFromBackgroundThread() throws Exception {
        // Regression for the JPA "No active EntityManager" error seen in polling
        // flows after the 60s cache TTL expired: findByType must work from a
        // thread without a request-level transaction (SDK executor threads,
        // virtual threads spawned from webhook controllers).
        models.ChannelConfig.evictAllCache();

        var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                // Not inside a transaction — the wrapper in findByType must
                // establish one on our behalf. Null result is fine; no throw.
                models.ChannelConfig.findByType("nonexistent-channel-for-test");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        thread.start();
        thread.join(5_000);

        assertNull(thrown.get(), () -> "findByType threw from a background thread: "
                + (thrown.get() == null ? "" : thrown.get().getMessage()));
    }

    // === Channel.sendWithRetry ===

    @Test
    void channelSendWithRetryHonoursRetryAfterFromSendResult() {
        var channel = new Channel() {
            int attempts = 0;
            @Override public String channelName() { return "test"; }
            @Override public SendResult trySend(String peer, String text) {
                if (++attempts == 1) return SendResult.rateLimited(2000L);
                return SendResult.OK;
            }
        };

        long start = System.currentTimeMillis();
        assertTrue(channel.sendWithRetry("peer", "hello"));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 1800 && elapsed < 4000,
                () -> "Expected ~2s sleep from rate-limit hint, got " + elapsed + "ms");
    }

    @Test
    void channelSendWithRetryUsesDefaultWhenSendResultHasNoHint() {
        var channel = new Channel() {
            int attempts = 0;
            @Override public String channelName() { return "test"; }
            @Override public SendResult trySend(String peer, String text) {
                if (++attempts == 1) return SendResult.FAILED;
                return SendResult.OK;
            }
        };
        long start = System.currentTimeMillis();
        assertTrue(channel.sendWithRetry("peer", "hello"));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 800 && elapsed < 2500,
                () -> "Expected ~1s sleep (default), got " + elapsed + "ms");
    }

    @Test
    void channelSendWithRetryReturnsFalseWhenBothAttemptsFail() {
        var channel = new Channel() {
            @Override public String channelName() { return "test"; }
            @Override public SendResult trySend(String peer, String text) {
                return SendResult.FAILED;
            }
        };
        assertFalse(channel.sendWithRetry("peer", "hello"));
    }

    @Test
    void channelSendWithRetryRunsRetryOnSchedulerThreadNotCaller() throws Exception {
        // Regression: the original implementation called Thread.sleep on the
        // caller's thread. Under virtual-thread dispatch, that pinned the
        // carrier per JDK-8373224. The fix routes the delay through a
        // platform-thread scheduler (RetryScheduler), so the second trySend
        // must run on the scheduler thread, not the caller.
        var firstAttemptThread = new java.util.concurrent.atomic.AtomicReference<String>();
        var secondAttemptThread = new java.util.concurrent.atomic.AtomicReference<String>();
        var channel = new Channel() {
            int attempts = 0;
            @Override public String channelName() { return "test"; }
            @Override public SendResult trySend(String peer, String text) {
                var name = Thread.currentThread().getName();
                if (++attempts == 1) {
                    firstAttemptThread.set(name);
                    return SendResult.FAILED;
                }
                secondAttemptThread.set(name);
                return SendResult.OK;
            }
        };

        long start = System.currentTimeMillis();
        assertTrue(channel.sendWithRetry("peer", "hello"));
        long elapsed = System.currentTimeMillis() - start;

        // Default delay is 1s when no retry-after hint is present.
        assertTrue(elapsed >= 800 && elapsed < 2500,
                () -> "Expected ~1s scheduled delay, got " + elapsed + "ms");
        assertNotNull(firstAttemptThread.get());
        assertNotNull(secondAttemptThread.get());
        assertNotEquals(firstAttemptThread.get(), secondAttemptThread.get(),
                "Retry must run on the platform-thread scheduler, not the caller's thread");
        assertEquals("jclaw-retry-scheduler", secondAttemptThread.get(),
                "Retry must run on the named platform-thread scheduler");
    }

    // === Slack ===

    @Test
    void slackVerifyValidSignature() throws Exception {
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
    void slackRejectInvalidSignature() {
        assertFalse(SlackChannel.verifySignature("secret",
                String.valueOf(Instant.now().getEpochSecond()),
                "body", "v0=invalid_signature"));
    }

    @Test
    void slackRejectReplayAttack() throws Exception {
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
    void slackParseMessageEvent() {
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
    void slackIgnoreBotMessage() {
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
    void slackIgnoreSubtypeMessage() {
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
    void whatsappVerifyValidSignature() throws Exception {
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
    void whatsappRejectInvalidSignature() {
        assertFalse(WhatsAppChannel.verifySignature("secret", "body", "sha256=invalid"));
    }

    @Test
    void whatsappParseTextMessage() {
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
    void whatsappParseStatusUpdate() {
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
    void whatsappParseNonTextMessage() {
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

    // === JCLAW-16: platform signature verification edge cases ===

    @Test
    void slackRejectMissingPrefix() {
        // Slack spec requires "v0=" prefix; a raw hex digest without it must fail.
        assertFalse(SlackChannel.verifySignature("secret",
                String.valueOf(Instant.now().getEpochSecond()),
                "body", "abcdef0123456789"));
    }

    @Test
    void slackRejectNullInputs() {
        var ts = String.valueOf(Instant.now().getEpochSecond());
        assertFalse(SlackChannel.verifySignature(null, ts, "body", "v0=abcd"));
        assertFalse(SlackChannel.verifySignature("secret", null, "body", "v0=abcd"));
        assertFalse(SlackChannel.verifySignature("secret", ts, "body", null));
    }

    @Test
    void slackRejectTamperedByte() throws Exception {
        // Flip one hex nibble in a valid signature — constant-time compare
        // must still return false.
        var secret = "test_signing_secret";
        var ts = String.valueOf(Instant.now().getEpochSecond());
        var body = "{\"ok\":1}";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var hash = mac.doFinal("v0:%s:%s".formatted(ts, body).getBytes(StandardCharsets.UTF_8));
        var hex = HexFormat.of().formatHex(hash);
        var tampered = "v0=" + flipFirstHexNibble(hex);
        assertFalse(SlackChannel.verifySignature(secret, ts, body, tampered));
    }

    @Test
    void whatsappRejectNullAppSecret() {
        // Controller guards against null appSecret, but the verify helper must
        // also reject it defensively so no future caller can bypass.
        assertFalse(WhatsAppChannel.verifySignature(null, "body", "sha256=abcd"));
    }

    @Test
    void whatsappRejectMissingPrefix() {
        // Meta spec requires "sha256=" prefix; raw hex must fail.
        assertFalse(WhatsAppChannel.verifySignature("secret", "body", "abcdef0123456789"));
    }

    @Test
    void whatsappRejectTamperedByte() throws Exception {
        var secret = "test_app_secret";
        var body = "{\"entry\":[]}";
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        var hex = HexFormat.of().formatHex(hash);
        var tampered = "sha256=" + flipFirstHexNibble(hex);
        assertFalse(WhatsAppChannel.verifySignature(secret, body, tampered));
    }

    private static String flipFirstHexNibble(String hex) {
        var first = hex.charAt(0);
        var flipped = first == '0' ? '1' : '0';
        return flipped + hex.substring(1);
    }

    // === Telegram forToken caching (Phase 3) ===

    @Test
    void telegramForTokenReturnsCachedInstancePerToken() {
        // The token → instance cache is load-bearing: OkHttpTelegramClient owns
        // a dispatcher thread pool, so new instances leak threads if the cache
        // misses. Same token must return the same instance.
        var tokenA = "123456:test-token-A-" + System.nanoTime();
        var tokenB = "123456:test-token-B-" + System.nanoTime();
        try {
            var first = TelegramChannel.forToken(tokenA);
            var second = TelegramChannel.forToken(tokenA);
            var otherToken = TelegramChannel.forToken(tokenB);
            assertSame(first, second, "same token must map to the same instance");
            assertNotSame(first, otherToken, "different tokens must map to different instances");
        } finally {
            TelegramChannel.evictToken(tokenA);
            TelegramChannel.evictToken(tokenB);
        }
    }

    @Test
    void telegramForTokenRejectsNullAndBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> TelegramChannel.forToken(null));
        assertThrows(IllegalArgumentException.class,
                () -> TelegramChannel.forToken(""));
        assertThrows(IllegalArgumentException.class,
                () -> TelegramChannel.forToken("   "));
    }

    @Test
    void telegramEvictTokenAllowsRebuildOnNextFetch() {
        // Critical for token rotation: after evict, the next forToken call must
        // hand back a fresh instance (the old OkHttpTelegramClient would still
        // hold stale bearer auth).
        var token = "evict-test-" + System.nanoTime();
        var first = TelegramChannel.forToken(token);
        TelegramChannel.evictToken(token);
        var second = TelegramChannel.forToken(token);
        assertNotSame(first, second, "evict + refetch must yield a new instance");
        TelegramChannel.evictToken(token);
    }

    @Test
    void telegramEvictTokenIgnoresNull() {
        // Defensive: the binding-delete path calls evictToken unconditionally.
        // A null bot token (misconfigured binding) must not throw.
        Assertions.assertDoesNotThrow(() -> TelegramChannel.evictToken(null));
    }

    // === ChannelType generic dispatch (Phase 3) ===

    @Test
    void channelTypeResolveReturnsNullForTelegramAndWeb() {
        // Contract documented at ChannelType.resolve: TELEGRAM needs a bot
        // token (carried by TelegramBinding, not the generic Channel), and
        // WEB is DB-polled not pushed. Callers rely on null to branch.
        assertNull(models.ChannelType.TELEGRAM.resolve());
        assertNull(models.ChannelType.WEB.resolve());
    }

    @Test
    void channelTypeResolveReturnsConcreteChannelsForSlackAndWhatsApp() {
        assertNotNull(models.ChannelType.SLACK.resolve());
        assertNotNull(models.ChannelType.WHATSAPP.resolve());
        assertTrue(models.ChannelType.SLACK.resolve() instanceof SlackChannel);
        assertTrue(models.ChannelType.WHATSAPP.resolve() instanceof WhatsAppChannel);
    }

    @Test
    void channelTypeFromValueReturnsNullForUnknownValues() {
        // Null fall-through, not throw — callers route unknown channels into
        // a default branch (e.g. treating as WEB) rather than crashing.
        assertNull(models.ChannelType.fromValue(null));
        assertNull(models.ChannelType.fromValue(""));
        assertNull(models.ChannelType.fromValue("discord"));
        assertNull(models.ChannelType.fromValue("WEB"),
                "fromValue is case-sensitive by design to match DB rows — uppercase must miss");
    }
}
