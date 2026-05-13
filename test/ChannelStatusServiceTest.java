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
 * the three sources of truth that govern channel activity (web from
 * Agent state, telegram from TelegramBinding, slack/whatsapp from
 * ChannelConfig). The dashboard's "Channels active" stat depends on
 * every cell of this matrix being right; before this service the
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

    // ─── web ─────────────────────────────────────────────────────────

    @Test
    void webIsActiveWhenAtLeastOneAgentEnabled() {
        Tx.run(() -> {
            seedAgent("main", true);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertTrue(active.contains("web"), "web should be active: " + active);
    }

    @Test
    void webNotActiveWhenAllAgentsDisabled() {
        Tx.run(() -> {
            seedAgent("inactive", false);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertFalse(active.contains("web"));
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
        // Reproduces the exact scenario the user reported: a Telegram
        // binding works (polling runner active), a few agents are
        // enabled, and the dashboard previously showed 0. Should now
        // count web + telegram = 2.
        Tx.run(() -> {
            var agent = seedAgent("main", true);
            seedTelegramBinding(agent, "1234:abc", "1001", true);
            return null;
        });
        var active = ChannelStatusService.activeChannelTypes();
        assertEquals(2, active.size(), "expected web + telegram, got: " + active);
        assertTrue(active.contains("web"));
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
