package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;

import java.util.List;

/**
 * JCLAW-372: optional per-(chatId, threadId) agent override for a
 * {@link TelegramBinding}. A forum topic in a group chat can be routed to a
 * different agent than the binding's default {@link TelegramBinding#agent} —
 * e.g. a "support" topic served by a different agent than the general chat.
 *
 * <p>Each row maps one ({@code binding}, {@code chatId}, {@code threadId})
 * tuple to one override {@link #agent}. Absence of a row means "use the
 * binding's default agent" — exactly the {@link AgentSkillConfig} "no row =
 * default" convention. The composite unique index makes a given topic resolve
 * to at most one override.
 *
 * <p>Consulted on the Telegram inbound path via
 * {@link TelegramBinding#resolveAgentForTopic(String, Integer)} (JCLAW-377).
 */
@Entity
@Table(name = "telegram_topic_binding", indexes = {
        @Index(name = "idx_telegram_topic_binding_binding", columnList = "binding_id"),
        @Index(name = "idx_telegram_topic_binding_unique", columnList = "binding_id,chat_id,thread_id", unique = true)
})
// Mirrors the binding's own L2 cache (JCLAW-204): once dispatch consults the
// override, it sits on the same per-inbound-message hot path as
// TelegramBinding.findById, so the per-topic lookup wants the same transparent
// READ_WRITE caching rather than a fresh query per message.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class TelegramTopicBinding extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "binding_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public TelegramBinding binding;

    /**
     * Telegram chat id the topic lives in. String-typed to match the rest of
     * the Telegram stack ({@code chatId} is a String everywhere — see
     * {@link channels.InboundMessage}).
     */
    @Column(name = "chat_id", nullable = false)
    public String chatId;

    /**
     * Forum-topic thread id ({@code message_thread_id}). Integer-typed to match
     * the inbound {@code messageThreadId} carried through the dispatch path.
     * Never null on a stored override — a null thread id means "non-topic
     * message", which by definition can't have a per-topic override.
     */
    @Column(name = "thread_id", nullable = false)
    public Integer threadId;

    /**
     * The agent this topic routes to instead of the binding's default. No
     * uniqueness constraint here (unlike {@link TelegramBinding#agent}): an
     * override agent is a deliberate routing target, not a privacy-scoped 1:1
     * owner — the operator may point several topics at one override agent.
     */
    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    public static List<TelegramTopicBinding> findByBinding(TelegramBinding binding) {
        return TelegramTopicBinding.find("binding = ?1", binding).fetch();
    }

    public static TelegramTopicBinding findByBindingAndTopic(
            TelegramBinding binding, String chatId, Integer threadId) {
        if (chatId == null || threadId == null) return null;
        return TelegramTopicBinding.find(
                "binding = ?1 AND chatId = ?2 AND threadId = ?3",
                binding, chatId, threadId).first();
    }
}
