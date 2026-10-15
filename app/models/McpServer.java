package models;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.List;

/**
 * Configured MCP (Model Context Protocol) server (JCLAW-31).
 *
 * <p>Each row describes one server jclaw can connect to. Operator-level —
 * not scoped per agent. Per-agent gating happens at the allowlist layer
 * (JCLAW-32) which writes {@link AgentSkillAllowedTool} rows on connect.
 *
 * <p>{@code transport} chooses how {@code configJson} is interpreted:
 *
 * <ul>
 *   <li>{@code STDIO}: {@code configJson} contains {@code command} (string),
 *       {@code args} (array of strings), {@code env} (object of string→string).</li>
 *   <li>{@code HTTP}: {@code configJson} contains {@code url} (string) and
 *       optional {@code headers} (object of string→string).</li>
 * </ul>
 *
 * <p>Status fields ({@code status}, {@code lastError},
 * {@code lastConnectedAt}, {@code lastDisconnectedAt}) are mutated by
 * {@code mcp.McpConnectionManager} as connections come up and down.
 * The admin UI (JCLAW-33) reads them to render badges; the connection
 * manager owns all writes to them.
 */
@Entity
@Table(name = "mcp_server", indexes = {
        @Index(name = "idx_mcp_server_enabled", columnList = "enabled"),
        @Index(name = "idx_mcp_server_status", columnList = "status")
})
// JCLAW-205 follow-up: Hibernate L2 cache via Caffeine. The mcp_server
// table is operator-level config — read on every McpConnectionManager
// reconcile (startup + after every CRUD), but mutated only via the
// Settings → MCP Servers UI (low frequency). Every write that matters
// goes through JPA (.save() / .delete() via McpServerService and
// ApiMcpServersController), so READ_WRITE auto-invalidation keeps the
// cache consistent. Status-field writes from the connection manager
// (status, lastConnectedAt, lastDisconnectedAt, lastError) also flow
// through .save(), so a CONNECTED → ERROR transition invalidates the
// row and the next reader sees fresh state.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class McpServer extends TimestampedModel {

    public enum Transport { STDIO, HTTP }

    public enum Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    /**
     * JCLAW-1443: how JClaw opens a connection. {@code AUTOMATIC} probes with
     * {@code server/discover} and speaks 2026-07-28 to a server that answers it;
     * {@code HANDSHAKE} never probes and opens with {@code initialize}.
     */
    public enum Protocol { AUTOMATIC, HANDSHAKE }

    @Column(nullable = false, unique = true, length = 100)
    public String name;

    @Column(nullable = false)
    public boolean enabled;

    /**
     * JCLAW-388: per-server interactive approval flag. When {@code true},
     * every tool call routed through this server's {@code mcp_<server>}
     * handle is treated as a {@linkplain agents.ToolRegistry.Tool#dangerous()
     * dangerous action} — {@link agents.DangerousActionGate} raises the same
     * approve/deny prompt it raises for {@code exec} when the running agent
     * is bound to a channel that supports interactive approval. Default
     * {@code false} (opt-in): until an operator flips this on, the server's
     * tools dispatch with no gate, exactly as before this flag existed.
     *
     * <p>Non-nullable with a {@code false} default. The {@link ColumnDefault}
     * is load-bearing: it makes the auto-DDL {@code ALTER TABLE ... ADD COLUMN}
     * emit {@code DEFAULT false}, so H2 can backfill the column on existing
     * {@code mcp_server} rows. Without it the migration fails with "NULL not
     * allowed" because the Java field default applies only to in-memory
     * instances, not to the DDL that runs against pre-existing rows.
     */
    @Column(name = "requires_approval", nullable = false)
    @ColumnDefault("false")
    public boolean requiresApproval = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    public Transport transport;

    /** Its env and header secrets stored encrypted (JCLAW-1455); one that will not decrypt keeps its ciphertext here. */
    @Convert(converter = JsonCredentialConverter.McpServerConfig.class)
    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    public String configJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.DISCONNECTED;

    @Column(name = "last_error", length = 500)
    public String lastError;

    /** Null on a row from before the column existed, and read as {@link Protocol#AUTOMATIC}: see {@link #protocolSetting()}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    public Protocol protocol = Protocol.AUTOMATIC;

    /** The protocol version JClaw last connected with; display only. */
    @Column(name = "protocol_version", length = 16)
    public String protocolVersion;

    /** When JClaw itself moved this server to {@link Protocol#HANDSHAKE}; cleared when the operator sets the protocol. */
    @Column(name = "protocol_switched_at")
    public Instant protocolSwitchedAt;

    @Column(name = "protocol_switch_reason", length = 500)
    public String protocolSwitchReason;

    /** The last probe ran out its budget unanswered, so connects skip it until the configuration is edited. */
    @Column(name = "probe_timed_out_at")
    public Instant probeTimedOutAt;

    @Column(name = "last_connected_at")
    public Instant lastConnectedAt;

    @Column(name = "last_disconnected_at")
    public Instant lastDisconnectedAt;

    /**
     * The registry tool name for {@code action} on the server named {@code serverName} —
     * {@code mcp_<server>} for the server-level handle (empty or null action),
     * {@code mcp_<server>_<action>} for one of its actions.
     *
     * <p>The flat-underscore form keeps the result a valid identifier across LLM tool-call
     * schemas (some OpenAI-style schemas reject {@code .}). Derived from the name on every
     * read rather than stored: JCLAW-983 keys a grant by {@link #id}, so the name a rename
     * changes is display only.
     */
    public static String toolName(String serverName, String action) {
        var handle = "mcp_" + serverName;
        return action == null || action.isEmpty() ? handle : handle + "_" + action;
    }

    public Protocol protocolSetting() {
        return protocol != null ? protocol : Protocol.AUTOMATIC;
    }

    public static List<McpServer> findEnabled() {
        return McpServer.find("enabled = true ORDER BY name").fetch();
    }

    public static McpServer findByName(String name) {
        return McpServer.find("name = ?1", name).first();
    }
}
