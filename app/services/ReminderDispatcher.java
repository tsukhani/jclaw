package services;

import channels.TelegramChannel;
import models.Notification;
import models.Task;
import models.TaskRun;
import models.TelegramBinding;

/**
 * Routes reminder-task fires (tasks with {@code payloadType="reminder"})
 * to the right user-facing surface based on the configured
 * {@link Task#delivery} channel:
 *
 * <ul>
 *   <li><b>web</b> → writes a {@link Notification} row that the global
 *       {@code NotificationBar} toast component picks up on its next
 *       poll. Reminders never append to a {@code Message} row, so they
 *       don't pollute conversation history or enter the LLM's context
 *       on subsequent turns.</li>
 *   <li><b>telegram</b> → sends a regular Bot API message via the
 *       agent's {@link TelegramBinding}, prefixed with a bell-emoji
 *       framing so the user can distinguish it from agent replies in a
 *       busy chat.</li>
 * </ul>
 *
 * <p>Unsupported channels (slack, whatsapp) return a failed result —
 * those targets need a per-channel "notification" surface that doesn't
 * exist yet, and the message tool's chat-append behaviour wouldn't be
 * right for a reminder (the LLM would see it on the next turn).
 *
 * <p>Companion to {@link DeliveryDispatcher} — both implement the
 * "post-completion delivery" boundary in {@link TaskExecutor#dispatchDelivery},
 * but {@code ReminderDispatcher} is the right one to call when
 * {@link TaskExecutor#isReminder} is true.
 */
public final class ReminderDispatcher {

    /** Framing prefix prepended to the reminder body for Telegram sends so
     *  the user can distinguish the reminder from regular agent replies in
     *  their chat scrollback. Web reminders don't need framing because the
     *  notification toast surface itself signals "this is a reminder". */
    private static final String TELEGRAM_FRAMING = "🔔 Reminder: ";

    private static final String TELEGRAM = "telegram";

    private ReminderDispatcher() {}

    /**
     * Route a reminder fire to its configured channel surface. Must run
     * inside an active JPA Tx (the {@link TaskExecutor#dispatchDelivery}
     * caller wraps the call).
     *
     * @param task    the firing reminder task — used for agent context
     *                and delivery-spec parsing
     * @param run     the closed TaskRun — linked from the resulting
     *                {@link Notification#sourceTaskRunId}
     * @param spec    the {@code <channel>:<target>} delivery spec
     *                copied from {@link Task#delivery}
     * @param content the reminder body, verbatim from the task
     *                description (already validated non-blank by
     *                {@code dispatchDelivery})
     * @return delivery outcome — {@code ok=true} when the notification or
     *         telegram send landed; failure reasons are human-readable
     *         and stamped on the TaskRun by the caller
     */
    public static DeliveryDispatcher.DispatchResult dispatch(Task task, TaskRun run,
                                                              String spec, String content) {
        var idx = spec.indexOf(':');
        var channel = (idx > 0 ? spec.substring(0, idx) : spec).toLowerCase();
        var target = idx > 0 && idx < spec.length() - 1 ? spec.substring(idx + 1) : null;

        return switch (channel) {
            case "web" -> dispatchWeb(task, run, content);
            case TELEGRAM -> dispatchTelegram(task, target, content);
            default -> DeliveryDispatcher.DispatchResult.unsupported(channel);
        };
    }

    private static DeliveryDispatcher.DispatchResult dispatchWeb(Task task, TaskRun run, String content) {
        if (task.agent == null) {
            return DeliveryDispatcher.DispatchResult.failedDelivery(
                    "Reminder web dispatch requires an agent context.");
        }
        var n = new Notification();
        n.agent = task.agent;
        n.content = content;
        n.sourceTaskRunId = run.id;
        n.sourceTaskId = task.id;
        n.save();
        return DeliveryDispatcher.DispatchResult.delivered();
    }

    private static DeliveryDispatcher.DispatchResult dispatchTelegram(Task task, String chatId, String content) {
        if (task.agent == null) {
            return DeliveryDispatcher.DispatchResult.failedDelivery(
                    "Reminder telegram dispatch requires an agent context for per-binding bot-token lookup.");
        }
        if (chatId == null || chatId.isBlank()) {
            return DeliveryDispatcher.DispatchResult.failedDelivery(
                    "Reminder telegram dispatch requires a chat id (delivery spec was 'telegram' with no target).");
        }
        var binding = TelegramBinding.findByAgentOrAncestor(task.agent);
        if (binding == null) {
            return DeliveryDispatcher.DispatchResult.noConfig(TELEGRAM,
                    "Connect a Telegram bot for agent '" + task.agent.name
                            + "' (or any of its ancestors) in Settings → Channels → Telegram, "
                            + "or via POST /api/telegram-bindings.");
        }
        if (!binding.enabled) {
            return DeliveryDispatcher.DispatchResult.noConfig(TELEGRAM,
                    "Telegram binding for agent '" + binding.agent.name + "' is disabled.");
        }
        return TelegramChannel.sendMessage(binding.botToken, chatId,
                TELEGRAM_FRAMING + content, task.agent)
                ? DeliveryDispatcher.DispatchResult.delivered()
                : DeliveryDispatcher.DispatchResult.failedDelivery(
                        "Telegram API rejected the reminder (see logs for details).");
    }
}
