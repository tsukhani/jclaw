package channels;

import models.WhatsAppBinding;

/**
 * Resolves the outbound {@link Channel} for a {@link WhatsAppBinding} by its
 * transport (JCLAW-446). Centralizes the one transport switch so both call sites —
 * {@link ChannelRegistry} (agent-initiated dispatch) and {@link WhatsAppInbound}
 * (reply sink) — share it rather than each branching, and so the WhatsApp-Web arm
 * is filled in exactly one place (JCLAW-450).
 */
public final class WhatsAppChannelFactory {

    private WhatsAppChannelFactory() {}

    /**
     * The {@link Channel} that delivers outbound messages for {@code binding}, or
     * {@code null} when none is available yet. Caller checks {@code binding.enabled}.
     */
    public static Channel forBinding(WhatsAppBinding binding) {
        if (binding == null) return null;
        return switch (binding.transport) {
            case CLOUD_API -> WhatsAppChannel.forBinding(binding);
            case WHATSAPP_WEB -> WhatsAppCobaltChannel.forBinding(binding);
        };
    }
}
