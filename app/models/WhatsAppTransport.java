package models;

import java.util.Locale;

/**
 * Which WhatsApp integration stack a {@link WhatsAppBinding} uses (JCLAW-444).
 *
 * <p>Unlike {@link channels.ChannelTransport} (which describes an <em>inbound
 * mechanism</em> — polling vs webhook vs socket), this is a choice between two
 * entirely different integration stacks with different credentials, message
 * ceilings, and compliance profiles:
 *
 * <ul>
 *   <li>{@link #CLOUD_API} — Meta's official WhatsApp Business Platform (Graph
 *       API over HTTPS, webhook-delivered inbound). Compliant, zero ban risk,
 *       rich 1:1 messaging. Cannot participate in group chats (a structural
 *       Cloud-API limitation, not a JClaw gap).</li>
 *   <li>{@link #WHATSAPP_WEB} — the unofficial WhatsApp-Web protocol (Cobalt /
 *       {@code it.auties.whatsapp}, QR-paired). A personal or Business-App
 *       number with full group participation, but a real protocol-level
 *       <em>ban risk</em>. Gated behind an in-form warning (JCLAW-444) and the
 *       pairing flow (JCLAW-448).</li>
 * </ul>
 */
public enum WhatsAppTransport {
    CLOUD_API,
    WHATSAPP_WEB;

    /** Parse a request/config value; null/blank/unknown → {@code fallback}. */
    public static WhatsAppTransport parse(String value, WhatsAppTransport fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return WhatsAppTransport.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            return fallback;
        }
    }
}
