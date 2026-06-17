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

    // JCLAW-463: per-type sub-toggles, gated by the master, default on under it.

    @Test
    void subTogglesDefaultOnUnderEnabledMaster() {
        var a = named(Agent.MAIN_AGENT_NAME, null); // master on by role default
        assertTrue(a.compressionJsonEffective(), "JSON defaults on under the master");
        assertTrue(a.compressionCodeEffective(), "Code defaults on under the master");
    }

    @Test
    void subTogglesAreGatedOffByTheMaster() {
        var a = named("helper", false); // master explicitly off
        a.compressionJson = true;
        a.compressionCode = true;
        assertFalse(a.compressionJsonEffective(), "master off gates JSON off");
        assertFalse(a.compressionCodeEffective(), "master off gates Code off");
    }

    @Test
    void subToggleCanBeOptedOutUnderAnEnabledMaster() {
        var a = named("helper", true); // master on
        a.compressionJson = false;      // JSON opted out
        assertFalse(a.compressionJsonEffective(), "JSON opted out");
        assertTrue(a.compressionCodeEffective(), "Code still on (null -> default on)");
    }
}
