package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.Model;

@Entity
@Table(name = "agent_binding", indexes = {
        @Index(name = "idx_binding_channel_peer", columnList = "channel_type,peer_id"),
        @Index(name = "idx_binding_agent", columnList = "agent_id")
})
// JCLAW-205 follow-up: routed on every inbound channel message
// (Telegram, Slack, WhatsApp, web webhook) to resolve which agent
// handles the peer. Mutated only via Settings → Agent Bindings
// (operator-level, low frequency). Reads dominate writes by orders of
// magnitude — the entity-cache pattern other operator-config rows
// already use applies cleanly.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class AgentBinding extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    public Agent agent;

    @Column(name = "channel_type", nullable = false)
    public String channelType;

    @Column(name = "peer_id")
    public String peerId;

    @Column(nullable = false)
    public int priority = 0;

    public static AgentBinding findByChannelAndPeer(String channelType, String peerId) {
        return AgentBinding.find("channelType = ?1 AND peerId = ?2", channelType, peerId).first();
    }

    public static AgentBinding findByChannel(String channelType) {
        return AgentBinding.find("channelType = ?1 AND peerId IS NULL", channelType).first();
    }
}
