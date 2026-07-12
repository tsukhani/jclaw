package channels;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import services.EventLogger;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
 * <p>The shared approve/deny lifecycle (pending registry, resolve, expiry,
 * await) lives in {@link ApprovalRegistry}; this class supplies only the
 * Telegram-specific emit (inline keyboard), the {@code Decision}→{@code Outcome}
 * mapping, and how the live prompt is retired.
 */
public final class TelegramApprovalService {

    private TelegramApprovalService() {}

    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "telegram";

    /** How a resolved approval was decided. */
    public enum Outcome { APPROVED_ONCE, APPROVED_SESSION, APPROVED_ALWAYS, DENIED, TIMED_OUT, EXPIRED }

    private static final ApprovalRegistry<TelegramApprovalCallback.Decision, Outcome> REGISTRY =
            new ApprovalRegistry<>(CHANNEL_NAME, TelegramApprovalService::toOutcome, o -> o == Outcome.DENIED);

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
        var approvalId = ApprovalRegistry.newId();
        var future = new CompletableFuture<Outcome>();
        var keyboard = keyboard(approvalId, allowScopes);
        var messageId = TelegramChannel.forToken(botToken).sendMessageWithKeyboard(chatId, prompt, keyboard);
        if (messageId == null) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Approval prompt failed to send to chat %s; resolving as EXPIRED".formatted(chatId));
            future.complete(Outcome.EXPIRED);
            return future;
        }
        EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                "Approval %s requested in chat %s".formatted(approvalId, chatId));
        return REGISTRY.register(approvalId, authorizedUserId, future, new ApprovalRegistry.LivePrompt() {
            @Override public void onResolved(boolean denied) {
                // The callback dispatcher edits the tapped message itself on Telegram.
            }
            @Override public void onExpired() {
                TelegramChannel.editMessageText(botToken, chatId, messageId, "⏲ Approval request expired.", null);
            }
        });
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
        return REGISTRY.await(future, timeout, Outcome.TIMED_OUT);
    }

    /**
     * Resolve a pending approval from a callback tap. Gates on the bound
     * user id (mirrors the {@code /model} callback gating): a tap from a
     * user the approval wasn't issued for is rejected without resolving.
     *
     * @param approvalId id from the parsed callback payload
     * @param decision   the tapped decision
     * @param fromId     Telegram user id of the tapper (callback {@code fromId})
     * @return a {@link Resolution} describing what happened — the dispatcher
     *         uses it to answer the callback query appropriately
     */
    public static Resolution resolve(String approvalId, TelegramApprovalCallback.Decision decision, String fromId) {
        var r = REGISTRY.resolve(approvalId, decision, fromId);
        return new Resolution(r.resolved(), r.outcome(), r.userMessage());
    }

    /**
     * Expire a pending approval without a user decision (timeout or
     * shutdown). Completes the future with {@code outcome} and removes the
     * entry. No-op if already resolved.
     */
    public static void expire(String approvalId, Outcome outcome) {
        REGISTRY.expire(approvalId, outcome);
    }

    /** Visible only for testing: whether an approval id is still pending. */
    public static boolean isPending(String approvalId) {
        return REGISTRY.isPending(approvalId);
    }

    /** Visible only for testing: drop every pending entry. */
    public static void clearAll() {
        REGISTRY.clearAll();
    }

    /**
     * Visible only for testing: register a pending entry without the live
     * Telegram I/O that {@link #request} performs. Exercises the
     * resolve/expire/gating logic against an in-memory future.
     */
    public static CompletableFuture<Outcome> registerForTest(String approvalId, String authorizedUserId) {
        return REGISTRY.registerForTest(approvalId, authorizedUserId);
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
