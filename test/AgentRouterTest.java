import org.junit.jupiter.api.*;
import play.test.*;
import agents.AgentRouter;
import models.Agent;
import models.AgentBinding;

public class AgentRouterTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    public void tier1ExactPeerMatch() {
        var agent = createAgent("support");
        createBinding(agent, "telegram", "12345");

        var result = AgentRouter.resolve("telegram", "12345");
        assertNotNull(result);
        assertEquals("support", result.agent().name);
        assertEquals("peer", result.matchedBy());
    }

    @Test
    public void tier2ChannelWideMatch() {
        var agent = createAgent("support");
        createBinding(agent, "slack", null);

        var result = AgentRouter.resolve("slack", "U999");
        assertNotNull(result);
        assertEquals("support", result.agent().name);
        assertEquals("channel", result.matchedBy());
    }

    @Test
    public void tier3MainAgentFallback() {
        createAgent("main");

        var result = AgentRouter.resolve("whatsapp", "5551234");
        assertNotNull(result);
        assertEquals("main", result.agent().name);
        assertEquals("main", result.matchedBy());
    }

    @Test
    public void peerMatchTakesPriorityOverChannelMatch() {
        var support = createAgent("support");
        var other = createAgent("other");
        createBinding(support, "telegram", "VIP_USER");
        createBinding(other, "telegram", null);

        var result = AgentRouter.resolve("telegram", "VIP_USER");
        assertNotNull(result);
        assertEquals("support", result.agent().name);
        assertEquals("peer", result.matchedBy());
    }

    @Test
    public void channelMatchTakesPriorityOverMainFallback() {
        var channelAgent = createAgent("channel-agent");
        createAgent("main");
        createBinding(channelAgent, "slack", null);

        var result = AgentRouter.resolve("slack", "U123");
        assertNotNull(result);
        assertEquals("channel-agent", result.agent().name);
        assertEquals("channel", result.matchedBy());
    }

    @Test
    public void noRouteReturnsNull() {
        // No agents, no bindings
        var result = AgentRouter.resolve("telegram", "12345");
        assertNull(result);
    }

    @Test
    public void disabledAgentSkipped() {
        var agent = createAgent("disabled");
        agent.enabled = false;
        agent.save();
        createBinding(agent, "telegram", "12345");

        // Should fall through to main (which doesn't exist here)
        var result = AgentRouter.resolve("telegram", "12345");
        assertNull(result);
    }

    @Test
    public void noMainAgentConfigured() {
        // Agents exist but none is named "main", and no binding matches
        createAgent("non-main");

        var result = AgentRouter.resolve("unknown-channel", "peer");
        assertNull(result);
    }

    @Test
    public void disabledAgentInChannelTierFallsThroughToMain() {
        // Channel-tier binding points at a disabled agent; router should
        // skip it and fall back to main. This guard is at AgentRouter:35.
        var channelAgent = createAgent("channel-agent");
        channelAgent.enabled = false;
        channelAgent.save();
        createBinding(channelAgent, "slack", null);
        createAgent("main");

        var result = AgentRouter.resolve("slack", "U123");
        assertNotNull(result);
        assertEquals("main", result.agent().name);
        assertEquals("main", result.matchedBy());
    }

    @Test
    public void disabledMainAgentReturnsNull() {
        // Main agent exists but is disabled; no other route available.
        // Guards AgentRouter:41 — without this check a disabled main would
        // still match and deliver messages to a shut-off agent.
        var main = createAgent("main");
        main.enabled = false;
        main.save();

        var result = AgentRouter.resolve("telegram", "12345");
        assertNull(result);
    }

    @Test
    public void nullPeerIdSkipsPeerTierAndUsesChannelBinding() {
        // Channel-wide binding (peerId IS NULL) must match when caller
        // passes null peerId — e.g. Slack app_mention without a user hint.
        var channelAgent = createAgent("channel-agent");
        createBinding(channelAgent, "slack", null);

        var result = AgentRouter.resolve("slack", null);
        assertNotNull(result);
        assertEquals("channel-agent", result.agent().name);
        assertEquals("channel", result.matchedBy());
    }

    // --- Helpers ---

    private Agent createAgent(String name) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
        return agent;
    }

    private void createBinding(Agent agent, String channelType, String peerId) {
        var binding = new AgentBinding();
        binding.agent = agent;
        binding.channelType = channelType;
        binding.peerId = peerId;
        binding.save();
    }
}
