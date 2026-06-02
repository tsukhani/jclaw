package channels;

import models.TelegramBinding;
import services.EventLogger;

/**
 * JCLAW-339: keeps each Telegram binding's webhook registration on Telegram in
 * sync with its transport/enabled state. A WEBHOOK + enabled binding (with a
 * public base URL and an auto-generated secret) registers automatically;
 * flipping it back to POLLING, disabling it, or deleting it deregisters it —
 * which long-poll {@code getUpdates} requires, since Telegram returns 409 while
 * a webhook is set.
 *
 * <p>The full webhook URL is {@code base + /api/webhooks/telegram/{id}/{secret}}:
 * the base is the operator-visible public host stored on the binding
 * ({@link TelegramBinding#webhookBaseUrl}, pre-filled from the Tailscale Funnel
 * or the public origin), and the path is fixed by the route + Telegram contract.
 *
 * <p>Telegram persists webhook state server-side across JClaw restarts, so this
 * holds no local registry — Telegram is the source of truth. {@code setWebhook} /
 * {@code deleteWebhook} are idempotent, so the {@link #apply} decision is safe to
 * run on every binding mutation. Callers run it BEFORE
 * {@link TelegramPollingRunner#reconcile()} so a WEBHOOK→POLLING switch clears
 * the webhook before the poller's first {@code getUpdates}, avoiding the 409.
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
            apply(b.id, b.botToken, b.webhookSecret, b.webhookBaseUrl, b.transport, b.enabled, TELEGRAM);
        }
    }

    /** A deleted binding can no longer receive updates — drop its webhook. */
    public static void onBindingDeleted(String botToken) {
        if (botToken != null) TELEGRAM.deleteWebhook(botToken);
    }

    // ── testable core (public for default-package tests) ──

    /** Decide and perform the registration action for one binding's state. */
    public static void apply(Long id, String botToken, String webhookSecret, String baseUrl,
                             ChannelTransport transport, boolean enabled, WebhookApi api) {
        if (transport == ChannelTransport.WEBHOOK && enabled) {
            registerOne(id, botToken, webhookSecret, baseUrl, api);
        } else {
            // POLLING / disabled: ensure Telegram holds no webhook, else the
            // poller's getUpdates would 409. Idempotent if none is set.
            api.deleteWebhook(botToken);
        }
    }

    /**
     * Register {@code base + path} with Telegram, or warn + skip when a
     * prerequisite (secret, public base) is missing. Returns true on success.
     */
    public static boolean registerOne(Long id, String botToken, String webhookSecret,
                                      String baseUrl, WebhookApi api) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            EventLogger.warn(CATEGORY, null, CHANNEL,
                    "Webhook binding %d has no secret — not registered with Telegram".formatted(id));
            return false;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            EventLogger.warn(CATEGORY, null, CHANNEL,
                    "Webhook binding %d has no public base URL — not registered with Telegram".formatted(id));
            return false;
        }
        return api.setWebhook(botToken, webhookUrl(baseUrl, id, webhookSecret), webhookSecret);
    }

    /** The full webhook URL Telegram POSTs to: public base + the routed path. */
    public static String webhookUrl(String baseUrl, Long id, String secret) {
        var base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/api/webhooks/telegram/" + id + "/" + secret;
    }

    // ── Health probe (JCLAW-362) ──

    /**
     * Structured result of a binding health probe. {@code ok} is true only when
     * {@code getMe} (and, for WEBHOOK bindings, {@code getWebhookInfo})
     * succeeded. {@code botUsername} / {@code botId} come from {@code getMe};
     * the {@code webhook*} fields come from {@code getWebhookInfo} and stay null
     * for POLLING bindings. {@code error} carries a human-readable failure
     * reason (e.g. a 401 "Unauthorized" from a bad token) when {@code ok} is
     * false.
     *
     * @param ok                   true when every probed Bot API call succeeded
     * @param transport            the binding's transport at probe time
     * @param botUsername          the bot's @-handle from {@code getMe}, or null
     * @param botId                the bot's numeric id from {@code getMe}, or null
     * @param webhookUrl           the registered webhook URL Telegram holds, or
     *                             null (POLLING, or none set)
     * @param webhookPendingUpdates count of updates Telegram has queued for the
     *                             webhook, or null
     * @param webhookLastError     Telegram's last webhook delivery error message,
     *                             or null when none
     * @param error                failure reason when {@code ok} is false, else null
     */
    public record ProbeResult(boolean ok, String transport, String botUsername,
                              Long botId, String webhookUrl, Integer webhookPendingUpdates,
                              String webhookLastError, String error) {}

    /**
     * Telegram-facing health probe, injectable so tests don't hit the API.
     * Mirrors {@link WebhookApi}: a default impl drives the real Bot API via
     * {@link TelegramChannel#forToken(String)}; tests inject a stub.
     */
    public interface ProbeApi {
        org.telegram.telegrambots.meta.api.objects.User getMe(String botToken)
                throws org.telegram.telegrambots.meta.exceptions.TelegramApiException;
        org.telegram.telegrambots.meta.api.objects.WebhookInfo getWebhookInfo(String botToken)
                throws org.telegram.telegrambots.meta.exceptions.TelegramApiException;
    }

    private static final ProbeApi TELEGRAM_PROBE = new ProbeApi() {
        @Override public org.telegram.telegrambots.meta.api.objects.User getMe(String token)
                throws org.telegram.telegrambots.meta.exceptions.TelegramApiException {
            return TelegramChannel.forToken(token).client()
                    .execute(org.telegram.telegrambots.meta.api.methods.GetMe.builder().build());
        }
        @Override public org.telegram.telegrambots.meta.api.objects.WebhookInfo getWebhookInfo(String token)
                throws org.telegram.telegrambots.meta.exceptions.TelegramApiException {
            return TelegramChannel.forToken(token).client()
                    .execute(org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo.builder().build());
        }
    };

    /** Probe a binding's health against the live Bot API. */
    public static ProbeResult probe(TelegramBinding b) {
        return probe(b.botToken, b.transport, TELEGRAM_PROBE);
    }

    /**
     * Testable core: call {@code getMe} (always) and {@code getWebhookInfo}
     * (WEBHOOK transport only) via {@code api}, mapping any failure to a
     * non-ok {@link ProbeResult} rather than throwing. A bad token surfaces
     * here as a 401 instead of waiting for the next send.
     */
    public static ProbeResult probe(String botToken, ChannelTransport transport, ProbeApi api) {
        var t = transport != null ? transport.name() : ChannelTransport.POLLING.name();
        org.telegram.telegrambots.meta.api.objects.User me;
        try {
            me = api.getMe(botToken);
        } catch (Exception e) {
            return new ProbeResult(false, t, null, null, null, null, null,
                    "getMe failed: " + e.getMessage());
        }
        String username = me != null ? me.getUserName() : null;
        Long botId = me != null ? me.getId() : null;
        if (transport == ChannelTransport.WEBHOOK) {
            try {
                var info = api.getWebhookInfo(botToken);
                return new ProbeResult(true, t, username, botId,
                        info != null ? info.getUrl() : null,
                        info != null ? info.getPendingUpdatesCount() : null,
                        info != null ? info.getLastErrorMessage() : null,
                        null);
            } catch (Exception e) {
                return new ProbeResult(false, t, username, botId, null, null, null,
                        "getWebhookInfo failed: " + e.getMessage());
            }
        }
        return new ProbeResult(true, t, username, botId, null, null, null, null);
    }
}
