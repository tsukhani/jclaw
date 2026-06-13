import channels.SlackWebApi;
import channels.SlackWebApi.ChannelInfo;
import channels.SlackWebApi.ChannelLister;
import models.Agent;
import models.SlackBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.DeliveryAdvisor;
import services.Tx;

import java.lang.reflect.Field;

/**
 * JCLAW-455 unit tests for {@link DeliveryAdvisor#advisoryFor}: only Slack channel targets are
 * probed; a non-Slack target, a missing/disabled binding, or a reachable channel yields no
 * advisory; an unreachable channel yields an actionable one. The {@code conversations.list}
 * lookup is swapped via the {@code SlackWebApi.CHANNEL_LISTER} seam (reflection), and each
 * Slack test uses a distinct bot token (probe verdicts are 60 s-cached), so nothing hits the
 * network.
 */
class DeliveryAdvisorTest extends UnitTest {

    private static final Field LISTER_FIELD;
    static {
        try {
            LISTER_FIELD = SlackWebApi.class.getDeclaredField("CHANNEL_LISTER");
            LISTER_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private Object originalLister;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @AfterEach
    void teardown() throws Exception {
        if (originalLister != null) {
            LISTER_FIELD.set(null, originalLister);
            originalLister = null;
        }
    }

    private void setLister(ChannelLister lister) throws Exception {
        originalLister = LISTER_FIELD.get(null);
        LISTER_FIELD.set(null, lister);
    }

    private Agent createAgent(String name) {
        return Tx.run(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    private void enableSlack(Agent agent, String botToken) {
        Tx.run(() -> {
            var b = new SlackBinding();
            b.agent = agent;
            b.botToken = botToken;
            b.signingSecret = "sec";
            b.enabled = true;
            b.save();
        });
    }

    @Test
    void nonSlackDeliveryHasNoAdvisory() {
        var agent = createAgent("da-non-slack");
        assertNull(DeliveryAdvisor.advisoryFor(agent, "telegram:12345"));
        assertNull(DeliveryAdvisor.advisoryFor(agent, "tool:send_gmail_message"));
        assertNull(DeliveryAdvisor.advisoryFor(agent, "none"));
        assertNull(DeliveryAdvisor.advisoryFor(agent, null));
    }

    @Test
    void slackDeliveryWithoutBindingHasNoAdvisory() {
        var agent = createAgent("da-nobind");
        assertNull(DeliveryAdvisor.advisoryFor(agent, "slack:daily-briefings"),
                "no Slack binding → no probe, no advisory");
    }

    @Test
    void slackDeliveryToUnreachableChannelReturnsActionableAdvisory() throws Exception {
        var agent = createAgent("da-unreach");
        enableSlack(agent, "xoxb-da-unreach");
        setLister((token, name) -> null); // channel not found / bot not a member of a private channel
        var advisory = DeliveryAdvisor.advisoryFor(agent, "slack:#daily-briefings");
        assertNotNull(advisory, "an unreachable Slack channel must produce an advisory");
        assertTrue(advisory.contains("#daily-briefings"), advisory);
        assertTrue(advisory.contains("invite the bot"), advisory);
    }

    @Test
    void slackDeliveryToReachableChannelHasNoAdvisory() throws Exception {
        var agent = createAgent("da-reach");
        enableSlack(agent, "xoxb-da-reach");
        setLister((token, name) -> new ChannelInfo("C0OK", true)); // bot is a member
        assertNull(DeliveryAdvisor.advisoryFor(agent, "slack:ops"));
    }
}
