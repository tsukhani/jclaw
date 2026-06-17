import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-465: per-agent compression default resolution. When the explicit flag
 * is unset (null), the effective state follows the agent's role — main on,
 * custom off; an explicit value overrides either way.
 */
class AgentCompressionTest extends UnitTest {

    private static Agent named(String name, Boolean flag) {
        var a = new Agent();
        a.name = name;
        a.compressionEnabled = flag;
        return a;
    }

    @Test
    void mainDefaultsOnWhenUnset() {
        assertTrue(named(Agent.MAIN_AGENT_NAME, null).compressionEffective());
    }

    @Test
    void customDefaultsOffWhenUnset() {
        assertFalse(named("helper", null).compressionEffective());
    }

    @Test
    void explicitValueOverridesRoleDefault() {
        assertFalse(named(Agent.MAIN_AGENT_NAME, false).compressionEffective(), "main can be turned off");
        assertTrue(named("helper", true).compressionEffective(), "custom can be turned on");
    }
}
