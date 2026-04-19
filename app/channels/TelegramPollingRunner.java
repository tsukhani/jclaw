package channels;

import agents.AgentRunner;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import services.EventLogger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Long-polling runner for the Telegram channel. Uses the SDK's
 * {@link TelegramBotsLongPollingApplication} to own the {@code getUpdates} loop
 * on a background thread and dispatch each update through
 * {@link #consume(Update)}.
 *
 * <p>Holds at most one active bot registration at a time. {@link #reconcile()}
 * is the single entry point — idempotent, safe to call at startup, after admin
 * config saves, and at shutdown. When the channel is disabled or switched to
 * {@link ChannelTransport#WEBHOOK}, the runner stops cleanly.
 */
public final class TelegramPollingRunner implements LongPollingSingleThreadUpdateConsumer {

    private static final TelegramPollingRunner INSTANCE = new TelegramPollingRunner();

    private static final AtomicReference<TelegramBotsLongPollingApplication> APP = new AtomicReference<>();
    private static final AtomicReference<String> ACTIVE_TOKEN = new AtomicReference<>();

    private TelegramPollingRunner() {}

    /**
     * Reconcile the runner's state against {@link TelegramChannel.TelegramConfig}:
     * start when enabled + transport=POLLING and token differs, stop when disabled
     * or mode changed. No-op when desired state already matches.
     */
    public static synchronized void reconcile() {
        var config = TelegramChannel.TelegramConfig.load();
        boolean shouldRun = config != null && config.transport() == ChannelTransport.POLLING;
        String targetToken = shouldRun ? config.botToken() : null;
        String currentToken = ACTIVE_TOKEN.get();

        if (Objects.equals(currentToken, targetToken)) return;

        stopInternal();

        if (targetToken == null) return;

        try {
            var app = new TelegramBotsLongPollingApplication();
            app.registerBot(targetToken, INSTANCE);
            if (!app.isRunning()) app.start();
            APP.set(app);
            ACTIVE_TOKEN.set(targetToken);
            EventLogger.info("channel", null, "telegram", "Long-polling runner started");
        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram",
                    "Failed to start polling runner: %s".formatted(e.getMessage()));
            stopInternal();
        }
    }

    /** Stop any active runner. Safe to call at app shutdown. */
    public static synchronized void stop() {
        stopInternal();
    }

    /** Test-visible: is a runner currently active? */
    public static boolean isRunning() {
        var app = APP.get();
        return app != null && app.isRunning();
    }

    private static void stopInternal() {
        var app = APP.getAndSet(null);
        var token = ACTIVE_TOKEN.getAndSet(null);
        if (app == null) return;
        try {
            if (token != null) app.unregisterBot(token);
            app.close();
            EventLogger.info("channel", null, "telegram", "Long-polling runner stopped");
        } catch (Exception e) {
            EventLogger.warn("channel", null, "telegram",
                    "Polling runner shutdown error: %s".formatted(e.getMessage()));
        }
    }

    @Override
    public void consume(Update update) {
        try {
            var msg = TelegramChannel.parseUpdate(update);
            if (msg == null) return;

            EventLogger.info("channel", null, "telegram",
                    "Polling received from %s: %s".formatted(
                            msg.fromUsername() != null ? msg.fromUsername() : msg.fromId(),
                            truncate(msg.text())));

            AgentRunner.processWebhookMessage("telegram", msg.chatId(), msg.text(),
                    (peer, resp) -> TelegramChannel.sendMessage(peer, resp),
                    peer -> TelegramChannel.sendMessage(peer, "No agent configured for this chat."));
        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram",
                    "Polling update processing error: %s".formatted(e.getMessage()));
        }
    }

    private static String truncate(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
