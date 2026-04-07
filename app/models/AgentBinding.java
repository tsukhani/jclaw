package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

@Entity
@Table(name = "agent_binding", indexes = {
        @Index(name = "idx_binding_channel_peer", columnList = "channel_type,peer_id"),
        @Index(name = "idx_binding_agent", columnList = "agent_id")
})
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
