package services;

import models.ChannelConfig;
import models.SlackBinding;
import models.TelegramBinding;
import models.WhatsAppBinding;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Aggregates the operator's "what messaging channels are doing work right
 * now" view across the two transport-backed sources of truth in the
 * system:
 *
 * <ul>
 *   <li><b>telegram / slack / whatsapp</b> — per-agent bindings
 *       ({@link TelegramBinding}, {@link SlackBinding}, {@link WhatsAppBinding};
 *       JCLAW-89, JCLAW-444), each carrying its credentials and enabled flag,
 *       NOT {@link ChannelConfig}.</li>
 *   <li><b>any other ChannelConfig-backed transport</b> — enabled flag in
 *       {@link ChannelConfig#enabled}.</li>
 * </ul>
 *
 * <p>The in-app SPA chat ("web") is intentionally NOT counted here: it
 * has no transport row of its own and is implicit in "you have enabled
 * agents," so counting it made the dashboard's "Channels active" stat
 * exceed the channel cards shown on the /channels page (which has no web
 * card). The stat now matches the externally-configured channels the
 * operator can actually see and toggle.
 *
 * <p>The dashboard's "Channels active" stat depends on this aggregation
 * existing in one place — before this service the dashboard was reading
 * only {@code ChannelConfig} and silently missing Telegram bindings, so
 * an operator with a working Telegram bot saw "0 channels active" while
 * the polling runner pulled messages from Telegram's API.
 */
public final class ChannelStatusService {

    private static final String ENABLED_TRUE = "enabled = true";

    private ChannelStatusService() {}

    /**
     * Set of channel types currently doing work. Insertion order is
     * telegram, slack, whatsapp, then anything from {@code ChannelConfig} — gives a stable
     * order for the dashboard's display purposes. The in-app "web" chat is
     * deliberately excluded (see the class Javadoc).
     */
    public static Set<String> activeChannelTypes() {
        return Tx.run(() -> {
            var active = new LinkedHashSet<String>();

            // Telegram: per-binding source of truth. The polling runner
            // iterates TelegramBinding rows; ChannelConfig is never
            // consulted on the Telegram path.
            if (TelegramBinding.count(ENABLED_TRUE) > 0) {
                active.add("telegram");
            }
            if (SlackBinding.count(ENABLED_TRUE) > 0) {
                active.add("slack");
            }
            if (WhatsAppBinding.count(ENABLED_TRUE) > 0) {
                active.add("whatsapp");
            }

            // Any other transport has at most one ChannelConfig
            // row whose enabled flag governs whether the transport
            // accepts inbound messages. Trust the channelType column
            // rather than enumerating known kinds — adding a new
            // ChannelConfig-backed channel just needs to write a ChannelConfig
            // row, no code change here.
            List<ChannelConfig> configs = ChannelConfig.find(ENABLED_TRUE).fetch();
            for (var c : configs) {
                if (c.channelType != null && !c.channelType.isBlank()) {
                    active.add(c.channelType);
                }
            }

            return active;
        });
    }
}
