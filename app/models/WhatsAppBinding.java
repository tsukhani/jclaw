package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

/**
 * One WhatsApp presence bound to one JClaw agent (JCLAW-444). Mirrors
 * {@link SlackBinding}/{@link TelegramBinding}'s per-agent model: each agent
 * owns its own WhatsApp credentials, so multiple WhatsApp bots can coexist on
 * one instance, one per agent. Replaces the pre-444 app-global
 * {@code ChannelConfig("whatsapp")} (single Cloud-API token) as the new source
 * of truth; the inbound/outbound paths migrate onto it in JCLAW-446/447.
 *
 * <p>A binding carries a {@link WhatsAppTransport}: {@link WhatsAppTransport#CLOUD_API}
 * stores Cloud-API credentials ({@link #phoneNumberId} + {@link #accessToken},
 * with {@link #appSecret}/{@link #verifyToken} for the inbound webhook);
 * {@link WhatsAppTransport#WHATSAPP_WEB} carries no credentials at create time —
 * it's QR-paired later (JCLAW-448), so those columns stay null.
 */
@Entity
@Table(name = "whatsapp_binding", indexes = {
        @Index(name = "idx_whatsapp_binding_phone", columnList = "phone_number_id")
})
// Mirror SlackBinding/TelegramBinding (JCLAW-204/441): the inbound hot path
// resolves a binding per event, so L2-cache this entity for the same transparent
// caching the sibling bindings enjoy.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class WhatsAppBinding extends Model {

    /**
     * CLOUD_API = Meta's official Cloud API (the foundation default — what the
     * pre-444 app-global path used). WHATSAPP_WEB = the unofficial QR-paired
     * Cobalt stack (JCLAW-448), ban-warned in the UI.
     */
    @Column(nullable = false)
    public WhatsAppTransport transport = WhatsAppTransport.CLOUD_API;

    /**
     * Cloud-API phone number id (the WABA number's id on Meta's side), the
     * binding's outbound sender and the key the inbound webhook routes on
     * (JCLAW-446). Globally unique on Meta's side; we mirror that with a DB
     * constraint. Null for WHATSAPP_WEB bindings (multiple nulls are allowed by
     * the unique index).
     */
    @Column(name = "phone_number_id", unique = true)
    public String phoneNumberId;

    /** Cloud-API access token (Bearer) for Graph API calls. Null for WHATSAPP_WEB. */
    @Column(name = "access_token")
    public String accessToken;

    /**
     * Cloud-API app secret for HMAC-SHA256 verification of the inbound webhook
     * (JCLAW-446). Null for WHATSAPP_WEB (and may be null on a Cloud-API binding
     * until the operator completes setup).
     */
    @Column(name = "app_secret")
    public String appSecret;

    /**
     * Operator-chosen token echoed back on Meta's GET hub-verification challenge
     * (JCLAW-446). Null for WHATSAPP_WEB.
     */
    @Column(name = "verify_token")
    public String verifyToken;

    /**
     * The WhatsApp JID of the user who paired a WhatsApp-Web binding (JCLAW-450),
     * used by {@link channels.WhatsAppAccessPolicy} as the DM owner. Null for
     * Cloud-API bindings (a business number has no single "owner" — its DMs are
     * open) and until pairing completes.
     */
    @Column(name = "owner_jid")
    public String ownerJid;

    /**
     * Cloud-API proactive-send recipient (E.164), the agent's per-agent outbound
     * destination for {@code message(channel="whatsapp")} when there's no explicit
     * target and no live conversation peer (JCLAW-425). A Cloud-API business number
     * has no single inherent recipient — it receives from many customers — so the
     * operator configures the one the agent should proactively reach (typically
     * their own phone for a personal assistant). DESTINATION ONLY, not access
     * control: Cloud-API inbound stays open (see {@link channels.WhatsAppAccessPolicy}).
     * The WhatsApp-Web analog is {@link #ownerJid}; null for WHATSAPP_WEB. An
     * out-of-window proactive send to it uses {@link #templateName}/{@link
     * #templateLanguage}. Nullable, so the ALTER on a populated table needs no
     * {@code @ColumnDefault}.
     */
    @Column(name = "default_target")
    public String defaultTarget;

    /**
     * Meta's verified business name for this Cloud-API number, resolved by the
     * {@link channels.WhatsAppCloudApiProbe} at create/update and cached for
     * display (JCLAW-445). Nullable — null for WHATSAPP_WEB bindings and until a
     * successful probe runs. No {@code @ColumnDefault}: the column is nullable, so
     * the ALTER on a populated table is safe.
     */
    @Column(name = "verified_name")
    public String verifiedName;

    /**
     * Meta's human-readable {@code display_phone_number} for this Cloud-API number,
     * cached alongside {@link #verifiedName} from the probe (JCLAW-445). Nullable —
     * see {@link #verifiedName} for the rationale.
     */
    @Column(name = "display_phone_number")
    public String displayPhoneNumber;

    /**
     * Pre-approved message-template name used for business-initiated outbound that
     * falls OUTSIDE the 24h customer-service window (JCLAW-447). Null when the
     * operator hasn't configured one — out-of-window sends then log a warning and
     * best-effort send free-form (which Meta will reject, but the failure is the
     * operator's to resolve). Cloud-API-only; null for WHATSAPP_WEB. Nullable, so
     * the ALTER on a populated table needs no {@code @ColumnDefault}.
     */
    @Column(name = "template_name")
    public String templateName;

    /**
     * BCP-47 language/locale code for {@link #templateName} (e.g. {@code en_US}).
     * Defaults to {@code en_US} at send time when null but a template name is set.
     */
    @Column(name = "template_language")
    public String templateLanguage;

    /**
     * Agent uniqueness is a privacy constraint, not a modelling choice: agent
     * memory is scoped by agentId only, so binding one agent to multiple
     * WhatsApp numbers (and thus multiple owners) would share memories across
     * them. One WhatsApp presence per agent, across either transport. Enforced
     * here at the schema, in {@link controllers.ApiWhatsAppBindingsController},
     * and in the frontend.
     */
    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false, unique = true)
    public Agent agent;

    @Column(nullable = false)
    public boolean enabled = true;

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

    public static WhatsAppBinding findByAgent(Agent agent) {
        return WhatsAppBinding.find("agent", agent).first();
    }

    /**
     * Resolve a Cloud-API binding by its {@link #phoneNumberId}. Used by the CRUD
     * controller's uniqueness guard (JCLAW-444) and the inbound webhook router
     * (JCLAW-446), which keys on the {@code phone_number_id} in the payload.
     */
    public static WhatsAppBinding findByPhoneNumberId(String phoneNumberId) {
        if (phoneNumberId == null || phoneNumberId.isBlank()) return null;
        return WhatsAppBinding.find("phoneNumberId", phoneNumberId).first();
    }

    /**
     * Resolve a WhatsApp binding by walking the {@link Agent#parentAgent} chain —
     * the calling agent's own binding when present, else the nearest ancestor's,
     * else null. Mirrors {@link SlackBinding#findByAgentOrAncestor}: subagents
     * have no binding of their own, so a child's outbound
     * {@code message(channel="whatsapp", ...)} reaches the number its root
     * ancestor owns. Bounded 64-hop walk guards against a cyclic
     * {@code parent_agent_id}. CRUD callers (admin add/remove) must use
     * {@link #findByAgent} — exact row identity, no inherited match.
     */
    public static WhatsAppBinding findByAgentOrAncestor(Agent agent) {
        var cur = agent;
        int hops = 0;
        while (cur != null && hops++ < 64) {
            var binding = findByAgent(cur);
            if (binding != null) return binding;
            cur = cur.parentAgent;
        }
        return null;
    }

    /**
     * Resolve a Cloud-API binding by its operator-chosen {@link #verifyToken} —
     * used by the inbound webhook's GET hub-verification challenge (JCLAW-446),
     * which has no {@code phone_number_id} to route on. Returns the first enabled
     * match (the token is operator-unique in practice; multiple bindings sharing
     * one token would be a misconfiguration). Null/blank token → null.
     */
    public static WhatsAppBinding findByVerifyToken(String verifyToken) {
        if (verifyToken == null || verifyToken.isBlank()) return null;
        return WhatsAppBinding.find(
                "verifyToken = ?1 and enabled = true", verifyToken).first();
    }

    public static List<WhatsAppBinding> findAllEnabled() {
        return WhatsAppBinding.find("enabled = true").fetch();
    }

    /**
     * All enabled bindings on a given transport. Used by the WhatsApp-Web
     * connection runner (JCLAW-449) to reconcile live Cobalt sessions, mirroring
     * {@link SlackBinding#findAllEnabledByTransport}.
     */
    public static List<WhatsAppBinding> findAllEnabledByTransport(WhatsAppTransport transport) {
        return WhatsAppBinding.find("enabled = true and transport = ?1", transport).fetch();
    }
}
