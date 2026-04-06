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
        var agent = createAgent("support", false);
        createBinding(agent, "telegram", "12345");

        var result = AgentRouter.resolve("telegram", "12345");
        assertNotNull(result);
        assertEquals("support", result.agent().name);
        assertEquals("peer", result.matchedBy());
    }

    @Test
    public void tier2ChannelWideMatch() {
        var agent = createAgent("main", false);
        createBinding(agent, "slack", null);

        var result = AgentRouter.resolve("slack", "U999");
        assertNotNull(result);
        assertEquals("main", result.agent().name);
        assertEquals("channel", result.matchedBy());
    }

    @Test
    public void tier3DefaultAgentFallback() {
        createAgent("fallback", true);

        var result = AgentRouter.resolve("whatsapp", "5551234");
        assertNotNull(result);
        assertEquals("fallback", result.agent().name);
        assertEquals("default", result.matchedBy());
    }

    @Test
    public void peerMatchTakesPriorityOverChannelMatch() {
        var support = createAgent("support", false);
        var main = createAgent("main", false);
        createBinding(support, "telegram", "VIP_USER");
        createBinding(main, "telegram", null);

        var result = AgentRouter.resolve("telegram", "VIP_USER");
        assertNotNull(result);
        assertEquals("support", result.agent().name);
        assertEquals("peer", result.matchedBy());
    }

    @Test
    public void channelMatchTakesPriorityOverDefault() {
        var channelAgent = createAgent("channel-agent", false);
        createAgent("default-agent", true);
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
        var agent = createAgent("disabled", false);
        agent.enabled = false;
        agent.save();
        createBinding(agent, "telegram", "12345");

        // Should fall through to default (which doesn't exist)
        var result = AgentRouter.resolve("telegram", "12345");
        assertNull(result);
    }

    @Test
    public void noDefaultAgentConfigured() {
        // Agents exist but none is default, and no binding matches
        createAgent("non-default", false);

        var result = AgentRouter.resolve("unknown-channel", "peer");
        assertNull(result);
    }

    // --- Helpers ---

    private Agent createAgent(String name, boolean isDefault) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.isDefault = isDefault;
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
