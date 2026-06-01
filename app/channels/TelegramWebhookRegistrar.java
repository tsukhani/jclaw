package channels;

import models.TelegramBinding;
import services.EventLogger;
import services.TailscaleFunnel;
import services.Tx;

/**
 * JCLAW-339: keeps each Telegram binding's webhook registration on Telegram in
 * sync with its transport/enabled state. Flipping a binding to WEBHOOK (with the
 * Tailscale Funnel live) registers it automatically; flipping it back to POLLING,
 * disabling it, or deleting it deregisters it — which long-poll {@code getUpdates}
 * requires, since Telegram returns 409 while a webhook is set.
 *
 * <p>Telegram persists webhook state server-side across JClaw restarts, so this
 * holds no local registry — Telegram is the source of truth. {@code setWebhook} /
 * {@code deleteWebhook} are idempotent, so the {@link #apply} decision is safe to
 * run on every binding mutation.
 *
 * <p>Callers run the deregister path BEFORE {@link TelegramPollingRunner#reconcile()}
 * so a WEBHOOK→POLLING switch clears the webhook before the poller's first
 * {@code getUpdates}, avoiding the 409.
 */
public final class TelegramWebhookRegistrar {

    private static final String CATEGORY = "channel";
    private static final String CHANNEL = "telegram";

    /** Telegram-facing operations, injectable so tests don't hit the API. */
    public interface WebhookApi {
        boolean setWebhook(String botToken, String url, String secretToken);
        boolean deleteWebhook(String botToken);
    }

    private static final WebhookApi TELEGRAM = new WebhookApi() {
        @Override public boolean setWebhook(String token, String url, String secretToken) {
            return TelegramChannel.setWebhook(token, url, secretToken);
        }
        @Override public boolean deleteWebhook(String token) {
            return TelegramChannel.deleteWebhook(token);
        }
    };

    private TelegramWebhookRegistrar() {}

    /** Reconcile a single binding's webhook registration after a create/update. */
    public static void onBindingSaved(TelegramBinding b) {
        if (b != null) {
            apply(b.id, b.botToken, b.webhookSecret, b.transport, b.enabled,
                    TailscaleFunnel.publicBaseUrl(), TELEGRAM);
        }
    }

    /** A deleted binding can no longer receive updates — drop its webhook. */
    public static void onBindingDeleted(String botToken) {
        if (botToken != null) TELEGRAM.deleteWebhook(botToken);
    }

    /**
     * (Re)register every enabled WEBHOOK binding — invoked when the funnel comes
     * online so bindings configured while it was offline get picked up.
     */
    public static void registerAllEnabled() {
        var base = TailscaleFunnel.publicBaseUrl();
        var bindings = Tx.run(() ->
                TelegramBinding.findAllEnabledByTransport(ChannelTransport.WEBHOOK));
        for (var b : bindings) {
            registerOne(b.id, b.botToken, b.webhookSecret, base, TELEGRAM);
        }
    }

    // ── testable core (public for default-package tests) ──

    /** Decide and perform the registration action for one binding's state. */
    public static void apply(Long id, String botToken, String webhookSecret,
                             ChannelTransport transport, boolean enabled,
                             String funnelBase, WebhookApi api) {
        if (transport == ChannelTransport.WEBHOOK && enabled) {
            registerOne(id, botToken, webhookSecret, funnelBase, api);
        } else {
            // POLLING / disabled: ensure Telegram holds no webhook, else the
            // poller's getUpdates would 409. Idempotent if none is set.
            api.deleteWebhook(botToken);
        }
    }

    /**
     * Register the funnel-derived URL with Telegram, or warn + skip when a
     * prerequisite (secret, live funnel) is missing. Returns true on success.
     */
    public static boolean registerOne(Long id, String botToken, String webhookSecret,
                                      String funnelBase, WebhookApi api) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            EventLogger.warn(CATEGORY, null, CHANNEL,
                    "Webhook binding %d has no secret — not registered with Telegram".formatted(id));
            return false;
        }
        if (funnelBase == null) {
            EventLogger.warn(CATEGORY, null, CHANNEL,
                    "Webhook binding %d enabled but Tailscale Funnel is offline — not registered with Telegram".formatted(id));
            return false;
        }
        return api.setWebhook(botToken, webhookUrl(funnelBase, id, webhookSecret), webhookSecret);
    }

    /** The webhook URL Telegram POSTs to: funnel base + the routed binding path. */
    public static String webhookUrl(String funnelBase, Long id, String secret) {
        return funnelBase + "/api/webhooks/telegram/" + id + "/" + secret;
    }
}
