import channels.InboundMessage;
import channels.PendingAttachment;
import channels.TelegramClientCache;
import models.Agent;
import models.MessageAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.TelegramUrl;
import play.test.UnitTest;

import java.util.List;

/**
 * JCLAW-1231: the webhook controller and the polling runner each carried a copy of the
 * inbound turn, and the copies had drifted — a failed turn replied to the sender on the
 * webhook transport and only wrote a log line on the polling one. Both now run
 * {@code channels.TelegramInboundTurn}; this drives it through the polling wrapper,
 * which is the side that used to stay silent.
 */
class TelegramInboundTurnTest extends UnitTest {

    private static final String TOKEN = "jclaw-1231:inbound-turn-token";
    private static final long BINDING_ID = 424242L;

    private MockTelegramServer mock;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockTelegramServer();
        mock.start();
        TelegramClientCache.installForTest(TOKEN, TelegramUrl.builder()
                .schema("http").host("127.0.0.1").port(mock.port()).build());
    }

    @AfterEach
    void teardown() {
        TelegramClientCache.clearForTest(TOKEN);
        mock.close();
    }

    @Test
    void aFailedPollingTurnTellsTheSenderAndNamesTheBinding() throws Exception {
        var dispatchMerged = Class.forName("channels.TelegramPollingRunner")
                .getDeclaredMethod("dispatchMerged", Long.class, String.class, Agent.class,
                        String.class, InboundMessage.class);
        dispatchMerged.setAccessible(true);
        // A null agent throws inside prepareInboundAttachments, which reads the agent name
        // to stage the download: a deterministic in-turn failure with no network and no run.
        var message = new InboundMessage("chat-1", "private", "hi", "42", "ada", "Ada", false,
                List.of(new PendingAttachment("F1", null, "image/jpeg", 100L,
                        MessageAttachment.KIND_IMAGE)),
                null, 1, null, null);

        dispatchMerged.invoke(null, BINDING_ID, TOKEN, null, "42", message);

        assertTrue(await(() -> mock.requests().stream()
                        .anyMatch(r -> r.method().equalsIgnoreCase("sendMessage")
                                && r.body().contains("an error occurred"))),
                "the sender must be told the turn failed, not just the log: " + mock.requests());
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return false;
    }
}
