package channels;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.block.element.ButtonElement;
import services.EventLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Generic Slack approve/deny Block Kit workflow (JCLAW-350) — the Slack analog of
 * {@link TelegramApprovalService}. It gives a Slack-primary user the interactive
 * approve/deny surface that {@link agents.DangerousActionGate} already has on
 * Telegram, so dangerous tools (shell exec, approval-flagged MCP servers) prompt
 * for a tap instead of falling to the off-channel allow/deny policy.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>The gate calls {@link #request} with the channel to prompt, the binding's
 *       authorized owner user id (for gating), and a prompt. The service posts a
 *       Block Kit message with approve/deny buttons via
 *       {@link SlackWebApi#postMessageWithBlocks} and registers a pending entry.</li>
 *   <li>The owner taps a button. {@code WebhookSlackController.interactive} verifies
 *       the request signature, parses the {@code sa:}-prefixed {@code action_id},
 *       and calls {@link #resolve}, which gates on the owner id and completes the
 *       pending future.</li>
 *   <li>The gate observes the {@link Outcome} via {@link #await} and proceeds or
 *       aborts.</li>
 * </ol>
 *
 * <p>The shared approve/deny lifecycle (pending registry, resolve, expiry, await)
 * lives in {@link ApprovalRegistry}; this class supplies only the Slack-specific
 * emit (Block Kit), the {@code Decision}→{@code Outcome} mapping, and how the live
 * prompt is swapped to a static confirmation.
 */
public final class SlackApprovalService {

    private SlackApprovalService() {}

    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "slack";

    /** How a resolved approval was decided. Mirrors {@link TelegramApprovalService.Outcome}. */
    public enum Outcome { APPROVED_ONCE, APPROVED_SESSION, APPROVED_ALWAYS, DENIED, TIMED_OUT, EXPIRED }

    private static final ApprovalRegistry<SlackApprovalCallback.Decision, Outcome> REGISTRY =
            new ApprovalRegistry<>(CHANNEL_NAME, SlackApprovalService::toOutcome, o -> o == Outcome.DENIED);

    /**
     * Request an interactive approval. Posts approve/deny buttons to the given
     * channel (optionally threaded) and returns a future that completes when the
     * authorized user taps a button (via {@link #resolve}) or {@link #expire} runs.
     *
     * @param botToken         bot to post through
     * @param channelId        Slack channel to prompt in (the conversation's peer)
     * @param threadTs         thread to post under, or {@code null} for channel level
     * @param authorizedUserId Slack user id allowed to resolve this approval; a tap
     *                         from any other user is rejected
     * @param prompt           mrkdwn-safe prompt describing what is being approved
     * @param allowScopes      when {@code true}, render Once/Session/Always; when
     *                         {@code false}, a single Approve button
     * @return a future for the {@link Outcome}; completed as {@link Outcome#EXPIRED}
     *         immediately if the prompt could not be posted
     */
    public static CompletableFuture<Outcome> request(String botToken, String channelId, String threadTs,
                                                     String authorizedUserId, String prompt,
                                                     boolean allowScopes) {
        var approvalId = ApprovalRegistry.newId();
        var future = new CompletableFuture<Outcome>();
        var blocks = approvalBlocks(approvalId, prompt, allowScopes);
        var fallback = "Approval required: %s".formatted(prompt);
        var messageTs = SlackWebApi.postMessageWithBlocks(botToken, channelId, threadTs, fallback, blocks);
        if (messageTs == null) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Approval prompt failed to post to channel %s; resolving as EXPIRED".formatted(channelId));
            future.complete(Outcome.EXPIRED);
            return future;
        }
        EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                "Approval %s requested in channel %s".formatted(approvalId, channelId));
        return REGISTRY.register(approvalId, authorizedUserId, future, new ApprovalRegistry.LivePrompt() {
            @Override public void onResolved(boolean denied) {
                swapToStatic(botToken, channelId, messageTs, denied ? "🚫 Denied." : "✅ Approved.");
            }
            @Override public void onExpired() {
                swapToStatic(botToken, channelId, messageTs, "⏲ Approval request expired.");
            }
        });
    }

    /**
     * Block the calling (virtual) thread until the approval resolves or the timeout
     * elapses. On timeout the pending entry is dropped and its prompt is replaced
     * with an "expired" line so the stale buttons can't be tapped afterwards.
     */
    public static Outcome await(CompletableFuture<Outcome> future, Duration timeout) {
        return REGISTRY.await(future, timeout, Outcome.TIMED_OUT);
    }

    /**
     * Resolve a pending approval from a button tap. Gates on the bound owner id: a
     * tap from any other user is rejected without resolving (fail closed: a null
     * authorized id rejects everyone). On success the future completes, the entry is
     * removed, and the prompt is swapped for a static confirmation so it can't be
     * tapped again.
     *
     * @param approvalId id parsed from the tapped button's {@code action_id}
     * @param decision   the tapped decision
     * @param fromId     Slack user id of the tapper
     * @return a {@link Resolution} describing what happened
     */
    public static Resolution resolve(String approvalId, SlackApprovalCallback.Decision decision, String fromId) {
        var r = REGISTRY.resolve(approvalId, decision, fromId);
        return new Resolution(r.resolved(), r.outcome(), r.userMessage());
    }

    /**
     * Expire a pending approval without a user decision (timeout or shutdown).
     * Completes the future with {@code outcome}, removes the entry, and swaps the
     * prompt for an "expired" line. No-op if already resolved.
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
     * Visible only for testing: register a pending entry without the live Slack I/O
     * that {@link #request} performs. Exercises resolve/expire/gating against an
     * in-memory future; the entry carries no live message so the swap-to-static
     * no-ops.
     */
    public static CompletableFuture<Outcome> registerForTest(String approvalId, String authorizedUserId) {
        return REGISTRY.registerForTest(approvalId, authorizedUserId);
    }

    // ── Internals ──────────────────────────────────────────────────────

    private static void swapToStatic(String botToken, String channelId, String messageTs, String text) {
        SlackWebApi.updateMessageWithBlocks(botToken, channelId, messageTs, text, List.of(section(text)));
    }

    private static Outcome toOutcome(SlackApprovalCallback.Decision decision) {
        return switch (decision) {
            case APPROVE_ONCE -> Outcome.APPROVED_ONCE;
            case APPROVE_SESSION -> Outcome.APPROVED_SESSION;
            case APPROVE_ALWAYS -> Outcome.APPROVED_ALWAYS;
            case DENY -> Outcome.DENIED;
        };
    }

    /**
     * Build the approval message: a mrkdwn section with the prompt plus an actions
     * row of approve/deny buttons. With {@code allowScopes} the approve options fan
     * out to Once / Session / Always; otherwise a single Approve. The decision + id
     * ride in each button's {@code action_id} (see {@link SlackApprovalCallback}).
     */
    static List<LayoutBlock> approvalBlocks(String approvalId, String prompt, boolean allowScopes) {
        var blocks = new ArrayList<LayoutBlock>();
        blocks.add(section("🔐 *Approval required*\n\n" + prompt));
        List<BlockElement> buttons = new ArrayList<>();
        if (allowScopes) {
            buttons.add(button("✓ Once", SlackApprovalCallback.encodeApproveOnce(approvalId), "primary"));
            buttons.add(button("✓ Session", SlackApprovalCallback.encodeApproveSession(approvalId), null));
            buttons.add(button("✓ Always", SlackApprovalCallback.encodeApproveAlways(approvalId), null));
        } else {
            buttons.add(button("✓ Approve", SlackApprovalCallback.encodeApproveOnce(approvalId), "primary"));
        }
        buttons.add(button("✕ Deny", SlackApprovalCallback.encodeDeny(approvalId), "danger"));
        blocks.add(ActionsBlock.builder().elements(buttons).build());
        return blocks;
    }

    private static SectionBlock section(String mrkdwn) {
        return SectionBlock.builder()
                .text(MarkdownTextObject.builder().text(mrkdwn).build())
                .build();
    }

    private static ButtonElement button(String label, String actionId, String style) {
        var b = ButtonElement.builder()
                .text(PlainTextObject.builder().text(label).emoji(true).build())
                .actionId(actionId);
        if (style != null) b.style(style);
        return b.build();
    }

    /**
     * Result of {@link #resolve}, consumed by the interactivity controller. The
     * message-swap happens inside {@code resolve}/{@code expire}, so the controller
     * only needs to ack 200; this record is for logging/symmetry with Telegram.
     *
     * @param resolved    whether the pending future was completed by this tap
     * @param outcome     the resolved outcome when {@code resolved}; empty otherwise
     * @param userMessage short human-readable status
     */
    public record Resolution(boolean resolved, Optional<Outcome> outcome, String userMessage) {}
}
