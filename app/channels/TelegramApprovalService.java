package channels;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import services.EventLogger;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Generic Telegram approve/deny button workflow (JCLAW-373).
 *
 * <p>The {@code /model} selector (JCLAW-109) proved out the inline-keyboard
 * + {@code callback_query} plumbing, but only that one feature used it.
 * This service generalises it so any code path — a dangerous tool, an
 * {@code exec} call, a privileged slash command — can surface an
 * interactive approve/deny prompt to the bound user and block until they
 * tap a button (or it times out).
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>The requesting code calls {@link #request} with the chat to prompt,
 *       the bound user's Telegram id (for gating), and a human-readable
 *       prompt. The service renders approve/deny buttons via
 *       {@link TelegramChannel#sendMessageWithKeyboard} and registers a
 *       pending entry keyed by a fresh approval id.</li>
 *   <li>The user taps a button. {@link TelegramCallbackDispatcher} parses
 *       the {@code a:}-prefixed callback and calls {@link #resolve}, which
 *       gates on the bound user id and completes the pending future.</li>
 *   <li>The requesting code observes the {@link Outcome} on the returned
 *       future (or via the blocking {@link #await} helper) and proceeds or
 *       aborts.</li>
 * </ol>
 *
 * <p>The registry is process-local and in-memory: a JVM restart drops
 * pending approvals (the user's tap then resolves to "no longer pending"
 * and the requester sees a timeout). That matches the ephemeral nature of
 * an in-flight tool call — there is nothing durable to recover.
 */
public final class TelegramApprovalService {

    private TelegramApprovalService() {}

    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "telegram";

    /** How a resolved approval was decided. */
    public enum Outcome { APPROVED_ONCE, APPROVED_SESSION, APPROVED_ALWAYS, DENIED, TIMED_OUT, EXPIRED }

    /** A pending approval awaiting a button tap. */
    private record Pending(String botToken, String chatId, Integer messageId,
                           String authorizedUserId, CompletableFuture<Outcome> future) {}

    private static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<>();

    /**
     * Request an interactive approval. Renders approve/deny buttons in the
     * given chat and returns a future that completes when the bound user
     * taps a button (via {@link #resolve}) or {@link #expire} is called.
     *
     * @param botToken         bot to send through
     * @param chatId           chat to prompt (the bound user's private chat)
     * @param authorizedUserId Telegram user id allowed to resolve this
     *                         approval; a tap from any other user is rejected
     * @param prompt           HTML-safe prompt text describing what is being
     *                         approved (e.g. the tool name and arguments)
     * @param allowScopes      when {@code true}, render session/always
     *                         buttons in addition to once; when {@code false}
     *                         render only a single Approve button
     * @return a future for the {@link Outcome}; empty future-result only if
     *         the prompt could not be sent (treated as {@link Outcome#EXPIRED})
     */
    public static CompletableFuture<Outcome> request(String botToken, String chatId,
                                                     String authorizedUserId, String prompt,
                                                     boolean allowScopes) {
        var approvalId = newId();
        var future = new CompletableFuture<Outcome>();
        var keyboard = keyboard(approvalId, allowScopes);
        var messageId = TelegramChannel.sendMessageWithKeyboard(botToken, chatId, prompt, keyboard);
        if (messageId == null) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Approval prompt failed to send to chat %s; resolving as EXPIRED".formatted(chatId));
            future.complete(Outcome.EXPIRED);
            return future;
        }
        PENDING.put(approvalId, new Pending(botToken, chatId, messageId, authorizedUserId, future));
        EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                "Approval %s requested in chat %s".formatted(approvalId, chatId));
        return future;
    }

    /**
     * Block the calling (virtual) thread until the approval resolves or the
     * timeout elapses. On timeout the pending entry is dropped and the
     * keyboard is cleared so the stale prompt can't be tapped afterwards.
     *
     * @return the {@link Outcome}; {@link Outcome#TIMED_OUT} if no tap
     *         arrived within {@code timeout}
     */
    public static Outcome await(CompletableFuture<Outcome> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Find and expire the entry this future belongs to.
            PENDING.entrySet().stream()
                    .filter(en -> en.getValue().future() == future)
                    .map(java.util.Map.Entry::getKey)
                    .findFirst()
                    .ifPresent(id -> expire(id, Outcome.TIMED_OUT));
            future.complete(Outcome.TIMED_OUT);
            return Outcome.TIMED_OUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.TIMED_OUT;
        } catch (ExecutionException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Approval await failed: %s".formatted(e.getMessage()));
            return Outcome.TIMED_OUT;
        }
    }

    /**
     * Resolve a pending approval from a callback tap. Gates on the bound
     * user id (mirrors the {@code /model} callback gating): a tap from a
     * user the approval wasn't issued for is rejected without resolving.
     *
     * <p>On success the pending future completes, the entry is removed, and
     * the originating message's keyboard is replaced with a plain
     * confirmation line so it can't be tapped again.
     *
     * @param approvalId id from the parsed callback payload
     * @param decision   the tapped decision
     * @param fromId     Telegram user id of the tapper (callback {@code fromId})
     * @return a {@link Resolution} describing what happened — the dispatcher
     *         uses it to answer the callback query appropriately
     */
    public static Resolution resolve(String approvalId, TelegramApprovalCallback.Decision decision, String fromId) {
        var pending = PENDING.get(approvalId);
        if (pending == null) {
            return new Resolution(false, Optional.empty(),
                    "This approval is no longer pending.");
        }
        if (pending.authorizedUserId() != null && !pending.authorizedUserId().equals(fromId)) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Rejected approval %s tap from user %s: issued for user %s".formatted(
                            approvalId, fromId, pending.authorizedUserId()));
            return new Resolution(false, Optional.empty(),
                    "This approval was not issued for you.");
        }
        // Won the race? Remove before completing so a double-tap can't
        // resolve twice.
        if (PENDING.remove(approvalId, pending)) {
            var outcome = toOutcome(decision);
            pending.future().complete(outcome);
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Approval %s resolved as %s by user %s".formatted(approvalId, outcome, fromId));
            return new Resolution(true, Optional.of(outcome),
                    outcome == Outcome.DENIED ? "Denied." : "Approved.");
        }
        return new Resolution(false, Optional.empty(),
                "This approval is no longer pending.");
    }

    /**
     * Expire a pending approval without a user decision (timeout or
     * shutdown). Completes the future with {@code outcome} and removes the
     * entry. No-op if already resolved.
     */
    public static void expire(String approvalId, Outcome outcome) {
        var pending = PENDING.remove(approvalId);
        if (pending == null) return;
        pending.future().complete(outcome);
        if (pending.messageId() != null) {
            TelegramChannel.editMessageText(pending.botToken(), pending.chatId(),
                    pending.messageId(), "⏲ Approval request expired.", null);
        }
    }

    /** Visible only for testing: whether an approval id is still pending. */
    public static boolean isPending(String approvalId) {
        return PENDING.containsKey(approvalId);
    }

    /** Visible only for testing: drop every pending entry. */
    public static void clearAll() {
        PENDING.clear();
    }

    /**
     * Visible only for testing: register a pending entry without the live
     * Telegram I/O that {@link #request} performs. Exercises the
     * resolve/expire/gating logic against an in-memory future.
     */
    public static CompletableFuture<Outcome> registerForTest(String approvalId, String authorizedUserId) {
        var future = new CompletableFuture<Outcome>();
        PENDING.put(approvalId, new Pending(null, "chat", null, authorizedUserId, future));
        return future;
    }

    // ── Internals ──────────────────────────────────────────────────────

    private static Outcome toOutcome(TelegramApprovalCallback.Decision decision) {
        return switch (decision) {
            case APPROVE_ONCE -> Outcome.APPROVED_ONCE;
            case APPROVE_SESSION -> Outcome.APPROVED_SESSION;
            case APPROVE_ALWAYS -> Outcome.APPROVED_ALWAYS;
            case DENY -> Outcome.DENIED;
        };
    }

    /**
     * Short, URL-safe-ish approval id. The first segment of a random UUID
     * gives 32 bits of entropy in 8 chars — collision-safe for the handful
     * of in-flight approvals a single bound user can have, and tiny against
     * the 64-byte callback_data budget.
     */
    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Build the approve/deny keyboard. With {@code allowScopes}, the approve
     * options fan out to Once / Session / Always on their own row; the Deny
     * button always sits on the final row.
     */
    static InlineKeyboardMarkup keyboard(String approvalId, boolean allowScopes) {
        if (allowScopes) {
            var approveRow = new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("✓ Once")
                            .callbackData(TelegramApprovalCallback.encodeApproveOnce(approvalId))
                            .build(),
                    InlineKeyboardButton.builder()
                            .text("✓ Session")
                            .callbackData(TelegramApprovalCallback.encodeApproveSession(approvalId))
                            .build(),
                    InlineKeyboardButton.builder()
                            .text("✓ Always")
                            .callbackData(TelegramApprovalCallback.encodeApproveAlways(approvalId))
                            .build()));
            var denyRow = new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("✕ Deny")
                    .callbackData(TelegramApprovalCallback.encodeDeny(approvalId))
                    .build());
            return InlineKeyboardMarkup.builder().keyboardRow(approveRow).keyboardRow(denyRow).build();
        }
        var row = new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("✓ Approve")
                        .callbackData(TelegramApprovalCallback.encodeApproveOnce(approvalId))
                        .build(),
                InlineKeyboardButton.builder()
                        .text("✕ Deny")
                        .callbackData(TelegramApprovalCallback.encodeDeny(approvalId))
                        .build()));
        return InlineKeyboardMarkup.builder().keyboardRow(row).build();
    }

    /**
     * Result of {@link #resolve}, consumed by the dispatcher to answer the
     * callback query.
     *
     * @param resolved   whether the pending future was completed by this tap
     * @param outcome    the resolved outcome when {@code resolved}; empty
     *                   otherwise
     * @param userMessage short message for {@code answerCallbackQuery}
     */
    public record Resolution(boolean resolved, Optional<Outcome> outcome, String userMessage) {}
}
