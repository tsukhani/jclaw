package mcp;

import models.McpServer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Opens a connected {@link McpClient} for one server and settles which protocol era it speaks
 * (JCLAW-1443). Shared by the connection manager and Test connection, so both read a server
 * the same way.
 */
public final class McpEraDetection {

    private McpEraDetection() {}

    /** Builds an unconnected client over a fresh transport each call: a fresh process on stdio. */
    @FunctionalInterface
    public interface ClientFactory {
        McpClient create(McpClient.Opening opening);
    }

    /**
     * @param client       connected and {@link McpClient.State#READY}; the caller owns it
     * @param switchReason why the server should move to {@link McpServer.Protocol#HANDSHAKE}:
     *                     it answered discovery, failed 2026-07-28, and the handshake worked
     * @param offersModern the server said it offers 2026-07-28, whichever era it ended on
     */
    public record Opened(McpClient client, @Nullable String switchReason, boolean offersModern) {}

    /**
     * @param skipProbe the server's last probe ran out its budget, so open with {@code initialize}
     * @throws McpException the connect failed; for a {@code HANDSHAKE} server refused with -32022,
     *                      one that names the setting to change
     */
    public static Opened open(String serverName, McpServer.Protocol protocol, boolean skipProbe,
                              ClientFactory clients) throws IOException {
        if (protocol == McpServer.Protocol.HANDSHAKE) {
            try {
                return new Opened(connected(clients.create(McpClient.Opening.INITIALIZE)), null, false);
            } catch (McpException e) {
                if (!Objects.equals(e.code(), McpClient.UNSUPPORTED_PROTOCOL_VERSION)) throw e;
                throw new McpException(McpClient.UNSUPPORTED_PROTOCOL_VERSION, ("MCP server '%s' refused the"
                        + " handshake: it now needs protocol 2026-07-28. Set its Protocol to Automatic on the"
                        + " MCP Servers page.").formatted(serverName), e.supportedVersions());
            }
        }
        var first = clients.create(skipProbe ? McpClient.Opening.INITIALIZE_OR_MODERN : McpClient.Opening.PROBE);
        try {
            return new Opened(connected(first), null, first.offersModern());
        } catch (McpException.NeedsHandshake retry) {
            McpClient handshake;
            try {
                handshake = connected(clients.create(McpClient.Opening.INITIALIZE));
            } catch (McpException e) {
                if (retry.switchOnSuccess()) throw e;
                var versions = new LinkedHashSet<>(retry.supportedVersions());
                versions.addAll(e.supportedVersions());
                throw new McpException(("MCP server '%s' shares no protocol version with JClaw: it offers %s,"
                        + " and JClaw speaks %s, or %s through the handshake, which it refused too.").formatted(
                        serverName, McpClient.describeVersions(List.copyOf(versions)),
                        McpClient.MODERN_PROTOCOL_VERSION, McpClient.PROTOCOL_VERSION), e);
            }
            return new Opened(handshake, retry.switchOnSuccess() ? retry.reason() : null, first.offersModern());
        }
    }

    /** {@link McpClient#connect()} closes its transport on a failure; this closes the client too. */
    private static McpClient connected(McpClient client) throws IOException {
        try {
            client.connect();
            return client;
        } catch (IOException | RuntimeException e) {
            client.close();
            throw e;
        }
    }
}
