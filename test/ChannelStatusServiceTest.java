import channels.ChannelTransport;
import models.Agent;
import models.ChannelConfig;
import models.TelegramBinding;
import org.junit.jupiter.api.*;
import play.test.*;
import services.ChannelStatusService;
import services.Tx;

/**
 * Tests for {@link services.ChannelStatusService}'s aggregation across
 * the two transport-backed sources of truth that govern channel activity
 * (telegram from TelegramBinding, slack/whatsapp from ChannelConfig). The
 * in-app "web" chat is deliberately not counted — enabled agents must not
 * inflate the dashboard's "Channels active" stat above the channel cards
 * the operator sees on the /channels page. The dashboard's stat depends
 * on every cell of this matrix being right; before this service the
 * Telegram cell silently returned no result and the dashboard showed 0
 * while messages were flowing through the polling runner.
 */
class ChannelStatusServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void emptyDatabaseReturnsEmptySet() {
        var active = ChannelStatusService.activeChannelTypes();
        assertTrue(active.isEmpty(), "expected empty set, got: " + active);
    }

    // ─── web (deliberately not a channel) ────────────────────────────

    @Test
    void enabledAgentsDoNotCountAsAChannel() {
        // "web" is the in-app SPA chat — implicit in having enabled
        // agents, with no transport row and no card on /channels. It must
        // not appear in the active set, so an operator's agents never
        // inflate the dashboard's "Channels active" stat on their own.
        Tx.run(() -> {
            seedAgent("main", true);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertFalse(active.contains("web"), "web must not be counted: " + active);
        assertTrue(active.isEmpty(), "enabled agents alone yield no channels: " + active);
    }

    // ─── telegram ────────────────────────────────────────────────────

    @Test
    void telegramIsActiveWhenEnabledBindingExists() {
        Tx.run(() -> {
            var agent = seedAgent("main", true);
            seedTelegramBinding(agent, "1234:abc", "1001", true);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertTrue(active.contains("telegram"), "telegram should be active: " + active);
    }

    @Test
    void telegramNotActiveWhenAllBindingsDisabled() {
        Tx.run(() -> {
            var agent = seedAgent("main", true);
            seedTelegramBinding(agent, "1234:abc", "1001", false);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertFalse(active.contains("telegram"));
    }

    @Test
    void telegramActiveWhenAtLeastOneBindingEnabledAmongMany() {
        Tx.run(() -> {
            var a1 = seedAgent("alice", true);
            var a2 = seedAgent("bob", true);
            seedTelegramBinding(a1, "1234:abc", "1001", false); // disabled
            seedTelegramBinding(a2, "5678:def", "1002", true);  // enabled
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertTrue(active.contains("telegram"));
    }

    // ─── slack / whatsapp via ChannelConfig ──────────────────────────

    @Test
    void slackIsActiveWhenChannelConfigEnabled() {
        Tx.run(() -> {
            seedChannelConfig("slack", true);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertTrue(active.contains("slack"));
    }

    @Test
    void slackNotActiveWhenChannelConfigDisabled() {
        Tx.run(() -> {
            seedChannelConfig("slack", false);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertFalse(active.contains("slack"));
    }

    @Test
    void multipleChannelConfigsAllSurfaceWhenEnabled() {
        Tx.run(() -> {
            seedChannelConfig("slack", true);
            seedChannelConfig("whatsapp", true);
            seedChannelConfig("discord", false);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertTrue(active.contains("slack"));
        assertTrue(active.contains("whatsapp"));
        assertFalse(active.contains("discord"));
    }

    // ─── combined / regression scenarios ─────────────────────────────

    @Test
    void mixedFleetCountsEveryActiveChannelOnce() {
        // A Telegram binding works (polling runner active) and agents are
        // enabled. Only the transport-backed channel counts: telegram = 1.
        // The enabled agent does NOT add a "web" entry.
        Tx.run(() -> {
            var agent = seedAgent("main", true);
            seedTelegramBinding(agent, "1234:abc", "1001", true);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertEquals(1, active.size(), "expected telegram only, got: " + active);
        assertFalse(active.contains("web"), "web must not be counted: " + active);
        assertTrue(active.contains("telegram"));
    }

    @Test
    void duplicateChannelTypeFromBothSourcesDeduplicates() {
        // Edge case: an operator with a TelegramBinding row AND also a
        // legacy ChannelConfig row for "telegram". Both signals point at
        // active=true; the result should still be one entry, not two.
        Tx.run(() -> {
            var agent = seedAgent("main", true);
            seedTelegramBinding(agent, "1234:abc", "1001", true);
            seedChannelConfig("telegram", true);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        // Set semantics: telegram appears exactly once.
        long telegramOccurrences = active.stream().filter("telegram"::equals).count();
        assertEquals(1, telegramOccurrences);
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static Agent seedAgent(String name, boolean enabled) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.enabled = enabled;
        a.save();
        return a;
    }

    private static TelegramBinding seedTelegramBinding(Agent agent, String token,
                                                       String tgUserId, boolean enabled) {
        var b = new TelegramBinding();
        b.agent = agent;
        b.botToken = token;
        b.telegramUserId = tgUserId;
        b.transport = ChannelTransport.POLLING;
        b.enabled = enabled;
        b.save();
        return b;
    }

    private static ChannelConfig seedChannelConfig(String type, boolean enabled) {
        var c = new ChannelConfig();
        c.channelType = type;
        c.configJson = "{}";
        c.enabled = enabled;
        c.save();
        return c;
    }
}
