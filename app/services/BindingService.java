package services;

import models.AgentBinding;
import models.SlackBinding;
import models.TelegramBinding;
import models.WhatsAppBinding;

/**
 * JCLAW-153: entity-lookup accessors for the channel-binding rows so controllers
 * route their finder calls through the service layer instead of reaching into
 * raw {@code Entity.findById(...)}. Thin passthroughs that rely on the caller's
 * ambient JPA transaction — no {@link Tx} wrapper — matching
 * {@link AgentService#findById}.
 */
public final class BindingService {

    private BindingService() {}

    public static AgentBinding findAgentBindingById(Long id) {
        return AgentBinding.findById(id);
    }

    public static TelegramBinding findTelegramBindingById(Long id) {
        return TelegramBinding.findById(id);
    }

    public static SlackBinding findSlackBindingById(Long id) {
        return SlackBinding.findById(id);
    }

    public static WhatsAppBinding findWhatsAppBindingById(Long id) {
        return WhatsAppBinding.findById(id);
    }
}
