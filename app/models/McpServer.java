package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.Model;

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
public class McpServer extends Model {

    public enum Transport { STDIO, HTTP }

    public enum Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    @Column(nullable = false, unique = true, length = 100)
    public String name;

    @Column(nullable = false)
    public boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    public Transport transport;

    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    public String configJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.DISCONNECTED;

    @Column(name = "last_error", length = 500)
    public String lastError;

    @Column(name = "last_connected_at")
    public Instant lastConnectedAt;

    @Column(name = "last_disconnected_at")
    public Instant lastDisconnectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static List<McpServer> findEnabled() {
        return McpServer.find("enabled = true ORDER BY name").fetch();
    }

    public static McpServer findByName(String name) {
        return McpServer.find("name = ?1", name).first();
    }
}
