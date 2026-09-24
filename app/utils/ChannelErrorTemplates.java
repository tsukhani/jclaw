package utils;

import llm.LlmProvider;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Templates for a turn that failed before it could answer, rendered for the channel the person is
 * reading in (JCLAW-1133).
 *
 * <p>Two audiences, two templates. The operator's ({@link #forTurnFailure}) reuses the {@link
 * utils.LlmErrorTemplates.Failure} classified at the call that failed, so it names the provider,
 * the model, and whether the key or the balance is at fault. A channel reader never gets that one:
 * a Slack channel, a Telegram group with guests and a WhatsApp customer all read the sink's reply,
 * none of them can fix a key, and each would learn which provider the deployment runs on and that
 * its billing lapsed. They get {@link #forChannelReader}; the operator's remedy goes to the event
 * log through {@link #operatorDetail}.
 *
 * <p>Nothing static registers here: the useful templates are parameterized by the failing call, so
 * {@link #forTurnFailure} builds them and {@link ErrorTemplates#forCode} falls back for the codes.
 */
public final class ChannelErrorTemplates {

    /** A turn that failed for a reason the classifier could not name. */
    public static final String TURN_FAILED = "channel_turn_failed";

    /** What a channel reader is told about any failed turn. */
    public static final String REPLY_UNAVAILABLE = "channel_reply_unavailable";

    // --- binding failures an operator acts on (JCLAW-1135) ---
    public static final String SLACK_SIGNATURE_MISMATCH = "slack_signature_mismatch";
    public static final String TELEGRAM_TOKEN_REJECTED = "telegram_token_rejected";
    public static final String WHATSAPP_OUTSIDE_WINDOW = "whatsapp_outside_window";

    /**
     * Meta's error code for a free-form message sent outside the 24-hour customer-service window.
     * A business rule, not a fault: Meta requires an approved template to reopen the conversation.
     */
    public static final int META_OUTSIDE_WINDOW = 131047;

    private ChannelErrorTemplates() {}

    /**
     * The operator's template for a failed turn, reusing the provider classification when the
     * failure carries one (JCLAW-1134 attaches it to {@code LlmException}) rather than re-deriving
     * it from a message string. For the web chat, whose reader is the operator — never a channel
     * sink's reply, which gets {@link #forChannelReader}.
     */
    public static ErrorTemplate forTurnFailure(@Nullable Throwable t) {
        var failure = classifiedFailure(t);
        if (failure != null) return LlmErrorTemplates.forFailure(failure);
        return new ErrorTemplate(TURN_FAILED,
                "The reply could not be produced.",
                "Nothing on your side — the failure was inside JClaw, and the log entry for this "
                        + "turn carries what actually broke.",
                "Send the message again. If it fails the same way twice, the log is the thing to read.");
    }

    /** The reply a Slack, Telegram or WhatsApp reader gets for any failed turn, however classified. */
    public static ErrorTemplate forChannelReader() {
        return new ErrorTemplate(REPLY_UNAVAILABLE,
                "This message could not be answered.",
                "Nothing on your side — the problem is at this end, and it has been logged for "
                        + "whoever runs this assistant.",
                "Send the message again later.");
    }

    /**
     * The operator's remedy for a classified failure, rendered for {@code EventLog.details}, or
     * null when the failure is unclassified: the raw message on the same log line then says more
     * than the generic template would.
     */
    public static @Nullable String operatorDetail(@Nullable Throwable t) {
        var failure = classifiedFailure(t);
        return failure == null ? null : ErrorRendering.PLAIN.render(LlmErrorTemplates.forFailure(failure));
    }

    /** Walks the cause chain: the runner wraps provider failures before they reach a sink. */
    private static LlmErrorTemplates.@Nullable Failure classifiedFailure(@Nullable Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof LlmProvider.LlmException le && le.failure() != null) return le.failure();
        }
        return null;
    }

    /**
     * Render for a channel, honoring what it can display and how much of it.
     *
     * <p>Truncation takes from the middle, never the end: cutting the tail drops the retry
     * instruction, the only part the reader acts on. If even the first and last sections do
     * not fit, the check section goes entirely rather than the message being cut mid-word.
     *
     * @param maxChars the channel's hard message cap
     */
    public static String render(@NonNull ErrorTemplate template, @NonNull ErrorRendering mode,
                                int maxChars) {
        var full = mode.render(template);
        if (full.length() <= maxChars) return full;

        var withoutCheck = mode.render(new ErrorTemplate(template.code(), template.whatBroke(),
                "…", template.howToRetry()));
        if (withoutCheck.length() >= maxChars) {
            // Even the ends do not fit; hard-cut rather than emit an over-cap message the channel
            // would reject outright.
            return full.substring(0, Math.max(0, maxChars - 1)) + "…";
        }
        var room = maxChars - (withoutCheck.length() - 1);
        var check = template.whatToCheck();
        var kept = check.length() <= room ? check : check.substring(0, Math.max(0, room - 1)) + "…";
        return mode.render(new ErrorTemplate(template.code(), template.whatBroke(), kept,
                template.howToRetry()));
    }

    // --- binding failures (JCLAW-1135) ---
    // Operator-facing, read through /logs: the audience is whoever configures the binding, not the
    // person chatting. Each names the binding it concerns — an operator with several cannot act on
    // "a signature failed" — and none takes a secret as a parameter, so none can render one: the
    // signing secret, the signature Slack sent, and every bot or access token stay out of reach of
    // the message by construction rather than by care at each call site.

    public static ErrorTemplate slackSignatureMismatch(long bindingId, @Nullable String teamId) {
        return new ErrorTemplate(SLACK_SIGNATURE_MISMATCH,
                "Slack binding %d%s rejected an incoming event: its signature did not verify."
                        .formatted(bindingId, teamId == null ? "" : " (team " + teamId + ")"),
                "Almost always a stale signing secret — the one stored on this binding no longer "
                        + "matches Settings → Basic Information → App Credentials → Signing Secret in "
                        + "the Slack app. Regenerating it there does not update it here, and Slack keeps "
                        + "the old secret valid for 24 hours, so this typically starts a day after "
                        + "someone regenerated it rather than at the moment they did.",
                "Copy the current Signing Secret from the Slack app into this binding, save, and "
                        + "Slack's next retry will verify.");
    }

    public static ErrorTemplate telegramTokenRejected(long bindingId) {
        return new ErrorTemplate(TELEGRAM_TOKEN_REJECTED,
                "Telegram binding %d was disabled: Telegram no longer accepts its bot token."
                        .formatted(bindingId),
                "The token was revoked in BotFather — /revoke issues a new token and the old one stops "
                        + "working at once, with no grace period. JClaw disabled the binding rather than "
                        + "keep polling with a token that cannot work.",
                "Paste the current token from BotFather into this binding and re-enable it.");
    }

    /**
     * The out-of-window case is worded as an expected constraint, not a fault (the story's AC): an
     * operator told "error" goes looking for a break that is not there, when the fix is a
     * configuration step or simply waiting for the customer.
     */
    public static ErrorTemplate whatsAppOutsideWindow(@Nullable Long bindingId, boolean templateConfigured) {
        var broke = "WhatsApp binding %s held a reply: the customer is outside Meta's 24-hour window."
                .formatted(bindingId == null ? "(unbound)" : String.valueOf(bindingId));
        var rule = "Expected, not a fault. Meta allows free-form replies only within 24 hours of the "
                + "customer's last message; after that it accepts only a pre-approved template";
        if (templateConfigured) {
            return new ErrorTemplate(WHATSAPP_OUTSIDE_WINDOW, broke,
                    rule + ". This binding has one, but JClaw sends it only ahead of a text reply its "
                            + "own window record places outside the window; this message was not one, "
                            + "so it went free-form.",
                    "Nothing to change on the binding — the window reopens the moment the customer "
                            + "writes again.");
        }
        return new ErrorTemplate(WHATSAPP_OUTSIDE_WINDOW, broke,
                rule + ", and this binding has none configured to reopen the conversation.",
                "Set an approved message template on the binding so JClaw can reopen the "
                        + "conversation, or wait — the window reopens the moment the customer writes again.");
    }

    static Map<String, ErrorTemplate> templates() {
        return Map.of();
    }
}
