package channels;

import models.WhatsAppBinding;
import services.AttachmentService;

import java.util.List;

/**
 * Downloads inbound WhatsApp media into the agent's staging dir, returning
 * {@link AttachmentService.Input}s the runner finalizes — the same shape Slack /
 * Telegram inbound produce, so the downstream agent path is unchanged.
 *
 * <p>One class, branched only at the byte-fetch step: the Cloud-API path does the
 * Graph media two-step ({@code GET /{mediaId}} → CDN URL → bytes, SSRF-guarded)
 * and the WhatsApp-Web path pulls bytes from the live Cobalt session. Each
 * transport fills its own private method (disjoint hunks → conflict-free merge of
 * the two tracks); the shared {@link #downloadAll} dispatch + the
 * {@link AttachmentService.Input} contract live here so nothing transport-specific
 * escapes.
 */
public final class WhatsAppMediaDownloader {

    private WhatsAppMediaDownloader() {}

    /** Per-message inbound media cap, matching Slack/Telegram. */
    static final int MAX_INBOUND_FILES = 8;

    /**
     * Stage every media part on {@code msg} for {@code binding}'s transport.
     * Empty when the message carries no media.
     */
    public static List<AttachmentService.Input> downloadAll(
            WhatsAppBinding binding, WhatsAppInboundMessage msg, String agentName) {
        if (msg.media() == null || msg.media().isEmpty()) {
            return List.of();
        }
        return switch (binding.transport) {
            case CLOUD_API -> downloadCloudApi(binding, msg, agentName);
            case WHATSAPP_WEB -> downloadCobalt(binding, msg, agentName);
        };
    }

    // Cloud-API Graph media download — implemented in JCLAW-446 (Track A).
    @SuppressWarnings("java:S1172") // params are the contract Track A fills in
    private static List<AttachmentService.Input> downloadCloudApi(
            WhatsAppBinding binding, WhatsAppInboundMessage msg, String agentName) {
        return List.of();
    }

    // WhatsApp-Web (Cobalt) media download — implemented in JCLAW-450 (Track B).
    @SuppressWarnings("java:S1172") // params are the contract Track B fills in
    private static List<AttachmentService.Input> downloadCobalt(
            WhatsAppBinding binding, WhatsAppInboundMessage msg, String agentName) {
        return List.of();
    }
}
