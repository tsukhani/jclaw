package channels;

/**
 * Inbound transport mode a channel uses to receive updates from the upstream
 * platform. Defaults to {@link #POLLING} when the config field is absent.
 *
 * <p>Polling modes (POLLING, SOCKET) do not require a public URL — the jclaw
 * process initiates the connection outbound. Webhook modes (WEBHOOK, HTTP)
 * require the platform to reach jclaw over HTTPS, which typically means a
 * reverse proxy, a tunnel (cloudflared / Tailscale Funnel), or a public VPS.
 *
 * <p>Which values are valid depends on the channel:
 * <ul>
 *   <li>Telegram: {@link #POLLING} (default), {@link #WEBHOOK}</li>
 *   <li>Slack: {@link #SOCKET} (default — JCLAW-83), {@link #HTTP} (JCLAW-83)</li>
 *   <li>WhatsApp: {@link #WEBHOOK} only (Meta Cloud API)</li>
 * </ul>
 */
public enum ChannelTransport {
    POLLING,
    WEBHOOK,
    SOCKET,
    HTTP;

    /** Parse a config value; null/blank/unknown → {@code fallback}. */
    public static ChannelTransport parse(String value, ChannelTransport fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return ChannelTransport.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException _) {
            return fallback;
        }
    }

    public boolean requiresPublicUrl() {
        return this == WEBHOOK || this == HTTP;
    }
}
