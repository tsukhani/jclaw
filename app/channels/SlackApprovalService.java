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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * <p>The registry is process-local and in-memory: a JVM restart drops pending
 * approvals (the owner's tap then resolves to "no longer pending" and the requester
 * sees a timeout). That matches the ephemeral nature of an in-flight tool call —
 * there is nothing durable to recover.
 */
public final class SlackApprovalService {

    private SlackApprovalService() {}

    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "slack";

    /** How a resolved approval was decided. Mirrors {@link TelegramApprovalService.Outcome}. */
    public enum Outcome { APPROVED_ONCE, APPROVED_SESSION, APPROVED_ALWAYS, DENIED, TIMED_OUT, EXPIRED }

    /** A pending approval awaiting a button tap. {@code messageTs} is the posted prompt's Slack ts. */
    private record Pending(String botToken, String channelId, String messageTs,
                           String authorizedUserId, CompletableFuture<Outcome> future) {}

    private static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<>();

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
        var approvalId = newId();
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
        PENDING.put(approvalId, new Pending(botToken, channelId, messageTs, authorizedUserId, future));
        EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                "Approval %s requested in channel %s".formatted(approvalId, channelId));
        return future;
    }

    /**
     * Block the calling (virtual) thread until the approval resolves or the timeout
     * elapses. On timeout the pending entry is dropped and its prompt is replaced
     * with an "expired" line so the stale buttons can't be tapped afterwards.
     */
    public static Outcome await(CompletableFuture<Outcome> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            PENDING.entrySet().stream()
                    .filter(en -> en.getValue().future() == future)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .ifPresent(id -> expire(id, Outcome.TIMED_OUT));
            future.complete(Outcome.TIMED_OUT);
            return Outcome.TIMED_OUT;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Outcome.TIMED_OUT;
        } catch (ExecutionException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Approval await failed: %s".formatted(e.getMessage()));
            return Outcome.TIMED_OUT;
        }
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
        var pending = PENDING.get(approvalId);
        if (pending == null) {
            return new Resolution(false, Optional.empty(), "This approval is no longer pending.");
        }
        if (pending.authorizedUserId() == null || !pending.authorizedUserId().equals(fromId)) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Rejected approval %s tap from user %s: issued for user %s".formatted(
                            approvalId, fromId, pending.authorizedUserId()));
            return new Resolution(false, Optional.empty(), "This approval was not issued for you.");
        }
        // Won the race? Remove before completing so a double-tap can't resolve twice.
        if (PENDING.remove(approvalId, pending)) {
            var outcome = toOutcome(decision);
            pending.future().complete(outcome);
            swapToStatic(pending, outcome == Outcome.DENIED ? "🚫 Denied." : "✅ Approved.");
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Approval %s resolved as %s by user %s".formatted(approvalId, outcome, fromId));
            return new Resolution(true, Optional.of(outcome),
                    outcome == Outcome.DENIED ? "Denied." : "Approved.");
        }
        return new Resolution(false, Optional.empty(), "This approval is no longer pending.");
    }

    /**
     * Expire a pending approval without a user decision (timeout or shutdown).
     * Completes the future with {@code outcome}, removes the entry, and swaps the
     * prompt for an "expired" line. No-op if already resolved.
     */
    public static void expire(String approvalId, Outcome outcome) {
        var pending = PENDING.remove(approvalId);
        if (pending == null) return;
        pending.future().complete(outcome);
        swapToStatic(pending, "⏲ Approval request expired.");
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
     * Visible only for testing: register a pending entry without the live Slack I/O
     * that {@link #request} performs. Exercises resolve/expire/gating against an
     * in-memory future. {@code botToken} is null so {@link #swapToStatic} no-ops.
     */
    public static CompletableFuture<Outcome> registerForTest(String approvalId, String authorizedUserId) {
        var future = new CompletableFuture<Outcome>();
        PENDING.put(approvalId, new Pending(null, "C0", "1.0", authorizedUserId, future));
        return future;
    }

    // ── Internals ──────────────────────────────────────────────────────

    private static void swapToStatic(Pending pending, String text) {
        if (pending.botToken() == null) return; // test-registered entry: no live message
        SlackWebApi.updateMessageWithBlocks(pending.botToken(), pending.channelId(), pending.messageTs(),
                text, List.of(section(text)));
    }

    private static Outcome toOutcome(SlackApprovalCallback.Decision decision) {
        return switch (decision) {
            case APPROVE_ONCE -> Outcome.APPROVED_ONCE;
            case APPROVE_SESSION -> Outcome.APPROVED_SESSION;
            case APPROVE_ALWAYS -> Outcome.APPROVED_ALWAYS;
            case DENY -> Outcome.DENIED;
        };
    }

    /** First 8 chars of a random UUID — collision-safe for the handful of in-flight approvals. */
    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
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
