package jobs;

import channels.TelegramChannel;
import models.Conversation;
import models.TelegramBinding;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.Tx;

import java.util.List;

/**
 * JCLAW-95: finalize Telegram streaming placeholders left orphaned by a JVM
 * crash or restart.
 *
 * <p>When the {@code TelegramStreamingSink} sends its initial placeholder
 * message it writes the (messageId, chatId) onto the {@code Conversation}
 * row. On seal / errorFallback the columns are cleared. If the process dies
 * between those two points the placeholder sits on Telegram indefinitely
 * as "partial text..." — unsealed, unedited, confusing.
 *
 * <p>This job runs once at application startup. It scans for conversations
 * with a non-null {@code activeStreamMessageId}, looks up the agent's
 * {@link TelegramBinding} for its bot token, and edits each orphan with a
 * short note so the user sees closure on the interrupted turn rather than
 * a ghost message. Failures per row are swallowed — a stale placeholder
 * that Telegram no longer knows about (e.g., user deleted the chat) just
 * produces a logged warning and we continue.
 *
 * <p>Runs AFTER {@link DefaultConfigJob} (both {@code @OnApplicationStart};
 * Play's jobs don't guarantee order, but both depend only on JPA being
 * ready, which is the precondition Play satisfies before any
 * {@code @OnApplicationStart} runs).
 */
@OnApplicationStart
public class TelegramStreamingRecoveryJob extends Job<Void> {

    /** User-facing text edited into each orphaned placeholder. */
    static final String INTERRUPT_NOTE =
            "This response was interrupted — please send your message again.";

    @Override
    public void doJob() {
        recoverAll();
    }

    /**
     * Visible for tests so they can invoke the recovery pass synchronously
     * without waiting for a JVM restart.
     */
    public static void recoverAll() {
        List<Conversation> orphans = Tx.run(() -> Conversation.<Conversation>find(
                "activeStreamMessageId IS NOT NULL").fetch());
        if (orphans.isEmpty()) return;

        EventLogger.info("channel", null, "telegram",
                "Recovery: %d orphaned streaming placeholder(s) found".formatted(orphans.size()));

        for (var orphan : orphans) {
            try {
                recoverOne(orphan);
            } catch (Exception e) {
                // Per-row failures must not stop the batch. Most commonly the
                // user deleted the chat / message and Telegram 400s us.
                EventLogger.warn("channel",
                        orphan.agent != null ? orphan.agent.name : null, "telegram",
                        "Recovery failed for conversation %d (messageId=%s): %s"
                                .formatted(orphan.id, orphan.activeStreamMessageId, e.getMessage()));
            }
        }
    }

    private static void recoverOne(Conversation orphan) throws Exception {
        // Clear the checkpoint first in a separate transaction. If the
        // editMessageText below fails, we still want the column cleared so
        // the next boot doesn't re-try indefinitely on a placeholder
        // Telegram will never accept again.
        final Long convId = orphan.id;
        final Integer messageId = orphan.activeStreamMessageId;
        final String chatId = orphan.activeStreamChatId;
        final var agent = orphan.agent;

        Tx.run(() -> {
            var row = (Conversation) Conversation.findById(convId);
            if (row != null) {
                row.activeStreamMessageId = null;
                row.activeStreamChatId = null;
                row.save();
            }
        });

        if (messageId == null || chatId == null || agent == null) return;

        // Look up the binding to get the bot token. Disabled bindings are
        // skipped — the placeholder stays visible but we can't edit it
        // without a live token, and re-enabling the binding later won't
        // help (messageId is long gone).
        var binding = Tx.run(() -> TelegramBinding.findByAgent(agent));
        if (binding == null || !binding.enabled) {
            EventLogger.info("channel", agent.name, "telegram",
                    "Recovery: skipping orphan for conversation %d — no enabled binding"
                            .formatted(convId));
            return;
        }

        TelegramChannel.editMessageText(binding.botToken, chatId, messageId, INTERRUPT_NOTE);

        EventLogger.info("channel", agent.name, "telegram",
                "Recovery: interrupted-note written to orphan messageId=%d for conversation %d"
                        .formatted(messageId, convId));
    }
}
