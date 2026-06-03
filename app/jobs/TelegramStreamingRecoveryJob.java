package jobs;

import channels.TelegramChannel;
import models.Conversation;
import models.TelegramBinding;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.Tx;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private static final String EVENT_CATEGORY_CHANNEL = "channel";
    private static final String CHANNEL_TELEGRAM = "telegram";

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

        EventLogger.info(EVENT_CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                "Recovery: %d orphaned streaming placeholder(s) found".formatted(orphans.size()));

        // Fetch all enabled bindings once (agent_id is unique → at most one
        // binding per agent) instead of a per-orphan findByAgent lookup. A
        // disabled binding is simply absent here, which the per-orphan check
        // below treats the same as before (no enabled binding → skip).
        Map<Long, String> tokensByAgentId = Tx.run(() -> TelegramBinding.findAllEnabled().stream()
                .collect(Collectors.toMap(b -> b.agent.id, b -> b.botToken, (first, dup) -> first)));

        for (var orphan : orphans) {
            try {
                recoverOne(orphan, tokensByAgentId);
            } catch (Exception e) {
                // Per-row failures must not stop the batch. Most commonly the
                // user deleted the chat / message and Telegram 400s us.
                EventLogger.warn(EVENT_CATEGORY_CHANNEL,
                        orphan.agent != null ? orphan.agent.name : null, CHANNEL_TELEGRAM,
                        "Recovery failed for conversation %d (messageId=%s): %s"
                                .formatted(orphan.id, orphan.activeStreamMessageId, e.getMessage()));
            }
        }
    }

    private static void recoverOne(Conversation orphan, Map<Long, String> tokensByAgentId)
            throws TelegramApiException {
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

        // Resolve the bot token from the pre-fetched enabled-binding map.
        // A missing entry means the agent has no enabled binding — the
        // placeholder stays visible but we can't edit it without a live
        // token, and re-enabling the binding later won't help (messageId is
        // long gone).
        var botToken = tokensByAgentId.get(agent.id);
        if (botToken == null) {
            EventLogger.info(EVENT_CATEGORY_CHANNEL, agent.name, CHANNEL_TELEGRAM,
                    "Recovery: skipping orphan for conversation %d — no enabled binding"
                            .formatted(convId));
            return;
        }

        TelegramChannel.editMessageText(botToken, chatId, messageId, INTERRUPT_NOTE);

        EventLogger.info(EVENT_CATEGORY_CHANNEL, agent.name, CHANNEL_TELEGRAM,
                "Recovery: interrupted-note written to orphan messageId=%d for conversation %d"
                        .formatted(messageId, convId));
    }
}
