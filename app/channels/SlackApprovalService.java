package channels;

import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.element.BlockElement;
import com.slack.api.model.block.element.ButtonElement;
import org.jspecify.annotations.Nullable;
import services.EventLogger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
 *       authorized owner user id (for gating), and a prompt, in as many messages as
 *       it needs. The service posts them in order via
 *       {@link SlackWebApi#postMessageWithBlocks}, the last as a Block Kit message with
 *       approve/deny buttons, and registers a pending entry.</li>
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

    /** Block Kit section block: its text holds at most 3000 characters. From the docs, not measured by a post. */
    public static final int SECTION_MAX_CHARS = 3000;

    /**
     * Request an interactive approval. Posts {@code parts} to the given channel in order (optionally threaded),
     * with the approve/deny buttons on the last, and returns a future that completes when the authorized user taps
     * a button (via {@link #resolve}) or {@link #expire} runs. The buttons only ever follow the whole prompt
     * (JF-294): when a part is over {@link #SECTION_MAX_CHARS} nothing is posted, and when a part fails to post the
     * rest is not posted, so there is nothing to tap.
     *
     * @param botToken         bot to post through
     * @param channelId        Slack channel to prompt in (the conversation's peer)
     * @param threadTs         thread to post under, or {@code null} for channel level
     * @param authorizedUserId Slack user id allowed to resolve this approval; a tap
     *                         from any other user is rejected
     * @param parts            mrkdwn-safe prompt describing what is being approved, one entry a message
     * @param allowScopes      when {@code true}, render Once/Session/Always; when
     *                         {@code false}, a single Approve button
     * @return a future for the {@link Outcome}, already {@link Outcome#EXPIRED} when the prompt was not posted whole
     */
    public static CompletableFuture<Outcome> request(String botToken, String channelId, @Nullable String threadTs,
                                                     String authorizedUserId, List<String> parts,
                                                     boolean allowScopes) {
        var future = new CompletableFuture<Outcome>();
        if (parts.isEmpty() || parts.stream().anyMatch(part -> part.length() > SECTION_MAX_CHARS)) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    ("Approval prompt for channel %s has no part, or a part over %d characters; nothing posted, "
                            + "resolving as EXPIRED").formatted(channelId, SECTION_MAX_CHARS));
            future.complete(Outcome.EXPIRED);
            return future;
        }
        var slack = wire(botToken);
        int last = parts.size() - 1;
        for (int i = 0; i < last; i++) {
            var part = parts.get(i);
            if (slack.post(channelId, threadTs, part, List.of(section(part))) == null) {
                EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                        "Approval prompt part %d of %d failed to post to channel %s; no buttons shown, resolving as EXPIRED"
                                .formatted(i + 1, parts.size(), channelId));
                future.complete(Outcome.EXPIRED);
                return future;
            }
        }
        var approvalId = ApprovalRegistry.newId();
        var prompt = parts.get(last);
        var messageTs = slack.post(channelId, threadTs, prompt, approvalBlocks(approvalId, prompt, allowScopes));
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
     * with an "expired" line so the stale buttons can't be tapped afterward.
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
    public static Resolution resolve(String approvalId, SlackApprovalCallback.Decision decision,
                                     @Nullable String fromId) {
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
        wire(botToken).update(channelId, messageTs, text, List.of(section(text)));
    }

    /**
     * The two Slack calls a prompt makes. A test installs its own for one bot token, as
     * {@code TelegramChannel.installForTest} does for the Bot API, so its prompts never reach Slack.
     */
    public interface Wire {
        /** Posts a Block Kit message; its {@code ts}, or {@code null} when Slack did not take it. */
        @Nullable String post(String channelId, @Nullable String threadTs, String fallbackText, List<LayoutBlock> blocks);

        /** Replaces a posted message's blocks; whether Slack took it. */
        boolean update(String channelId, String ts, String fallbackText, List<LayoutBlock> blocks);
    }

    private static final Map<String, Wire> TEST_WIRES = new ConcurrentHashMap<>();

    /** Visible only for testing: route the prompts sent through {@code botToken} to {@code wire}. */
    public static void installForTest(String botToken, Wire wire) {
        TEST_WIRES.put(botToken, wire);
    }

    /** Visible only for testing: undo {@link #installForTest}. */
    public static void clearForTest(String botToken) {
        TEST_WIRES.remove(botToken);
    }

    private static Wire wire(@Nullable String botToken) {
        if (botToken == null) return NO_TOKEN;
        var installed = TEST_WIRES.get(botToken);
        return installed != null ? installed : new SlackWire(botToken);
    }

    /** A bot token that did not decrypt reads as null (JCLAW-1455): nothing is posted, which reads as a failed send. */
    private static final Wire NO_TOKEN = new Wire() {
        @Override public @Nullable String post(String channelId, @Nullable String threadTs, String fallbackText,
                                               List<LayoutBlock> blocks) {
            return null;
        }
        @Override public boolean update(String channelId, String ts, String fallbackText, List<LayoutBlock> blocks) {
            return false;
        }
    };

    private record SlackWire(String botToken) implements Wire {
        @Override public @Nullable String post(String channelId, @Nullable String threadTs, String fallbackText,
                                               List<LayoutBlock> blocks) {
            return SlackWebApi.postMessageWithBlocks(botToken, channelId, threadTs, fallbackText, blocks);
        }
        @Override public boolean update(String channelId, String ts, String fallbackText, List<LayoutBlock> blocks) {
            return SlackWebApi.updateMessageWithBlocks(botToken, channelId, ts, fallbackText, blocks);
        }
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
     * Build the message the buttons come with: a mrkdwn section with the prompt's last
     * part plus an actions row of approve/deny buttons. With {@code allowScopes} the approve options fan
     * out to Once / Session / Always; otherwise a single Approve. The decision + id
     * ride in each button's {@code action_id} (see {@link SlackApprovalCallback}).
     */
    static List<LayoutBlock> approvalBlocks(String approvalId, String prompt, boolean allowScopes) {
        var blocks = new ArrayList<LayoutBlock>();
        blocks.add(section(prompt));
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

    private static ButtonElement button(String label, String actionId, @Nullable String style) {
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
