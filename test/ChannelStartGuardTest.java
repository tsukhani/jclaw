import channels.ChannelTransport;
import channels.SlackSocketModeRunner;
import channels.WhatsAppCobaltRunner;
import models.SlackBinding;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;

import java.util.UUID;

/**
 * The channel start runs off the boot thread (JF-277), so a stop can win the race to it. A start that finds a stop
 * begun starts nothing, even for a binding that would otherwise connect; TelegramPollingRunnerTest pins both sides
 * of the same guard against its in-memory polling app. With the guard in place nothing here dials Slack or WhatsApp.
 */
class ChannelStartGuardTest extends UnitTest {

    @Test
    void aSlackStartThatLosesTheRaceToAStopConnectsNothing() {
        long id = Tx.run(() -> {
            var b = new SlackBinding();
            b.agent = AgentService.create("slack-guard-" + UUID.randomUUID(), "openrouter", "gpt-4.1");
            b.botToken = "xoxb-guard";
            b.signingSecret = "guard-signing-secret";
            b.appToken = "xapp-guard";
            b.transport = ChannelTransport.SOCKET;
            b.enabled = true;
            b.save();
            return b.id;
        });

        SlackSocketModeRunner.reconcileUnless(() -> true);

        assertFalse(SlackSocketModeRunner.activeBindingIds().contains(id));
    }

    @Test
    void aWhatsAppStartThatLosesTheRaceToAStopOpensNoSession() {
        long id = Tx.run(() -> {
            var b = new WhatsAppBinding();
            b.agent = AgentService.create("whatsapp-guard-" + UUID.randomUUID(), "openrouter", "gpt-4.1");
            b.transport = WhatsAppTransport.WHATSAPP_WEB;
            b.enabled = true;
            b.save();
            return b.id;
        });

        WhatsAppCobaltRunner.reconcileUnless(() -> true);

        assertFalse(WhatsAppCobaltRunner.activeBindingIds().contains(id));
        assertNull(WhatsAppCobaltRunner.session(id));
    }
}
