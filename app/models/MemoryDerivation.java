package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;

/**
 * One input of a derived {@link Memory} (JCLAW-1318, {@code prov:wasDerivedFrom}).
 *
 * <p>The input's source turn is copied in at write time, so one hop from a derived memory
 * reaches the turns it rests on even after the input row is deleted — at which point
 * {@link #inputMemoryId} is nulled and the turn refs are all that remain. All three ids are
 * plain columns, not foreign keys, like {@code Memory.supersededById}.
 */
@Entity
@Table(name = "memory_derivation", indexes = {
        @Index(name = "idx_memory_derivation_derived", columnList = "derived_memory_id")
})
public class MemoryDerivation extends Model {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "derived_memory_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Memory derivedMemory;

    @Column(name = "input_memory_id")
    public Long inputMemoryId;

    @Column(name = "input_conversation_id")
    public Long inputConversationId;

    @Column(name = "input_message_id")
    public Long inputMessageId;
}
