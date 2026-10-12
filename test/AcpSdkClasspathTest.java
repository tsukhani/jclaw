import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * The acp-core SDK loads its JSON implementation through a ServiceLoader and ships none of its
 * own, so a missing JSON module compiles and fails only when a harness run builds its transport.
 * The other ACP tests construct schema records and never reach that lookup.
 */
class AcpSdkClasspathTest extends UnitTest {

    @Test
    void theStdioTransportTheRunnerBuildsFindsAJsonImplementation() {
        // The constructor starts no process, so the command need not exist.
        var params = AgentParameters.builder("/nonexistent/acp-harness").build();
        assertDoesNotThrow(() -> new StdioAcpClientTransport(params));
    }
}
